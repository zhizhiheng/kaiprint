package app.kai.photomark

import android.graphics.Bitmap
import android.util.Xml
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStream
import java.io.StringReader

/** Adapters only describe native layers. PresetArchive validates and installs their assets. */
internal object ExternalTemplates {
    const val SVG_ARTWORK = "artwork.png"

    fun decodeJson(text: String, displayName: String = ""): WatermarkTemplate {
        val document = storageJson.parseToJsonElement(text.removePrefix("\uFEFF"))
        return when (document) {
            is JsonObject -> {
                if ("id" !in document || "title" !in document) {
                    throw PresetFormatException("无法识别这种 JSON 模板。请选择光印模板或 semi-utils 单段 watermark 模板")
                }
                storageJson.decodeFromJsonElement(WatermarkTemplate.serializer(), document)
            }
            is JsonArray -> SemiFooter.convert(document, titleFrom(displayName, "参数底栏"))
            else -> throw PresetFormatException("无法识别这种 JSON 模板")
        }
    }

    fun decodeSvg(text: String, displayName: String, png: OutputStream): WatermarkTemplate {
        val svg = text.removePrefix("\uFEFF")
        val documentTitle = staticSvgTitle(svg)
        val bitmap = renderSvg(svg.byteInputStream())
        try {
            if (!hasInk(bitmap)) throw PresetFormatException("SVG 未绘制出可见图形")
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw PresetFormatException("SVG 素材保存失败")
        } finally {
            bitmap.recycle()
        }
        return WatermarkTemplate("svg", documentTitle.ifBlank { titleFrom(displayName, "SVG 装饰") },
            layers = listOf(WatermarkLayer("artwork", LayerKind.LOGO, logoFile = SVG_ARTWORK, size = .45f)))
    }

    private fun titleFrom(name: String, fallback: String) =
        name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().ifEmpty { fallback }

