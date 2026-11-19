package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AdaptiveWatermarkTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp

    @Test fun diffusionRetainsHighlightEnergy() {
        val sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 64) for (x in 0 until 64) {
                sample.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
            }
            blurPhotoSample(sample, .08f)
            // A 50:50 black/white pattern transmits 50% linear light (~188 in sRGB).
            assertTrue(Color.red(sample.getPixel(32, 32)) in 185..190)
        } finally {
            sample.recycle()
        }
    }

    @Test fun glassCaptionsChooseInkLocallyOnTheSameFooter() {
        val source = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        Canvas(source).drawRect(200f, 0f, 400f, 240f, Paint().apply { color = Color.BLACK })
        val template = Templates.frostedJournal.copy(layers = listOf(LayerColumn.LEFT_HALF, LayerColumn.RIGHT_HALF).map {
            WatermarkLayer(it.name, LayerKind.TEXT, "TEST", area = LayerArea.BOTTOM, anchor = Anchor.CENTER,
                column = it, size = .065f, autoGlassInk = true)
        })
        val layout = WatermarkLayout.calculate(PixelSize(400, 240), template.frame)
        val output = app.renderer.preview(source, layout, template, PhotoMetadata(), emptyMap())
        try {
            val y0 = layout.photo.bottom.roundToInt() + 4
            val y1 = output.height - 4
            fun hasInk(center: Int, check: (Int) -> Boolean) = (y0 until y1).any { y ->
                (center - 26..center + 26).any { x -> check(Color.red(output.getPixel(x, y))) }
            }
            assertTrue(hasInk(output.width / 4) { it < 45 })
            assertTrue(hasInk(output.width * 3 / 4) { it > 220 })
        } finally {
            output.recycle()
            source.recycle()
        }
    }

    @Test fun frostedBackgroundUsesEachPhotoAndKeepsItsInteriorSharp() {
        val template = Templates.frostedGallery.copy(layers = emptyList())
        val layout = WatermarkLayout.calculate(PixelSize(300, 200), template.frame)
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(180, 90, 60))
        val warm = app.renderer.preview(source, layout, template, PhotoMetadata(), emptyMap())
        source.eraseColor(Color.rgb(30, 100, 175))
        val cool = app.renderer.preview(source, layout, template, PhotoMetadata(), emptyMap())
        try {
            assertNotEquals(warm.getPixel(3, 3), cool.getPixel(3, 3))
            assertEquals(Color.rgb(30, 100, 175), cool.getPixel(layout.photo.left.roundToInt() + 20, 40))
        } finally {
            warm.recycle()
            cool.recycle()
            source.recycle()
        }
    }

    @Test fun paletteAndPerPhotoCaptionAppearInTheRenderedTemplate() {
        val colors = listOf(Color.rgb(45, 80, 90), Color.rgb(170, 77, 58), Color.rgb(212, 196, 145))
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(index * 100f, 0f, (index + 1) * 100f, 200f, paint)
        }
        val template = Templates.colorAtlas.copy(captions = mapOf(MetadataField.AUTHOR to "Shared author"))
        val layout = WatermarkLayout.calculate(PixelSize(300, 200), template.frame)
        val withAuthor = app.renderer.preview(source, layout, template, PhotoMetadata(), emptyMap())
        val blankAuthor = app.renderer.preview(source, layout, template, PhotoMetadata(), mapOf(MetadataField.AUTHOR to ""))
        try {
            val footer = layout.photo.bottom.roundToInt()
            val bandPixels = IntArray(withAuthor.width * (withAuthor.height - footer))
            withAuthor.getPixels(bandPixels, 0, withAuthor.width, 0, footer, withAuthor.width, withAuthor.height - footer)
            assertTrue(colors.all { it in bandPixels })
            val otherPixels = IntArray(bandPixels.size)
            blankAuthor.getPixels(otherPixels, 0, blankAuthor.width, 0, footer, blankAuthor.width, blankAuthor.height - footer)
            assertFalse(bandPixels.contentEquals(otherPixels))
        } finally {
            withAuthor.recycle()
            blankAuthor.recycle()
            source.recycle()
        }
    }
}
