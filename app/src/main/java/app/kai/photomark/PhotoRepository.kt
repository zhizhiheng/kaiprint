package app.kai.photomark

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

class PhotoRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val captureTimes = LruCache<String, String>(512)

    fun permissionState(): LibraryAccess {
        if (BuildConfig.FLAVOR != "library") return LibraryAccess.NONE
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> LibraryAccess.FULL
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> LibraryAccess.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.PARTIAL
            else -> LibraryAccess.NONE
        }
    }

    fun libraryPage(offset: Int, pageSize: Int): List<PhotoSource> {
        if (permissionState() == LibraryAccess.NONE) return emptyList()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.VOLUME_NAME,
        )
        val options = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(
                MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID,
            ))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        return resolver.query(collection, columns, options, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val volume = cursor.getString(9) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val itemCollection = MediaStore.Images.Media.getContentUri(volume)
                    add(PhotoSource(
                        ContentUris.withAppendedId(itemCollection, cursor.getLong(0)).toString(),
                        cursor.getString(1) ?: "照片", cursor.getString(2) ?: "设备照片",
                        "$volume/${cursor.getString(3).orEmpty()}",
                        cursor.getLong(4).takeIf { it > 0 } ?: cursor.getLong(5) * 1000,
                        cursor.getString(6), cursor.getInt(7), cursor.getInt(8),
                    ))
                }
            }
        }.orEmpty()
    }

    fun openDocument(uri: Uri): PhotoSource? {
        val mime = resolver.getType(uri)
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "照片"
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val pathParent = documentId?.substringBeforeLast('/', "")?.takeIf(String::isNotBlank)
        val parent = if (pathParent == null) "file:$uri" else "${uri.authority}/$pathParent"
        val source = PhotoSource(uri.toString(), name, "导入照片", parent, mime = mime)
        return source.takeIf { it.format != PhotoFormat.UNKNOWN || mime?.startsWith("image/") == true }
    }

    fun importFolder(tree: Uri): List<PhotoSource> {
        data class Directory(val id: String, val name: String?)
        val root = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque(listOf(Directory(root, null)))
        val visited = mutableSetOf<String>()
        val result = mutableListOf<PhotoSource>()
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            if (!visited.add(dir.id)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: id.substringAfterLast('/')
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(Directory(id, name))
                    } else if (PhotoFormat.identify(name, mime) != PhotoFormat.UNKNOWN || mime?.startsWith("image/") == true) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        result += PhotoSource(uri.toString(), name, dir.name ?: root.substringAfterLast('/').substringAfter(':'),
                            "${tree.authority}/${dir.id}", cursor.getLong(3), mime)
                    }
                }
            }
        }
        return result
    }

    fun groups(sources: List<PhotoSource>): List<PhotoGroup> {
        val groups = sources.distinctBy(PhotoSource::uri)
            .groupBy { it.parentKey to it.stem.lowercase(Locale.ROOT) }
        return groups.values.flatMap { group ->
            val raw = group.singleOrNull { it.format.raw }
            val companion = group.singleOrNull { it.format == PhotoFormat.JPEG || it.format == PhotoFormat.HEIF }
            if (raw != null && companion != null && group.size == 2 && timestampsMatch(raw, companion)) {
                listOf(PhotoGroup(raw, companion))
            } else {
                group.map(::PhotoGroup)
            }
        }.sortedByDescending { maxOf(it.primary.timeMillis, it.developed?.timeMillis ?: 0L) }
    }

    private fun timestampsMatch(first: PhotoSource, second: PhotoSource): Boolean {
        fun captureTime(source: PhotoSource): String = captureTimes.get(source.uri)
            ?: readExif(source)?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty()
                .also { captureTimes.put(source.uri, it) }
        val a = captureTime(first)
        val b = captureTime(second)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    fun info(group: PhotoGroup): PhotoInfo {
        val source = group.primary
        resolver.openAssetFileDescriptor(Uri.parse(source.uri), "r")?.close()
            ?: throw IOException("无法读取源照片")
        val exif = readExif(source)
        val raw = if (source.format.raw) nativeInfo(source) else null
        val primaryMetadata = metadata(exif, raw)
        val companionExif = group.developed?.let(::readExif)
        val fallbackMetadata = metadata(companionExif, null)
        val metadata = primaryMetadata.copy(
            values = fallbackMetadata.values + primaryMetadata.values,
            exif = fallbackMetadata.exif + primaryMetadata.exif,
        )
        val choices = buildList {
            if (source.format.raw) {
                if (group.developed != null) {
                    standardChoice(group.developed, PixelKind.COMPANION, companionExif)?.let(::add)
                }
                val preview = embedded(source, exif, metadata.orientation)
                val previewSize = preview?.size?.oriented(preview.orientation)
                if (previewSize != null) {
                    add(PixelChoice(source, PixelKind.EMBEDDED, previewSize, preview.orientation))
                }
                if (raw != null && raw.compression !in 13..14 && raw.size.width > 0 &&
                    raw.size.height > 0 && raw.size.width.toLong() * raw.size.height <= 40_000_000L
                ) {
                    val full = raw.size.oriented(metadata.orientation)
                    val visible = previewSize?.takeIf { apparent ->
                        metadata.values[MetadataField.MODEL]?.uppercase(Locale.ROOT) in setOf("ZR", "NIKON ZR") &&
                            full.width - apparent.width in 0..32 && full.height - apparent.height in 0..32
                    } ?: full
                    val candidate = PixelChoice(source, PixelKind.RAW, visible, metadata.orientation)
                    // Confirm this file decodes before offering the original RAW pixels.
                    if (decodeRaw(candidate, true)?.let { it.recycle(); true } == true) add(candidate)
                }
            } else {
                standardChoice(source, PixelKind.STANDARD, exif)?.let(::add)
            }
        }
        return PhotoInfo(metadata, choices)
    }

    private fun standardChoice(source: PhotoSource, kind: PixelKind, exif: ExifInterface?): PixelChoice? {
        val raster = if (source.format in listOf(PhotoFormat.TIFF, PhotoFormat.HEIF)) {
            withDescriptor(source) { RasterCodec.info(it, source.format) }
        } else null
        val orientation = raster?.orientation ?:
            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)?.takeIf { it in 1..8 } ?: 1
        val uri = Uri.parse(source.uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val encoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0 && options.outHeight > 0) PixelSize(options.outWidth, options.outHeight) else null
        }.getOrNull()
        val size = (raster?.size ?: encoded)?.oriented(orientation) ?: return null
        val hdr = when (source.format) {
            PhotoFormat.HEIF, PhotoFormat.AVIF -> raster?.hdr == true || hdrHeif(uri) ||
                exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.uppercase(Locale.ROOT) in setOf("ZR", "NIKON ZR")
            PhotoFormat.JPEG -> jpegGainMap(uri)
            else -> false
        }
        return PixelChoice(source, kind, size, orientation, hdr)
    }

    fun thumbnail(group: PhotoGroup, size: Int): Bitmap? {
        val source = group.developed ?: group.primary
        if (!source.format.raw) {
            val uri = Uri.parse(source.uri)
            return runCatching { resolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
                ?: runCatching {
                    val choice = standardChoice(source, PixelKind.STANDARD, readExif(source)) ?: return@runCatching null
                    load(choice, size)
                }.getOrNull()
        }
        val exif = readExif(source)
        val preview = embedded(source, exif, exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1) ?: return null
        return decodeEmbedded(preview, size)
    }

    fun load(choice: PixelChoice, maximumEdge: Int, outputColor: OutputColor = OutputColor.SRGB): Bitmap {
        val bitmap = when (choice.kind) {
            PixelKind.EMBEDDED -> {
                val preview = embedded(choice.source, readExif(choice.source), choice.orientation)
                    ?: throw IOException("RAW 内嵌预览不可用")
                decodeEmbedded(preview, maximumEdge)
            }
            PixelKind.RAW -> decodeRaw(choice, maximumEdge > 0 && maximumEdge < choice.size.longEdge / 2)
            PixelKind.STANDARD, PixelKind.COMPANION -> {
                decodePhoto(choice, maximumEdge, outputColor)
            }
        } ?: throw IOException("无法解码此文件")
        if (maximumEdge == 0 && PixelSize(bitmap.width, bitmap.height) != choice.size) {
            bitmap.recycle()
            throw IOException("解码尺寸与源文件记录不一致")
        }
        if (maximumEdge > 0 && maxOf(bitmap.width, bitmap.height) > maximumEdge) {
            val size = PixelSize(bitmap.width, bitmap.height).fit(maximumEdge)
            return Bitmap.createScaledBitmap(bitmap, size.width, size.height, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
        return bitmap
    }

    private fun decodePhoto(choice: PixelChoice, maximumEdge: Int, outputColor: OutputColor): Bitmap {
        val format = choice.source.format
        fun software() = withDescriptor(choice.source) { RasterCodec.decode(it, format, maximumEdge, outputColor) }
            ?.let { rotate(it, choice.orientation) }
        if (format == PhotoFormat.TIFF || (format == PhotoFormat.HEIF && choice.hdr)) {
            software()?.let { return it }
        }
        return try {
            decodeStandard(Uri.parse(choice.source.uri), maximumEdge, outputColor)
        } catch (error: IOException) {
            if (format == PhotoFormat.HEIF && !choice.hdr) software()?.let { return it }
            throw error
        }
    }

    private fun decodeStandard(uri: Uri, maximumEdge: Int, outputColor: OutputColor): Bitmap {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(outputColor.androidColorSpace())
            if (maximumEdge > 0) {
                val limit = PixelSize(info.size.width, info.size.height).fit(maximumEdge)
                if (limit.width != info.size.width || limit.height != info.size.height) {
                    decoder.setTargetSize(limit.width, limit.height)
                }
            }
        }.also { if (Build.VERSION.SDK_INT >= 34 && it.hasGainmap()) it.gainmap = null }
    }

    private fun hdrHeif(uri: Uri): Boolean = runCatching {
        val data = resolver.openInputStream(uri)?.use { it.readNBytesCompat(512 * 1024) } ?: return false
        (0 until data.size - 12).any { i ->
            data[i] == 'c'.code.toByte() && data[i + 1] == 'o'.code.toByte() &&
                data[i + 2] == 'l'.code.toByte() && data[i + 3] == 'r'.code.toByte() &&
                data[i + 4] == 'n'.code.toByte() && data[i + 5] == 'c'.code.toByte() &&
                data[i + 6] == 'l'.code.toByte() && data[i + 7] == 'x'.code.toByte() &&
                data[i + 10] == 0.toByte() && data[i + 11].toInt() in setOf(16, 18)
        }
    }.getOrDefault(false)

    private fun jpegGainMap(uri: Uri): Boolean = runCatching {
        val prefix = resolver.openInputStream(uri)?.use { it.readNBytesCompat(96 * 1024) } ?: return false
        String(prefix, Charsets.ISO_8859_1).contains("hdr-gain-map")
    }.getOrDefault(false)

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(bytes, count, limit - count)
            if (read <= 0) break
            count += read
        }
        return bytes.copyOf(count)
    }

    private data class EmbeddedPreview(val bytes: ByteArray, val size: PixelSize, val orientation: Int)

    private fun embedded(source: PhotoSource, exif: ExifInterface?, orientation: Int): EmbeddedPreview? {
        val thumbnails = listOfNotNull(exif?.thumbnailBytes, withDescriptor(source, RawDecoder::preview))
        return thumbnails.mapNotNull { bytes ->
            val size = dimensions(bytes) ?: return@mapNotNull null
            val marker = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }
                .getOrDefault(0)
            EmbeddedPreview(bytes, size, marker.takeIf { it in 1..8 } ?: orientation)
        }.maxByOrNull { it.size.width.toLong() * it.size.height }
    }

    private fun decodeEmbedded(preview: EmbeddedPreview, maximumEdge: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maximumEdge > 0 && preview.size.longEdge / (inSampleSize * 2) >= maximumEdge) {
                inSampleSize *= 2
            }
        }
        return BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size, options)
            ?.let { rotate(it, preview.orientation) }
    }

    private fun readExif(source: PhotoSource): ExifInterface? {
        val platform = runCatching {
            resolver.openInputStream(Uri.parse(source.uri))?.use { ExifInterface(it) }
        }.getOrNull()
        if (source.format != PhotoFormat.HEIF || platform?.getAttribute(ExifInterface.TAG_MODEL) != null) {
            return platform
        }
        val embedded = runCatching { withDescriptor(source, RasterCodec::heifExif) }.getOrNull()
        return embedded?.takeIf { exif -> safeExif.any { exif.getAttribute(it) != null } } ?: platform
    }

    private fun nativeInfo(source: PhotoSource): RawInfo? = withDescriptor(source, RawDecoder::describe)

    private fun decodeRaw(choice: PixelChoice, half: Boolean): Bitmap? {
        val bitmap = withDescriptor(choice.source) { RawDecoder.decode(it, half) }?.let { rotate(it, choice.orientation) }
            ?: return null
        val width = if (half) (choice.size.width + 1) / 2 else choice.size.width
        val height = if (half) (choice.size.height + 1) / 2 else choice.size.height
        val dx = bitmap.width - width
        val dy = bitmap.height - height
        if (dx !in 0..32 || dy !in 0..32 || (dx == 0 && dy == 0)) return bitmap
        return Bitmap.createBitmap(bitmap, dx / 2, dy / 2, width, height).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun <T> withDescriptor(source: PhotoSource, action: (Int) -> T?): T? {
        val uri = Uri.parse(source.uri)
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use { file ->
            val seekable = runCatching { Os.lseek(file.fileDescriptor, 0, OsConstants.SEEK_CUR) }.isSuccess
            if (seekable && file.statSize > 0L) return action(file.fd)
        }
        val temp = File.createTempFile("raw-", ".${source.format.name.lowercase(Locale.ROOT)}", context.cacheDir)
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { stream ->
                temp.outputStream().use { stream.copyBounded(it, 512 * 1024 * 1024) }
            }
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { action(it.fd) }
        } finally {
            temp.delete()
        }
    }

    private fun dimensions(bytes: ByteArray): PixelSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) PixelSize(options.outWidth, options.outHeight) else null
    }
}