    private fun hasInk(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            if (row.any { it ushr 24 != 0 }) return true
        }
        return false
    }

    private val svgElements = setOf("svg", "g", "title", "desc", "defs", "path", "rect", "circle", "ellipse",
        "line", "polygon", "polyline", "text", "tspan", "linearGradient", "radialGradient", "stop", "clipPath")
    private val declaration = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
    private val localPaint = Regex("""url\(\s*['"]?#[\w.-]+['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val externalPaint = Regex("""url\s*\(|@import""", RegexOption.IGNORE_CASE)
    private val darktableVariable = Regex("""\$\([A-Za-z_]""")

    private fun requireStaticValue(value: String) {
        if (darktableVariable.containsMatchIn(value)) {
            throw PresetFormatException("SVG 含有 darktable 动态变量。请先替换为固定值，或移除变量，导入后在光印中添加拍摄参数图层")
        }
    }

    /** Accept a static vector subset; parsing never needs a document type or another file. */
    private fun staticSvgTitle(svg: String): String {
        if (declaration.containsMatchIn(svg)) throw PresetFormatException("SVG 包含不支持的文档声明")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(svg))
        var elements = 0
        var title = ""
        var textTail = ""
        var unsupported: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val content = textTail + parser.text.orEmpty()
                    requireStaticValue(content)
                    // Entity references and CDATA may split a placeholder across XML tokens.
                    textTail = content.takeLast(2)
                }
                else -> textTail = ""
            }
            if (parser.eventType == XmlPullParser.START_TAG) {
                val name = parser.name
                if (++elements > 12_000 || parser.depth > 48) throw PresetFormatException("SVG 图形过于复杂")
                if ((parser.depth == 1 && name != "svg") || name !in svgElements ||
                    (!parser.namespace.isNullOrEmpty() && parser.namespace != "http://www.w3.org/2000/svg")) {
                    unsupported = unsupported ?: "SVG 含有不支持的元素，请导出为静态矢量图形"
                }
                for (i in 0 until parser.attributeCount) {
                    val key = parser.getAttributeName(i)
                    val value = parser.getAttributeValue(i)
                    requireStaticValue(value)
                    if (key == "href" || key.startsWith("on", ignoreCase = true) ||
                        externalPaint.containsMatchIn(localPaint.replace(value, ""))) {
                        unsupported = unsupported ?: "SVG 含有不支持的文件引用或事件"
                    }
                }
                if (name == "title" && parser.depth == 2 && title.isEmpty()) {
                    title = parser.nextText().trim()
                    requireStaticValue(title)
                }
            } else if (parser.eventType == XmlPullParser.PROCESSING_INSTRUCTION &&
                parser.text.trimStart().startsWith("xml-stylesheet", true)) {
                unsupported = unsupported ?: "请将 SVG 样式写在图形内部"
            }
            parser.nextToken()
        }
        if (elements == 0) throw PresetFormatException("SVG 缺少根元素")
        unsupported?.let { throw PresetFormatException(it) }
        return title
    }
}

/** Translates the single-watermark pipeline used in semi-utils standard1 into native layers. */
private object SemiFooter {
    private val cells = listOf("left_top", "left_bottom", "right_top", "right_bottom")
    private val footerKeys = cells + listOf("processor_name", "color", "delimiter_color", "right_alignment", "right_logo")
    private val cellKeys = setOf("text", "font_path", "is_bold", "color")
    private val fieldByTag = buildMap {
        for ((field, tags) in listOf(
            MetadataField.MAKE to "Make", MetadataField.MODEL to "CameraModelName Model",
            MetadataField.LENS to "LensModel", MetadataField.FOCAL to "FocalLength",
            MetadataField.FOCAL_EQUIVALENT to "FocalLengthIn35mmFormat FocalLengthIn35mmFilm",
            MetadataField.APERTURE to "AperatureValue ApertureValue FNumber",
            MetadataField.SHUTTER to "ShutterSpeed ShutterSpeedValue ExposureTime",
            MetadataField.ISO to "ISO", MetadataField.AUTHOR to "Artist", MetadataField.TITLE to "Title",
            MetadataField.DATE to "DateTimeOriginal CreateDate DigitalCreationDate DateCreated DateTimeCreated DigitalCreationDateTime",
        )) for (tag in tags.split(' ')) put("exif.$tag", field)
    }
    private val expression = Regex("""\{\{([^{}]{1,512})}}""")
    private val filter = Regex("""\s*\|\s*(?:default\(\s*(?:'[-0]?'|"[-0]?")\s*\)|replace\(\s*(?:'[ _]'|"[ _]")\s*,\s*(?:''|"")\s*\))""")
    private val fieldSeparator = Regex("""[\s·|,/-]|mm|f/|s|ISO""")
    private val autoLogo = Regex("""\{\{\s*auto_logo\(\)(?:\s*\|\s*replace\(\s*'\\'\s*,\s*'/'\s*\))?\s*}}""")
    private val shortHex = Regex("#[0-9a-fA-F]{3}")
    private val rgb = Regex("#[0-9a-fA-F]{6}")
    private val rgba = Regex("#[0-9a-fA-F]{8}")

    fun convert(nodes: JsonArray, name: String): WatermarkTemplate {
        val footer = nodes.singleOrNull() as? JsonObject ?: unsupported("请选择只有一个 watermark 处理器的模板")
        if (footer.string("processor_name") != "watermark") unsupported("当前支持 watermark 底栏处理器")
        if (!footerKeys.containsAll(footer.keys)) unsupported("底栏包含不支持的布局设置")
        val alignment = footer.string("right_alignment") ?: "right"
        if (alignment !in listOf("left", "center", "right")) unsupported()
        val layers = mutableListOf<WatermarkLayer>()
        var minutes = false
        for (slot in cells) {
            val cell = footer[slot] ?: continue
            val segment = segment(cell as? JsonObject ?: unsupported())
            val content = segment.string("text") ?: unsupported()
            if (content.isBlank()) continue
            if (content.length > 2048) unsupported()
            val left = slot.startsWith("left")
            val column = if (left) LayerColumn.LEFT_HALF else LayerColumn.RIGHT_HALF
            val horizontal = if (left || alignment == "left") 0f else if (alignment == "center") .5f else 1f
            val vertical = if (slot.endsWith("top")) 0f else 1f
            val ink = color(segment.string("color") ?: "black")
            val layer = WatermarkLayer(slot, LayerKind.TEXT, area = LayerArea.BOTTOM, column = column,
                anchor = Anchor.entries.first { it.x == horizontal && it.y == vertical },
                size = if (vertical == 0f) .036f else .028f, margin = .025f,
                color = solidPart(ink), opacity = opacity(ink),
                bold = segment["is_bold"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: unsupported() }
                    ?: segment.string("font_path").orEmpty().contains("bold", true),
                separator = Separator.SPACE)
            val matches = expression.findAll(content).toList()
            if (matches.isEmpty()) {
                if (content.codePointCount(0, content.length) > 240 || listOf("{{", "}}", "{%", "{#").any(content::contains)) {
                    unsupported("文本区需要短文案或支持的 EXIF 字段")
                }
                layers += layer.copy(text = content)
            } else {
                if (fieldSeparator.replace(expression.replace(content, ""), "").isNotEmpty()) unsupported("请把固定文案和 EXIF 参数放在不同文本区")
                if (matches.size > MetadataField.entries.size) unsupported()
                val fields = matches.map { field(it.groupValues[1]) }
                minutes = minutes || matches.any { it.groupValues[1].trimEnd().endsWith("[:16]") }
                layers += layer.copy(kind = LayerKind.EXIF, fields = fields)
            }
        }
        val delimiter = footer.string("delimiter_color")
        if (delimiter != null) {
            val ink = color(delimiter)
            layers += WatermarkLayer("delimiter", LayerKind.LINE, area = LayerArea.BOTTOM, anchor = Anchor.CENTER,
                size = .065f, rotation = 90f, color = solidPart(ink), opacity = opacity(ink))
        }
        val logo = footer.string("right_logo")
        if (!logo.isNullOrEmpty()) {
            if (!autoLogo.matches(logo)) unsupported("右侧标志只支持 auto_logo() 自动厂标")
            layers += WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.BOTTOM, anchor = Anchor.CENTER_RIGHT,
                brandStyle = BrandStyle.WORDMARK, size = .12f, margin = .012f, reserveBrandSpace = true)
        }
        if (layers.isEmpty()) unsupported()
        return WatermarkTemplate("semi-footer", name, Frame(bottom = .145f, color = color(footer.string("color") ?: "white")),
            layers, dateStyle = if (minutes) DateStyle.MINUTE else DateStyle.ORIGINAL)
    }

    private fun segment(cell: JsonObject): JsonObject = when (cell.string("processor_name")) {
        "rich_text" -> cell.also { if (!(cellKeys + "processor_name").containsAll(it.keys)) unsupported() }
        "multi_rich_text" -> {
            if (cell.keys.any { it != "processor_name" && it != "text_segments" }) unsupported()
            val only = (cell["text_segments"] as? JsonArray)?.singleOrNull() as? JsonObject ?: unsupported("富文本区只支持一个文本片段")
            if (!cellKeys.containsAll(only.keys)) unsupported()
            only
        }
        else -> unsupported()
    }

    private fun field(code: String): MetadataField {
        var value = filter.replace(code, "").trim()
        val minute = value.endsWith("[:16]")
        if (minute) value = value.removeSuffix("[:16]").trimEnd()
        if (value.startsWith('(') && value.endsWith(')')) value = value.drop(1).dropLast(1).trim()
        val alternatives = value.split(Regex("\\s+or\\s+")).map { fieldByTag[it] ?: unsupported("文本区含有不支持的 EXIF 字段或表达式") }.distinct()
        val field = alternatives.singleOrNull() ?: unsupported()
        if (minute && field != MetadataField.DATE) unsupported()
        return field
    }

    private fun solidPart(argb: String) = "#" + argb.takeLast(6)
    private fun opacity(argb: String) = if (argb.length == 9) argb.substring(1, 3).toInt(16) / 255f else 1f

    private fun color(value: String): String = when {
        rgb.matches(value) -> value
        rgba.matches(value) -> "#" + value.takeLast(2) + value.substring(1, 7)
        shortHex.matches(value) -> "#" + value.drop(1).map { "$it$it" }.joinToString("")
        value.equals("white", true) -> "#FFFFFF"
        value.equals("black", true) -> "#000000"
        else -> unsupported()
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.let { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: unsupported() }

    private fun unsupported(reason: String = "模板包含不支持的设置"): Nothing =
        throw PresetFormatException("无法转换 semi-utils 模板。$reason")
}
