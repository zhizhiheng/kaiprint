package app.kai.photomark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraCatalogTest {
    @Test fun exifCodesBecomeCatalogNames() {
        val examples = listOf(
            Triple("NIKON CORPORATION", "NIKON Z 6_3", "Z6III"),
            Triple("NIKON CORPORATION", "NIKON Z 8", "Z8"),
            Triple("NIKON CORPORATION", "NIKON Z 5_2", "Z5II"),
            Triple("NIKON CORPORATION", "NIKON Z 50_2", "Z50II"),
            Triple("NIKON CORPORATION", "NIKON Z f", "Zf"),
            Triple("NIKON CORPORATION", "NIKON Z fc", "Zfc"),
            Triple("NIKON CORPORATION", "NIKON ZR", "ZR"),
            Triple("NIKON CORPORATION", "NIKON D850", "D850"),
            Triple("Canon", "Canon EOS R5m2", "EOS R5 Mark II"),
            Triple("Canon", "Canon EOS R6m2", "EOS R6 Mark II"),
            Triple("Canon", "Canon EOS 5D Mark IV", "EOS 5D Mark IV"),
            Triple("Canon", "Canon EOS R50 V", "EOS R50 V"),
            Triple("SONY", "ILCE-7M4", "α7 IV"),
            Triple("SONY", "ILCE-7RM5", "α7R V"),
            Triple("SONY", "ILCE-7SM3", "α7S III"),
            Triple("SONY", "ILCE-7CM2", "α7C II"),
            Triple("SONY", "ILCE-7CR", "α7CR"),
            Triple("SONY", "ILCE-1M2", "α1 II"),
            Triple("SONY", "ILCE-6700", "α6700"),
            Triple("SONY", "ILCE-7RM4A", "α7R IVA"),
            Triple("SONY", "ILME-FX3", "FX3"),
            Triple("SONY", "DSC-RX100M7", "RX100 VII"),
            Triple("SONY", "ZV-E10M2", "ZV-E10 II"),
            Triple("FUJIFILM", "X-T5", "X-T5"),
            Triple("FUJIFILM", "GFX100 II", "GFX100 II"),
            Triple("Panasonic", "DC-S5M2", "S5II"),
            Triple("Panasonic", "DC-S5M2X", "S5IIX"),
            Triple("Panasonic", "DC-S1RM2", "S1RII"),
            Triple("Panasonic", "DC-S1M2ES", "S1IIE"),
            Triple("Panasonic", "DC-GH5S", "GH5S"),
            Triple("Panasonic", "DC-G9M2", "G9II"),
            Triple("OM Digital Solutions", "OM-1MarkII", "OM-1 Mark II"),
            Triple("OLYMPUS CORPORATION", "E-M1MarkIII", "E-M1 Mark III"),
            Triple("RICOH IMAGING COMPANY, LTD.", "PENTAX K-3 Mark III", "K-3 Mark III"),
            Triple("RICOH IMAGING COMPANY, LTD.", "RICOH GR IIIx", "GR IIIx"),
            Triple("LEICA CAMERA AG", "LEICA Q3", "Q3"),
            Triple("Apple", "iPhone 15 Pro", "iPhone 15 Pro"),
            Triple("Unknown maker", "Model 7", "Model 7"),
        )
        for ((make, model, expected) in examples) {
            assertEquals("$make $model", expected, Cameras.model(make, model))
        }
    }

    @Test fun makersReadLikeTheirBrands() {
        assertEquals("Nikon", Cameras.make("NIKON CORPORATION", "NIKON Z 9"))
        assertEquals("Sony", Cameras.make("SONY", "ILCE-7M4"))
        assertEquals("Panasonic", Cameras.make("Panasonic", "DC-S5M2"))
        assertEquals("OM SYSTEM", Cameras.make("OM Digital Solutions", "OM-1"))
        assertEquals("OLYMPUS", Cameras.make("OLYMPUS CORPORATION", "E-M10MarkIV"))
        assertEquals("PENTAX", Cameras.make("RICOH IMAGING COMPANY, LTD.", "PENTAX K-1 Mark II"))
        assertEquals("Small Maker Co.", Cameras.make(" Small Maker Co. ", "X"))
    }

    @Test fun modelMarksMatchExifTypedNamesAndOnlyTheirOwnBrand() {
        fun mark(brand: BrandMark, make: String?, model: String?) = Cameras.mark(brand, make, model)?.file
        assertEquals("models/nikon/z-6iii.svg", mark(BrandMark.NIKON_Z, "NIKON CORPORATION", "NIKON Z 6_3"))
        assertEquals("models/nikon/zr.svg", mark(BrandMark.NIKON_Z, "NIKON CORPORATION", "NIKON ZR"))
        assertEquals("models/nikon/zf.svg", mark(BrandMark.NIKON_Z, null, "Zf"))
        assertEquals("models/sony/a7-iv.svg", mark(BrandMark.SONY_ALPHA, "SONY", "ILCE-7M4"))
        assertEquals("models/sony/a7r-v.svg", mark(BrandMark.SONY_ALPHA, "Sony", "α7R V"))
        assertEquals("models/canon/eos-r5-ii.svg", mark(BrandMark.CANON, "Canon", "Canon EOS R5m2"))
        assertEquals("models/canon/eos-r5.svg", mark(BrandMark.CANON, "Canon", "Canon EOS R5"))
        assertEquals("models/fujifilm/x-t5.svg", mark(BrandMark.FUJIFILM, "FUJIFILM", "X-T5"))
        assertEquals("models/lumix/s5-ii.svg", mark(BrandMark.LUMIX, "Panasonic", "DC-S5M2"))
        assertEquals("models/om-system/om-1-ii.svg", mark(BrandMark.OM_SYSTEM, "OM Digital Solutions", "OM-1MarkII"))
        assertEquals("models/om-system/pen-e-p7.svg", mark(BrandMark.OLYMPUS, "OLYMPUS CORPORATION", "E-P7"))
        assertEquals("models/pentax/k-3-iii.svg", mark(BrandMark.PENTAX, "RICOH IMAGING COMPANY, LTD.", "PENTAX K-3 Mark III"))
        // Hand-typed names in any spacing or case, as people enter them for film or adapted bodies.
        assertEquals("models/nikon/z-6iii.svg", mark(BrandMark.NIKON_Z, null, "z6 iii"))
        assertEquals("models/canon/eos-r6-ii.svg", mark(BrandMark.CANON, null, "EOS R6 Mark II"))
        // Neighbouring generations never share a mark.
        assertEquals("models/nikon/z-6ii.svg", mark(BrandMark.NIKON_Z, "NIKON CORPORATION", "NIKON Z 6_2"))
        assertEquals("models/sony/a7c.svg", mark(BrandMark.SONY_ALPHA, "SONY", "ILCE-7C"))
        assertEquals("models/sony/a7cr.svg", mark(BrandMark.SONY_ALPHA, "SONY", "ILCE-7CR"))
        // No mark for bodies outside the library, and none across brands.
        assertNull(mark(BrandMark.NIKON, "NIKON CORPORATION", "NIKON D6"))
        assertNull(mark(BrandMark.CANON, "NIKON CORPORATION", "NIKON Z 8"))
        assertNull(mark(BrandMark.SONY_ALPHA, "SONY", null))
    }

    @Test fun modelStyleFallsBackToTheBrandMark() {
        val zr = PhotoMetadata(mapOf(MetadataField.MAKE to "Nikon", MetadataField.MODEL to "ZR"))
        val model = BrandMark.NIKON_Z.graphic(BrandStyle.MODEL, zr, emptyMap())
        assertEquals("models/nikon/zr.svg", model?.file)
        assertTrue(model!!.model)
        val d6 = PhotoMetadata(mapOf(MetadataField.MAKE to "Nikon", MetadataField.MODEL to "D6"))
        assertEquals(BrandMark.NIKON.graphic(BrandStyle.ORIGINAL), BrandMark.NIKON.graphic(BrandStyle.MODEL, d6, emptyMap()))
        // A per-photo override of the model changes the mark too.
        val overridden = BrandMark.NIKON_Z.graphic(BrandStyle.MODEL, d6, mapOf(MetadataField.MODEL to "Z8"))
        assertEquals("models/nikon/z-8.svg", overridden?.file)
    }

    @Test fun bundledMarksAreTheLibraryFiles() {
        val app = File(".").canonicalFile.let { if (it.name == "app") it else File(it, "app") }
        val library = File(app.parentFile, "model-marks")
        assertTrue(modelMarks.size >= 70)
        for (mark in modelMarks) {
            val bundled = File(app, "src/main/assets/${mark.file}")
            val source = File(library, mark.file.removePrefix("models/"))
            assertTrue(mark.file, bundled.isFile)
            assertArrayEquals(mark.file, source.readBytes(), bundled.readBytes())
        }
        assertEquals(modelMarks.sumOf { it.names.size }, modelMarks.flatMap { m -> m.names.map { m.brand to Cameras.key(it) } }.distinct().size)
    }
}
