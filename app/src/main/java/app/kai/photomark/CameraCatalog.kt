package app.kai.photomark

import java.text.Normalizer
import java.util.Locale

/** A body-specific mark from the bundled model-mark library. [names] are the titles EXIF or people use. */
internal data class ModelMark(val brand: BrandMark, val name: String, val file: String, val aliases: List<String>) {
    constructor(brand: BrandMark, name: String, file: String, vararg aliases: String) :
        this(brand, name, file, aliases.toList())

    val names get() = listOf(name) + aliases
}

/** Turns the maker codes cameras write into EXIF into the names printed on the body and in catalogs. */
object Cameras {
    private val byKey by lazy {
        modelMarks.flatMap { mark -> mark.names.map { (mark.brand to key(it)) to mark } }.toMap()
    }

    /** The brand as people write it, such as Nikon for "NIKON CORPORATION". Unknown makers keep their own text. */
    fun make(make: String?, model: String?): String? {
        val brand = BrandMark.detect(make, model) ?: return clean(make).ifEmpty { null }
        return when (brand) {
            BrandMark.NIKON_Z -> "Nikon"
            BrandMark.SONY_ALPHA -> "Sony"
            BrandMark.LUMIX -> "Panasonic"
            else -> brand.label
        }
    }

    /** The marketing name of a body, such as Z6III for "NIKON Z 6_3" or α7 IV for "ILCE-7M4". */
    fun model(make: String?, model: String?): String? {
        val raw = clean(model).ifEmpty { return null }
        val brand = BrandMark.detect(make, raw)?.family
        val name = when (brand) {
            BrandMark.NIKON -> nikon(raw.stripPrefix("NIKON"))
            BrandMark.CANON -> canon(raw.stripPrefix("Canon"))
            BrandMark.SONY -> sony(raw.stripPrefix("SONY"))
            BrandMark.FUJIFILM -> raw.stripPrefix("FUJIFILM")
            BrandMark.LUMIX -> lumix(raw.stripPrefix("Panasonic").stripPrefix("LUMIX"))
            BrandMark.OM_SYSTEM -> raw.stripPrefix("OLYMPUS").stripPrefix("OM SYSTEM").replace(markSuffix, " Mark ")
            BrandMark.PENTAX -> raw.stripPrefix("PENTAX").stripPrefix("RICOH")
            BrandMark.RICOH -> raw.stripPrefix("RICOH")
            BrandMark.LEICA -> raw.stripPrefix("LEICA")
            BrandMark.SIGMA -> raw.stripPrefix("SIGMA")
            BrandMark.HASSELBLAD -> raw.stripPrefix("HASSELBLAD").stripPrefix("Hasselblad")
            else -> raw
        }.trim()
        return markFor(brand, name)?.name ?: name.ifEmpty { raw }
    }

    /** The model mark for this body when the library has one and it belongs to [brand]. */
    internal fun mark(brand: BrandMark, make: String?, model: String?): ModelMark? {
        if (model.isNullOrBlank()) return null
        val family = brand.family
        val detected = BrandMark.detect(make, model)?.family
        if (detected != null && detected != family) return null
        return markFor(family, model(make ?: brand.label, model) ?: return null)
    }

    private fun markFor(brand: BrandMark?, name: String): ModelMark? =
        brand?.let { byKey[it to key(name)] }

    /** Compares names regardless of spacing, case, hyphens, "Mark" and the Greek alpha. */
    internal fun key(name: String) = Normalizer.normalize(name, Normalizer.Form.NFKC).uppercase(Locale.ROOT)
        .replace('Α', 'A').replace("MARK", "").filter(Char::isLetterOrDigit)

    private val nikonZ = Regex("^Z\\s*(\\d+)(?:_(\\d))?(C?)$", RegexOption.IGNORE_CASE)
    private val nikonZf = Regex("^Z\\s*(fc|f)$", RegexOption.IGNORE_CASE)
    private val sonyAlpha = Regex("^IL(?:CE|CA)-(\\d+)([A-Z]*?)(?:M(\\d))?([A-Z]?)$")
    private val sonyCinema = Regex("^ILME-(FX\\d+[A-Z]?)$")
    private val sonyCompact = Regex("^(?:DSC-)?(RX\\d+[A-Z]*?|ZV-[A-Z]?\\d+)M(\\d)([A-Z]?)$")
    private val canonMark = Regex("m(\\d)$")
    private val lumixCode = Regex("^(?:DC|DMC)-([A-Z]+\\d+[A-Z]*?)(?:M(\\d))?([A-Z]*)$")
    private val markSuffix = Regex("\\s*Mark\\s*", RegexOption.IGNORE_CASE)

