package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoWorkflowTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp

    @Test fun orientationAndTemplatesRenderBothAspectRatios() {
        val file = sample("portrait.JPG", 600, 400, 6)
        val photo = photo(file)
        val group = PhotoGroup(photo)
        val info = app.photos.info(group)
        val choice = info.choices.single()
        assertEquals(PixelSize(400, 600), choice.size)
        assertEquals("NIKON ZR", info.metadata.values[MetadataField.MODEL])
        assertEquals("ISO 400", info.metadata.values[MetadataField.ISO])
        val source = app.photos.load(choice, 0)
        assertEquals(400, source.width)
        assertEquals(600, source.height)
        try {
            for (template in Templates.all) {
                for (size in listOf(choice.size, PixelSize(600, 400))) {
                    val layout = WatermarkLayout.calculate(size, template.frame)
                    val result = app.renderer.preview(source, layout, template, info.metadata, emptyMap())
                    assertEquals(layout.canvas.width, result.width)
                    assertEquals(layout.canvas.height, result.height)
                    if (template.frame.top > 0f || template.frame.bottom > 0f) {
                        assertTrue(result.width > size.width || result.height > size.height)
                    }
                    result.recycle()
                }
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test fun exportWritesPixelsRetainsAllowlistedExifAndCanRecoverWithoutDuplicates() = runBlocking(Dispatchers.IO) {
        val file = sample("export.JPG", 640, 480, 1)
        try {
            verifyExport(file, Templates.signature, PixelSize(640, 480))
        } finally {
            file.delete()
        }
    }

    @Test fun externalZrJpegExportsAtOriginalDimensions() = runBlocking(Dispatchers.IO) {
        val file = File(app.filesDir, "test-fixtures/ZR.jpg")
        assumeTrue("仅在提供评测网站的 ZR JPEG 样片时运行", file.isFile)
        verifyExport(file, Templates.signature, PixelSize(6048, 4032))
    }

    @Test fun generatedHlgHeifExportsFullSizeAsSdr() = runBlocking(Dispatchers.IO) {
        val file = File(app.filesDir, "test-fixtures/ZR-HLG.HIF")
        assumeTrue("仅在提供生成的 HLG HEIF 校验图时运行", file.isFile)
        val choice = app.photos.info(PhotoGroup(photo(file))).choices.single()
        assertTrue(choice.hdr)
        val preview = app.photos.load(choice, 1008)
        try {
            val shadow = Color.red(preview.getPixel(5, 160))
            val highlight = Color.red(preview.getPixel(982, 160))
            assertTrue(highlight - shadow > 70)
        } finally {
            preview.recycle()
        }
        verifyExport(file, Templates.signature, PixelSize(6048, 4032))
    }

    @Test fun externalZrNefUsesLargestPreviewAndOriginalRawWhenAvailable(): Unit = runBlocking(Dispatchers.IO) {
        val file = File(app.filesDir, "test-fixtures/ZR.NEF")
        assumeTrue("仅在提供评测网站的 ZR NEF 样片时运行", file.isFile)
        val source = photo(file)
        val info = app.photos.info(PhotoGroup(source))
        assertEquals("NIKON ZR", info.metadata.values[MetadataField.MODEL])
        assertTrue(info.choices.isNotEmpty())
        val choice = info.choices.firstOrNull { it.kind == PixelKind.RAW }
            ?: info.choices.single { it.kind == PixelKind.EMBEDDED }
        val bitmap = app.photos.load(choice, 2048)
        assertTrue(bitmap.width > 300 && bitmap.height > 200)
        bitmap.recycle()
        if (choice.kind == PixelKind.RAW) {
            assertEquals(PixelSize(6048, 4032), choice.size)
            val output = app.photos.load(choice, 0)
            assertEquals(choice.size, PixelSize(output.width, output.height))
            output.recycle()
        }
        android.util.Log.i("PhotoMarkTest", "ZR NEF compression=${info.metadata.rawCompression} choices=${info.choices.map { it.kind to it.size }}")
    }

    @Test fun tenBitHlgHeifKeepsToneStepsWhenConvertedToSdr() = runBlocking(Dispatchers.IO) {
        val file = File(app.filesDir, "test-fixtures/ZR-HLG10.HIF")
        assumeTrue(file.isFile)
        val info = app.photos.info(PhotoGroup(photo(file)))
        val choice = info.choices.single()
        assertTrue(choice.hdr)
        val bitmap = app.photos.load(choice, 1008)
        try {
            val samples = listOf(.15f, .32f, .50f, .70f, .9f).map {
                val pixel = bitmap.getPixel((bitmap.width * it).toInt(), bitmap.height / 2)
                assertEquals(255, Color.alpha(pixel))
                Color.red(pixel)
            }
            assertTrue(samples.zipWithNext().all { (dark, bright) -> bright - dark > 7 })
        } finally { bitmap.recycle() }
        verifyExport(file, Templates.signature, PixelSize(6048, 4032))
    }

    @Test fun eachOutputFormatProducesAReadableMediaStoreCopy() = runBlocking(Dispatchers.IO) {
        val file = sample("formats.JPG", 640, 480, 1)
        val dao = app.database.dao()
        val exporter = Exporter(app)
        try {
            for (format in OutputFormat.entries) {
                val settings = ExportSettings(format = format)
                val job = createJob(listOf(ExportInput(photo(file))), Templates.gallery, settings)
                try {
                    exporter.exportItem(dao.items(job).single(), Templates.gallery, settings)
                    val uri = requireNotNull(dao.items(job).single().outputUri)
                    val result = PhotoSource(uri, "result.${format.extension}", "测试", "测试", mime = format.mime)
                    val info = app.photos.info(PhotoGroup(result))
                    val expected = WatermarkLayout.calculate(PixelSize(640, 480), Templates.gallery.frame).canvas
                    assertEquals(expected, info.choices.single().size)
                    assertEquals("NIKON ZR", info.metadata.values[MetadataField.MODEL])
                } finally { clearOutputs(job) }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun displayP3JpegRetainsAnOutputProfile() = runBlocking(Dispatchers.IO) {
        val file = sample("wide.JPG", 640, 480, 1)
        val template = Templates.signature
        val settings = ExportSettings(colorSpace = OutputColor.DISPLAY_P3)
        val job = createJob(listOf(ExportInput(photo(file))), template, settings)
        val dao = app.database.dao()
        try {
            Exporter(app).exportItem(dao.items(job).single(), template, settings)
            val output = Uri.parse(dao.items(job).single().outputUri)
            val decoded = app.contentResolver.openInputStream(output)!!.use { BitmapFactory.decodeStream(it) }
            try {
                assertTrue(decoded.colorSpace?.isWideGamut == true)
            } finally { decoded.recycle() }
        } finally {
            clearOutputs(job)
            file.delete()
        }
    }

    @Test fun tiffRoundTripKeepsIccAndSupportsExifReading() {
        val bitmap = Bitmap.createBitmap(311, 197, Bitmap.Config.ARGB_8888, true, OutputColor.DISPLAY_P3.androidColorSpace())
        Canvas(bitmap).drawColor(Color.rgb(60, 140, 175))
        val file = File(app.cacheDir, "roundtrip.tif")
        try {
            assertTrue(RasterCodec.writeTiff(file, bitmap, OutputColor.DISPLAY_P3, PhotoMetadata(
                exif = mapOf(ExifInterface.TAG_MAKE to "NIKON", ExifInterface.TAG_MODEL to "NIKON ZR")
            )))
            val info = app.photos.info(PhotoGroup(photo(file)))
            assertEquals("NIKON ZR", info.metadata.values[MetadataField.MODEL])
            assertEquals(PixelSize(311, 197), info.choices.single().size)
            val decoded = app.photos.load(info.choices.single(), 0, OutputColor.DISPLAY_P3)
            assertEquals(OutputColor.DISPLAY_P3.androidColorSpace(), decoded.colorSpace)
            assertTrue(decoded.getPixel(40, 40) != Color.BLACK)
            decoded.recycle()
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test fun generatedSixteenBitTiffLoadsAtOriginalSize() {
        val file = File(app.filesDir, "test-fixtures/Gray16.tiff")
        assumeTrue(file.isFile)
        val info = app.photos.info(PhotoGroup(photo(file)))
        val bitmap = app.photos.load(info.choices.single(), 0)
        try {
            assertEquals(PixelSize(768, 512), PixelSize(bitmap.width, bitmap.height))
            assertTrue(Color.red(bitmap.getPixel(720, 256)) > Color.red(bitmap.getPixel(25, 256)))
        } finally { bitmap.recycle() }
    }

    @Test fun svgAndPresetArchiveRoundTrip() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 10"><rect x="2" y="2" width="26" height="6" fill="#2D77B2"/></svg>"""
        val bitmap = renderSvg(svg.byteInputStream())
        val folder = File(app.filesDir, "logos").apply { mkdirs() }
        val logo = File(folder, "${UUID.randomUUID()}.png")
        val archive = File(app.cacheDir, "${UUID.randomUUID()}.zip")
        var imported: File? = null
        try {
            assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 20)
            logo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val template = Templates.zr.copy(layers = Templates.zr.layers +
                WatermarkLayer("brand", LayerKind.LOGO, logoFile = logo.name))
            val manager = PresetArchive(app)
            manager.write(template, Uri.fromFile(archive))
            val decoded = manager.read(Uri.fromFile(archive))
            assertEquals(template.title, decoded.title)
            assertEquals(template.layers.size, decoded.layers.size)
            imported = File(folder, requireNotNull(decoded.layers.last().logoFile))
            assertTrue(imported.isFile && logo.readBytes().contentEquals(imported.readBytes()))
        } finally {
            bitmap.recycle()
            logo.delete()
            archive.delete()
            imported?.delete()
        }
    }

    @Test fun foregroundQueueRetriesOnlyTheFailedItem() = runBlocking(Dispatchers.IO) {
        val good = sample("valid.JPG", 640, 480, 1)
        val damaged = File(app.cacheDir, "damaged.JPG").apply { writeText("broken") }
        val job = createJob(listOf(ExportInput(photo(good)), ExportInput(photo(damaged))), Templates.gallery, ExportSettings())
        val dao = app.database.dao()
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                activity.onActivity { ExportService.start(it, job) }
                waitForFinish(job)
                val results = dao.items(job)
                assertEquals(ItemState.DONE.name, results[0].state)
                assertEquals(ItemState.FAILED.name, results[1].state)
                good.copyTo(damaged, overwrite = true)
                activity.onActivity { ExportService.start(it, job, retryFailures = true) }
                waitForFinish(job)
                val recovered = dao.items(job)
                assertEquals(results[0].outputUri, recovered[0].outputUri)
                assertTrue(recovered.all { it.state == ItemState.DONE.name })
            } finally {
                clearOutputs(job)
                good.delete()
                damaged.delete()
            }
        }
    }

    private suspend fun waitForFinish(job: String) {
        withTimeout(30_000L) {
            while (app.activeExport.value != null || app.database.dao().job(job)?.state != RunState.FINISHED.name) {
                delay(75)
            }
        }
    }

    @Test fun optionalBatchStressExportsOneHundredImages() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stress") == "true")
        val file = sample("batch.JPG", 6048, 4032, 1)
        val item = ExportInput(photo(file))
        val dao = app.database.dao()
        val template = Templates.gallery
        val settings = ExportSettings()
        val job = createJob((1..100).map { item }, template, settings)
        try {
            val exporter = Exporter(app)
            for (row in dao.items(job)) {
                exporter.exportItem(row, template, settings)
            }
            val rows = dao.items(job)
            assertEquals(100, rows.count { it.state == ItemState.DONE.name && it.outputUri != null })
            assertEquals(100, rows.map { it.outputUri }.distinct().size)
        } finally {
            clearOutputs(job)
            file.delete()
        }
    }

    private suspend fun verifyExport(file: File, template: WatermarkTemplate, expected: PixelSize) {
        val settings = ExportSettings()
        val dao = app.database.dao()
        val job = createJob(listOf(ExportInput(photo(file))), template, settings)
        try {
            val exporter = Exporter(app)
            val item = dao.items(job).single()
            exporter.exportItem(item, template, settings)
            val saved = dao.items(job).single()
            assertEquals(ItemState.DONE.name, saved.state)
            assertNotNull(saved.outputUri)
            val uri = Uri.parse(saved.outputUri)
            app.contentResolver.openInputStream(uri)!!.use { source ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(source, null, bounds)
                assertEquals(expected, PixelSize(bounds.outWidth, bounds.outHeight))
            }
            app.contentResolver.openInputStream(uri)!!.use { source ->
                val exif = ExifInterface(source)
                assertEquals("NIKON ZR", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals(1, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                assertNull(exif.latLong)
                assertNull(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
            }
            exporter.exportItem(item, template, settings)
            assertEquals(saved.outputUri, dao.items(job).single().outputUri)
        } finally {
            clearOutputs(job)
        }
    }

    private suspend fun createJob(inputs: List<ExportInput>, template: WatermarkTemplate, settings: ExportSettings): String {
        val id = UUID.randomUUID().toString()
        val dao = app.database.dao()
        app.database.withTransaction {
            dao.insertJob(ExportJobRow(id, System.currentTimeMillis(), storageJson.encodeToString(template),
                storageJson.encodeToString(settings)))
            dao.insertItems(inputs.mapIndexed { i, item ->
                ExportItemRow(id, i, storageJson.encodeToString(item), item.photo.name, item.photo.format.label)
            })
        }
        return id
    }

    private suspend fun clearOutputs(job: String) {
        app.database.dao().items(job).forEach { item ->
            item.outputUri?.let { app.contentResolver.delete(Uri.parse(it), null, null) }
        }
    }

    private fun photo(file: File) = PhotoSource(Uri.fromFile(file).toString(), file.name, "测试样片", file.parent.orEmpty())

    private fun sample(name: String, width: Int, height: Int, orientation: Int): File {
        val file = File(app.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(50, 94, 123))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "NIKON")
            setAttribute(ExifInterface.TAG_MODEL, "NIKON ZR")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "test-serial")
            setLatLong(1.234, 103.123)
            saveAttributes()
        }
        return file
    }
}
