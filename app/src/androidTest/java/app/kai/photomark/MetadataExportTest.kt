package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
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

    @Test fun modelWithoutMakerKeepsItsBrandAfterFormatting() {
        val file = sample("model-only.jpg")
        try {
            for ((model, brand) in mapOf("NIKON D850" to BrandMark.NIKON, "DC-S5M2" to BrandMark.LUMIX,
                    "LEICA Q3" to BrandMark.LEICA)) {
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_MAKE, null)
                    setAttribute(ExifInterface.TAG_MODEL, model)
                    saveAttributes()
                }
                val metadata = app.photos.info(PhotoGroup(photo(file))).metadata
                assertEquals(brand, BrandMark.AUTO.resolve(metadata, emptyMap()))
                assertNull(metadata.exif[ExifInterface.TAG_MAKE])
            }
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

    @Test fun typedCameraDetailsAreSavedInTheFileOnlyWhenAsked() = runBlocking(Dispatchers.IO) {
        val file = sample("typed.JPG")
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_LENS_MAKE, "SIGMA")
            setAttribute(ExifInterface.TAG_LENS_SPECIFICATION, "35/1,35/1,14/10,14/10")
            saveAttributes()
        }
        val typed = mapOf(
            MetadataField.MAKE to "Leica", MetadataField.MODEL to "M11-P", MetadataField.LENS to "Summicron-M 50mm f/2",
            MetadataField.FOCAL to "50 mm", MetadataField.APERTURE to "f/2", MetadataField.SHUTTER to "1/125 s",
            MetadataField.ISO to "ISO 800", MetadataField.DATE to "2026-11-20 17:42:10",
        )
        try {
            exported(file, Templates.gallery, ExportSettings(writeManual = true), typed) { exif, _ ->
                assertEquals("Leica", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertEquals("M11-P", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals("Summicron-M 50mm f/2", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
                assertEquals(50.0, exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0), 1e-6)
                assertEquals(2.0, exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 1e-6)
                assertEquals(.008, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 1e-6)
                assertEquals(800, exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
                assertEquals("2026:11:20 17:42:10", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                // The maker and focal range on record belonged to the lens that was typed over.
                assertNull(exif.getAttribute(ExifInterface.TAG_LENS_MAKE))
                assertNull(exif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION))
                assertEquals("Test Author", exif.getAttribute(ExifInterface.TAG_ARTIST))
            }
            // Typed values change the watermark alone until the export is told to write them.
            exported(file, Templates.gallery, ExportSettings(), typed) { exif, _ ->
                assertEquals("SONY", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertEquals("ILCE-7M4", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals("35mm F1.4 DG DN | Art 019", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
                assertEquals("SIGMA", exif.getAttribute(ExifInterface.TAG_LENS_MAKE))
                assertEquals(1.4, exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 1e-6)
                assertEquals(400, exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
            }
        } finally {
            file.delete()
        }
    }

    @Test fun motionPhotoKeepsItsVideoBehindTheFramedStill() = runBlocking(Dispatchers.IO) {
        val video = clip()
        val file = sample("moving.jpg")
        file.writeBytes(motionPhoto(file.readBytes(), video))
        try {
            val info = app.photos.info(PhotoGroup(photo(file)))
            assertTrue("样片应被认出是动态照片", info.motion)
            val keep = ExportSettings(keepMotion = true, keepLocation = true)
            val plan = planExport(info, null, Templates.gallery, keep, emptyMap(), Build.VERSION.SDK_INT)
            assertTrue(plan.motion)
            exported(file, Templates.gallery, keep) { exif, size ->
                val bytes = File(app.cacheDir, "last-export.jpg").readBytes()
                assertTrue(exportedName, exportedName.endsWith(".MP.jpg"))
                assertNull(exportedRemark)
                val found = requireNotNull(MotionPhoto.video(bytes))
                assertEquals(bytes.size, found.offset + found.length)
                assertEquals(500_000L, found.timestampUs)
                assertArrayEquals(video, bytes.copyOfRange(found.offset, bytes.size))
                // The still in front of the video is the framed photo, whole and with its details.
                assertEquals(plan.layout.canvas, size)
                assertEquals("ILCE-7M4", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals(31.2397, exif.latLong!![0], 1e-4)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val photo = plan.layout.photo
                val middle = decoded.getPixel(((photo.left + photo.right) / 2).toInt(), ((photo.top + photo.bottom) / 2).toInt())
                decoded.recycle()
                assertEquals(94f, Color.green(middle).toFloat(), 6f)
            }
            // Without the photo's position in the still, the one recorded in the video goes too.
            exported(file, Templates.gallery, ExportSettings(keepMotion = true)) { exif, _ ->
                val bytes = File(app.cacheDir, "last-export.jpg").readBytes()
                assertNull(exif.latLong)
                val found = requireNotNull(MotionPhoto.video(bytes))
                assertEquals(video.size, found.length)
                val carried = bytes.copyOfRange(found.offset, bytes.size)
                assertFalse("+31.2397" in String(carried, Charsets.ISO_8859_1))
                // Everything ahead of that one box is the camera's video as it was.
                val place = String(video, Charsets.ISO_8859_1).indexOf("+31.2397")
                assertArrayEquals(video.copyOf(place - 8), carried.copyOf(place - 8))
                assertEquals("free", String(carried, place - 8, 4, Charsets.ISO_8859_1))
            }
            exported(file, Templates.gallery, ExportSettings(keepLocation = true)) { _, _ ->
                val bytes = File(app.cacheDir, "last-export.jpg").readBytes()
                assertFalse(exportedName, exportedName.endsWith(".MP.jpg"))
                assertFalse(MotionPhoto.marked(bytes))
                assertTrue(bytes.size < video.size)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun ultraHdrMotionPhotoKeepsBothItsGainMapAndItsVideo() = runBlocking(Dispatchers.IO) {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val video = clip()
        val file = File(app.cacheDir, "moving-hdr.jpg")
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
        // The way a camera's file is laid out: still, gain map, then the video, all named in one directory.
        file.writeBytes(requireNotNull(MotionPhoto.attach(file.readBytes(), video, MotionPhoto.Video(0, video.size, 500_000))))
        fun hasGainMap(bytes: ByteArray): Boolean {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return decoded.hasGainmap().also { decoded.recycle() }
        }
        try {
            assertTrue("带上视频后系统仍应读到增益图", hasGainMap(file.readBytes()))
            val info = app.photos.info(PhotoGroup(photo(file)))
            assertTrue(info.motion)
            assertTrue(info.choices.single().hdr)
            exported(file, Templates.gallery, ExportSettings(keepMotion = true, keepLocation = true)) { _, size ->
                val bytes = File(app.cacheDir, "last-export.jpg").readBytes()
                assertTrue(exportedName, exportedName.endsWith(".MP.jpg"))
                assertNull(exportedRemark)
                assertTrue("成片应保留增益图", hasGainMap(bytes))
                val found = requireNotNull(MotionPhoto.video(bytes))
                assertArrayEquals(video, bytes.copyOfRange(found.offset, found.offset + found.length))
                assertEquals(bytes.size, found.offset + found.length)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                assertEquals(size, PixelSize(bounds.outWidth, bounds.outHeight))
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

    /** The name and remark the album gave the photo [exported] wrote last. */
    private var exportedName = ""
    private var exportedRemark: String? = null

    private suspend fun exported(
        file: File, template: WatermarkTemplate, settings: ExportSettings,
        overrides: Map<MetadataField, String> = emptyMap(), check: (ExifInterface, PixelSize) -> Unit,
    ) {
        val dao = app.database.dao()
        val id = UUID.randomUUID().toString()
        app.database.withTransaction {
            dao.insertJob(ExportJobRow(id, System.currentTimeMillis(), storageJson.encodeToString(template),
                storageJson.encodeToString(settings)))
            dao.insertItems(listOf(ExportItemRow(id, 0,
                storageJson.encodeToString(ExportInput(photo(file), overrides = overrides)), file.name, "JPEG")))
        }
        try {
            Exporter(app).exportItem(dao.items(id).single(), template, settings)
            val item = dao.items(id).single()
            val uri = Uri.parse(requireNotNull(item.outputUri))
            exportedRemark = item.errorMessage
            exportedName = app.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!
                .use { it.moveToFirst(); it.getString(0) }
            val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            File(app.cacheDir, "last-export.jpg").writeBytes(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(ExifInterface(bytes.inputStream()), PixelSize(bounds.outWidth, bounds.outHeight))
        } finally {
            dao.items(id).forEach { item -> item.outputUri?.let { app.contentResolver.delete(Uri.parse(it), null, null) } }
        }
    }

    private fun box(type: String, body: ByteArray) =
        ByteBuffer.allocate(body.size + 8).putInt(body.size + 8).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    /** The parts of an MP4 the app looks at: 40 000 bytes of frames, then a header that records a position. */
    private fun clip() = box("ftyp", "isom\u0000\u0000\u0002\u0000isommp42".toByteArray(Charsets.ISO_8859_1)) +
        box("mdat", ByteArray(40_000) { (it * 31).toByte() }) +
        box("moov", box("mvhd", ByteArray(100)) +
            box("udta", box("\u00A9xyz", "\u0000\u0012\u0015\u00C7+31.2397+121.4998/".toByteArray(Charsets.ISO_8859_1))))

    /** [still] with [video] behind it and the note a camera leaves in the still to say so. */
    private fun motionPhoto(still: ByteArray, video: ByteArray): ByteArray {
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
              xmlns:Container="http://ns.google.com/photos/1.0/container/"
              xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="500000">
              <Container:Directory><rdf:Seq>
                <rdf:li rdf:parseType="Resource">
                  <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
                <rdf:li rdf:parseType="Resource">
                  <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${video.size}" Item:Padding="0"/></rdf:li>
              </rdf:Seq></Container:Directory>
            </rdf:Description></rdf:RDF></x:xmpmeta>"""
        val body = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xml.toByteArray()
        val packet = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
        // Behind the JFIF and EXIF headers, where cameras put it.
        var at = 2
        while ((still[at + 1].toInt() and 0xFF) in 0xE0..0xE1) {
            at += 2 + (((still[at + 2].toInt() and 0xFF) shl 8) or (still[at + 3].toInt() and 0xFF))
        }
        return still.copyOfRange(0, at) + packet + still.copyOfRange(at, still.size) + video
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
