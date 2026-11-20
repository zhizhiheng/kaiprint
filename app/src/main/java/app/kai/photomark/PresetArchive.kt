package app.kai.photomark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

private const val MAX_ASSET = 16 * 1024 * 1024
private const val MAX_ARCHIVE = 48 * 1024 * 1024

private val assetName = Regex("[a-zA-Z0-9_-]{1,80}\\.(png|ttf|otf)")

internal fun InputStream.copyBounded(output: OutputStream, limit: Int): Int {
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return total
        total += count
        if (total > limit) throw IOException("文件超过大小限制")
        output.write(buffer, 0, count)
    }
}

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

/** A preset read from a ZIP, with what it referred to but did not carry. */
data class ImportedPreset(val template: WatermarkTemplate, val missingFonts: Int = 0, val missingLogos: Int = 0)

class PresetArchive(private val context: Context) {
    /** Writes template.json, a preview.jpg rendered on a sample photo when [preview] is given, and the assets. */
    fun write(template: WatermarkTemplate, uri: Uri, preview: Bitmap? = null) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("文件无法写入")
        ZipOutputStream(stream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("template.json"))
            zip.write(storageJson.encodeToString(template).toByteArray())
            zip.closeEntry()
            if (preview != null) {
                zip.putNextEntry(ZipEntry(PREVIEW))
                preview.compress(Bitmap.CompressFormat.JPEG, 88, zip)
                zip.closeEntry()
            }
            var remaining = MAX_ARCHIVE
            for (name in assets(template)) {
                val asset = File(File(context.filesDir, directory(name)), name)
                if (!asset.isFile) throw IOException("无法读取预设素材")
                zip.putNextEntry(ZipEntry("assets/$name"))
                asset.inputStream().use { remaining -= it.copyBounded(zip, minOf(remaining, MAX_ASSET)) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a preset ZIP. A font the archive names but does not carry falls back to the layer's own type style,
     * and a missing logo leaves its layer empty, so a preset shared without its assets still opens.
     */
    fun read(uri: Uri): ImportedPreset {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("文件无法读取")
        val staged = mutableMapOf<String, File>()
        val imported = mutableListOf<File>()
        try {
            var manifest: String? = null
            var remaining = MAX_ARCHIVE
            ZipInputStream(stream.buffered()).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 50)
                    if (entry.name == "template.json" && manifest == null) {
                        val bytes = ByteArrayOutputStream()
                        zip.copyBounded(bytes, 256 * 1024)
                        manifest = bytes.toString(Charsets.UTF_8.name())
                    } else if (entry.name == PREVIEW || entry.name == "README.md") {
                        zip.copyBounded(object : OutputStream() {
                            override fun write(b: Int) = Unit
                            override fun write(b: ByteArray, off: Int, len: Int) = Unit
                        }, MAX_ASSET)
                    } else if (entry.name.startsWith("assets/")) {
                        val name = entry.name.removePrefix("assets/")
                        require(assetName.matches(name) && name !in staged && !entry.isDirectory)
                        val temp = File.createTempFile("preset-", ".${name.substringAfterLast('.')}", context.cacheDir)
                        staged[name] = temp
                        temp.outputStream().use { remaining -= zip.copyBounded(it, minOf(remaining, MAX_ASSET)) }
                    } else {
                        throw IOException("预设包包含未知文件")
                    }
                    zip.closeEntry()
                }
            }
            val template = storageJson.decodeFromString<WatermarkTemplate>(manifest ?: throw IOException("缺少模板"))
            require(template.version == 1 && template.title.isNotBlank() && template.layers.size <= 24)
            require(template.layers.map(WatermarkLayer::id).toSet().size == template.layers.size)
            val referenced = assets(template)
            require(referenced.containsAll(staged.keys))
            val missing = referenced - staged.keys
            val replacements = staged.mapValues { (name, source) ->
                if (name.endsWith(".png")) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
                    require(bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600 && bounds.outMimeType == "image/png")
                } else {
                    require(Typeface.Builder(source).build() != null)
                }
                val directory = File(context.filesDir, directory(name)).apply { mkdirs() }
                val newName = "${UUID.randomUUID()}.${name.substringAfterLast('.')}"
                val target = File(directory, newName)
                imported += target
                source.copyTo(target)
                newName
            }
            val imported = template.copy(id = UUID.randomUUID().toString(), title = template.title.take(60), layers = template.layers.map {
                it.copy(logoFile = replacements[it.logoFile], fontFile = replacements[it.fontFile], text = it.text.take(240))
            })
            return ImportedPreset(imported, missing.count { !it.endsWith(".png") }, missing.count { it.endsWith(".png") })
        } catch (error: Exception) {
            imported.forEach(File::delete)
            throw error
        } finally {
            staged.values.forEach(File::delete)
        }
    }

    private fun assets(template: WatermarkTemplate): Set<String> = template.layers
        .flatMap { listOfNotNull(it.logoFile, it.fontFile) }.toSet().also { names ->
            require(names.all(assetName::matches))
        }

    private fun directory(name: String) = if (name.endsWith(".png")) "logos" else "fonts"

    companion object {
        const val PREVIEW = "preview.jpg"
    }
}
