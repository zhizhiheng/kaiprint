package app.kai.photomark

import androidx.exifinterface.media.ExifInterface

enum class HdrHandling(val label: String) {
    NONE("标准动态范围"), KEPT("保留 Ultra HDR 增益图"), SDR("转为 SDR"),
}

/**
 * Everything one finished photo will be, worked out before anything is written: which pixels it uses, its
 * sizes, how HDR is handled and which tags go into the file. The export panel shows it, and the exporter
 * writes exactly this, so the two cannot disagree.
 */
data class ExportPlan(
    val pixels: PixelChoice,
    val layout: WatermarkLayout,
    val hdr: HdrHandling,
    val tags: Map<String, String>,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Things the file cannot carry, said plainly next to the summary. */
    val notes: List<String> = emptyList(),
) {
    /** The photo itself on the output canvas, after any size limit. */
    val photo get() = PixelSize(Math.round(layout.photo.width), Math.round(layout.photo.height))
    val scaled get() = photo != pixels.size
    val location get() = latitude != null && longitude != null

    /** Chinese names of what the file will carry, in a stable order. */
    val fields: List<String> get() = tagLabels.mapNotNull { (tag, label) -> label.takeIf { tag in tags } } +
        listOfNotNull("拍摄位置".takeIf { location })

    companion object {
        val tagLabels = listOf(
            ExifInterface.TAG_MAKE to "品牌", ExifInterface.TAG_MODEL to "机型",
            ExifInterface.TAG_LENS_MAKE to "镜头品牌", ExifInterface.TAG_LENS_MODEL to "镜头",
            ExifInterface.TAG_LENS_SPECIFICATION to "镜头规格", ExifInterface.TAG_FOCAL_LENGTH to "焦距",
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "等效焦距", ExifInterface.TAG_F_NUMBER to "光圈",
            ExifInterface.TAG_EXPOSURE_TIME to "快门", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "ISO",
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "曝光补偿", ExifInterface.TAG_DATETIME_ORIGINAL to "拍摄时间",
            ExifInterface.TAG_ARTIST to "作者", ExifInterface.TAG_COPYRIGHT to "版权",
        )
    }
}

/** Ultra HDR survives only from a JPEG with a gain map to a JPEG, on Android 14 and later. */
internal fun PixelChoice.keepsHdr(settings: ExportSettings, sdk: Int) =
    hdr && settings.keepHdr && sdk >= 34 && settings.format == OutputFormat.JPEG &&
        source.format == PhotoFormat.JPEG && kind in listOf(PixelKind.STANDARD, PixelKind.COMPANION)

internal fun planExport(
    info: PhotoInfo,
    kind: PixelKind?,
    template: WatermarkTemplate,
    settings: ExportSettings,
    overrides: Map<MetadataField, String>,
    sdk: Int,
): ExportPlan {
    val choice = selectPixels(info, kind, settings, sdk)
    val hdr = when {
        !choice.hdr -> HdrHandling.NONE
        choice.keepsHdr(settings, sdk) -> HdrHandling.KEPT
        else -> HdrHandling.SDR
    }
    val metadata = info.metadata
    val layout = WatermarkLayout.calculate(choice.size, template, settings, metadata, overrides)
    if (!settings.keepMetadata) return ExportPlan(choice, layout, hdr, emptyMap())

    val tags = metadata.exif.filterKeys { it in safeExif }.toMutableMap()
    val author = when (settings.credit) {
        CreditSource.SOURCE -> metadata.exif[ExifInterface.TAG_ARTIST]
        CreditSource.WATERMARK -> overrides[MetadataField.AUTHOR] ?: template.captions[MetadataField.AUTHOR]
            ?: metadata.exif[ExifInterface.TAG_ARTIST]
        CreditSource.NONE -> null
    }?.trim()?.takeIf(String::isNotEmpty)
    val copyright = if (settings.credit == CreditSource.NONE) null
        else settings.copyright.trim().ifEmpty { metadata.exif[ExifInterface.TAG_COPYRIGHT].orEmpty() }
            .replace("©", "(C)").takeIf(String::isNotEmpty)
    // EXIF text tags are ASCII. Writing Chinese would leave question marks in the file.
    val notes = buildList {
        if (author != null && !author.isAscii()) add("作者含中文或符号，EXIF 只能存英文字母、数字和常用标点，未写入")
        if (copyright != null && !copyright.isAscii()) add("版权含中文或符号，EXIF 只能存英文字母、数字和常用标点，未写入")
    }
    author?.takeIf(String::isAscii)?.let { tags[ExifInterface.TAG_ARTIST] = it.take(120) }
    copyright?.takeIf(String::isAscii)?.let { tags[ExifInterface.TAG_COPYRIGHT] = it.take(200) }
    if (settings.format == OutputFormat.TIFF) {
        // The TIFF writer stores make, model and capture time only.
        return ExportPlan(choice, layout, hdr, tags.filterKeys { it in setOf(ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL) },
            notes = listOfNotNull("TIFF 只写入品牌、机型和拍摄时间".takeIf { tags.size > 3 }))
    }
    val position = metadata.takeIf { settings.keepLocation && it.hasLocation }
    return ExportPlan(choice, layout, hdr, tags, position?.latitude, position?.longitude, notes)
}

private fun String.isAscii() = all { it.code in 32..126 }

/** A batch at a glance before it runs: where pixels come from, how many turn SDR, what is missing. */
data class ExportSummary(
    val checked: Int,
    val total: Int,
    val kinds: Map<PixelKind, Int> = emptyMap(),
    val sdr: Int = 0,
    val keptHdr: Int = 0,
    val scaled: Int = 0,
    val noCamera: Int = 0,
    val located: Int = 0,
    val blocked: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun of(results: List<Pair<PhotoMetadata?, Result<ExportPlan>>>, total: Int): ExportSummary {
            val plans = results.mapNotNull { it.second.getOrNull() }
            return ExportSummary(
                checked = results.size,
                total = total,
                kinds = plans.groupingBy { it.pixels.kind }.eachCount(),
                sdr = plans.count { it.hdr == HdrHandling.SDR },
                keptHdr = plans.count { it.hdr == HdrHandling.KEPT },
                scaled = plans.count { it.scaled },
                noCamera = results.count { (metadata, _) ->
                    metadata != null && metadata.values[MetadataField.MAKE] == null && metadata.values[MetadataField.MODEL] == null
                },
                located = plans.count { it.location },
                blocked = results.mapNotNull { it.second.exceptionOrNull() }
                    .map { if (it is ExportFailure) it.message.orEmpty() else "无法读取这张照片" }
                    .groupingBy { it }.eachCount(),
            )
        }
    }
}
