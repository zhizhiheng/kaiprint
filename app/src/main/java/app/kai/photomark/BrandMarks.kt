package app.kai.photomark

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@Serializable
enum class BrandTone(val label: String) {
    ORIGINAL("品牌原色"), LIGHT("浅色"), DARK("深色"),
}

@Serializable
enum class BrandStyle(val label: String) {
    ORIGINAL("原版"), WORDMARK("横版字标"), SYMBOL("独立图标"), SQUARE("方形"),
}

internal data class BrandGraphic(val asset: String, val tintable: Boolean = true, val squareBadge: Boolean = false)

@Serializable
enum class BrandMark(val label: String, val asset: String? = null, val tintable: Boolean = true) {
    AUTO("按照片自动识别"),
    NONE("隐藏标志"),
    NIKON("Nikon", "nikon", false),
    NIKON_Z("Nikon Z", "nikon-z"),
    CANON("Canon", "canon"),
    SONY("Sony", "sony"),
    SONY_ALPHA("Sony α", "sony-alpha"),
    FUJIFILM("FUJIFILM", "fujifilm"),
    LUMIX("LUMIX", "lumix"),
    PANASONIC("Panasonic", "panasonic"),
    LEICA("Leica", "leica", false),
    OM_SYSTEM("OM SYSTEM", "om-system"),
    OLYMPUS("OLYMPUS", "olympus", false),
    PENTAX("PENTAX", "pentax"),
    RICOH("RICOH", "ricoh"),
    HASSELBLAD("Hasselblad", "hasselblad"),
    DJI("DJI", "dji"),
    SIGMA("SIGMA", "sigma"),
    APPLE("Apple", "apple"),
    GOPRO("GoPro", "gopro", false),
    INSTA360("Insta360", "insta360"),
    HUAWEI("HUAWEI", "huawei", false),
    XIAOMI("Xiaomi", "xiaomi");

    internal fun graphic(style: BrandStyle): BrandGraphic? {
        val original = BrandGraphic(asset ?: return null, tintable)
        return when (style) {
            BrandStyle.ORIGINAL -> original
            BrandStyle.WORDMARK -> when (this) {
                NIKON, NIKON_Z -> BrandGraphic("nikon-wordmark")
                SONY_ALPHA -> BrandGraphic("sony")
                FUJIFILM -> BrandGraphic("fujifilm-wordmark")
                LUMIX -> BrandGraphic("panasonic")
                LEICA -> BrandGraphic("leica-wordmark")
                OLYMPUS -> BrandGraphic("olympus-wordmark")
                GOPRO -> BrandGraphic("gopro-wordmark")
                HUAWEI -> BrandGraphic("huawei-wordmark")
                else -> original
            }
            BrandStyle.SYMBOL -> when (this) {
                HUAWEI -> BrandGraphic("huawei-symbol")
                else -> original
            }
            BrandStyle.SQUARE -> when (this) {
                NIKON, NIKON_Z -> BrandGraphic("nikon", tintable = false)
                OM_SYSTEM -> BrandGraphic("om-system-square")
                XIAOMI -> original
                else -> original.copy(squareBadge = true)
            }
        }
    }

    fun resolve(metadata: PhotoMetadata, overrides: Map<MetadataField, String>): BrandMark? =
        when (this) {
            AUTO -> detect(
                overrides[MetadataField.MAKE] ?: metadata.values[MetadataField.MAKE],
                overrides[MetadataField.MODEL] ?: metadata.values[MetadataField.MODEL],
            )
            NONE -> null
            else -> this
        }

