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

    @Test fun logoStyleUsesTheDetectedBrandAndRoundTripsWithPresets() {
        val legacy = storageJson.decodeFromString<WatermarkLayer>("""{"id":"brand","kind":"BRAND","brand":"AUTO"}""")
        assertEquals(BrandStyle.ORIGINAL, legacy.brandStyle)
        val square = legacy.copy(brandStyle = BrandStyle.SQUARE)
        assertEquals(square, storageJson.decodeFromString<WatermarkLayer>(storageJson.encodeToString(square)))
        assertEquals("nikon", BrandMark.detect(null, "NIKON ZR")?.graphic(square.brandStyle)?.asset)
        assertEquals("nikon-wordmark", BrandMark.NIKON_Z.graphic(BrandStyle.WORDMARK)?.asset)
        assertEquals("sony", BrandMark.SONY_ALPHA.graphic(BrandStyle.WORDMARK)?.asset)
        assertEquals("om-system-square", BrandMark.OM_SYSTEM.graphic(BrandStyle.SQUARE)?.asset)
        assertEquals(BrandGraphic("canon", squareBadge = true), BrandMark.CANON.graphic(square.brandStyle))
        assertNull(BrandMark.NONE.graphic(square.brandStyle))
    }

    @Test fun splitFooterHasIndependentLogoAndParameterBounds() {
        val layout = WatermarkLayout.calculate(PixelSize(4032, 6048), Templates.zr.frame)
        val brand = Templates.zr.layers.single { it.kind == LayerKind.BRAND }
        val data = Templates.zr.layers.single { it.id == "exif" }
        assertEquals(layout.area(brand).right, layout.area(data).left, 0f)
        assertTrue(layout.area(brand).width < layout.area(data).width)
    }

    @Test fun centeredFooterReservesRoomForNikonSquareAndKeepsTheCoverPhotoWhole() {
        val source = PixelSize(900, 1200)
        val template = Templates.frostedJournal
        val original = template.layers.single { it.kind == LayerKind.BRAND }
        val brand = BrandPlacement.FOOT_CENTER.place(original.copy(brandStyle = BrandStyle.SQUARE, x = .3f))
        assertEquals(0f, brand.x)
        assertEquals(BrandStyle.SQUARE, brand.brandStyle)
        val placed = template.copy(layers = template.layers.map { if (it.id == brand.id) brand else it })
        val layout = WatermarkLayout.calculate(source, placed)
        val logoBox = layout.area(brand)
        val parameters = layout.area(placed.layers.first { it.id == "exif" })
        assertEquals(logoBox.bottom, parameters.top, 0f)
        assertEquals(layout.photo.bottom, logoBox.top, 0f)
        assertEquals(source.height.toFloat(), layout.photo.height, .01f)
        assertEquals(layout.canvas.width.toFloat(), logoBox.width, .01f)
        assertEquals(placed, storageJson.decodeFromString<WatermarkTemplate>(storageJson.encodeToString(placed)))
    }

    @Test fun movingFromABorderlessPhotoToAnEdgeAndBackOnlyAddsTheRequestedSpace() {
        val source = PixelSize(1200, 800)
        val brand = Templates.brandStamp.layers.first { it.kind == LayerKind.BRAND }
        for (placement in BrandPlacement.entries) {
            val moved = placement.place(brand)
            val template = WatermarkTemplate("test", "test", layers = listOf(moved))
            val layout = WatermarkLayout.calculate(source, template)
            assertTrue(layout.area(moved).height > 0)
            assertEquals(source.width.toFloat(), layout.photo.width, .01f)
            assertEquals(placement.area == LayerArea.PHOTO, layout.canvas == source)
            assertEquals(source, WatermarkLayout.calculate(source, template, metadata = PhotoMetadata()).canvas)
            assertEquals(layout.canvas, WatermarkLayout.calculate(source, template.copy(layers = listOf(placement.place(moved)))).canvas)
        }
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
