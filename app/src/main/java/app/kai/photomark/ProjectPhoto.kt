package app.kai.photomark

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.WeakHashMap
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

internal const val MAX_ORIGINAL = 256 * 1024 * 1024
internal const val MAX_PROJECT_MANIFEST = 16 * 1024

@Serializable
data class EditableFileSnapshot(
    val template: WatermarkTemplate,
    val photo: PhotoSource,
    val overrides: Map<MetadataField, String> = emptyMap(),
) {
    val supportsEmbeddedPhoto get() = EmbeddedPhotoType.forSource(photo) != null
    val embeddedPhotoName get() = photo.name.takeIf { supportsEmbeddedPhoto }
}

internal enum class EmbeddedPhotoType(val format: PhotoFormat, val mediaType: String, val extension: String) {
    JPEG(PhotoFormat.JPEG, "image/jpeg", "jpg"),
    PNG(PhotoFormat.PNG, "image/png", "png"),
    WEBP(PhotoFormat.WEBP, "image/webp", "webp"),
    NEF(PhotoFormat.NEF, "image/x-nikon-nef", "nef");

    val path get() = "photo/original.$extension"

    fun validateImage(file: File) {
        val header = ByteArray(12)
        if (file.inputStream().use { it.read(header) } != header.size) invalidPhoto()
        val correct = when (this) {
            JPEG -> header.take(3) == listOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
            PNG -> header.copyOf(8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            WEBP -> header.decodeToString(0, 4) == "RIFF" && header.decodeToString(8, 12) == "WEBP"
            NEF -> header.copyOf(4).contentEquals(byteArrayOf(73, 73, 42, 0)) ||
                header.copyOf(4).contentEquals(byteArrayOf(77, 77, 0, 42))
        }
        if (!correct) invalidPhoto()
        if (this == NEF) {
            val make = runCatching { ExifInterface(file).getAttribute(ExifInterface.TAG_MAKE) }.getOrNull()
            if (!make.orEmpty().trim().startsWith("Nikon", ignoreCase = true)) invalidPhoto()
        } else {
            // Decode a tiny raster to reject incomplete images before publishing the imported file.
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    if (info.mimeType != mediaType) invalidPhoto()
                    val size = PixelSize(info.size.width, info.size.height).fit(64)
                    decoder.setTargetSize(size.width, size.height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.recycle()
            } catch (_: ImageDecoder.DecodeException) {
                invalidPhoto()
            }
        }
    }

    private fun invalidPhoto(): Nothing = throw PresetFormatException("作品原照损坏，或与声明的 $extension 格式不符")

    companion object {
        fun forSource(source: PhotoSource) = entries.firstOrNull { it.format == source.format }
    }
}

@Serializable
internal data class OriginalDescriptor(val path: String, val name: String, val mediaType: String, val bytes: Long, val sha256: String) {
    val type: EmbeddedPhotoType get() = EmbeddedPhotoType.entries.firstOrNull { it.path == path && it.mediaType == mediaType }
        ?: throw PresetFormatException("作品原照的格式或路径不支持")

