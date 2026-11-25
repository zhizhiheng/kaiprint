package app.kai.photomark

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTemplatesTest {
    @Test fun nativeJsonRetainsAnEditableLayerAndItsLayout() {
        val template = ExternalTemplates.decodeJson(
            """{"id":"mono","title":"Mono","layers":[{"id":"s","kind":"TEXT","text":"北京","rotation":-12.5}]}""")
        assertEquals("Mono", template.title)
        assertEquals("北京", template.layers.single().text)
        assertEquals(-12.5f, template.layers.single().rotation, 0f)
    }

    @Test fun standardFooterConvertsTextAndMetadataWithStylesAndIndependentLogo() {
        val source = buildJsonArray {
            add(buildJsonObject {
                put("processor_name", "watermark")
                put("left_top", buildJsonObject {
                    put("processor_name", "multi_rich_text")
                    put("text_segments", buildJsonArray { add(buildJsonObject {
                        put("text", "{{ exif.CameraModelName | default('-') | replace('_', '') }}")
                        put("font_path", "example-bold.otf")
                    }) })
                })
                put("left_bottom", rich("作品一号"))
                put("right_alignment", "left")
                put("right_top", rich("{{exif.FocalLengthIn35mmFormat|replace(' ', '')}} f/{{exif.AperatureValue or exif.FNumber}} {{exif.ShutterSpeed or exif.ShutterSpeedValue}}s ISO{{exif.ISO|default('0')}}"))
                put("right_bottom", rich("{{(exif.DateTimeOriginal or exif.CreateDate|default('0'))[:16]}}"))
                put("right_logo", "{{auto_logo()|replace('\\', '/')}}")
                put("color", "#ABC")
                put("delimiter_color", "#11223300")
            })
        }
        val converted = ExternalTemplates.decodeJson(source.toString(), "旅行底栏.json")
        assertEquals("旅行底栏", converted.title)
        assertEquals("#AABBCC", converted.frame.color)
        val layers = converted.layers.associateBy { it.id }
        val model = layers.getValue("left_top")
        assertEquals(LayerKind.EXIF, model.kind)
        assertEquals(listOf(MetadataField.MODEL), model.fields)
        assertTrue(model.bold)
        assertEquals(LayerKind.TEXT, layers.getValue("left_bottom").kind)
        assertEquals("作品一号", layers.getValue("left_bottom").text)
        val exposure = layers.getValue("right_top")
        assertEquals(Anchor.TOP_LEFT, exposure.anchor)
        assertEquals(LayerColumn.RIGHT_HALF, exposure.column)
        assertEquals(listOf(MetadataField.FOCAL_EQUIVALENT, MetadataField.APERTURE, MetadataField.SHUTTER, MetadataField.ISO), exposure.fields)
        assertEquals(DateStyle.MINUTE, converted.dateStyle)
        assertEquals(LayerKind.BRAND, layers.getValue("brand").kind)
        assertEquals(0f, layers.getValue("delimiter").opacity, 0f)
        assertFalse(converted.layers.any { it.fontFile != null })
        val values = PhotoMetadata(mapOf(MetadataField.ISO to "ISO 200", MetadataField.APERTURE to "f/5.6"))
        assertEquals(listOf("f/5.6", "ISO 200"), values.parts(exposure.fields, emptyMap()))
    }

    @Test fun unsupportedFunctionsProcessorsAndMixedProseFailWithExplanation() {
        for (text in listOf("现场 {{exif.Model}}", "{{exif.Model|upper}}", "{{exif.Unknown}}", "{{some_function()}}")) {
            val source = buildJsonArray { add(buildJsonObject {
                put("processor_name", "watermark"); put("left_top", rich(text))
            }) }
            assertThrows(PresetFormatException::class.java) { ExternalTemplates.decodeJson(source.toString()) }
        }
        assertThrows(PresetFormatException::class.java) {
            ExternalTemplates.decodeJson("""[{"processor_name":"margin"},{"processor_name":"watermark"}]""")
        }
    }

    private fun rich(text: String) = buildJsonObject { put("processor_name", "rich_text"); put("text", text) }
}
