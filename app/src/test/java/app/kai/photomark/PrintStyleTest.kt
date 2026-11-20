package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintStyleTest {
    @Test fun dateStylesReprintRecordedDates() {
        val recorded = "2026-11-20 17:42:10"
        val expected = mapOf(
            DateStyle.ORIGINAL to recorded, DateStyle.MINUTE to "2026-11-20 17:42", DateStyle.DATE to "2026-11-20",
            DateStyle.DOTTED to "2026.11.20", DateStyle.SLASHED to "2026/11/20", DateStyle.CHINESE to "2026年11月20日",
            DateStyle.ENGLISH to "Nov 20, 2026", DateStyle.STAMP to "'26 11 20",
        )
        assertEquals(DateStyle.entries.toSet(), expected.keys)
        for ((style, text) in expected) assertEquals(style.name, text, style.format(recorded))
        // The label of each fixed style is its own sample.
        assertEquals(DateStyle.DOTTED.label, DateStyle.DOTTED.format(recorded))
        assertEquals(DateStyle.CHINESE.label, DateStyle.CHINESE.format(recorded))
        assertEquals(DateStyle.ENGLISH.label, DateStyle.ENGLISH.format(recorded))
    }

    @Test fun datesInOtherSpellingsAreReadTheSameWay() {
        assertEquals("2026-11-20 17:42", DateStyle.MINUTE.format("2026:11:20 17:42:10"))
        assertEquals("2026-11-20 07:05", DateStyle.MINUTE.format("2026/11/20T7:05"))
        assertEquals("2026-01-05", DateStyle.MINUTE.format(" 2026.1.5 "))
        assertEquals("2026年1月5日", DateStyle.CHINESE.format("2026-01-05"))
        assertEquals("Jan 5, 2026", DateStyle.ENGLISH.format("2026-01-05 08:00"))
        assertEquals("'07 03 09", DateStyle.STAMP.format("2007-3-9"))
    }

    @Test fun textThatIsNotADateIsLeftAlone() {
        for (typed in listOf("初冬", "2026", "2026-13-01", "2026-11-32", "2026-11-20 傍晚", "20 Nov 2026", "")) {
            for (style in DateStyle.entries) assertEquals("$style $typed", typed, style.format(typed))
        }
    }

    @Test fun templatePrintsRecordedAndTypedDatesInItsStyle() {
        val plain = WatermarkTemplate("t", "t")
        val metadata = PhotoMetadata(mapOf(MetadataField.DATE to "2026-11-20 17:42:10", MetadataField.MODEL to "ZR"))
        assertSame(metadata, plain.shown(metadata))
        val chinese = plain.copy(dateStyle = DateStyle.CHINESE)
        val shown = chinese.shown(metadata, "上海")
        assertEquals("2026年11月20日", shown.values[MetadataField.DATE])
        assertEquals("ZR", shown.values[MetadataField.MODEL])
        assertEquals("上海", shown.values[MetadataField.LOCATION])
        assertEquals(mapOf(MetadataField.DATE to "2025年3月9日"), chinese.shown(mapOf(MetadataField.DATE to "2025/3/9")))
        val season = mapOf(MetadataField.DATE to "初冬", MetadataField.ISO to "400")
        assertSame(season, chinese.shown(season))
        val undated = mapOf(MetadataField.ISO to "400")
        assertSame(undated, chinese.shown(undated))
    }

    @Test fun parametersMoveOnePlaceAndStopAtEitherEnd() {
        val fields = listOf(MetadataField.FOCAL, MetadataField.APERTURE, MetadataField.SHUTTER, MetadataField.ISO)
        assertEquals(listOf(MetadataField.APERTURE, MetadataField.FOCAL, MetadataField.SHUTTER, MetadataField.ISO),
            fields.moved(MetadataField.APERTURE, -1))
        assertEquals(listOf(MetadataField.FOCAL, MetadataField.SHUTTER, MetadataField.APERTURE, MetadataField.ISO),
            fields.moved(MetadataField.APERTURE, 1))
        assertEquals(listOf(MetadataField.APERTURE, MetadataField.SHUTTER, MetadataField.ISO, MetadataField.FOCAL),
            fields.moved(MetadataField.FOCAL, 3))
        assertSame(fields, fields.moved(MetadataField.FOCAL, -1))
        assertSame(fields, fields.moved(MetadataField.ISO, 1))
        assertSame(fields, fields.moved(MetadataField.DATE, 1))
        assertEquals(fields, fields.moved(MetadataField.SHUTTER, 1).moved(MetadataField.SHUTTER, -1))
    }

    @Test fun separatorsAreDistinctAndKeepSpaceOnBothSides() {
        assertEquals(Separator.entries.size, Separator.entries.map { it.text }.toSet().size)
        assertEquals(Separator.entries.size, Separator.entries.map { it.label }.toSet().size)
        for (separator in Separator.entries) {
            assertTrue(separator.name, separator.text.startsWith(" ") && separator.text.endsWith(" "))
        }
        assertEquals("50mm   |   f/1.8", listOf("50mm", "f/1.8").joinToString(Separator.BAR.text))
    }

    @Test fun cornerFollowsTheReferenceEdgeAndStopsAtAFullPill() {
        val photo = PixelRect(100f, 100f, 1300f, 900f)
        assertEquals(0f, Frame().cornerRadius(photo, 800f), 0f)
        assertEquals(40f, Frame(corner = .05f).cornerRadius(photo, 800f), .001f)
        assertEquals(80f, Frame(corner = .05f).cornerRadius(photo, 1600f), .001f)
        // Presets edited by hand cannot ask for more than the slider allows, or for less than none.
        assertEquals(Frame.MAX_CORNER * 800f, Frame(corner = 3f).cornerRadius(photo, 800f), .001f)
        assertEquals(0f, Frame(corner = -1f).cornerRadius(photo, 800f), 0f)
        // A thin slice of photo on a large canvas rounds to a pill at most.
        assertEquals(30f, Frame(corner = Frame.MAX_CORNER).cornerRadius(PixelRect(0f, 0f, 900f, 60f), 800f), .001f)
    }

    @Test fun inkFollowsWhatLiesUnderTheLayerOnlyWhereThatIsThePhoto() {
        val onPhoto = WatermarkLayer("exif", LayerKind.EXIF, area = LayerArea.PHOTO, autoPhotoInk = true)
        val solid = Frame()
        val frosted = Frame(background = FrameBackground.FROSTED)
        assertTrue(onPhoto.adaptsInk(solid))
        assertTrue(onPhoto.adaptsInk(frosted))
        assertFalse(onPhoto.copy(autoPhotoInk = false).adaptsInk(solid))
        assertFalse(onPhoto.copy(tile = true).adaptsInk(solid))
        assertFalse(onPhoto.copy(background = "#74000000").adaptsInk(solid))
        // On a plain frame the chosen colour already suits the frame colour.
        val onFrame = WatermarkLayer("exif", LayerKind.EXIF, area = LayerArea.BOTTOM, autoGlassInk = true)
        assertFalse(onFrame.adaptsInk(solid))
        assertTrue(onFrame.adaptsInk(frosted))
        assertFalse(onFrame.copy(area = LayerArea.CANVAS).adaptsInk(frosted))
        // Each switch belongs to its own place: a glass caption moved onto the photo keeps the colour it had.
        assertFalse(onFrame.copy(area = LayerArea.PHOTO).adaptsInk(frosted))
        assertFalse(onPhoto.copy(area = LayerArea.BOTTOM).adaptsInk(frosted))
        for (kind in LayerKind.entries) {
            val expected = kind in listOf(LayerKind.TEXT, LayerKind.EXIF, LayerKind.BRAND)
            assertEquals(kind.name, expected, onPhoto.copy(kind = kind).canAdaptInk)
        }
        // No built-in template changes colour over the photo on its own; the switch is the user's to turn on.
        for (template in Templates.all) {
            for (layer in template.layers) assertFalse("${template.id} ${layer.id}", layer.autoPhotoInk)
        }
    }

    @Test fun presetsSavedBeforeTheseOptionsKeepTheirLook() {
        val old = storageJson.decodeFromString<WatermarkTemplate>(
            """{"id":"mine","title":"旧参数集","frame":{"bottom":0.16},"layers":[{"id":"exif","kind":"EXIF"}]}""")
        assertEquals(DateStyle.ORIGINAL, old.dateStyle)
        assertEquals(0f, old.frame.corner, 0f)
        assertEquals(0f, old.frame.shadow, 0f)
        assertEquals(Separator.DOT, old.layers.single().separator)
        assertEquals(0f, old.layers.single().letterSpacing, 0f)
        assertEquals("   ·   ", Separator.DOT.text)
        // A caption saved on the photo with the glass switch still on keeps the colour it was saved with.
        val moved = storageJson.decodeFromString<WatermarkLayer>(
            """{"id":"exif","kind":"EXIF","area":"PHOTO","autoGlassInk":true}""")
        assertFalse(moved.adaptsInk(Frame(background = FrameBackground.FROSTED)))
    }

    @Test fun newOptionsRoundTripThroughASavedPreset() {
        val styled = Templates.floatingPrint.copy(dateStyle = DateStyle.STAMP)
        assertTrue(styled.frame.corner > 0f && styled.frame.corner <= Frame.MAX_CORNER)
        assertTrue(styled.frame.shadow > 0f && styled.frame.shadow <= Frame.MAX_SHADOW)
        val text = storageJson.encodeToString(styled)
        assertTrue("\"separator\":\"BAR\"" in text)
        assertTrue("\"dateStyle\":\"STAMP\"" in text)
        assertEquals(styled, storageJson.decodeFromString<WatermarkTemplate>(text))
        assertTrue(Templates.floatingPrint in Templates.all)
        // The photo keeps its size: rounding and shadow are drawn, not laid out.
        val source = PixelSize(1200, 800)
        val flat = styled.copy(frame = styled.frame.copy(corner = 0f, shadow = 0f))
        assertEquals(WatermarkLayout.calculate(source, flat), WatermarkLayout.calculate(source, styled))
    }
}
