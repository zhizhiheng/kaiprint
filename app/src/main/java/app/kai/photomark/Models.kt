package app.kai.photomark

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class PhotoFormat(val label: String, val raw: Boolean = false) {
    JPEG("JPEG"), PNG("PNG"), WEBP("WebP"), HEIF("HEIF"), AVIF("AVIF"), TIFF("TIFF"),
    NEF("NEF", true), CR2("CR2", true), CR3("CR3", true), ARW("ARW", true),
    RAF("RAF", true), DNG("DNG", true), RW2("RW2", true), ORF("ORF", true),
    PEF("PEF", true), NRW("NRW", true), SRW("SRW", true), UNKNOWN("图片");

    companion object {
        fun identify(name: String, mime: String? = null): PhotoFormat {
            return when (val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "jpg", "jpeg" -> JPEG
                "png" -> PNG
                "webp" -> WEBP
                "heic", "heif", "hif" -> HEIF
                "avif" -> AVIF
                "tif", "tiff" -> TIFF
                else -> entries.firstOrNull { it.raw && it.name.lowercase(Locale.ROOT) == extension }
                    ?: when (mime?.lowercase(Locale.ROOT)) {
                        "image/jpeg" -> JPEG
                        "image/png" -> PNG
                        "image/webp" -> WEBP
                        "image/heic", "image/heif" -> HEIF
                        "image/avif" -> AVIF
                        "image/tiff" -> TIFF
                        "image/x-adobe-dng" -> DNG
                        else -> UNKNOWN
                    }
            }
        }
    }
}

@Serializable
data class PhotoSource(
    val uri: String,
    val name: String,
    val folder: String,
    val parentKey: String,
    val timeMillis: Long = 0,
    val mime: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val embeddedFormat: PhotoFormat? = null,
) {
    val format get() = embeddedFormat ?: PhotoFormat.identify(name, mime)
    val stem get() = name.substringBeforeLast('.')
}

data class PhotoGroup(val primary: PhotoSource, val developed: PhotoSource? = null) {
    val id get() = primary.uri
    val label get() = if (developed == null) primary.format.label
        else "${primary.format.label} + ${developed.format.label}"
}

@Serializable
enum class MetadataField(val label: String) {
    MAKE("品牌"), MODEL("机型"), LENS("镜头"), FOCAL("焦距"), APERTURE("光圈"),
    SHUTTER("快门"), ISO("ISO"), DATE("拍摄时间"), EXPOSURE_BIAS("曝光补偿"),
    FOCAL_EQUIVALENT("等效焦距"), FILM("胶片模拟"), TITLE("作品标题"), AUTHOR("署名"), LOCATION("地点");

    companion object {
        val captionFields = listOf(TITLE, AUTHOR, LOCATION)
    }
}

/** Moves [item] by [offset] places. A move past either end leaves the list as it is. */
fun <T> List<T>.moved(item: T, offset: Int): List<T> {
    val from = indexOf(item)
    if (from < 0 || from + offset !in indices) return this
    return toMutableList().apply { add(from + offset, removeAt(from)) }
}

/** What stands between two parameters printed on one line. */
@Serializable
enum class Separator(val label: String, val text: String) {
    DOT("圆点 ·", "   ·   "), BAR("竖线 |", "   |   "), SLASH("斜线 /", "   /   "),
    DASH("短横 -", "   -   "), SPACE("空格", "    "),
}

/** How the capture time is printed. Text that is not a date, such as a season typed by hand, is left alone. */
@Serializable
enum class DateStyle(val label: String) {
    ORIGINAL("原样 · 2026-11-20 17:42:10"),
    MINUTE("到分钟 · 2026-11-20 17:42"),
    DATE("仅日期 · 2026-11-20"),
    DOTTED("2026.11.20"),
    SLASHED("2026/11/20"),
    CHINESE("2026年11月20日"),
    ENGLISH("Nov 20, 2026"),
    STAMP("胶片日期戳 · '26 11 20");

