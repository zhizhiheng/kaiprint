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
) {
    val format get() = PhotoFormat.identify(name, mime)
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
    FOCAL_EQUIVALENT("等效焦距"), TITLE("作品标题"), AUTHOR("署名"), LOCATION("地点");

    companion object {
        val captionFields = listOf(TITLE, AUTHOR, LOCATION)
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
) {
    fun display(fields: List<MetadataField>, overrides: Map<MetadataField, String>): String =
        fields.mapNotNull { (overrides[it] ?: values[it])?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString("   ·   ")
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

data class PhotoInfo(val metadata: PhotoMetadata, val choices: List<PixelChoice>)

@Serializable
enum class FrameRatio(val label: String, val across: Int, val down: Int) {
    ORIGINAL("自由留白", 0, 0), SQUARE("1:1", 1, 1), PORTRAIT("4:5", 4, 5), STORY("9:16", 9, 16)
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
)

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
) {
    val canUseGlassInk get() = !tile && background == null && area != LayerArea.PHOTO && area != LayerArea.CANVAS &&
        kind in listOf(LayerKind.TEXT, LayerKind.EXIF, LayerKind.BRAND)
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
)

@Serializable
data class EditDraft(
    val template: WatermarkTemplate,
    val overrides: Map<String, Map<MetadataField, String>> = emptyMap(),
    val pixelChoices: Map<String, PixelKind> = emptyMap(),
)

@Serializable
enum class OutputFormat(val label: String, val extension: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"), PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp"), TIFF("TIFF", "tif", "image/tiff")
}

@Serializable
enum class OutputColor(val label: String) {
    SRGB("sRGB"), DISPLAY_P3("Display P3")
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
)

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

    fun fit(longEdge: Int): PixelSize {
        if (longEdge == 0 || this.longEdge <= longEdge) return this
        val scale = longEdge.toDouble() / this.longEdge
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
        ): WatermarkLayout {
            val layers = if (metadata == null) template.layers else template.layers.filter {
                it.kind != LayerKind.BRAND || it.brand.resolve(metadata, overrides) != null
            }
            return calculate(source, template.frame, maximumEdge, layers)
        }

        private data class EdgeSpace(val padding: Float, val brandRow: Float = 0f)

        private fun edgeSpace(layers: List<WatermarkLayer>, area: LayerArea, padding: Float): EdgeSpace {
            val visible = layers.filter { it.area == area && it.visible && it.opacity > 0f &&
                (it.kind != LayerKind.BRAND || it.brand != BrandMark.NONE) }
            val (logos, others) = visible.partition { it.kind == LayerKind.BRAND && it.reserveBrandSpace }
            if (logos.isEmpty()) return EdgeSpace(padding)
            val height = logos.maxOf {
                val aspectAllowance = if (it.brandStyle == BrandStyle.WORDMARK) .48f else 1f
                (it.size * aspectAllowance).coerceIn(.04f, .75f) + 2 * it.margin.coerceIn(0f, .035f)
            }
            val sharedColumn = logos.any { logo -> others.any {
                logo.column.start < it.column.end && it.column.start < logo.column.end
            } }
            return if (sharedColumn) EdgeSpace(padding + height, height) else EdgeSpace(maxOf(padding, height))
        }

        fun calculate(source: PixelSize, frame: Frame, maximumEdge: Int = 0) =
            calculate(source, frame, maximumEdge, emptyList())

        private fun calculate(source: PixelSize, frame: Frame, maximumEdge: Int, layers: List<WatermarkLayer>): WatermarkLayout {
            require(source.width > 0 && source.height > 0)
            val unit = source.shortEdge.toFloat()
            val left = (frame.left.coerceIn(0f, 1f) * unit).roundToInt()
            val right = (frame.right.coerceIn(0f, 1f) * unit).roundToInt()
            val topSpace = edgeSpace(layers, LayerArea.TOP, frame.top.coerceIn(0f, 1f))
            val bottomSpace = edgeSpace(layers, LayerArea.BOTTOM, frame.bottom.coerceIn(0f, 1f))
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
            val canvas = PixelSize(width, height).fit(maximumEdge)
            val scale = canvas.longEdge.toFloat() / max(width, height)
            val photo = PixelRect(
                photoLeft * scale, photoTop * scale,
                (photoLeft + source.width) * scale, (photoTop + source.height) * scale,
            )
            return WatermarkLayout(canvas, photo, unit * scale, (topSpace.brandRow * unit).roundToInt() * scale,
                (bottomSpace.brandRow * unit).roundToInt() * scale)
        }
    }
}
