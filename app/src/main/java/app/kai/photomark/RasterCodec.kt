package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import java.io.File

internal data class RasterInfo(val size: PixelSize, val hdr: Boolean, val orientation: Int?)

internal object RasterCodec {
    private val available = runCatching { System.loadLibrary("rasterbridge") }.isSuccess

    private external fun nativeHeifInfo(fd: Int): IntArray?
    private external fun nativeHeifExif(fd: Int): ByteArray?
    private external fun nativeTiffInfo(fd: Int): IntArray?
    private external fun nativeHeif(fd: Int, edge: Int, space: ColorSpace, p3: Boolean): Bitmap?
    private external fun nativeTiff(fd: Int, edge: Int, space: ColorSpace, p3: Boolean): Bitmap?
    private external fun nativeWriteTiff(
        path: String, bitmap: Bitmap, p3: Boolean, make: String?, model: String?, date: String?,
    ): Boolean

    fun info(fd: Int, format: PhotoFormat): RasterInfo? {
        if (!available) return null
        return when (format) {
            PhotoFormat.HEIF -> nativeHeifInfo(fd)?.let {
                RasterInfo(PixelSize(it[0], it[1]), it[2] == 16 || it[2] == 18, 1.takeIf { _ -> it[3] == 1 })
            }
            PhotoFormat.TIFF -> nativeTiffInfo(fd)?.let {
                RasterInfo(PixelSize(it[0], it[1]), false, it[3].takeIf { value -> value in 1..8 })
            }
            else -> null
        }
    }

    fun heifExif(fd: Int): ExifInterface? =
        if (!available) null else nativeHeifExif(fd)?.let {
            ExifInterface(it.inputStream(), ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY)
        }

    fun decode(fd: Int, format: PhotoFormat, edge: Int, color: OutputColor): Bitmap? {
        if (!available) return null
        val space = color.androidColorSpace()
        val p3 = color == OutputColor.DISPLAY_P3
        return when (format) {
            PhotoFormat.HEIF -> nativeHeif(fd, edge, space, p3)
            PhotoFormat.TIFF -> nativeTiff(fd, edge, space, p3)
            else -> null
        }
    }

    fun writeTiff(file: File, bitmap: Bitmap, color: OutputColor, tags: Map<String, String>?): Boolean =
        available && nativeWriteTiff(
            file.absolutePath, bitmap, color == OutputColor.DISPLAY_P3,
            tags?.get(ExifInterface.TAG_MAKE),
            tags?.get(ExifInterface.TAG_MODEL),
            tags?.get(ExifInterface.TAG_DATETIME_ORIGINAL),
        )
}
