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
