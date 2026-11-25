package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PresetGalleryTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp

    // These are the README's licensed source photographs. The camera tags are for the layout demonstration.
    private enum class Picture(val file: String, val title: String, val author: String) {
        VALLEY("valley", "云过山脊", "Andrew Ridley"),
        CITY("london", "泰晤士河的夜色", "Vadim Sherbakov"),
        BUILDING("architecture", "楼宇之间", "Anthony DELANOIX"),
        DOG("dog", "林间午后", "Matthew Wiebe"),
        FALLS("waterfall", "林间水声", "Paul Jarvis"),
    }

    private val gallery = mapOf(
        "instant-print" to Picture.DOG,
        "field-postcard" to Picture.VALLEY,
        "cinema-scope" to Picture.CITY,
        "film-rebate" to Picture.BUILDING,
        "specimen-label" to Picture.DOG,
        "half-title" to Picture.VALLEY,
        "color-margin" to Picture.FALLS,
        "frost-cover" to Picture.FALLS,
    )

    private fun demoMetadata(picture: Picture): PhotoMetadata {
        val fields = mutableMapOf(
            MetadataField.TITLE to picture.title, MetadataField.AUTHOR to picture.author,
            MetadataField.MAKE to "Nikon", MetadataField.MODEL to "ZR",
            MetadataField.LENS to "NIKKOR Z 24–70mm f/2.8 S", MetadataField.FOCAL to "35 mm",
            MetadataField.APERTURE to "f/5.6", MetadataField.SHUTTER to "1/125 s", MetadataField.ISO to "ISO 200",
            MetadataField.DATE to "2026-05-05 10:17:42",
        )
        when (picture) {
            Picture.VALLEY -> fields[MetadataField.LOCATION] = "Isle of Skye"
            Picture.CITY -> fields[MetadataField.LOCATION] = "London"
            Picture.BUILDING -> fields.putAll(mapOf(
                MetadataField.MAKE to "FUJIFILM", MetadataField.MODEL to "X-T5",
                MetadataField.LENS to "XF16–55mmF2.8 R LM WR", MetadataField.FILM to "CLASSIC CHROME",
            ))
            Picture.DOG, Picture.FALLS -> Unit
        }
        return PhotoMetadata(fields)
    }

    @Test fun newPresetsRenderOnBothOrientations() {
        val templates = Templates.all.associateBy(WatermarkTemplate::id)
        val metadata = demoMetadata(Picture.VALLEY)
        for ((width, height) in listOf(480 to 320, 320 to 480)) {
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // The four quadrants help detect a displaced, reflected or missing photograph.
                val canvas = Canvas(source)
                val paint = Paint()
                listOf(0xFF175A47, 0xFFC89A56, 0xFF506B8D, 0xFF9D655C).forEachIndexed { index, color ->
                    val x = (index % 2) * width / 2f
                    val y = (index / 2) * height / 2f
                    paint.color = color.toInt()
                    canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
                }
                for (id in gallery.keys) {
                    val template = templates.getValue(id)
                    for (details in listOf(metadata, PhotoMetadata())) {
                        val layout = WatermarkLayout.calculate(PixelSize(width, height), template, metadata = details)
                        val image = app.renderer.preview(source, layout, template, details, emptyMap(), layout.canvas.longEdge)
                        try {
                            assertEquals(id, layout.canvas.width, image.width)
                            assertEquals(id, layout.canvas.height, image.height)
                            for (x in listOf(width / 3, width * 2 / 3)) for (y in listOf(height / 3, height * 2 / 3)) {
                                assertEquals("$id ($x, $y)", source.getPixel(x, y), image.getPixel(
                                    layout.photo.left.roundToInt() + x, layout.photo.top.roundToInt() + y))
                            }
                            if (details === metadata) {
                                val bare = app.renderer.preview(source, layout, template.copy(layers = emptyList()),
                                    details, emptyMap(), layout.canvas.longEdge)
                                try {
                                    assertFalse("$id must draw at least one of its layers", image.sameAs(bare))
                                } finally {
                                    bare.recycle()
                                }
                            }
                        } finally {
                            image.recycle()
                        }
                    }
                }
            } finally {
                source.recycle()
            }
        }
        if (InstrumentationRegistry.getArguments().getString("presetGallery") == "true") {
            exportGallery(templates)
        }
    }

    /** To reproduce the gallery, put the five source JPEGs into files/preset-gallery/input first. */
    private fun exportGallery(templates: Map<String, WatermarkTemplate>) {
        val folder = File(app.filesDir, "preset-gallery")
        val out = File(folder, "output").apply { mkdirs() }
        for ((id, picture) in gallery) {
            val file = File(folder, "input/${picture.file}.jpg")
            require(file.isFile) { "Missing gallery source: $file" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val size = PixelSize(bounds.outWidth, bounds.outHeight)
            val photo = PhotoSource(Uri.fromFile(file).toString(), file.name, "Sample", file.parent.orEmpty())
            val metadata = demoMetadata(picture)
            val template = templates.getValue(id)
            val layout = WatermarkLayout.calculate(size, template, maximumEdge = 1500, metadata = metadata)
            val image = app.renderer.renderForExport(PixelChoice(photo, PixelKind.STANDARD, size),
                layout, template, metadata, emptyMap()) { }
            try {
                File(out, "$id.jpg").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
            } finally {
                image.recycle()
            }
        }
        val postcard = templates.getValue("field-postcard")
        val preview = app.renderer.sample(postcard)
        try {
            PresetArchive(app).write(postcard, Uri.fromFile(File(folder, "field-postcard.kaiphoto")), preview)
        } finally {
            preview.recycle()
        }
    }
}