    private fun nikon(name: String): String {
        nikonZf.matchEntire(name)?.let { return "Z" + it.groupValues[1].lowercase(Locale.ROOT) }
        nikonZ.matchEntire(name)?.let {
            val (digits, generation, suffix) = it.destructured
            return "Z$digits${roman(generation)}${suffix.uppercase(Locale.ROOT)}"
        }
        return name
    }

    private fun canon(name: String): String =
        canonMark.find(name)?.let { name.removeRange(it.range).trimEnd() + " Mark " + roman(it.groupValues[1]) } ?: name

    private fun sony(name: String): String {
        sonyCinema.matchEntire(name)?.let { return it.groupValues[1] }
        sonyAlpha.matchEntire(name)?.let {
            val (number, series, generation, tail) = it.destructured
            val version = roman(generation)
            return "α$number$series" + (if (version.isEmpty()) tail else " $version$tail")
        }
        sonyCompact.matchEntire(name)?.let {
            val (base, generation, tail) = it.destructured
            return "$base ${roman(generation)}$tail"
        }
        return name.removePrefix("DSC-")
    }

    private fun lumix(name: String): String {
        val match = lumixCode.matchEntire(name) ?: return name
        val (base, generation, tail) = match.destructured
        return base + roman(generation) + if (tail == "ES") "E" else tail
    }

    private fun roman(digit: String) = when (digit) {
        "2" -> "II"; "3" -> "III"; "4" -> "IV"; "5" -> "V"; "6" -> "VI"; "7" -> "VII"; "8" -> "VIII"
        else -> ""
    }

    private fun String.stripPrefix(prefix: String) =
        if (startsWith("$prefix ", ignoreCase = true)) substring(prefix.length + 1).trim() else this

    internal fun clean(value: String?) =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC).replace('\u0000', ' ').trim().replace(Regex("\\s+"), " ")
}

/** Groups system marks under their maker, so a Z camera and a Nikon logo share one model library. */
val BrandMark.family get() = when (this) {
    BrandMark.NIKON_Z -> BrandMark.NIKON
    BrandMark.SONY_ALPHA -> BrandMark.SONY
    BrandMark.PANASONIC -> BrandMark.LUMIX
    BrandMark.OLYMPUS -> BrandMark.OM_SYSTEM
    else -> this
}

/** Lens names as EXIF records them, tidied for a watermark. */
object Lenses {
    /** Phones name their cameras, not their lenses, so an empty lens name stays empty. */
    private val phones = setOf(
        BrandMark.APPLE, BrandMark.HUAWEI, BrandMark.XIAOMI, BrandMark.SAMSUNG, BrandMark.OPPO, BrandMark.VIVO,
        BrandMark.HONOR, BrandMark.GOOGLE, BrandMark.ONEPLUS, BrandMark.MOTOROLA, BrandMark.NOKIA, BrandMark.ASUS,
    )
    private val thirdParty = listOf(
        "SIGMA", "TAMRON", "Viltrox", "SAMYANG", "TTArtisan", "7Artisans", "LAOWA", "ZEISS", "Voigtländer",
        "Tokina", "Meike", "SIRUI", "YONGNUO", "Rokinon", "Venus Optics", "Mitakon", "Thypoch", "AstrHori",
    )
    private val sigmaName = Regex("\\bD[CG]\\s?(?:DN|HSM|OS)\\b|\\|\\s*(?:Art|Contemporary|Sports|I)\\b")
    private val tamronName = Regex("\\bDi\\s?(?:III|II)?\\b|\\b(?:VXD|RXD)\\b|\\([AB]0\\d\\d\\)")
    private val viltroxName = Regex("^AF \\d+(?:\\.\\d+)?/\\d")
    private val sigmaLine = Regex("\\s*\\|\\s*(Art|Contemporary|Sports|I)(?:\\s+0?\\d{2,3})?\\s*$")
    private val modelCode = Regex("\\s*\\((?:Model\\s+)?[A-Z]\\d{3}[A-Z]?\\)\\s*$")
    private val empty = Regex("^[-\\s.0]*$")
    private val unknownAperture = Regex("(?:^|\\s)0(?:\\.0)?\\s?mm|f/?0(?:\\.0)?$|F0(?:\\.0)?$", RegexOption.IGNORE_CASE)
    private val phone = Regex("^(?:(back|front)\\s+)?(?:(dual|triple|quad)(?:\\s+wide)?\\s+)?camera\\s+", RegexOption.IGNORE_CASE)

