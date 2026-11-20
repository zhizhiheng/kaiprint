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
    /** The source is a motion photo and its video goes into the file after the still. */
    val motion: Boolean = false,
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
    val motion = info.motion && settings.keepMotion && settings.format == OutputFormat.JPEG &&
        choice.kind == PixelKind.STANDARD && choice.source.format == PhotoFormat.JPEG
    val motionNote = when {
        motion -> "动态部分是原视频，不带边框和水印，视频里的拍摄时间等信息会一并保留"
        info.motion && settings.keepMotion -> "只有从 JPEG 导出 JPEG 才能带上动态部分，这张导出为静态照片"
        else -> null
    }
    if (!settings.keepMetadata) {
        return ExportPlan(choice, layout, hdr, emptyMap(), notes = listOfNotNull(motionNote), motion = motion)
    }

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
        if (author != null && !author.isAscii()) add("作者含中文或符号，$ASCII_ONLY")
        if (copyright != null && !copyright.isAscii()) add("版权含中文或符号，$ASCII_ONLY")
    }.toMutableList()
    author?.takeIf(String::isAscii)?.let { tags[ExifInterface.TAG_ARTIST] = it.take(120) }
    copyright?.takeIf(String::isAscii)?.let { tags[ExifInterface.TAG_COPYRIGHT] = it.take(200) }
    val tiff = settings.format == OutputFormat.TIFF
    if (settings.writeManual) {
        // The TIFF writer stores make, model and capture time only, so only those are worth reporting on.
        val typed = if (tiff) overrides.filterKeys { it in tiffFields } else overrides
        val manual = manualExif(typed, metadata)
        tags -= manual.stale
        tags += manual.tags
        notes += manual.notes
    }
    if (tiff) {
        val kept = tags.filterKeys { it in setOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL) }
        return ExportPlan(choice, layout, hdr, kept, notes = notes.filter { it.startsWith("手填") } +
            listOfNotNull("TIFF 只写入品牌、机型和拍摄时间".takeIf { tags.size > 3 }, motionNote))
    }
    motionNote?.let(notes::add)
    val position = metadata.takeIf { settings.keepLocation && it.hasLocation }
    return ExportPlan(choice, layout, hdr, tags, position?.latitude, position?.longitude, notes, motion)
}

private const val ASCII_ONLY = "EXIF 只能存英文字母、数字和常用标点，未写入"
private val tiffFields = setOf(MetadataField.MAKE, MetadataField.MODEL, MetadataField.DATE)

private fun String.isAscii() = all { it.code in 32..126 }

/** Hand-typed details as EXIF: the tags to write, recorded tags they make stale, and what could not be written. */
internal class ManualExif(val tags: Map<String, String>, val stale: Set<String>, val notes: List<String>)

/**
 * Turns values typed over a photo's parameters into EXIF tags. A value left as the camera recorded it, or
 * cleared to hide it from the watermark, changes nothing in the file.
 */
