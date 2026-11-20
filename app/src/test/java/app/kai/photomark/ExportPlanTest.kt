package app.kai.photomark

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanTest {
    private val source = PhotoSource("file:///a.jpg", "a.jpg", "camera", "camera")
    private val exif = mapOf(
        ExifInterface.TAG_MAKE to "NIKON CORPORATION", ExifInterface.TAG_MODEL to "NIKON ZR",
        ExifInterface.TAG_LENS_MODEL to "NIKKOR Z 50mm f/1.8 S", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "400",
        ExifInterface.TAG_ARTIST to "Test Author", ExifInterface.TAG_COPYRIGHT to "Studio",
    )
    private val metadata = PhotoMetadata(mapOf(MetadataField.MODEL to "ZR"), exif, latitude = 31.2, longitude = 121.5)
    private val info = PhotoInfo(metadata, listOf(PixelChoice(source, PixelKind.STANDARD, PixelSize(1200, 800))))

    private fun plan(settings: ExportSettings = ExportSettings(), template: WatermarkTemplate = Templates.gallery,
                     overrides: Map<MetadataField, String> = emptyMap(), target: PhotoInfo = info, sdk: Int = 36) =
        planExport(target, null, template, settings, overrides, sdk)

    @Test fun defaultKeepsShootingDetailsAndTheSourceAuthorButNoPosition() {
        val plan = plan()
        assertEquals("Test Author", plan.tags[ExifInterface.TAG_ARTIST])
        assertEquals("Studio", plan.tags[ExifInterface.TAG_COPYRIGHT])
        assertNull(plan.latitude)
        assertEquals(listOf("品牌", "机型", "镜头", "ISO", "作者", "版权"), plan.fields)
        assertEquals(PixelSize(1200, 800), plan.pixels.size)
        assertEquals(plan.layout, WatermarkLayout.calculate(PixelSize(1200, 800), Templates.gallery, ExportSettings(), metadata, emptyMap()))
    }

    @Test fun authorFollowsTheChosenSource() {
        val credited = Templates.gallery.copy(captions = mapOf(MetadataField.AUTHOR to "Kai Liu"))
        assertEquals("Kai Liu", plan(ExportSettings(credit = CreditSource.WATERMARK), credited).tags[ExifInterface.TAG_ARTIST])
        assertEquals("Per Photo", plan(ExportSettings(credit = CreditSource.WATERMARK), credited,
            mapOf(MetadataField.AUTHOR to "Per Photo")).tags[ExifInterface.TAG_ARTIST])
        val none = plan(ExportSettings(credit = CreditSource.NONE))
        assertNull(none.tags[ExifInterface.TAG_ARTIST])
        assertNull(none.tags[ExifInterface.TAG_COPYRIGHT])
        assertEquals("(C) 2026 Kai", plan(ExportSettings(copyright = "© 2026 Kai")).tags[ExifInterface.TAG_COPYRIGHT])
    }

    @Test fun chineseCreditIsReportedInsteadOfWrittenAsQuestionMarks() {
        val chinese = Templates.gallery.copy(captions = mapOf(MetadataField.AUTHOR to "刘旭凯"))
        val plan = plan(ExportSettings(credit = CreditSource.WATERMARK), chinese)
        assertNull(plan.tags[ExifInterface.TAG_ARTIST])
        assertTrue(plan.notes.single().startsWith("作者"))
    }

    @Test fun positionIsWrittenOnlyWhenAsked() {
        val kept = plan(ExportSettings(keepLocation = true))
        assertEquals(31.2, kept.latitude!!, 0.0)
        assertTrue("拍摄位置" in kept.fields)
        assertNull(plan(ExportSettings(keepLocation = true, keepMetadata = false)).latitude)
        assertNull(plan(ExportSettings(keepLocation = true, format = OutputFormat.TIFF)).latitude)
        assertTrue(plan(ExportSettings(keepMetadata = false)).fields.isEmpty())
    }

    @Test fun tiffCarriesMakeModelAndTimeOnly() {
        val plan = plan(ExportSettings(format = OutputFormat.TIFF))
        assertEquals(setOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL), plan.tags.keys)
        assertEquals(1, plan.notes.size)
    }

    @Test fun ultraHdrIsKeptOnlyFromJpegToJpegOnNewerAndroid() {
        val hdr = info.copy(choices = listOf(info.choices.single().copy(hdr = true)))
        assertEquals(HdrHandling.KEPT, plan(target = hdr).hdr)
        assertEquals(HdrHandling.SDR, plan(target = hdr, sdk = 33).hdr)
        assertEquals(HdrHandling.SDR, plan(ExportSettings(format = OutputFormat.PNG), target = hdr).hdr)
        assertEquals(HdrHandling.SDR, plan(ExportSettings(keepHdr = false), target = hdr).hdr)
        assertEquals(HdrHandling.KEPT, plan(ExportSettings(allowSdr = false), target = hdr).hdr)
        assertThrows(ExportFailure::class.java) { plan(ExportSettings(allowSdr = false, keepHdr = false), target = hdr) }
        assertEquals(HdrHandling.NONE, plan().hdr)
    }

    @Test fun limitedSizeReportsTheScaledPhotoBody() {
        val plan = plan(ExportSettings().withSize(SizeLimit(longEdge = 600)))
        assertTrue(plan.scaled)
        assertEquals(600, plan.layout.canvas.longEdge)
        assertTrue(plan.photo.width < 600)
    }

    @Test fun batchSummaryCountsWhatEachPhotoNeeds() {
        val raw = PhotoSource("file:///b.nef", "b.NEF", "camera", "camera")
        val previewOnly = PhotoInfo(PhotoMetadata(), listOf(PixelChoice(raw, PixelKind.EMBEDDED, PixelSize(1024, 683))))
        val hdr = info.copy(choices = listOf(info.choices.single().copy(hdr = true)))
        val settings = ExportSettings(format = OutputFormat.PNG)
        val results = listOf(info, previewOnly, hdr).map { it.metadata to runCatching { planExport(it, null, Templates.gallery, settings, emptyMap(), 36) } }
        val summary = ExportSummary.of(results, 5)
        assertEquals(3, summary.checked)
        assertEquals(mapOf(PixelKind.STANDARD to 2), summary.kinds)
        assertEquals(1, summary.sdr)
        assertEquals(1, summary.noCamera)
        assertEquals(1, summary.blocked.values.single())
    }
}
