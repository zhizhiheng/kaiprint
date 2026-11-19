package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test fun sourcePixelsMatchInPhotoWatermark() {
        val source = PixelSize(6048, 4032)
        val result = WatermarkLayout.calculate(source, Frame())
        assertEquals(source, result.canvas)
        assertEquals(PixelRect(0f, 0f, 6048f, 4032f), result.photo)
    }

    @Test fun borderExpandsAndFixedRatioDoesNotCrop() {
        val source = PixelSize(6048, 4032)
        val result = WatermarkLayout.calculate(source, Frame(.1f, .05f, .2f, .15f, ratio = FrameRatio.PORTRAIT))
        assertEquals(result.canvas.width * 5, result.canvas.height * 4)
        assertEquals(6048f, result.photo.width, 0f)
        assertEquals(4032f, result.photo.height, 0f)
        assertTrue(result.area(LayerArea.BOTTOM).height >= 605f)
        assertTrue(result.area(LayerArea.RIGHT).width >= 806f)
        assertEquals(2048, WatermarkLayout.calculate(source, Frame(ratio = FrameRatio.STORY), 2048).canvas.longEdge)
    }

    @Test fun everyRatioKeepsTheWholePhotoOnAnExactCanvas() {
        for (source in listOf(PixelSize(6048, 4032), PixelSize(4032, 6048), PixelSize(3000, 3000))) {
            for (ratio in FrameRatio.entries - FrameRatio.ORIGINAL) {
                val result = WatermarkLayout.calculate(source, Frame(bottom = .1f, ratio = ratio))
                assertEquals("$ratio on $source", result.canvas.width * ratio.down, result.canvas.height * ratio.across)
                assertEquals(source.width.toFloat(), result.photo.width, 0f)
                assertEquals(source.height.toFloat(), result.photo.height, 0f)
                assertTrue(result.photo.left >= 0f && result.photo.top >= 0f)
                assertTrue(result.photo.right <= result.canvas.width && result.photo.bottom <= result.canvas.height)
            }
        }
    }

    @Test fun postTargetsUseTheirPlatformShapes() {
        assertEquals(FrameRatio.THREE_FOUR, PostTarget.XIAOHONGSHU.ratio)
        assertEquals(FrameRatio.STORY, PostTarget.DOUYIN.ratio)
        assertEquals(FrameRatio.WIDE, PostTarget.BILIBILI.ratio)
        assertEquals(PostTarget.entries.size, PostTarget.entries.map { it.ratio }.distinct().size)
    }

    @Test fun shortEdgeLimitGivesExactPostSizes() {
        val landscape = PixelSize(6048, 4032)
        val portrait = PixelSize(4032, 6048)
        assertEquals(PixelSize(1080, 1440),
            WatermarkLayout.calculate(landscape, Frame(ratio = FrameRatio.THREE_FOUR), maximumShortEdge = 1080).canvas)
        assertEquals(PixelSize(1080, 1920),
            WatermarkLayout.calculate(portrait, Frame(ratio = FrameRatio.STORY), maximumShortEdge = 1080).canvas)
        assertEquals(PixelSize(1920, 1080),
            WatermarkLayout.calculate(landscape, Frame(ratio = FrameRatio.WIDE), maximumShortEdge = 1080).canvas)
        assertEquals(PixelSize(1440, 1440),
            WatermarkLayout.calculate(portrait, Frame(ratio = FrameRatio.SQUARE), maximumShortEdge = 1440).canvas)
        val settings = ExportSettings().withSize(SizeLimit(shortEdge = 1080))
        val framed = WatermarkLayout.calculate(landscape, Templates.gallery, settings, null, emptyMap())
        assertEquals(1080, framed.canvas.shortEdge)
        assertEquals(framed.canvas.width.toFloat() / framed.canvas.height,
            WatermarkLayout.calculate(landscape, Templates.gallery).canvas.let { it.width.toFloat() / it.height }, .002f)
    }

    @Test fun sizeLimitsNeverEnlargeAndBothCapsHold() {
        val small = PixelSize(800, 600)
        assertEquals(small, small.fit(0, 1080))
        assertEquals(small, small.fit(2048))
        assertEquals(PixelSize(1440, 1080), PixelSize(6048, 4536).fit(0, 1080))
        assertEquals(PixelSize(1080, 810), PixelSize(6048, 4536).fit(1080, 1080))
        assertEquals(PixelSize(6048, 4032), PixelSize(6048, 4032).fit(0, 0))
        assertEquals(SizeLimit.choices.size, SizeLimit.choices.map { it.label }.distinct().size)
    }

    @Test fun savedExportSettingsFromOlderVersionsStillLoad() {
        val older = """{"format":"JPEG","quality":95,"longEdge":2048,"keepMetadata":true,""" +
            """"allowEmbedded":false,"allowSdr":true,"colorSpace":"SRGB"}"""
        val settings = storageJson.decodeFromString<ExportSettings>(older)
        assertEquals(SizeLimit(longEdge = 2048), settings.size)
        val withShortEdge = ExportSettings().withSize(SizeLimit(shortEdge = 1080))
        assertEquals(withShortEdge, storageJson.decodeFromString<ExportSettings>(storageJson.encodeToString(withShortEdge)))
        val olderFrame = """{"bottom":0.1,"ratio":"PORTRAIT"}"""
        assertEquals(FrameRatio.PORTRAIT, storageJson.decodeFromString<Frame>(olderFrame).ratio)
    }

    @Test fun portraitOrientationAndSourceFormats() {
        assertEquals(PixelSize(4032, 6048), PixelSize(6048, 4032).oriented(6))
        assertEquals(PhotoFormat.HEIF, PhotoFormat.identify("DSC_001.HIF", "application/octet-stream"))
        assertEquals(PhotoFormat.NEF, PhotoFormat.identify("DSC_002.NeF"))
        assertEquals(PhotoFormat.CR3, PhotoFormat.identify("DSC_003.CR3"))
    }

    @Test fun missingAndOverriddenExifDoNotLeaveDanglingSeparators() {
        val metadata = PhotoMetadata(mapOf(MetadataField.MODEL to "NIKON ZR", MetadataField.ISO to "ISO 100"))
        val fields = listOf(MetadataField.MODEL, MetadataField.LENS, MetadataField.ISO)
        assertEquals("NIKON ZR   ·   ISO 100", metadata.display(fields, emptyMap()))
        assertEquals("NIKON ZR", metadata.display(fields, mapOf(MetadataField.ISO to "")))
    }

    @Test fun previewRequiresExplicitConsentAndCompanionHasPriority() {
        val raw = PhotoSource("file:///raw", "ZR.NEF", "camera", "camera")
        val companion = raw.copy(uri = "file:///jpeg", name = "ZR.JPG")
        val preview = PixelChoice(raw, PixelKind.EMBEDDED, PixelSize(1024, 768))
        val standard = PixelChoice(companion, PixelKind.COMPANION, PixelSize(6048, 4032))
        val info = PhotoInfo(PhotoMetadata(), listOf(preview, standard))
        assertEquals(standard, selectPixels(info, null, ExportSettings()))
        assertThrows(ExportFailure::class.java) { selectPixels(info, PixelKind.EMBEDDED, ExportSettings()) }
        assertEquals(preview, selectPixels(info, PixelKind.EMBEDDED, ExportSettings(allowEmbedded = true)))
        assertFalse(ExportSettings().allowEmbedded)
    }

    @Test fun savedTemplateRoundTripPreservesLayerOrder() {
        for (template in Templates.all) {
            assertEquals(template, storageJson.decodeFromString<WatermarkTemplate>(storageJson.encodeToString(template)))
        }
        val personalized = Templates.colorAtlas.copy(captions = mapOf(MetadataField.AUTHOR to "摄影师"))
        assertEquals(personalized, storageJson.decodeFromString<WatermarkTemplate>(storageJson.encodeToString(personalized)))
        assertEquals(12, Templates.all.map { it.id }.distinct().size)
    }
}
