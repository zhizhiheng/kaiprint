package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandRenderingTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp

    @Test fun everyBundledVectorProducesVisiblePixels() {
        val bundled = BrandMark.entries.filter { it.asset != null }
        assertTrue(bundled.size >= 20)
        for (brand in bundled) {
            for (style in BrandStyle.entries) {
                val label = "${brand.label} ${style.label}"
                val bitmap = checkNotNull(app.renderer.brandThumbnail(brand, BrandTone.ORIGINAL, style)) { label }
                try {
                    assertTrue(label, pixels(bitmap).any { Color.alpha(it) > 128 })
                    if (style == BrandStyle.SQUARE) assertEquals(label, bitmap.width, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun sharedTemplateRendersTheRightBrandForEachPhotoEvenOnPortraits() {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val template = Templates.zr.copy(layers = Templates.zr.layers.filter { it.kind == LayerKind.BRAND })
        val layout = WatermarkLayout.calculate(PixelSize(400, 600), template.frame)
        try {
            fun preview(
                make: String, model: String, override: BrandMark = BrandMark.AUTO,
                style: BrandStyle = BrandStyle.ORIGINAL,
            ): IntArray {
                val exif = PhotoMetadata(mapOf(MetadataField.MAKE to make, MetadataField.MODEL to model))
                val draft = template.copy(layers = template.layers.map { it.copy(brand = override, brandStyle = style) })
                val bitmap = app.renderer.preview(source, layout, draft, exif, emptyMap())
                return try { pixels(bitmap) } finally { bitmap.recycle() }
            }
            val nikon = preview("NIKON CORPORATION", "NIKON ZR")
            val canon = preview("Canon", "EOS R5")
            val unknown = preview("Unknown", "Unknown")
            assertFalse(unknown.contentEquals(nikon))
            assertFalse(unknown.contentEquals(canon))
            assertFalse(nikon.contentEquals(canon))
            assertTrue(canon.contentEquals(preview("NIKON CORPORATION", "NIKON ZR", BrandMark.CANON)))
            val nikonSquare = preview("Nikon", "NIKON ZR", style = BrandStyle.SQUARE)
            val canonSquare = preview("Canon", "EOS R5", style = BrandStyle.SQUARE)
            assertFalse(nikonSquare.contentEquals(nikon))
            assertFalse(canonSquare.contentEquals(canon))
            assertFalse(canonSquare.contentEquals(nikonSquare))
            assertTrue(canon.contentEquals(preview("Canon", "EOS R5", style = BrandStyle.WORDMARK)))
        } finally {
            source.recycle()
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
