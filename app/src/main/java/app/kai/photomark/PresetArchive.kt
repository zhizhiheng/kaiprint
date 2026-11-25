package app.kai.photomark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.caverock.androidsvg.SVG
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

private const val MAX_ASSET = 16 * 1024 * 1024
private const val MAX_ARCHIVE = 48 * 1024 * 1024
private const val MAX_TEMPLATE = 256 * 1024
private const val MAX_MANIFEST = 4096
private const val MAX_SVG = 2 * 1024 * 1024
private const val MANIFEST = "manifest.json"
private const val TEMPLATE = "template.json"
private const val FORMAT_HEADER = """{"format":"kaiphoto","formatVersion":1,"template":"template.json"}"""

internal class PresetFormatException(message: String) : IOException(message)

private val assetName = Regex("[a-zA-Z0-9_-]{1,80}\\.(png|ttf|otf)")

internal fun InputStream.copyBounded(output: OutputStream, limit: Int): Int {
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return total
        total += count
        if (total > limit) throw PresetFormatException("文件超过大小限制")
        output.write(buffer, 0, count)
    }
}

private fun ByteArrayOutputStream.utf8Text(): String = try {
    toByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
} catch (_: CharacterCodingException) {
    throw PresetFormatException("模板文本需要 UTF-8 编码")
}

private fun String.takeCodePoints(limit: Int) =
    if (length <= limit || codePointCount(0, length) <= limit) this else substring(0, offsetByCodePoints(0, limit))

internal fun renderSvg(input: InputStream): Bitmap {
    val bytes = ByteArrayOutputStream()
    input.copyBounded(bytes, 2 * 1024 * 1024)
    val text = bytes.toString(Charsets.UTF_8.name())
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text))
    val svg = SVG.getFromString(text)
    val viewBox = svg.documentViewBox
    val width = svg.documentWidth.takeIf { it.isFinite() && it in 1f..100_000f } ?: viewBox?.width() ?: 512f
    val height = svg.documentHeight.takeIf { it.isFinite() && it in 1f..100_000f } ?: viewBox?.height() ?: 512f
    require(width in 1f..100_000f && height in 1f..100_000f)
    if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
    val scale = 1600f / maxOf(width, height)
    val bitmap = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
    return bitmap
}

data class ImportedPreset(val template: WatermarkTemplate, val missingFonts: Int = 0, val missingLogos: Int = 0,
                          val conversion: String? = null)

