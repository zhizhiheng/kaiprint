package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilmSimulationTest {
    private val short = 3
    private val long = 4

    /** A maker note laid out as the camera writes it: header, IFD offset, then 12-byte little-endian entries. */
    private fun note(vararg entries: Triple<Int, Int, Int>, header: String = "FUJIFILM", start: Int = 12): ByteArray {
        val bytes = ByteArray(start + 2 + entries.size * 12 + 4)
        header.toByteArray(Charsets.US_ASCII).copyInto(bytes)
        fun put16(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value shr 8).toByte() }
        fun put32(at: Int, value: Int) { put16(at, value and 0xffff); put16(at + 2, value ushr 16) }
        put32(8, start)
        put16(start, entries.size)
        entries.forEachIndexed { index, (tag, type, value) ->
            val at = start + 2 + index * 12
            put16(at, tag); put16(at + 2, type); put32(at + 4, 1); put32(at + 8, value)
        }
        return bytes
    }

    @Test fun namesColourSimulationsFromTheFilmMode() {
        val names = mapOf(
            0x000 to "PROVIA", 0x120 to "ASTIA", 0x200 to "Velvia", 0x400 to "Velvia", 0x500 to "PRO Neg. Std",
            0x501 to "PRO Neg. Hi", 0x600 to "CLASSIC CHROME", 0x700 to "ETERNA", 0x800 to "CLASSIC Neg.",
            0x900 to "ETERNA BLEACH BYPASS", 0xa00 to "NOSTALGIC Neg.", 0xb00 to "REALA ACE",
        )
        for ((code, name) in names) {
            assertEquals(name, FilmSimulation.read(note(Triple(0x1401, short, code))))
        }
    }

    @Test fun blackAndWhiteComesFromTheSaturationSettingAndWinsOverAStaleFilmMode() {
        assertEquals("ACROS", FilmSimulation.read(note(Triple(0x1003, short, 0x500))))
        assertEquals("ACROS + R", FilmSimulation.read(note(Triple(0x1003, short, 0x501))))
        assertEquals("MONOCHROME + Ye", FilmSimulation.read(note(Triple(0x1003, short, 0x302))))
        assertEquals("SEPIA", FilmSimulation.read(note(Triple(0x1003, short, 0x310))))
        assertEquals("ACROS + G", FilmSimulation.read(note(Triple(0x1401, short, 0x600), Triple(0x1003, short, 0x503))))
        // An ordinary saturation level leaves the film mode in charge.
        assertEquals("CLASSIC CHROME", FilmSimulation.read(note(Triple(0x1003, short, 0x100), Triple(0x1401, short, 0x600))))
    }

    @Test fun findsTheEntryAmongOthersAndAtALaterOffset() {
        val entries = arrayOf(Triple(0x0000, 7, 0x30333130), Triple(0x1000, 2, 0x20), Triple(0x1401, short, 0x800),
            Triple(0x1422, short, 1))
        assertEquals("CLASSIC Neg.", FilmSimulation.read(note(*entries)))
        assertEquals("CLASSIC Neg.", FilmSimulation.read(note(*entries, start = 40)))
    }

    @Test fun unknownOrForeignNotesNameNothing() {
        assertNull(FilmSimulation.read(null))
        assertNull(FilmSimulation.read(ByteArray(0)))
        assertNull(FilmSimulation.read(ByteArray(64)))
        assertNull(FilmSimulation.read(note(Triple(0x1401, short, 0x600), header = "Nikon\u0000\u0002\u0010")))
        assertNull(FilmSimulation.read(note(Triple(0x1422, short, 1))))
        // A studio mode without a film name, and a film mode stored in a type the camera never uses.
        assertNull(FilmSimulation.read(note(Triple(0x1401, short, 0x100))))
        assertNull(FilmSimulation.read(note(Triple(0x1401, long, 0x600))))
    }

    @Test fun damagedNotesAreReadAsFarAsTheyGoWithoutFailing() {
        val whole = note(Triple(0x1000, 2, 0x20), Triple(0x1401, short, 0x700))
        assertEquals("ETERNA", FilmSimulation.read(whole))
        // The film entry is cut off, then the IFD offset points outside the note.
        assertNull(FilmSimulation.read(whole.copyOf(12 + 2 + 12 + 6)))
        assertNull(FilmSimulation.read(whole.copyOf(13)))
        val astray = whole.copyOf().also { it[8] = 0x7f; it[9] = 0x7f }
        assertNull(FilmSimulation.read(astray))
        val early = whole.copyOf().also { it[8] = 4 }
        assertNull(FilmSimulation.read(early))
        // A count far beyond the data stops at the end instead of reading past it.
        val greedy = whole.copyOf().also { it[12] = 0xff.toByte(); it[13] = 0xff.toByte() }
        assertEquals("ETERNA", FilmSimulation.read(greedy))
    }
}
