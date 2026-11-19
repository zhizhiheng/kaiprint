package app.kai.photomark

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale

internal const val MAX_SHARED_PHOTOS = 300

/** Photos handed over by another app through Share or Open with. */
internal fun sharedUris(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    val direct = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.stream())
        Intent.ACTION_SEND_MULTIPLE -> intent.streams()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> return emptyList()
    }
    val clipped = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return (direct.ifEmpty { clipped }).filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct()
}

private fun Intent.stream(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(Intent.EXTRA_STREAM)
}

private fun Intent.streams(): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}.orEmpty().filterNotNull()

private val extensions = mapOf(
    "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/heic" to "heic",
    "image/heif" to "heif", "image/avif" to "avif", "image/tiff" to "tif", "image/x-adobe-dng" to "dng",
    "image/x-nikon-nef" to "nef", "image/x-canon-cr2" to "cr2", "image/x-canon-cr3" to "cr3",
    "image/x-sony-arw" to "arw", "image/x-fuji-raf" to "raf", "image/x-panasonic-rw2" to "rw2",
    "image/x-olympus-orf" to "orf", "image/x-pentax-pef" to "pef", "image/x-samsung-srw" to "srw",
)

/**
 * A file name that is safe to create, keeps the original stem so a RAW and its JPEG still pair,
 * and does not collide with a name already taken in the same batch.
 */
internal fun safeFileName(original: String?, mime: String?, taken: Set<String>): String {
    val cleaned = original.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trim('.').take(120)
    val base = cleaned.ifEmpty { "photo" }
    val known = PhotoFormat.identify(base) != PhotoFormat.UNKNOWN
    val extension = extensions[mime?.lowercase(Locale.ROOT)]
    val named = if (!known && extension != null) "$base.$extension" else base
    if (named.lowercase(Locale.ROOT) !in taken) return named
    val stem = named.substringBeforeLast('.', named)
    val suffix = named.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return generateSequence(2) { it + 1 }.map { "$stem-$it$suffix" }.first { it.lowercase(Locale.ROOT) !in taken }
}

/**
 * Copies shared photos into app storage. A share only grants access while the receiving screen
 * exists, and an export can outlive it.
 */
internal fun Context.copyShared(uris: List<Uri>, directory: File): List<PhotoSource> {
    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("无法创建导入目录")
    val resolver = contentResolver
    val storage = getSystemService(StorageManager::class.java)
    val volume = storage.getUuidForPath(directory)
    val taken = mutableSetOf<String>()
    try {
        return uris.mapNotNull { uri ->
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            val (original, length) = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) to (if (it.isNull(1)) -1L else it.getLong(1)) else null
                }
            }.getOrNull() ?: (uri.lastPathSegment to -1L)
            val name = safeFileName(original, mime, taken)
            if (PhotoFormat.identify(name, mime) == PhotoFormat.UNKNOWN && mime?.startsWith("image/") != true) {
                return@mapNotNull null
            }
            if (length > 0 && storage.getAllocatableBytes(volume) - length < RESERVED_SPACE) throw IOException("ENOSPC")
            val target = File(directory, name)
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            }.getOrNull()
            if (copied == null || target.length() == 0L) {
                target.delete()
                return@mapNotNull null
            }
            taken += name.lowercase(Locale.ROOT)
            PhotoSource(Uri.fromFile(target).toString(), name, "分享的照片", "shared/${directory.name}", mime = mime)
        }
    } catch (error: Exception) {
        directory.deleteRecursively()
        throw error
    }
}

/** Removes earlier shared batches that no open edit or unfinished export still points at. */
internal fun pruneShared(root: File, keep: Set<String>): Set<String> {
    val removed = mutableSetOf<String>()
    for (batch in root.listFiles { file -> file.isDirectory }.orEmpty()) {
        val files = batch.listFiles().orEmpty().map { Uri.fromFile(it).toString() }
        if (files.none(keep::contains)) {
            batch.deleteRecursively()
            removed += files
        }
    }
    return removed
}

private const val RESERVED_SPACE = 256L * 1024 * 1024
