package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

private fun colorBins(bitmap: Bitmap): List<Int> {
    val counts = mutableMapOf<Int, IntArray>()
    val step = (maxOf(bitmap.width, bitmap.height) / 56).coerceAtLeast(1)
    for (y in 0 until bitmap.height step step) {
        for (x in 0 until bitmap.width step step) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) < 127) continue
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val key = (r / 48) * 36 + (g / 48) * 6 + b / 48
            val bin = counts.getOrPut(key) { IntArray(4) }
            bin[0]++
            bin[1] += r
            bin[2] += g
            bin[3] += b
        }
    }
    return counts.values.sortedByDescending { it[0] }.map {
        Color.rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0])
    }
}

internal fun sampledColors(bitmap: Bitmap): List<String> = colorBins(bitmap).take(4).map {
    String.format(Locale.ROOT, "#%06X", it and 0xFFFFFF)
}

internal fun photoPalette(bitmap: Bitmap, count: Int): List<Int> {
    val distinct = mutableListOf<Int>()
    val candidates = colorBins(bitmap)
    for (color in candidates) {
        if (distinct.none { neighbor ->
                val dr = Color.red(color) - Color.red(neighbor)
                val dg = Color.green(color) - Color.green(neighbor)
                val db = Color.blue(color) - Color.blue(neighbor)
                dr * dr + dg * dg + db * db < 48 * 48
            }) distinct.add(color)
        if (distinct.size == count) break
    }
    return (distinct + candidates.filterNot(distinct::contains)).take(count)
}

// Average transmitted light before converting back to sRGB, so a bright waterfall
// still contributes light to a neighboring dark patch of forest.
private const val LINEAR_MAX = 16384
private val linearLight = IntArray(256) { byte ->
    val value = byte / 255.0
    val linear = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    (linear * LINEAR_MAX).roundToInt()
}
private val displayLight = IntArray(LINEAR_MAX + 1) { sample ->
    val linear = sample.toDouble() / LINEAR_MAX
    val value = if (linear <= .0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - .055
    (255 * value).roundToInt()
}

internal fun blurPhotoSample(sample: Bitmap, strength: Float) {
    val width = sample.width
    val height = sample.height
    val radius = (minOf(width, height) * strength.coerceIn(.02f, .24f)).roundToInt().coerceAtLeast(1)
    val pixels = IntArray(width * height)
    sample.getPixels(pixels, 0, width, 0, 0, width, height)
    val channel = IntArray(pixels.size)
    val scratch = IntArray(pixels.size)
    val result = IntArray(pixels.size)
    for (shift in listOf(24, 16, 8, 0)) {
        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i])
            val byte = (pixels[i] ushr shift) and 255
            channel[i] = if (shift == 24) alpha else linearLight[byte] * alpha / 255
        }
        repeat(3) {
            blurLines(channel, scratch, width, height, 1, width, radius)
            blurLines(scratch, channel, height, width, width, 1, radius)
        }
        for (i in result.indices) {
            val value = if (shift == 24) channel[i] else {
                val alpha = Color.alpha(result[i]).coerceAtLeast(1)
                displayLight[(channel[i] * 255 / alpha).coerceIn(0, LINEAR_MAX)]
            }
            result[i] = result[i] or (value shl shift)
        }
    }
    sample.setPixels(result, 0, width, 0, 0, width, height)
}

private fun blurLines(
    source: IntArray, output: IntArray, length: Int, lines: Int, stride: Int, lineStride: Int, radius: Int,
) {
    val kernel = radius * 2 + 1
    for (line in 0 until lines) {
        val start = line * lineStride
        var sum = 0
        for (offset in -radius..radius) sum += source[start + offset.coerceIn(0, length - 1) * stride]
        for (i in 0 until length) {
            output[start + i * stride] = (sum + radius) / kernel
            val leaving = (i - radius).coerceIn(0, length - 1)
            val entering = (i + radius + 1).coerceIn(0, length - 1)
            sum += source[start + entering * stride] - source[start + leaving * stride]
        }
    }
}