    fun format(value: String): String {
        if (this == ORIGINAL) return value
        val match = pattern.matchEntire(value.trim()) ?: return value
        val (y, m, d, hour, minute) = match.destructured
        val month = m.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return value
        val mm = m.padStart(2, '0')
        val dd = d.padStart(2, '0')
        return when (this) {
            ORIGINAL -> value
            MINUTE -> if (hour.isEmpty()) "$y-$mm-$dd" else "$y-$mm-$dd ${hour.padStart(2, '0')}:$minute"
            DATE -> "$y-$mm-$dd"
            DOTTED -> "$y.$mm.$dd"
            SLASHED -> "$y/$mm/$dd"
            CHINESE -> "${y}年${month}月${day}日"
            ENGLISH -> "${months[month - 1]} $day, $y"
            STAMP -> "'${y.takeLast(2)} $mm $dd"
        }
    }

    private companion object {
        val pattern = Regex("""(\d{4})[-:/.](\d{1,2})[-:/.](\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::\d{2})?)?""")
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}

@Serializable
data class PhotoMetadata(
    val values: Map<MetadataField, String> = emptyMap(),
    val exif: Map<String, String> = emptyMap(),
    val orientation: Int = 1,
    val sensorWidth: Int = 0,
    val sensorHeight: Int = 0,
    val rawCompression: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasLocation get() = latitude != null && longitude != null

    fun parts(fields: List<MetadataField>, overrides: Map<MetadataField, String>): List<String> =
        fields.mapNotNull { (overrides[it] ?: values[it])?.trim()?.takeIf(String::isNotEmpty) }

    fun display(fields: List<MetadataField>, overrides: Map<MetadataField, String>): String =
        parts(fields, overrides).joinToString(SEPARATOR)

    companion object {
        val SEPARATOR = Separator.DOT.text
    }
}

@Serializable
enum class PixelKind(val label: String) {
    STANDARD("原照片"), COMPANION("伴随图"), RAW("RAW 解码"), EMBEDDED("RAW 内嵌预览")
}

data class PixelChoice(
    val source: PhotoSource,
    val kind: PixelKind,
    val size: PixelSize,
    val orientation: Int = 1,
    val hdr: Boolean = false,
)

data class PhotoInfo(
    val metadata: PhotoMetadata,
    val choices: List<PixelChoice>,
    /** The source is a JPEG that carries a short video after the still, as phone cameras save a live shot. */
    val motion: Boolean = false,
)

/** Saved by name, so existing entries keep their names when new ratios are added. */
@Serializable
enum class FrameRatio(val label: String, val across: Int, val down: Int) {
    ORIGINAL("自由留白", 0, 0),
    SQUARE("1:1", 1, 1),
    PORTRAIT("4:5", 4, 5),
    THREE_FOUR("3:4", 3, 4),
    TWO_THREE("2:3", 2, 3),
    STORY("9:16", 9, 16),
    FOUR_THREE("4:3", 4, 3),
    THREE_TWO("3:2", 3, 2),
    WIDE("16:9", 16, 9),
}

/** One-tap canvas shapes for the places people post to most. */
enum class PostTarget(val label: String, val ratio: FrameRatio) {
    XIAOHONGSHU("小红书", FrameRatio.THREE_FOUR),
    DOUYIN("抖音", FrameRatio.STORY),
    BILIBILI("B 站封面", FrameRatio.WIDE),
    SQUARE("方图", FrameRatio.SQUARE),
}

/** An output size cap. Zero on both edges keeps the full rendered size. */
data class SizeLimit(val longEdge: Int = 0, val shortEdge: Int = 0) {
    val label get() = when {
        shortEdge > 0 -> "短边 $shortEdge px"
        longEdge > 0 -> "长边 $longEdge px"
        else -> "原尺寸"
    }

