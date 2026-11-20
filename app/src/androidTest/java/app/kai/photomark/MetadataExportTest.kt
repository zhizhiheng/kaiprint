package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MetadataExportTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp

    @Test fun cameraLensCreditAndPositionAreReadFromExif() {
        val file = sample("sony.JPG")
        try {
            val info = app.photos.info(PhotoGroup(photo(file)))
            val values = info.metadata.values
            assertEquals("Sony", values[MetadataField.MAKE])
            assertEquals("α7 IV", values[MetadataField.MODEL])
            assertEquals("SIGMA 35mm F1.4 DG DN | Art", values[MetadataField.LENS])
            assertEquals("Test Author", values[MetadataField.AUTHOR])
            assertEquals(31.2397, info.metadata.latitude!!, 1e-4)
            assertEquals("ILCE-7M4", info.metadata.exif[ExifInterface.TAG_MODEL])
            assertEquals("上海", app.renderer.place(Templates.gallery.copy(place = PlaceStyle.CITY), info.metadata))
            assertNull(app.renderer.place(Templates.gallery, info.metadata))
            assertTrue(Places.index(app).size > 30_000)
        } finally {
            file.delete()
        }
    }

    @Test fun exportKeepsTheSourceAuthorAndWritesPositionOnlyWhenAsked() = runBlocking(Dispatchers.IO) {
        val file = sample("credit.JPG")
        try {
            val info = app.photos.info(PhotoGroup(photo(file)))
            val defaults = ExportSettings()
            exported(file, Templates.gallery, defaults) { exif, size ->
                assertEquals("Test Author", exif.getAttribute(ExifInterface.TAG_ARTIST))
                assertEquals("Studio", exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
                assertEquals("ILCE-7M4", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals("35mm F1.4 DG DN | Art 019", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
                assertNull(exif.latLong)
                val plan = planExport(info, null, Templates.gallery, defaults, emptyMap(), Build.VERSION.SDK_INT)
                assertEquals(plan.layout.canvas, size)
            }
            val credited = Templates.gallery.copy(captions = mapOf(MetadataField.AUTHOR to "Kai Liu"))
            val asked = ExportSettings(credit = CreditSource.WATERMARK, copyright = "© 2026 Kai", keepLocation = true)
            exported(file, credited, asked) { exif, _ ->
                assertEquals("Kai Liu", exif.getAttribute(ExifInterface.TAG_ARTIST))
                assertEquals("(C) 2026 Kai", exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
                assertEquals(31.2397, exif.latLong!![0], 1e-4)
                assertEquals(121.4998, exif.latLong!![1], 1e-4)
            }
            exported(file, credited, ExportSettings(credit = CreditSource.NONE)) { exif, _ ->
                assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
                assertNull(exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
            }
        } finally {
            file.delete()
        }
    }

    @Test fun ultraHdrJpegKeepsItsGainMapOnlyUnderThePhoto() = runBlocking(Dispatchers.IO) {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val file = File(app.cacheDir, "ultra-hdr.jpg")
        val base = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        base.eraseColor(Color.rgb(120, 130, 140))
        val contents = Bitmap.createBitmap(200, 150, Bitmap.Config.ALPHA_8)
        contents.eraseColor(Color.argb(200, 0, 0, 0))
        base.gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(4f, 4f, 4f)
            displayRatioForFullHdr = 4f
        }
        file.outputStream().use { assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        base.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 9 Pro")
            saveAttributes()
        }
        try {
            val choice = app.photos.info(PhotoGroup(photo(file))).choices.single()
            assertTrue("测试图应被识别为 HDR", choice.hdr)
            exported(file, Templates.gallery, ExportSettings()) { _, size ->
                val bytes = File(app.cacheDir, "last-export.jpg").readBytes()
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    assertEquals(size, PixelSize(decoded.width, decoded.height))
                    assertTrue("成片应保留增益图", decoded.hasGainmap())
                    val map = decoded.gainmap!!.gainmapContents
                    val layout = WatermarkLayout.calculate(PixelSize(800, 600), Templates.gallery, ExportSettings(),
                        null, emptyMap())
                    val scale = map.width.toFloat() / layout.canvas.width
                    fun level(x: Float, y: Float): Int {
                        val pixel = map.getPixel((x * scale).toInt().coerceIn(0, map.width - 1),
                            (y * scale).toInt().coerceIn(0, map.height - 1))
                        return if (map.config == Bitmap.Config.ALPHA_8) Color.alpha(pixel) else Color.red(pixel)
                    }
                    assertTrue("照片区应保留提亮", level(layout.photo.left + 400, layout.photo.top + 300) > 150)
                    assertTrue("边框应不提亮", level(5f, layout.canvas.height - 5f) < 20)
                } finally {
                    decoded.recycle()
                }
            }
            exported(file, Templates.gallery, ExportSettings(keepHdr = false)) { _, _ ->
                val bytes = File(app.cacheDir, "last-export.jpg").readBytes()
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes))
                assertFalse(decoded.hasGainmap())
                decoded.recycle()
            }
        } finally {
            file.delete()
        }
    }

    @Test fun everyBundledModelMarkDrawsOnDevice() {
        for (mark in modelMarks) {
            val metadata = PhotoMetadata(mapOf(MetadataField.MODEL to mark.name))
            val graphic = mark.brand.graphic(BrandStyle.MODEL, metadata, emptyMap())
            assertEquals(mark.file, graphic?.file)
            val bitmap = app.renderer.brandThumbnail(mark.brand, BrandTone.DARK, BrandStyle.MODEL, metadata)
            assertNotNull(mark.file, bitmap)
            val inked = (0 until bitmap!!.width step 2).sumOf { x ->
                (0 until bitmap.height step 2).count { y -> Color.alpha(bitmap.getPixel(x, y)) > 128 }
            }
            assertTrue("${mark.file} 没有绘制内容", inked > 20)
            bitmap.recycle()
        }
    }

    @Test fun presetPackageCarriesAPreviewAndOpensWithoutItsFont() {
        val archive = File(app.cacheDir, "${UUID.randomUUID()}.zip")
        val template = Templates.modelPlate
        val preview = app.renderer.sample(template)
        try {
            PresetArchive(app).write(template, Uri.fromFile(archive), preview)
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(PresetArchive.PREVIEW)
                assertNotNull(entry)
                val image = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                assertEquals(preview.width, image.width)
                image.recycle()
            }
            assertEquals(template.layers, PresetArchive(app).read(Uri.fromFile(archive)).template.layers)

            val withFont = template.copy(layers = template.layers.map {
                if (it.kind == LayerKind.EXIF) it.copy(fontFile = "missing-font.ttf") else it
            })
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("template.json"))
                zip.write(storageJson.encodeToString(withFont).toByteArray())
                zip.closeEntry()
            }
            val imported = PresetArchive(app).read(Uri.fromFile(archive))
            assertEquals(1, imported.missingFonts)
            assertNull(imported.template.layers.first { it.kind == LayerKind.EXIF }.fontFile)
            assertEquals(TypeStyle.MONO, imported.template.layers.first { it.kind == LayerKind.EXIF }.type)
        } finally {
            preview.recycle()
            archive.delete()
        }
    }

    private suspend fun exported(
        file: File, template: WatermarkTemplate, settings: ExportSettings, check: (ExifInterface, PixelSize) -> Unit,
    ) {
        val dao = app.database.dao()
        val id = UUID.randomUUID().toString()
        app.database.withTransaction {
            dao.insertJob(ExportJobRow(id, System.currentTimeMillis(), storageJson.encodeToString(template),
                storageJson.encodeToString(settings)))
            dao.insertItems(listOf(ExportItemRow(id, 0, storageJson.encodeToString(ExportInput(photo(file))), file.name, "JPEG")))
        }
        try {
            Exporter(app).exportItem(dao.items(id).single(), template, settings)
            val uri = Uri.parse(requireNotNull(dao.items(id).single().outputUri))
            val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            File(app.cacheDir, "last-export.jpg").writeBytes(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(ExifInterface(bytes.inputStream()), PixelSize(bounds.outWidth, bounds.outHeight))
        } finally {
            dao.items(id).forEach { item -> item.outputUri?.let { app.contentResolver.delete(Uri.parse(it), null, null) } }
        }
    }

    private fun photo(file: File) = PhotoSource(Uri.fromFile(file).toString(), file.name, "测试样片", file.parent.orEmpty())

    private fun sample(name: String): File {
        val file = File(app.cacheDir, name)
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(50, 94, 123))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "SONY")
            setAttribute(ExifInterface.TAG_MODEL, "ILCE-7M4")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "35mm F1.4 DG DN | Art 019")
            setAttribute(ExifInterface.TAG_F_NUMBER, "14/10")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setAttribute(ExifInterface.TAG_ARTIST, "Test Author")
            setAttribute(ExifInterface.TAG_COPYRIGHT, "Studio")
            setLatLong(31.2397, 121.4998)
            saveAttributes()
        }
        return file
    }
}
