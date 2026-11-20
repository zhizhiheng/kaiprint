package app.kai.photomark

/**
 * Reads the film simulation a Fujifilm camera recorded for a photo. The camera stores it in its maker note,
 * a private block inside EXIF, so it never shows up among the standard tags.
 */
object FilmSimulation {
    private const val SATURATION = 0x1003
    private const val FILM_MODE = 0x1401
    private const val SHORT = 3

    /** Black-and-white simulations are recorded as a saturation setting, with no film mode at all. */
    private val monochrome = mapOf(
        0x300 to "MONOCHROME", 0x301 to "MONOCHROME + R", 0x302 to "MONOCHROME + Ye", 0x303 to "MONOCHROME + G",
        0x310 to "SEPIA",
        0x500 to "ACROS", 0x501 to "ACROS + R", 0x502 to "ACROS + Ye", 0x503 to "ACROS + G",
    )

    /** Codes of older studio modes that never carried a film name are left out. */
    private val colour = mapOf(
        0x000 to "PROVIA", 0x120 to "ASTIA", 0x200 to "Velvia", 0x400 to "Velvia",
        0x500 to "PRO Neg. Std", 0x501 to "PRO Neg. Hi", 0x600 to "CLASSIC CHROME", 0x700 to "ETERNA",
        0x800 to "CLASSIC Neg.", 0x900 to "ETERNA BLEACH BYPASS", 0xa00 to "NOSTALGIC Neg.", 0xb00 to "REALA ACE",
    )

    /** The simulation named in [note], or null when it is not a Fujifilm maker note or names none we know. */
    fun read(note: ByteArray?): String? {
        if (note == null || note.size < 14 || String(note, 0, 8, Charsets.US_ASCII) != "FUJIFILM") return null
        // Fujifilm always writes little-endian, with offsets counted from the start of the note.
        val start = note.int(8)
        if (start < 12 || start > note.size - 2) return null
        var film: Int? = null
        var saturation: Int? = null
        for (index in 0 until note.short(start)) {
            val entry = start + 2 + index * 12
            if (entry + 12 > note.size) break
            val tag = note.short(entry)
            if (tag != FILM_MODE && tag != SATURATION) continue
            if (note.short(entry + 2) != SHORT || note.int(entry + 4) != 1) continue
            val value = note.short(entry + 8)
            if (tag == FILM_MODE) film = value else saturation = value
        }
        return monochrome[saturation] ?: colour[film]
    }

    private fun ByteArray.short(at: Int) = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)
    private fun ByteArray.int(at: Int) = short(at) or (short(at + 2) shl 16)
}