    companion object {
        private val nikonZ = Regex("^Z[ -]*(?:[0-9]|R\\b|F(?:C)?\\b|CINEMA)")
        private val olympusModel = Regex("^E-(?:M[0-9]|P[0-9]|PL|PM)")
        private val omModel = Regex("^OM[ -]?(?:1|3|5)(?:\\b|MARK)")
        private val sonyAlpha = Regex("^(?:ILCE|ILCA|ILME|NEX|SLT)(?:[- ]|[0-9])")
        private val fujiModel = Regex("^(?:GFX(?:[ -]|[0-9])|X-(?:T|S|H|E|PRO|A|M)[0-9]|X100)")
        private val lumixModel = Regex("^(?:DC-|DMC-)[A-Z0-9]")
        private val pentaxModel = Regex("^(?:PENTAX|K-[0-9]|KP\\b|645[ZD]|K-?1[0-9]D)")
        private val ricohModel = Regex("^GR(?:[ -]|[0-9]|DIGITAL|$)")

        fun detect(makeValue: String?, modelValue: String?): BrandMark? {
            val make = normalized(makeValue)
            val model = normalized(modelValue)
            val camera = model.removePrefix("NIKON ").removePrefix("SONY ").trim()
            return when {
                make.startsWith("NIKON") || make.isEmpty() && model.startsWith("NIKON") ->
                    if (nikonZ.containsMatchIn(camera)) NIKON_Z else NIKON
                make.startsWith("CANON") -> CANON
                make.startsWith("SONY") ->
                    if (sonyAlpha.containsMatchIn(camera) || camera.startsWith("α", true)) SONY_ALPHA else SONY
                make.startsWith("FUJI") -> FUJIFILM
                make.startsWith("PANASONIC") || make.startsWith("LUMIX") -> LUMIX
                make.startsWith("LEICA") -> LEICA
                make.startsWith("OM DIGITAL") || make.startsWith("OM SYSTEM") || make.startsWith("OMDS") -> OM_SYSTEM
                make.startsWith("OLYMPUS") -> if (omModel.containsMatchIn(model)) OM_SYSTEM else OLYMPUS
                make.startsWith("PENTAX") -> PENTAX
                make.startsWith("RICOH") -> if (pentaxModel.containsMatchIn(model)) PENTAX else RICOH
                make.startsWith("HASSELBLAD") -> HASSELBLAD
                make.startsWith("DJI") -> DJI
                make.startsWith("SIGMA") -> SIGMA
                make.startsWith("APPLE") -> APPLE
                make.startsWith("GOPRO") -> GOPRO
                make.startsWith("INSTA360") || make.startsWith("ARASHI VISION") -> INSTA360
                make.startsWith("HUAWEI") -> HUAWEI
                listOf("XIAOMI", "REDMI", "POCO").any(make::startsWith) -> XIAOMI
                make.isNotEmpty() -> null
                nikonZ.containsMatchIn(camera) -> NIKON_Z
                sonyAlpha.containsMatchIn(camera) || camera.startsWith("α", true) -> SONY_ALPHA
                model.startsWith("SONY") || model.startsWith("DSC-") || model.startsWith("ZV-") -> SONY
                model.startsWith("CANON ") || model.startsWith("EOS ") || model.startsWith("POWERSHOT") ||
                    model.startsWith("IXUS") -> CANON
                model.startsWith("FUJIFILM") || fujiModel.containsMatchIn(model) -> FUJIFILM
                model.startsWith("LUMIX") || lumixModel.containsMatchIn(model) -> LUMIX
                model.startsWith("LEICA") -> LEICA
                omModel.containsMatchIn(model) -> OM_SYSTEM
                model.startsWith("OLYMPUS") || olympusModel.containsMatchIn(model) -> OLYMPUS
                pentaxModel.containsMatchIn(model) -> PENTAX
                model.startsWith("RICOH") || ricohModel.containsMatchIn(model) -> RICOH
                model.startsWith("HASSELBLAD") || model.startsWith("X2D") || model.startsWith("X1D") -> HASSELBLAD
                model.startsWith("DJI") -> DJI
                model.startsWith("SIGMA") -> SIGMA
                model.startsWith("IPHONE") || model.startsWith("IPAD") -> APPLE
                model.startsWith("GOPRO") || model.startsWith("HERO") -> GOPRO
                model.startsWith("INSTA360") -> INSTA360
                model.startsWith("HUAWEI") -> HUAWEI
                listOf("XIAOMI", "REDMI", "POCO").any(model::startsWith) -> XIAOMI
                else -> null
            }
        }

        private fun normalized(value: String?) =
            Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC).trim().uppercase(Locale.ROOT)
    }
}