internal fun manualExif(overrides: Map<MetadataField, String>, recorded: PhotoMetadata): ManualExif {
    val tags = LinkedHashMap<String, String>()
    val stale = mutableSetOf<String>()
    val notes = mutableListOf<String>()
    for ((field, typed) in overrides) {
        val value = typed.trim()
        if (value.isEmpty() || value == recorded.values[field]) continue
        fun unreadable() { notes += "手填的${field.label}「${value.take(24)}」认不出数值，未写入 EXIF" }
        fun text(tag: String) {
            if (value.isAscii()) tags[tag] = value.take(120) else notes += "手填的${field.label}含中文或符号，$ASCII_ONLY"
        }
        when (field) {
            MetadataField.MAKE -> text(ExifInterface.TAG_MAKE)
            MetadataField.MODEL -> text(ExifInterface.TAG_MODEL)
            MetadataField.LENS -> {
                text(ExifInterface.TAG_LENS_MODEL)
                // The recorded maker and focal range describe the lens that was replaced.
                if (ExifInterface.TAG_LENS_MODEL in tags) {
                    stale += listOf(ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_SPECIFICATION)
                }
            }
            MetadataField.FOCAL -> value.amount()?.takeIf { it > 0 && it < 100_000 }
                ?.let { tags[ExifInterface.TAG_FOCAL_LENGTH] = "${Math.round(it * 1000)}/1000" } ?: unreadable()
            MetadataField.FOCAL_EQUIVALENT -> value.amount()?.takeIf { it >= 1 && it <= 65535 }
                ?.let { tags[ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM] = Math.round(it).toString() } ?: unreadable()
            MetadataField.APERTURE -> value.amount()?.takeIf { it > 0 && it < 1000 }
                ?.let { tags[ExifInterface.TAG_F_NUMBER] = it.toString() } ?: unreadable()
            MetadataField.SHUTTER -> value.amount()?.takeIf { it > 0 && it < 100_000 }
                ?.let { tags[ExifInterface.TAG_EXPOSURE_TIME] = it.toString() } ?: unreadable()
            MetadataField.ISO -> {
                // "12,800" groups thousands here, where other fields read a comma as a decimal point.
                val iso = value.replace(",", "").amount()
                when {
                    iso == null || iso < 1 -> unreadable()
                    // The tag is a 16-bit number.
                    iso > 65535 -> notes += "手填的 ISO 超过 65535，EXIF 的 ISO 字段存不下，未写入"
                    else -> tags[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] = Math.round(iso).toString()
                }
            }
            MetadataField.EXPOSURE_BIAS -> value.amount(signed = true)?.takeIf { it > -100 && it < 100 }
                ?.let { tags[ExifInterface.TAG_EXPOSURE_BIAS_VALUE] = "${Math.round(it * 100)}/100" } ?: unreadable()
            MetadataField.DATE -> value.exifTime()?.let { tags[ExifInterface.TAG_DATETIME_ORIGINAL] = it } ?: unreadable()
            // Film simulation lives in a maker note, and titles, credit and place have their own export choices.
            MetadataField.FILM, MetadataField.TITLE, MetadataField.AUTHOR, MetadataField.LOCATION -> Unit
        }
    }
    return ManualExif(tags, stale, notes)
}

private val amountPattern = Regex("""([+\-−–]?)\s*(\d+(?:[.,]\d+)?)(?:\s*/\s*(\d+(?:[.,]\d+)?))?""")
private val timePattern =
    Regex("""(\d{4})\s*[-:/.年]\s*(\d{1,2})\s*[-:/.月]\s*(\d{1,2})(?:\s*日)?(?:[ T]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?""")

/**
 * The first number in typed text such as "50 mm", "f/1.8", "ISO 400", "1/125 s" or "+0.3 EV". A fraction is
 * divided out, and a sign counts only where [signed] says the value can be negative.
 */
internal fun String.amount(signed: Boolean = false): Double? {
    val match = amountPattern.find(this) ?: return null
    val (sign, first, second) = match.destructured
    val top = first.replace(',', '.').toDoubleOrNull() ?: return null
    val bottom = if (second.isEmpty()) 1.0 else second.replace(',', '.').toDoubleOrNull() ?: return null
    if (bottom == 0.0) return null
    val value = top / bottom
    return if (signed && sign.isNotEmpty() && sign != "+") -value else value
}

/** A typed date as EXIF stores it, "2026:11:20 17:42:10". A date without a time is taken as midnight. */
internal fun String.exifTime(): String? {
    val (y, m, d, hour, minute, second) = timePattern.find(trim())?.destructured ?: return null
    if (m.toInt() !in 1..12 || d.toInt() !in 1..31) return null
    val h = hour.ifEmpty { "0" }.toInt()
    val min = minute.ifEmpty { "0" }.toInt()
    val s = second.ifEmpty { "0" }.toInt()
    if (h > 23 || min > 59 || s > 59) return null
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "$y:${two(m.toInt())}:${two(d.toInt())} ${two(h)}:${two(min)}:${two(s)}"
}

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
