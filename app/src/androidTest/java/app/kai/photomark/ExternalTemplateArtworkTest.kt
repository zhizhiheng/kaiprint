package app.kai.photomark

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalTemplateArtworkTest {
    @Test fun staticSvgBecomesAPortableLayerAndKeepsTransparency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val svg = File.createTempFile("decor-", ".svg", context.cacheDir)
        val pack = File.createTempFile("decor-", ".kaiphoto", context.cacheDir)
        val assets = mutableListOf<File>()
        try {
            svg.writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><!-- $(YEAR) -->
                <title>旅行签章</title><circle cx="50" cy="25" r="20" fill="#e74e46"/></svg>""")
            val archive = PresetArchive(context)
            val imported = archive.read(Uri.fromFile(svg))
            val layer = imported.template.layers.single()
            assertEquals("旅行签章", imported.template.title)
            assertEquals(LayerKind.LOGO, layer.kind)
            val stored = File(context.filesDir, "logos/${requireNotNull(layer.logoFile)}").also(assets::add)
            val image = BitmapFactory.decodeFile(stored.path)
            try {
                assertEquals(1600, image.width)
                assertEquals(800, image.height)
                assertEquals(0, Color.alpha(image.getPixel(0, 0)))
                assertTrue(Color.red(image.getPixel(800, 400)) > 200)
            } finally {
                image.recycle()
            }
            archive.write(imported.template.copy(layers = listOf(layer.copy(anchor = Anchor.TOP_LEFT))), Uri.fromFile(pack))
            val reopened = archive.read(Uri.fromFile(pack)).template.layers.single()
            assertEquals(Anchor.TOP_LEFT, reopened.anchor)
            assertNotEquals(layer.logoFile, reopened.logoFile)
            val restored = File(context.filesDir, "logos/${requireNotNull(reopened.logoFile)}").also(assets::add)
            assertTrue(stored.readBytes().contentEquals(restored.readBytes()))
        } finally {
            assets.forEach(File::delete)
            svg.delete()
            pack.delete()
        }
    }

    @Test fun darktableVariablesExplainHowToKeepPhotoFieldsEditable() {
        for (body in listOf(
            """<text>© $(YEAR) · $(EXIF.MODEL)</text>""",
            """<editor:view xmlns:editor="urn:editor"/><text>$(EXIF.MODEL)</text>""",
            """<text>&#36;(EXIF_YEAR)</text>""",
            """<text><![CDATA[$(WATERMARK_TEXT)]]></text>""",
            """<g style="fill:$(WATERMARK_COLOR)"><circle r="2"/></g>""",
            """<title>$(YEAR)</title>""",
        )) {
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 20">$body</svg>"""
            val png = ByteArrayOutputStream()
            val error = assertThrows(PresetFormatException::class.java) {
                ExternalTemplates.decodeSvg(svg, "darktable.svg", png)
            }
            val message = error.message.orEmpty()
            assertTrue("模板片段 $body 返回了 $message", message.contains("darktable") && message.contains("拍摄参数图层"))
            assertEquals(0, png.size())
        }
    }

    @Test fun unsupportedSvgFeaturesAreRejectedBeforeAnAssetIsInstalled() {
        for (body in listOf(
            """<image href="reference.png"/>""",
            """<rect width="30" height="20" fill="url(reference.svg#paint)"/>""",
            """<animate attributeName="x" from="0" to="1"/>""",
            """<g/>""",
        )) {
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 20">$body</svg>"""
            assertThrows(PresetFormatException::class.java) {
                ExternalTemplates.decodeSvg(svg, "decoration.svg", ByteArrayOutputStream())
            }
        }
    }
}