    companion object {
        val choices = listOf(
            SizeLimit(), SizeLimit(longEdge = 4096), SizeLimit(longEdge = 3000), SizeLimit(longEdge = 2048),
            SizeLimit(shortEdge = 1440), SizeLimit(shortEdge = 1080), SizeLimit(longEdge = 1080),
        )
    }
}

@Serializable
enum class FrameBackground(val label: String) {
    SOLID("纯色"), FROSTED("照片毛玻璃"),
}

@Serializable
data class Frame(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val color: String = "#F5F1E9",
    val ratio: FrameRatio = FrameRatio.ORIGINAL,
    val hairline: Boolean = false,
    val background: FrameBackground = FrameBackground.SOLID,
    val blur: Float = .043f,
    val tintStrength: Float = .10f,
    /** Shrinks a parameter strip to the side margin when a photo has nothing to print in it. */
    val collapseEmpty: Boolean = true,
    /** Corner radius of the photo, as a share of its short edge. */
    val corner: Float = 0f,
    /** How far a soft shadow spreads from the photo onto the frame, as a share of its short edge. */
    val shadow: Float = 0f,
) {
    /** The corner radius in pixels for a photo of this size, never more than a full pill. */
    fun cornerRadius(photo: PixelRect, reference: Float) =
        (corner.coerceIn(0f, MAX_CORNER) * reference).coerceAtMost(minOf(photo.width, photo.height) / 2f)

    companion object {
        const val MAX_CORNER = .12f
        const val MAX_SHADOW = .08f
    }
}

@Serializable
enum class LayerKind(val label: String) {
    TEXT("文字"), BRAND("品牌标志"), LOGO("自定义 Logo"), EXIF("拍摄参数"), LINE("细线"), PALETTE("照片色卡")
}

@Serializable
enum class PaletteStyle(val label: String) {
    STRIP("连续色带"), BLOCKS("分隔色块"),
}

@Serializable
enum class LayerArea(val label: String) {
    PHOTO("照片内"), BOTTOM("底边"), TOP("顶边"), LEFT("左边"), RIGHT("右边"), CANVAS("全画布")
}

@Serializable
enum class LayerColumn(val label: String, val start: Float, val end: Float) {
    FULL("整区", 0f, 1f), LEFT("左栏", 0f, .28f), RIGHT("右栏", .28f, 1f),
    LEFT_HALF("左半区", 0f, .5f), RIGHT_HALF("右半区", .5f, 1f),
}

@Serializable
enum class Anchor(val label: String, val x: Float, val y: Float) {
    TOP_LEFT("左上", 0f, 0f), TOP_CENTER("上中", .5f, 0f), TOP_RIGHT("右上", 1f, 0f),
    CENTER_LEFT("左中", 0f, .5f), CENTER("居中", .5f, .5f), CENTER_RIGHT("右中", 1f, .5f),
    BOTTOM_LEFT("左下", 0f, 1f), BOTTOM_CENTER("下中", .5f, 1f), BOTTOM_RIGHT("右下", 1f, 1f)
}

@Serializable
enum class TypeStyle(val label: String) {
    SANS("无衬线"), SERIF("衬线"), MONO("等宽")
}

@Serializable
data class WatermarkLayer(
    val id: String,
    val kind: LayerKind,
    val text: String = "",
    val fields: List<MetadataField> = emptyList(),
    val logoFile: String? = null,
    val area: LayerArea = LayerArea.PHOTO,
    val anchor: Anchor = Anchor.BOTTOM_RIGHT,
    val x: Float = 0f,
    val y: Float = 0f,
    val size: Float = .035f,
    val margin: Float = .04f,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val color: String = "#FFFFFF",
    val background: String? = null,
    val type: TypeStyle = TypeStyle.SANS,
    val bold: Boolean = false,
    val fontFile: String? = null,
    val outline: Boolean = false,
    val shadow: Boolean = false,
    val tile: Boolean = false,
    val visible: Boolean = true,
    val brand: BrandMark = BrandMark.AUTO,
    val brandTone: BrandTone = BrandTone.ORIGINAL,
    val column: LayerColumn = LayerColumn.FULL,
    val brandStyle: BrandStyle = BrandStyle.ORIGINAL,
    val paletteStyle: PaletteStyle = PaletteStyle.STRIP,
    val paletteCount: Int = 5,
    val autoGlassInk: Boolean = false,
    val reserveBrandSpace: Boolean = false,
    val separator: Separator = Separator.DOT,
    /** Extra space between letters, in ems. */
    val letterSpacing: Float = 0f,
    /** Picks light or dark ink by what each photo shows under a layer placed on it. */
    val autoPhotoInk: Boolean = false,
) {
    val canAdaptInk get() = !tile && background == null && area != LayerArea.CANVAS &&
        kind in listOf(LayerKind.TEXT, LayerKind.EXIF, LayerKind.BRAND)

    /** Whether the ink follows what lies under the layer: the photo itself, or a frosted frame. */
    fun adaptsInk(frame: Frame) = canAdaptInk &&
        if (area == LayerArea.PHOTO) autoPhotoInk else autoGlassInk && frame.background == FrameBackground.FROSTED

    val label get() = when (kind) {
        LayerKind.TEXT -> text.takeIf(String::isNotBlank) ?: kind.label
        LayerKind.EXIF -> fields.singleOrNull()?.label ?: kind.label
        else -> kind.label
    }
}

@Serializable
data class WatermarkTemplate(
    val id: String,
    val title: String,
    val frame: Frame = Frame(),
    val layers: List<WatermarkLayer> = emptyList(),
    val version: Int = 1,
    val captions: Map<MetadataField, String> = emptyMap(),
    val place: PlaceStyle = PlaceStyle.MANUAL,
    val dateStyle: DateStyle = DateStyle.ORIGINAL,
) {
    /** Photo details with this template's titles, credit, location and date style, before per-photo overrides. */
    fun shown(metadata: PhotoMetadata, place: String? = null): PhotoMetadata {
        val extra = captions.filterKeys { it in MetadataField.captionFields }
            .let { if (place != null) it + (MetadataField.LOCATION to place) else it }
        val values = shown(metadata.values + extra)
        return if (values == metadata.values) metadata else metadata.copy(values = values)
    }

    /** Hand-typed values as they print: a typed date follows the date style like a recorded one. */
    fun shown(values: Map<MetadataField, String>): Map<MetadataField, String> {
        val date = values[MetadataField.DATE] ?: return values
        val styled = dateStyle.format(date)
        return if (styled == date) values else values + (MetadataField.DATE to styled)
    }
}

@Serializable
data class EditDraft(
    val template: WatermarkTemplate,
    val overrides: Map<String, Map<MetadataField, String>> = emptyMap(),
    val pixelChoices: Map<String, PixelKind> = emptyMap(),
) {
    /** Writes the same values onto several photos. Fields not in [values] keep each photo's own override. */
    fun withOverrides(photoIds: Collection<String>, values: Map<MetadataField, String>): EditDraft {
        if (values.isEmpty()) return this
        val cleaned = values.mapValues { it.value.take(120) }
        return copy(overrides = overrides + photoIds.associateWith { overrides[it].orEmpty() + cleaned })
    }
}

/** Camera details saved once and written onto many photos, such as one roll of film or a manual lens. */
@Serializable
data class ParameterSet(val id: String, val name: String, val values: Map<MetadataField, String>) {
    val summary get() = listOf(MetadataField.MODEL, MetadataField.LENS, MetadataField.ISO)
        .mapNotNull { values[it]?.takeIf(String::isNotBlank) }
        .ifEmpty { values.values.filter(String::isNotBlank).take(2) }
        .joinToString(" · ")