enum class LibraryAccess(val label: String) {
    NONE("未授权"), PARTIAL("仅已选照片"), FULL("设备相册")
}

private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
            else -> return bitmap
        }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

private data class RawInfo(val tags: Map<String, String>, val size: PixelSize, val compression: Int, val orientation: Int)

private object RawDecoder {
    private val available = runCatching { System.loadLibrary("rawbridge") }.isSuccess
    private external fun nativeDescribe(fd: Int): Array<String>?
    private external fun nativeDecode(fd: Int, half: Boolean): Bitmap?
    private external fun nativePreview(fd: Int): ByteArray?

    fun describe(fd: Int): RawInfo? {
        if (!available) return null
        val values = nativeDescribe(fd) ?: return null
        val tags = listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_DATETIME_ORIGINAL)
        val mapped = tags.zip(values.take(8)).filterIndexed { index, (_, value) ->
            value.isNotBlank() && (index !in 3..6 || (value.toDoubleOrNull() ?: 0.0) > 0.0)
        }.toMap()
        val orientation = when (values[11].toIntOrNull()) { 3 -> 3; 5 -> 8; 6 -> 6; else -> 1 }
        return RawInfo(mapped, PixelSize(values[8].toInt(), values[9].toInt()), values[10].toInt(), orientation)
    }

    fun decode(fd: Int, half: Boolean): Bitmap? = if (available) nativeDecode(fd, half) else null
    fun preview(fd: Int): ByteArray? = if (available) nativePreview(fd) else null
}

