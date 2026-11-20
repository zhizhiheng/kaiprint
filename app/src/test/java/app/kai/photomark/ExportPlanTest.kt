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

    @Test fun typedCameraDetailsReachTheFileOnlyWhenAsked() {
        val typed = mapOf(MetadataField.MAKE to "Leica", MetadataField.MODEL to "M6", MetadataField.ISO to "200")
        val untouched = plan(overrides = typed)
        assertEquals("NIKON CORPORATION", untouched.tags[ExifInterface.TAG_MAKE])
        assertEquals("400", untouched.tags[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY])
        val written = plan(ExportSettings(writeManual = true), overrides = typed)
        assertEquals("Leica", written.tags[ExifInterface.TAG_MAKE])
        assertEquals("M6", written.tags[ExifInterface.TAG_MODEL])
        assertEquals("200", written.tags[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY])
        assertEquals("NIKKOR Z 50mm f/1.8 S", written.tags[ExifInterface.TAG_LENS_MODEL])
        assertTrue(written.notes.isEmpty())
        // Nothing is written at all when the file is to carry no shooting details.
        assertTrue(plan(ExportSettings(writeManual = true, keepMetadata = false), overrides = typed).tags.isEmpty())
    }

    @Test fun typedValuesBecomeTheNumbersExifStores() {
        val manual = manualExif(mapOf(
            MetadataField.FOCAL to "85 mm", MetadataField.FOCAL_EQUIVALENT to "127.5mm", MetadataField.APERTURE to "f/1.8",
            MetadataField.SHUTTER to "1/125 s", MetadataField.ISO to "12,800", MetadataField.EXPOSURE_BIAS to "−0.7 EV",
            MetadataField.DATE to "2026年11月20日 17:42", MetadataField.FILM to "Classic Chrome",
            MetadataField.TITLE to "Dusk", MetadataField.LOCATION to "Shanghai",
        ), PhotoMetadata())
        assertEquals(mapOf(
            ExifInterface.TAG_FOCAL_LENGTH to "85000/1000", ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "128",
            ExifInterface.TAG_F_NUMBER to "1.8", ExifInterface.TAG_EXPOSURE_TIME to "0.008",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "12800", ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "-70/100",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:20 17:42:00",
        ), manual.tags)
        assertTrue(manual.stale.isEmpty())
        assertTrue(manual.notes.isEmpty())
    }

    @Test fun typedLensReplacesTheRecordedMakerAndFocalRange() {
        val recorded = metadata.copy(exif = exif + mapOf(
            ExifInterface.TAG_LENS_MAKE to "NIKON", ExifInterface.TAG_LENS_SPECIFICATION to "50/1 50/1 18/10 18/10"))
        val settings = ExportSettings(writeManual = true)
        val plan = plan(settings, overrides = mapOf(MetadataField.LENS to "Voigtlander NOKTON 40mm F1.2"),
            target = info.copy(metadata = recorded))
        assertEquals("Voigtlander NOKTON 40mm F1.2", plan.tags[ExifInterface.TAG_LENS_MODEL])
        assertNull(plan.tags[ExifInterface.TAG_LENS_MAKE])
        assertNull(plan.tags[ExifInterface.TAG_LENS_SPECIFICATION])
        // A lens name the file cannot store leaves the recorded lens whole.
        val chinese = plan(settings, overrides = mapOf(MetadataField.LENS to "老蛙 15mm"), target = info.copy(metadata = recorded))
        assertEquals("NIKKOR Z 50mm f/1.8 S", chinese.tags[ExifInterface.TAG_LENS_MODEL])
        assertEquals("NIKON", chinese.tags[ExifInterface.TAG_LENS_MAKE])
        assertTrue(chinese.notes.single().startsWith("手填的镜头"))
    }

    @Test fun typedValuesTheFileCannotHoldAreReportedAndLeftOut() {
        val recorded = PhotoMetadata(mapOf(MetadataField.MODEL to "ZR", MetadataField.ISO to "ISO 400"))
        val manual = manualExif(mapOf(
            MetadataField.MAKE to "尼康", MetadataField.APERTURE to "大光圈", MetadataField.ISO to "102400",
            MetadataField.SHUTTER to "1/0", MetadataField.DATE to "深秋", MetadataField.EXPOSURE_BIAS to "EV",
            // Left as recorded, or cleared to hide it from the watermark: the file keeps what it has.
            MetadataField.MODEL to " ZR ", MetadataField.FOCAL to "  ",
        ), recorded)
        assertTrue(manual.tags.isEmpty())
        assertEquals(6, manual.notes.size)
        assertTrue(manual.notes.all { it.startsWith("手填") })
        assertTrue(manual.notes.any { "65535" in it })
        assertTrue(manualExif(mapOf(MetadataField.ISO to "ISO 400"), recorded).tags.isEmpty())
    }

    @Test fun typedNumbersAndDatesAreReadInTheirUsualSpellings() {
        assertEquals(50.0, "50 mm".amount()!!, 0.0)
        assertEquals(1.8, "f/1.8".amount()!!, 0.0)
        assertEquals(2.8, "F2,8".amount()!!, 0.0)
        assertEquals(.004, "1/250".amount()!!, 1e-9)
        assertEquals(2.5, "2.5\"".amount()!!, 0.0)
        assertEquals(.3, "+0.3 EV".amount(signed = true)!!, 0.0)
        assertEquals(-1.0, "-1".amount(signed = true)!!, 0.0)
        assertEquals(-2.0 / 3, "–2/3 EV".amount(signed = true)!!, 1e-9)
        // A hyphen before a plain quantity is punctuation, not a sign.
        assertEquals(35.0, "-35mm".amount()!!, 0.0)
        assertNull("f/".amount())
        assertNull("1/0".amount())
        assertEquals("2026:11:20 17:42:10", "2026-11-20 17:42:10".exifTime())
        assertEquals("2026:11:20 17:42:00", "2026/11/20 17:42".exifTime())
        assertEquals("2026:01:05 00:00:00", "2026.1.5".exifTime())
        assertEquals("2026:11:20 09:05:00", "2026年11月20日 9:05".exifTime())
        assertEquals("2026:11:20 17:42:10", "2026:11:20T17:42:10".exifTime())
        assertNull("2026-13-01".exifTime())
        assertNull("2026-11-20 25:00".exifTime())
        assertNull("Nov 20".exifTime())
    }

    @Test fun tiffTakesTypedMakeModelAndTimeOnly() {
        val plan = plan(ExportSettings(format = OutputFormat.TIFF, writeManual = true), overrides = mapOf(
            MetadataField.MAKE to "Leica", MetadataField.DATE to "2026-11-20 17:42", MetadataField.MODEL to "徕卡 M6",
            MetadataField.APERTURE to "大光圈"))
        assertEquals(mapOf(
            ExifInterface.TAG_MAKE to "Leica", ExifInterface.TAG_MODEL to "NIKON ZR",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:20 17:42:00"), plan.tags)
        // The aperture is not written to a TIFF either way, so it is not worth a note of its own.
        assertEquals(1, plan.notes.count { it.startsWith("手填") })
        assertTrue(plan.notes.any { it.startsWith("TIFF") })
    }

    @Test fun motionPhotoKeepsItsVideoOnlyFromJpegToJpeg() {
        val live = info.copy(motion = true)
        val keep = ExportSettings(keepMotion = true)
        val kept = plan(keep, target = live)
        assertTrue(kept.motion)
        assertTrue(kept.notes.single().startsWith("动态部分"))
        // Off by default, and a still photo has nothing to say about it.
        assertTrue(!plan(target = live).motion && plan(target = live).notes.isEmpty())
        assertTrue(!plan(keep).motion && plan(keep).notes.isEmpty())
        // The video does not depend on the still's shooting details being written.
        val bare = plan(keep.copy(keepMetadata = false), target = live)
        assertTrue(bare.motion && bare.tags.isEmpty())
        assertTrue(bare.notes.single().startsWith("动态部分"))
        for (format in listOf(OutputFormat.PNG, OutputFormat.WEBP, OutputFormat.TIFF)) {
            val still = plan(keep.copy(format = format), target = live)
            assertTrue(format.name, !still.motion)
            assertTrue(format.name, still.notes.any { it.startsWith("只有从 JPEG 导出 JPEG") })
        }
        val developed = live.copy(choices = listOf(live.choices.single().copy(kind = PixelKind.COMPANION)))
        assertTrue(!plan(keep, target = developed).motion)
        assertTrue(plan(keep, target = developed).notes.single().startsWith("只有从 JPEG 导出 JPEG"))
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