    companion object {
        const val LIMIT = 50
    }
}

@Serializable
enum class OutputFormat(val label: String, val extension: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"), PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp"), TIFF("TIFF", "tif", "image/tiff")
}

@Serializable
enum class OutputColor(val label: String) {
    SRGB("sRGB"), DISPLAY_P3("Display P3")
}

/** Where the Artist tag of a finished photo comes from. */
@Serializable
enum class CreditSource(val label: String) {
    SOURCE("沿用原文件"), WATERMARK("使用水印署名"), NONE("不写入"),
}

@Serializable
data class ExportSettings(
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 95,
    val longEdge: Int = 0,
    val keepMetadata: Boolean = true,
    val allowEmbedded: Boolean = false,
    val allowSdr: Boolean = true,
    val colorSpace: OutputColor = OutputColor.SRGB,
    val shortEdge: Int = 0,
    val credit: CreditSource = CreditSource.SOURCE,
    /** Replaces the source Copyright tag when not blank. */
    val copyright: String = "",
    /** Writes the photo's own GPS position into the file. Printing a place on the photo is separate. */
    val keepLocation: Boolean = false,
    /** Keeps the gain map of an Ultra HDR JPEG when the output is JPEG too. */
    val keepHdr: Boolean = true,
    /** Writes hand-typed camera details over the recorded ones in the file's EXIF. */
    val writeManual: Boolean = false,
    /** Carries the video of a motion photo over to a JPEG output. The video itself is not reframed. */
    val keepMotion: Boolean = false,
) {
    val size get() = SizeLimit(longEdge, shortEdge)

    fun withSize(limit: SizeLimit) = copy(longEdge = limit.longEdge, shortEdge = limit.shortEdge)
}