class PresetArchive(private val context: Context) {
    fun write(template: WatermarkTemplate, uri: Uri, preview: Bitmap? = null) {
        validate(template)
        require(template.title.codePointCount(0, template.title.length) <= 60 &&
            template.layers.all { it.text.codePointCount(0, it.text.length) <= 240 } &&
            template.captions.values.all { it.codePointCount(0, it.length) <= 120 })
        val json = storageJson.encodeToString(template).toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_TEMPLATE) { "模板超过大小限制" }
        val referenced = assets(template)
        val buffer = File.createTempFile("preset-export-", ".kaiphoto", context.cacheDir)
        try {
            // Finish and validate the archive before opening the user's destination for writing.
            ZipOutputStream(buffer.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                val header = FORMAT_HEADER.toByteArray(Charsets.UTF_8)
                zip.write(header)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(TEMPLATE))
                zip.write(json)
                zip.closeEntry()
                var remaining = MAX_ARCHIVE - header.size - json.size
                if (preview != null) {
                    val bytes = ByteArrayOutputStream()
                    if (!preview.compress(Bitmap.CompressFormat.JPEG, 88, bytes)) throw IOException("无法保存预览图")
                    require(bytes.size() <= minOf(MAX_ASSET, remaining))
                    zip.putNextEntry(ZipEntry(PREVIEW))
                    bytes.writeTo(zip)
                    zip.closeEntry()
                    remaining -= bytes.size()
                }
                for (name in referenced) {
                    val asset = assetFile(name)
                    if (!asset.isFile) throw IOException("无法读取预设素材")
                    zip.putNextEntry(ZipEntry("assets/$name"))
                    asset.inputStream().use { remaining -= it.copyBounded(zip, minOf(remaining, MAX_ASSET)) }
                    zip.closeEntry()
                }
            }
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("文件无法写入")
            output.use { target -> buffer.inputStream().use { it.copyTo(target) } }
        } finally {
            buffer.delete()
        }
    }

    fun read(uri: Uri): ImportedPreset {
        val staged = mutableMapOf<String, File>()
        val imported = mutableListOf<File>()
        try {
            var conversion: String? = null
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("文件无法读取")
            val template = stream.buffered().use { input ->
                input.mark(2)
                val zipped = input.read() == 'P'.code && input.read() == 'K'.code
                input.reset()
                if (zipped) return@use readZip(input, staged)

                val bytes = ByteArrayOutputStream()
                input.copyBounded(bytes, MAX_SVG)
                val text = bytes.utf8Text().trimStart()
                when (text.firstOrNull()) {
                    '<' -> {
                        val png = stageFile(staged, ExternalTemplates.SVG_ARTWORK)
                        conversion = "SVG 已作为一个可移动的装饰图层导入"
                        png.outputStream().use { ExternalTemplates.decodeSvg(text, displayName(uri), it) }
                    }
                    '{', '[' -> {
                        if (bytes.size() > MAX_TEMPLATE) throw PresetFormatException("模板 JSON 超过 256 KiB")
                        if (text.startsWith('[')) conversion = "已转换为可编辑图层，字体与排版采用光印样式"
                        ExternalTemplates.decodeJson(text, displayName(uri))
                    }
                    else -> throw PresetFormatException("请选择 .kaiphoto、旧版预设 ZIP、模板 JSON 或静态 SVG")
                }
            }
            validate(template)
            val referenced = assets(template)
            if (!referenced.containsAll(staged.keys)) throw PresetFormatException("预设包包含模板未引用的素材")
            val missing = referenced - staged.keys
            val replacements = staged.mapValues { (name, source) ->
                if (name.endsWith(".png")) {
                    val bitmap = try {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                            if (info.mimeType != "image/png" || info.size.width !in 1..1600 || info.size.height !in 1..1600)
                                throw PresetFormatException("Logo 需要最长边不超过 1600 像素的 PNG 图片")
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } catch (_: ImageDecoder.DecodeException) {
                        throw PresetFormatException("无法解码预设的 PNG 素材")
                    }
                    bitmap.recycle()
                } else {
                    require(Typeface.Builder(source).build() != null)
                }
                val target = assetFile("${UUID.randomUUID()}.${name.substringAfterLast('.')}")
                target.parentFile?.mkdirs()
                imported += target
                source.copyTo(target)
                target.name
            }
            val saved = template.copy(id = UUID.randomUUID().toString(), title = template.title.takeCodePoints(60),
                captions = template.captions.mapValues { it.value.takeCodePoints(120) }, layers = template.layers.map {
                it.copy(logoFile = replacements[it.logoFile], fontFile = replacements[it.fontFile], text = it.text.takeCodePoints(240))
            })
            return ImportedPreset(saved, missing.count { !it.endsWith(".png") }, missing.count { it.endsWith(".png") }, conversion)
        } catch (error: Exception) {
            imported.forEach(File::delete)
            throw error
        } finally {
            staged.values.forEach(File::delete)
        }
    }

    private fun readZip(input: InputStream, staged: MutableMap<String, File>): WatermarkTemplate {
        var native = false
        var template: String? = null
        val entries = mutableSetOf<String>()
        var total = 0
        var assetBytes = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entries.add(entry.name) || entries.size > 51)
                    throw PresetFormatException("预设包包含重复、目录或过多条目")
                val remaining = if (native) MAX_ARCHIVE - total else Int.MAX_VALUE
                when (entry.name) {
                    MANIFEST, TEMPLATE -> {
                        val bytes = ByteArrayOutputStream()
                        val isHeader = entry.name == MANIFEST
                        total += zip.copyBounded(bytes, minOf(remaining, if (isHeader) MAX_MANIFEST else MAX_TEMPLATE))
                        val json = bytes.utf8Text()
                        if (isHeader) {
                            validateManifest(json)
                            native = true
                        } else template = json
                    }
                    PREVIEW, "README.md" -> total += zip.copyBounded(DISCARD, minOf(remaining, MAX_ASSET))
                    else -> {
                        if (!entry.name.startsWith("assets/")) throw PresetFormatException("预设包包含未知文件")
                        val name = entry.name.removePrefix("assets/")
                        if (!assetName.matches(name)) throw PresetFormatException("预设素材文件名无效")
                        val bytes = stageFile(staged, name).outputStream().use {
                            zip.copyBounded(it, minOf(remaining, MAX_ARCHIVE - assetBytes, MAX_ASSET))
                        }
                        assetBytes += bytes
                        total += bytes
                    }
                }
                if (native && total > MAX_ARCHIVE) throw PresetFormatException(".kaiphoto 展开后超过 48 MiB")
                zip.closeEntry()
            }
        }
        if (!native && entries.size > 50) throw PresetFormatException("旧版预设包超过 50 个条目")
        return storageJson.decodeFromString<WatermarkTemplate>(template ?: throw PresetFormatException("预设包缺少 template.json"))
    }

    private fun validateManifest(json: String) {
        val fields = runCatching { storageJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        if (fields == null || fields["format"] != JsonPrimitive("kaiphoto") || fields["template"] != JsonPrimitive(TEMPLATE))
            throw PresetFormatException(".kaiphoto 清单的格式标识或模板路径无效")
        val version = fields["formatVersion"] as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull != 1)
            throw PresetFormatException("此版本光印支持 formatVersion 为 1 的 .kaiphoto 文件")
    }

    private fun validate(template: WatermarkTemplate) {
        if (template.version != 1) throw PresetFormatException("此版本光印支持 version 为 1 的模板")
        if (template.id.isEmpty() || template.title.isBlank() || template.layers.size > 24 ||
            template.layers.any { it.id.isEmpty() } || template.layers.map(WatermarkLayer::id).toSet().size != template.layers.size)
            throw PresetFormatException("模板需要标题，最多 24 个图层，每个图层的 ID 应唯一")
        with(template.frame) {
            if (listOf(left, right, top, bottom, blur, tintStrength, corner, shadow).any { !it.isFinite() })
                throw PresetFormatException("边框尺寸和效果需要有限数值")
        }
        for (layer in template.layers) {
            if (layer.x !in -1f..1f || layer.y !in -1f..1f)
                throw PresetFormatException("图层偏移需要在 -1 至 1 之间")
            if (listOf(layer.size, layer.margin, layer.opacity, layer.rotation, layer.letterSpacing).any { !it.isFinite() })
                throw PresetFormatException("图层尺寸和效果需要有限数值")
            if (layer.fields.size > MetadataField.entries.size)
                throw PresetFormatException("每个图层最多包含 ${MetadataField.entries.size} 个拍摄字段")
        }
    }

    private fun assets(template: WatermarkTemplate): Set<String> = buildSet {
        for (layer in template.layers) {
            layer.logoFile?.let {
                if (!assetName.matches(it) || !it.endsWith(".png")) throw PresetFormatException("Logo 素材需要有效的 PNG 文件名")
                add(it)
            }
            layer.fontFile?.let {
                if (!assetName.matches(it) || it.endsWith(".png")) throw PresetFormatException("字体素材需要有效的 TTF 或 OTF 文件名")
                add(it)
            }
        }
    }

    /** Imported resources receive private, unique names and can be rolled back if the database write fails. */
    fun discard(imported: ImportedPreset) = assets(imported.template).forEach { assetFile(it).delete() }

    private fun stageFile(staged: MutableMap<String, File>, name: String): File =
        File.createTempFile("preset-", ".${name.substringAfterLast('.')}", context.cacheDir).also { staged[name] = it }

    private fun assetFile(name: String) =
        File(File(context.filesDir, if (name.endsWith(".png")) "logos" else "fonts"), name)

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        const val MIME = "application/vnd.kaiprint.kaiphoto+zip"
        const val PREVIEW = "preview.jpg"
        private val DISCARD = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}