    fun validate() {
        type
        if (bytes !in 1..MAX_ORIGINAL.toLong() || !sha256.matches(Regex("[0-9a-f]{64}")))
            throw PresetFormatException("作品原照的大小或 SHA-256 无效")
        val length = name.codePointCount(0, name.length)
        if (length !in 1..255 || name in setOf(".", "..") || name.any { it == '/' || it == '\\' || it.isISOControl() })
            throw PresetFormatException("作品原照的显示名称无效")
    }
}

@Serializable
internal data class ProjectManifest(
    val photo: OriginalDescriptor,
    val overrides: Map<MetadataField, String> = emptyMap(),
    val format: String = "kaiphoto",
    val formatVersion: Int = 2,
    val kind: String = "project",
    val template: String = "template.json",
) {
    fun validate() {
        photo.validate()
        if (overrides.values.any { it.codePointCount(0, it.length) > 120 })
            throw PresetFormatException("每项照片标注最多 120 个字符")
    }

    companion object {
        fun parse(fields: JsonObject): ProjectManifest {
            fun invalid(): Nothing = throw PresetFormatException("作品清单中的原照或标注信息无效")
            if (fields["kind"] != JsonPrimitive("project")) invalid()
            val photo = fields["photo"] as? JsonObject ?: invalid()
            val bytes = photo["bytes"] as? JsonPrimitive ?: invalid()
            if (bytes.isString || bytes.longOrNull == null) invalid()
            if (listOf("name", "sha256", "path", "mediaType").any { (photo[it] as? JsonPrimitive)?.isString != true }) invalid()
            if ("overrides" in fields) {
                val overrides = fields["overrides"] as? JsonObject ?: invalid()
                if (overrides.any { (k, v) -> MetadataField.entries.none { it.name == k } || (v as? JsonPrimitive)?.isString != true }) invalid()
            }
            val result = runCatching { storageJson.decodeFromJsonElement(serializer(), fields) }.getOrElse { invalid() }
            result.validate()
            return result
        }
    }
}

internal data class StagedOriginal(val file: File, val manifest: ProjectManifest, val crc: Long)

internal fun stageOriginal(context: Context, snapshot: EditableFileSnapshot): StagedOriginal {
    val type = EmbeddedPhotoType.forSource(snapshot.photo)
        ?: throw PresetFormatException("含原照的作品支持 JPEG、PNG、WebP 和尼康 NEF，可将此排版另存为模板")
    val scratch = File.createTempFile("kaiphoto-original-", ".${type.extension}", context.cacheDir)
    try {
        val uri = Uri.parse(snapshot.photo.uri)
        val resolver = context.contentResolver
        val stream = if (uri.authority == MediaStore.AUTHORITY && (context.applicationContext as? PhotoMarkApp)?.photos?.locationAccess == true) {
            runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull()
        } else null
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val size = (stream ?: resolver.openInputStream(uri) ?: throw IOException("无法打开原照")).use { input ->
            scratch.outputStream().use { output -> input.copyBounded(DigestOutputStream(CheckedOutputStream(output, crc), digest), MAX_ORIGINAL) }
        }
        val descriptor = OriginalDescriptor(type.path, snapshot.photo.name, type.mediaType, size.toLong(), digest.digest().hex())
        val manifest = ProjectManifest(descriptor, snapshot.overrides)
        manifest.validate()
        type.validateImage(scratch)
        return StagedOriginal(scratch, manifest, crc.value)
    } catch (error: Exception) {
        scratch.delete()
        throw error
    }
}

internal fun InputStream.copyOriginalTo(out: OutputStream, descriptor: OriginalDescriptor) {
    val digest = MessageDigest.getInstance("SHA-256")
    val size = copyBounded(DigestOutputStream(out, digest), descriptor.bytes.toInt())
    if (size.toLong() != descriptor.bytes || digest.digest().hex() != descriptor.sha256)
        throw PresetFormatException("作品原照的长度或 SHA-256 校验不符")
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }


internal data class ProjectPhotoLease(val photo: PhotoSource, val token: String)

/** A verified original is immutable. A private reference keeps it alive until its draft owns it. */
internal object ProjectPhotoStore {
    private val readers = mutableMapOf<String, String>()
    private val uncommitted = mutableSetOf<String>()
    private val sessions = WeakHashMap<Workspace, Set<String>>()

    @Synchronized
    fun install(context: Context, staged: File, info: OriginalDescriptor): ProjectPhotoLease {
        val directory = File(File(context.filesDir, "projects"), info.sha256)
        val target = File(directory, "original.${info.type.extension}")
        val uri = Uri.fromFile(target).toString()
        if (!target.isFile) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法保存作品原照")
            try {
                Files.move(staged.toPath(), target.toPath())
                uncommitted += uri
            } catch (error: Exception) {
                directory.delete()
                throw error
            }
        } else if (target.length() != info.bytes) {
            throw PresetFormatException("本地作品原照的大小与清单不符")
        }
        val token = UUID.randomUUID().toString()
        readers[token] = uri
        val source = PhotoSource(uri, info.name, "光印作品", directory.path,
            mime = info.mediaType, embeddedFormat = info.type.format)
        return ProjectPhotoLease(source, token)
    }

    @Synchronized
    fun remember(owner: Workspace, photos: List<PhotoGroup>) {
        sessions[owner] = photos.map { it.primary.uri }.toSet()
    }

    @Synchronized
    fun adopted(token: String) {
        readers.remove(token)?.let { uncommitted.remove(it) }
    }

    @Synchronized
    fun discard(token: String) {
        val uri = readers.remove(token) ?: return
        if (uri in readers.values || !uncommitted.remove(uri)) return
        val file = File(requireNotNull(Uri.parse(uri).path))
        file.delete()
        file.parentFile?.delete()
    }

    @Synchronized
    fun prune(context: Context, persisted: Set<String>) {
        pruneShared(File(context.filesDir, "projects"), persisted + readers.values + sessions.values.flatten())
    }
}
