package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandMarkTest {
    @Test fun recognizesCameraMakersAndSystemMarks() {
        val examples = listOf(
            Triple("NIKON CORPORATION", "NIKON ZR", BrandMark.NIKON_Z),
            Triple("Nikon", "NIKON Z 6_3", BrandMark.NIKON_Z),
            Triple("NIKON CORPORATION", "NIKON D850", BrandMark.NIKON),
            Triple("Canon Inc.", "Canon EOS R5", BrandMark.CANON),
            Triple("ＳＯＮＹ", "ILCE-7M4", BrandMark.SONY_ALPHA),
            Triple("SONY", "DSC-RX100M7", BrandMark.SONY),
            Triple("FUJIFILM", "X-T5", BrandMark.FUJIFILM),
            Triple("Panasonic", "DC-S5M2", BrandMark.LUMIX),
            Triple("OM Digital Solutions", "OM-1", BrandMark.OM_SYSTEM),
            Triple("OLYMPUS IMAGING CORP.", "E-M5MarkII", BrandMark.OLYMPUS),
            Triple("RICOH IMAGING COMPANY, LTD.", "PENTAX K-1", BrandMark.PENTAX),
            Triple("RICOH IMAGING COMPANY, LTD.", "RICOH GR III", BrandMark.RICOH),
            Triple("Hasselblad", "X2D 100C", BrandMark.HASSELBLAD),
            Triple("DJI", "FC3582", BrandMark.DJI),
        )
        for ((make, model, expected) in examples) {
            assertEquals("$make $model", expected, BrandMark.detect(make, model))
        }
    }

    @Test fun modelFallbackAndManualOverridesWorkIndependently() {
        assertEquals(BrandMark.NIKON_Z, BrandMark.detect(null, "ZR"))
        assertEquals(BrandMark.CANON, BrandMark.detect(null, "EOS R5"))
        assertEquals(BrandMark.SONY_ALPHA, BrandMark.detect(null, "ILCE-7M4"))
        assertNull(BrandMark.detect("Independent camera", "ILCE-7M4"))
        assertNull(BrandMark.detect(null, "unknown"))
        val exif = PhotoMetadata(mapOf(MetadataField.MAKE to "NIKON CORPORATION", MetadataField.MODEL to "NIKON ZR"))
        assertEquals(BrandMark.NIKON_Z, BrandMark.AUTO.resolve(exif, emptyMap()))
        assertEquals(BrandMark.CANON, BrandMark.CANON.resolve(exif, emptyMap()))
        assertEquals(BrandMark.CANON, BrandMark.AUTO.resolve(exif, mapOf(MetadataField.MAKE to "Canon")))
        assertNull(BrandMark.NONE.resolve(exif, emptyMap()))
    }

    @Test fun splitFooterHasIndependentLogoAndParameterBounds() {
        val layout = WatermarkLayout.calculate(PixelSize(4032, 6048), Templates.zr.frame)
        val brand = Templates.zr.layers.single { it.kind == LayerKind.BRAND }
        val data = Templates.zr.layers.single { it.id == "exif" }
        assertEquals(layout.area(brand).right, layout.area(data).left, 0f)
        assertTrue(layout.area(brand).width < layout.area(data).width)
    }

    @Test fun onlyTheOriginalZrTextPresetGetsUpgraded() {
        val text = WatermarkLayer("zr", LayerKind.TEXT, "ZR", area = LayerArea.BOTTOM, anchor = Anchor.CENTER_LEFT)
        val old = WatermarkTemplate("zr", "ZR 电影卡", layers = listOf(text))
        val migrated = Templates.upgradeLegacyZr(old)
        assertEquals(LayerKind.BRAND, migrated.layers.single().kind)
        assertEquals(BrandMark.AUTO, migrated.layers.single().brand)
        val custom = old.copy(id = "my-preset")
        assertEquals(custom, Templates.upgradeLegacyZr(custom))
    }
}