private val safeExif = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_DATETIME_ORIGINAL,
)

private fun metadata(exif: ExifInterface?, raw: RawInfo?): PhotoMetadata {
    val tags = raw?.tags.orEmpty() + safeExif.mapNotNull { tag ->
        exif?.getAttribute(tag)?.trim()?.takeIf(String::isNotBlank)?.let { tag to it }
    }
    fun num(tag: String): Double? {
        val value = tags[tag] ?: return null
        val parts = value.split('/')
        val numerator = parts[0].toDoubleOrNull() ?: return null
        val result = if (parts.size == 2) numerator / (parts[1].toDoubleOrNull() ?: return null) else numerator
        return result.takeIf { it.isFinite() && it > 0 }
    }
    fun decimal(value: Double): String = DecimalFormat("0.##", DecimalFormatSymbols(Locale.ROOT)).format(value)

    val values = buildMap {
        tags[ExifInterface.TAG_MAKE]?.let { put(MetadataField.MAKE, it) }
        tags[ExifInterface.TAG_MODEL]?.let { put(MetadataField.MODEL, it) }
        tags[ExifInterface.TAG_LENS_MODEL]?.let { put(MetadataField.LENS, it) }
        num(ExifInterface.TAG_FOCAL_LENGTH)?.let { put(MetadataField.FOCAL, "${decimal(it)} mm") }
        num(ExifInterface.TAG_F_NUMBER)?.let { put(MetadataField.APERTURE, "f/${decimal(it)}") }
        num(ExifInterface.TAG_EXPOSURE_TIME)?.let {
            val time = if (it < 1.0 && 1 / it < Int.MAX_VALUE) "1/${(1 / it).roundToInt()}" else decimal(it)
            put(MetadataField.SHUTTER, "$time s")
        }
        num(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { put(MetadataField.ISO, "ISO ${it.roundToInt()}") }
        tags[ExifInterface.TAG_DATETIME_ORIGINAL]?.let {
            put(MetadataField.DATE, if (it.length >= 10) it.take(10).replace(':', '-') + it.drop(10) else it)
        }
    }
    val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)?.takeIf { it in 1..8 } ?: raw?.orientation ?: 1
    return PhotoMetadata(values, tags, orientation,
        raw?.size?.width ?: 0, raw?.size?.height ?: 0, raw?.compression?.takeIf { it != 0 })
}

internal fun OutputColor.androidColorSpace(): ColorSpace = ColorSpace.get(
    when (this) {
        OutputColor.SRGB -> ColorSpace.Named.SRGB
        OutputColor.DISPLAY_P3 -> ColorSpace.Named.DISPLAY_P3
    },
)