@Serializable
data class ExportInput(
    val photo: PhotoSource,
    val companion: PhotoSource? = null,
    val pixelKind: PixelKind? = null,
    val overrides: Map<MetadataField, String> = emptyMap(),
)

data class PixelSize(val width: Int, val height: Int) {
    val longEdge get() = max(width, height)
    val shortEdge get() = minOf(width, height)

    /** Scales down until both caps hold. Zero means no cap, and a size is never scaled up. */
    fun fit(longEdge: Int, shortEdge: Int = 0): PixelSize {
        val scale = minOf(
            if (longEdge > 0 && this.longEdge > longEdge) longEdge.toDouble() / this.longEdge else 1.0,
            if (shortEdge > 0 && this.shortEdge > shortEdge) shortEdge.toDouble() / this.shortEdge else 1.0,
        )
        if (scale >= 1.0) return this
        return PixelSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }

    fun oriented(orientation: Int) = if (orientation in 5..8) PixelSize(height, width) else this
    override fun toString() = "$width × $height"
}

data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class WatermarkLayout(
    val canvas: PixelSize, val photo: PixelRect, val reference: Float,
    private val topBrandHeight: Float = 0f, private val bottomBrandHeight: Float = 0f,
    /** Edges whose strip was shrunk because nothing in it applies to this photo. */
    val collapsed: Set<LayerArea> = emptySet(),
) {
    fun area(layer: WatermarkLayer): PixelRect {
        val band = area(layer.area)
        val inBrandRow = layer.kind == LayerKind.BRAND && layer.reserveBrandSpace
        val box = when {
            layer.area == LayerArea.TOP && topBrandHeight > 0f -> {
                val split = band.bottom - topBrandHeight
                if (inBrandRow) band.copy(top = split) else band.copy(bottom = split)
            }
            layer.area == LayerArea.BOTTOM && bottomBrandHeight > 0f -> {
                val split = band.top + bottomBrandHeight
                if (inBrandRow) band.copy(bottom = split) else band.copy(top = split)
            }
            else -> band
        }
        return box.copy(left = box.left + box.width * layer.column.start, right = box.left + box.width * layer.column.end)
    }

    fun area(area: LayerArea) = when (area) {
        LayerArea.PHOTO -> photo
        LayerArea.BOTTOM -> PixelRect(0f, photo.bottom, canvas.width.toFloat(), canvas.height.toFloat())
        LayerArea.TOP -> PixelRect(0f, 0f, canvas.width.toFloat(), photo.top)
        LayerArea.LEFT -> PixelRect(0f, photo.top, photo.left, photo.bottom)
        LayerArea.RIGHT -> PixelRect(photo.right, photo.top, canvas.width.toFloat(), photo.bottom)
        LayerArea.CANVAS -> PixelRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
    }

    companion object {
        fun calculate(
            source: PixelSize, template: WatermarkTemplate, maximumEdge: Int = 0,
            metadata: PhotoMetadata? = null, overrides: Map<MetadataField, String> = emptyMap(),
            maximumShortEdge: Int = 0,
        ): WatermarkLayout {
            val layers = if (metadata == null) template.layers else template.layers.filter {
                it.kind != LayerKind.BRAND || it.brand.resolve(metadata, overrides) != null
            }
            val empty = if (metadata == null || !template.frame.collapseEmpty) emptySet() else {
                // An automatic place is counted as present; the strip stays if the city lookup comes back empty.
                val shown = template.shown(metadata, "·".takeIf { template.place != PlaceStyle.MANUAL && metadata.hasLocation })
                edges.filter { area ->
                    val here = layers.filter { it.area == area && it.visible && it.opacity > 0f }
                    here.isNotEmpty() && here.none { it.hasContent(shown, overrides) }
                }.toSet()
            }
            return calculate(source, template.frame, SizeLimit(maximumEdge, maximumShortEdge), layers, empty)
        }

        private val edges = listOf(LayerArea.TOP, LayerArea.BOTTOM, LayerArea.LEFT, LayerArea.RIGHT)

        private fun WatermarkLayer.hasContent(metadata: PhotoMetadata, overrides: Map<MetadataField, String>) =
            when (kind) {
                LayerKind.TEXT -> text.isNotBlank()
                LayerKind.EXIF -> metadata.display(fields, overrides).isNotBlank()
                LayerKind.BRAND -> brand.resolve(metadata, overrides) != null
                LayerKind.LOGO -> logoFile != null
                LayerKind.PALETTE -> true
                LayerKind.LINE -> false
            }

        fun calculate(
            source: PixelSize, template: WatermarkTemplate, settings: ExportSettings,
            metadata: PhotoMetadata?, overrides: Map<MetadataField, String>,
        ) = calculate(source, template, settings.longEdge, metadata, overrides, settings.shortEdge)

        private data class EdgeSpace(val padding: Float, val brandRow: Float = 0f)

        private fun edgeSpace(layers: List<WatermarkLayer>, area: LayerArea, padding: Float): EdgeSpace {
            val visible = layers.filter { it.area == area && it.visible && it.opacity > 0f &&
                (it.kind != LayerKind.BRAND || it.brand != BrandMark.NONE) }
            val (logos, others) = visible.partition { it.kind == LayerKind.BRAND && it.reserveBrandSpace }
            if (logos.isEmpty()) return EdgeSpace(padding)
            val height = logos.maxOf {
                val aspectAllowance = if (it.brandStyle == BrandStyle.WORDMARK || it.brandStyle == BrandStyle.MODEL) .48f else 1f
                (it.size * aspectAllowance).coerceIn(.04f, .75f) + 2 * it.margin.coerceIn(0f, .035f)
            }
            val sharedColumn = logos.any { logo -> others.any {
                logo.column.start < it.column.end && it.column.start < logo.column.end
            } }
            return if (sharedColumn) EdgeSpace(padding + height, height) else EdgeSpace(maxOf(padding, height))
        }

        fun calculate(source: PixelSize, frame: Frame, maximumEdge: Int = 0, maximumShortEdge: Int = 0) =
            calculate(source, frame, SizeLimit(maximumEdge, maximumShortEdge), emptyList())

        private fun calculate(
            source: PixelSize, frame: Frame, limit: SizeLimit, layers: List<WatermarkLayer>,
            empty: Set<LayerArea> = emptySet(),
        ): WatermarkLayout {
            require(source.width > 0 && source.height > 0)
            val unit = source.shortEdge.toFloat()
            fun edge(area: LayerArea, value: Float, across: Float) =
                (if (area in empty) minOf(value, across) else value).coerceIn(0f, 1f)
            val sides = maxOf(frame.left, frame.right)
            val ends = maxOf(frame.top, frame.bottom)
            val left = (edge(LayerArea.LEFT, frame.left, ends) * unit).roundToInt()
            val right = (edge(LayerArea.RIGHT, frame.right, ends) * unit).roundToInt()
            val kept = layers.filterNot { it.area in empty }
            val topSpace = edgeSpace(kept, LayerArea.TOP, edge(LayerArea.TOP, frame.top, sides))
            val bottomSpace = edgeSpace(kept, LayerArea.BOTTOM, edge(LayerArea.BOTTOM, frame.bottom, sides))
            val top = (topSpace.padding * unit).roundToInt()
            val bottom = (bottomSpace.padding * unit).roundToInt()
            var width = source.width + left + right
            var height = source.height + top + bottom
            var photoLeft = left.toFloat()
            var photoTop = top.toFloat()
            if (frame.ratio != FrameRatio.ORIGINAL) {
                val multiplier = ceil(max(width.toDouble() / frame.ratio.across, height.toDouble() / frame.ratio.down))
                    .toInt()
                val newWidth = multiplier * frame.ratio.across
                val newHeight = multiplier * frame.ratio.down
                photoLeft += (newWidth - width) / 2f
                photoTop += (newHeight - height) / 2f
                width = newWidth
                height = newHeight
            }
            val canvas = PixelSize(width, height).fit(limit.longEdge, limit.shortEdge)
            val scale = canvas.longEdge.toFloat() / max(width, height)
            val photo = PixelRect(
                photoLeft * scale, photoTop * scale,
                (photoLeft + source.width) * scale, (photoTop + source.height) * scale,
            )
            return WatermarkLayout(canvas, photo, unit * scale, (topSpace.brandRow * unit).roundToInt() * scale,
                (bottomSpace.brandRow * unit).roundToInt() * scale, empty)
        }
    }
}
