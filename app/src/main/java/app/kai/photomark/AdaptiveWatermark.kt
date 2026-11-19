package app.kai.photomark

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale
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

internal fun blurPhotoSample(sample: Bitmap, strength: Float) {
    val width = sample.width
    val height = sample.height
    val radius = (minOf(width, height) * strength.coerceIn(.02f, .24f)).roundToInt().coerceAtLeast(1)
    val pixels = IntArray(width * height)
    val temporary = IntArray(pixels.size)
    sample.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(3) {
        blurLines(pixels, temporary, width, height, 1, width, radius)
        blurLines(temporary, pixels, height, width, width, 1, radius)
    }
    sample.setPixels(pixels, 0, width, 0, 0, width, height)
}

private fun blurLines(
    source: IntArray, output: IntArray, length: Int, lines: Int, stride: Int, lineStride: Int, radius: Int,
) {
    val kernel = radius * 2 + 1
    for (line in 0 until lines) {
        val start = line * lineStride
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val pixel = source[start + offset.coerceIn(0, length - 1) * stride]
            alpha += Color.alpha(pixel)
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
        }
        for (i in 0 until length) {
            output[start + i * stride] = Color.argb(alpha / kernel, red / kernel, green / kernel, blue / kernel)
            val remove = source[start + (i - radius).coerceIn(0, length - 1) * stride]
            val add = source[start + (i + radius + 1).coerceIn(0, length - 1) * stride]
            alpha += Color.alpha(add) - Color.alpha(remove)
            red += Color.red(add) - Color.red(remove)
            green += Color.green(add) - Color.green(remove)
            blue += Color.blue(add) - Color.blue(remove)
        }
    }
}