    /**
     * The lens shown on the watermark. Unknown placeholders such as "----" or "0mm f/0" give null, so a manual
     * lens can be filled in by hand. Without a lens name, a non-phone camera falls back to the focal range and
     * aperture from LensSpecification.
     */
    fun display(
        lens: String?, lensMake: String?, specification: String?, make: String?, model: String?,
    ): String? {
        val brand = BrandMark.detect(make, model)
        var name = Cameras.clean(lens)
        val cameraModel = Cameras.clean(model)
        if (cameraModel.isNotEmpty() && name.startsWith("$cameraModel ", ignoreCase = true)) {
            name = name.substring(cameraModel.length + 1)
        }
        phone.find(name)?.let { match ->
            val (side, count) = match.destructured
            val where = when (side.lowercase(Locale.ROOT)) { "front" -> "前置"; "back" -> "后置"; else -> "" }
            val lenses = when (count.lowercase(Locale.ROOT)) { "dual" -> "双摄"; "triple" -> "三摄"; "quad" -> "四摄"; else -> "镜头" }
            name = "$where$lenses " + name.substring(match.range.last + 1)
        }
        if (empty.matches(name) || unknownAperture.containsMatchIn(name) ||
            name.lowercase(Locale.ROOT) in setOf("unknown", "n/a", "none", "manual lens", "no lens")
        ) {
            name = ""
        }
        if (name.isEmpty()) {
            if (brand in phones) return null
            name = fromSpecification(specification, brand) ?: return null
        }
        name = name.replace(sigmaLine) { " | ${it.groupValues[1]}" }.replace(modelCode, "")
        val maker = maker(lensMake, name)
        return if (maker != null && !name.startsWith(maker, ignoreCase = true)) "$maker $name" else name
    }

    private fun maker(lensMake: String?, name: String): String? {
        val make = Cameras.clean(lensMake)
        thirdParty.firstOrNull { make.startsWith(it, ignoreCase = true) }?.let { return it }
        if (make.isNotEmpty()) return null
        return when {
            sigmaName.containsMatchIn(name) -> "SIGMA"
            tamronName.containsMatchIn(name) -> "TAMRON"
            viltroxName.containsMatchIn(name) -> "Viltrox"
            else -> null
        }
    }

    internal fun fromSpecification(specification: String?, brand: BrandMark?): String? {
        val values = specification.orEmpty().split(',', ' ').filter(String::isNotBlank).map { part ->
            val pieces = part.split('/')
            val top = pieces[0].toDoubleOrNull() ?: return null
            if (pieces.size == 2) (pieces[1].toDoubleOrNull() ?: return null).let { if (it == 0.0) 0.0 else top / it } else top
        }
        if (values.size != 4) return null
        val (wide, tele, fastWide, fastTele) = values
        if (wide <= 0.0 || !wide.isFinite() || tele < wide) return null
        val focal = if (tele - wide < .5) "${number(wide)}mm" else "${number(wide)}-${number(tele)}mm"
        val stops = listOf(fastWide, fastTele).filter { it > 0.0 && it.isFinite() }.map(::number).distinct()
        if (stops.isEmpty()) return focal
        val aperture = stops.joinToString("-")
        return if (brand?.family == BrandMark.NIKON) "$focal f/$aperture" else "$focal F$aperture"
    }

    private fun number(value: Double): String =
        if (value >= 10) Math.round(value).toString()
        else String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
}
