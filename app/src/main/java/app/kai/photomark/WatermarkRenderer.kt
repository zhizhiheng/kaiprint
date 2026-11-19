package app.kai.photomark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class WatermarkRenderer(private val context: Context, private val photos: PhotoRepository) {
    private val fonts = LruCache<String, Typeface>(12)
    private val brands = LruCache<BrandMark, BrandArtwork>(24)
    private val logos = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun preview(
        bitmap: Bitmap,
        layout: WatermarkLayout,
        draft: WatermarkTemplate,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
        maximumEdge: Int = 1200,
    ): Bitmap {
        val resolved = layout.canvas.fit(maximumEdge)
        val output = Bitmap.createBitmap(resolved.width, resolved.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.scale(resolved.width.toFloat() / layout.canvas.width, resolved.height.toFloat() / layout.canvas.height)
        drawBackground(canvas, layout, draft.frame)
        drawPhoto(canvas, bitmap, layout.photo)
        drawLayers(canvas, layout, draft, metadata, overrides)
        return output
    }

    fun renderForExport(
        choice: PixelChoice,
        layout: WatermarkLayout,
        draft: WatermarkTemplate,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
        outputColor: OutputColor = OutputColor.SRGB,
        format: OutputFormat = OutputFormat.JPEG,
        checkpoint: () -> Unit,
    ): Bitmap {
        val bytes = layout.canvas.width.toLong() * layout.canvas.height * 4
        val runtime = Runtime.getRuntime()
        val javaAvailable = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (bytes > minOf(javaAvailable * .87, 240.0 * 1024 * 1024).toLong()) {
            throw IOException("当前内存不足，请选择较小的输出尺寸")
        }
        val output = Bitmap.createBitmap(layout.canvas.width, layout.canvas.height, Bitmap.Config.ARGB_8888,
            true, outputColor.androidColorSpace())
        try {
            val canvas = Canvas(output)
            drawBackground(canvas, layout, draft.frame, preserveTransparency = format != OutputFormat.JPEG)
            val regionEligible = choice.kind in listOf(PixelKind.STANDARD, PixelKind.COMPANION) &&
                choice.orientation == 1 && !choice.hdr &&
                choice.source.format in listOf(PhotoFormat.JPEG, PhotoFormat.PNG, PhotoFormat.WEBP, PhotoFormat.HEIF)
            val filled = regionEligible && drawRegions(canvas, choice, layout.photo, outputColor, checkpoint)
            if (!filled) {
                val photoEdge = max(layout.photo.width, layout.photo.height).roundToInt()
                val fullSize = if (photoEdge >= choice.size.longEdge) 0 else photoEdge.coerceAtLeast(1)
                val bitmap = photos.load(choice, fullSize, outputColor)
                try {
                    drawPhoto(canvas, bitmap, layout.photo)
                } finally {
                    bitmap.recycle()
                }
            }
            checkpoint()
            drawLayers(canvas, layout, draft, metadata, overrides)
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private fun drawRegions(
        canvas: Canvas, choice: PixelChoice, photo: PixelRect, outputColor: OutputColor, checkpoint: () -> Unit,
    ): Boolean {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(choice.source.uri), "r")
        }.getOrNull() ?: return false
        descriptor.use {
            @Suppress("DEPRECATION")
            val decoder = runCatching { BitmapRegionDecoder.newInstance(it.fileDescriptor, false) }.getOrNull()
                ?: return false
            try {
                if (decoder.width != choice.size.width || decoder.height != choice.size.height) return false
                val scale = photo.height / decoder.height
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = outputColor.androidColorSpace()
                    // Keep the decoded strip larger than its destination to avoid enlarging a preview.
                    val ratio = minOf(decoder.width / photo.width, decoder.height / photo.height)
                    while (inSampleSize.coerceAtLeast(1) * 2 <= ratio) {
                        inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                    }
                }
                val overlap = options.inSampleSize.coerceAtLeast(1) * 2
                var top = 0
                while (top < decoder.height) {
                    checkpoint()
                    val bottom = minOf(top + 384, decoder.height)
                    val decodeTop = max(0, top - overlap)
                    val decodeBottom = minOf(decoder.height, bottom + overlap)
                    val strip = runCatching {
                        decoder.decodeRegion(Rect(0, decodeTop, decoder.width, decodeBottom), options)
                    }.getOrNull() ?: return false
                    val state = canvas.save()
                    canvas.clipRect(photo.left, photo.top + top * scale, photo.right, photo.top + bottom * scale)
                    canvas.drawBitmap(strip, null, RectF(photo.left, photo.top + decodeTop * scale,
                        photo.right, photo.top + decodeBottom * scale), paint)
                    canvas.restoreToCount(state)
                    strip.recycle()
                    top = bottom
                }
                return true
            } finally {
                decoder.recycle()
            }
        }
    }

    private fun drawBackground(
        canvas: Canvas, layout: WatermarkLayout, frame: Frame, preserveTransparency: Boolean = true,
    ) {
        if (preserveTransparency && frame.left == 0f && frame.top == 0f && frame.right == 0f &&
            frame.bottom == 0f && frame.ratio == FrameRatio.ORIGINAL && !frame.hairline
        ) return
        val warmWhite = 0xFFF5F2EB.toInt()
        canvas.drawColor(color(frame.color, warmWhite))
        if (frame.hairline) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x50484540
                style = Paint.Style.STROKE
                strokeWidth = max(1f, layout.reference * .00075f)
            }
            val rect = layout.photo.asRect()
            rect.inset(-paint.strokeWidth * 2, -paint.strokeWidth * 2)
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, photo: PixelRect) {
        canvas.drawBitmap(bitmap, null, photo.asRect(), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawLayers(
        canvas: Canvas,
        layout: WatermarkLayout,
        template: WatermarkTemplate,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
    ) {
        for (layer in template.layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            val area = layout.area(layer)
            if (area.width < 1f || area.height < 1f) continue
            val marker = getMarker(layer, area, layout.reference, metadata, overrides) ?: continue
            val state = canvas.save()
            canvas.clipRect(area.asRect())
            if (layer.tile) {
                drawTiled(canvas, area, layer, marker, layout.reference)
            } else {
                val margin = layer.margin.coerceIn(0f, .3f) * layout.reference
                val remainingX = (area.width - marker.width).coerceAtLeast(0f)
                val remainingY = (area.height - marker.height).coerceAtLeast(0f)
                val padX = minOf(margin, remainingX / 2f)
                val padY = minOf(margin, remainingY / 2f)
                val x = area.left + padX + (remainingX - 2 * padX) * layer.anchor.x + area.width * layer.x
                val y = area.top + padY + (remainingY - 2 * padY) * layer.anchor.y + area.height * layer.y
                drawMarker(canvas, marker, x, y, layer.rotation)
            }
            canvas.restoreToCount(state)
        }
    }

    private fun getMarker(
        layer: WatermarkLayer,
        area: PixelRect,
        reference: Float,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
    ): Marker? {
        val alpha = (layer.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        val margin = layer.margin.coerceIn(0f, .3f) * reference
        val availableWidth = area.width - minOf(margin * 2, area.width * .35f)
        val availableHeight = area.height - minOf(margin * 2, area.height * .35f)
        val size = layer.size.coerceIn(.004f, 1f) * reference

        return when (layer.kind) {
            LayerKind.LINE -> {
                val w = minOf(size, availableWidth)
                val h = minOf(max(1f, size * .013f), availableHeight)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = color(layer.color, Color.WHITE)
                    this.alpha = alpha
                }
                Marker(w, h, layer.background, alpha) { it.drawRect(0f, 0f, w, h, paint) }
            }
            LayerKind.BRAND -> {
                val brand = layer.brand.resolve(metadata, overrides) ?: return null
                val artwork = brandArtwork(brand) ?: return null
                val w = minOf(size, availableWidth, availableHeight * artwork.aspect)
                val h = w / artwork.aspect
                Marker(w, h, layer.background, alpha) { canvas ->
                    artwork.draw(canvas, RectF(0f, 0f, w, h), brand, layer.brandTone, alpha)
                }
            }
            LayerKind.LOGO -> {
                val name = layer.logoFile ?: return null
                val bitmap = logo(name) ?: return null
                val w = minOf(size, availableWidth, availableHeight * bitmap.width / bitmap.height)
                val h = w * bitmap.height / bitmap.width
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
                Marker(w, h, layer.background, alpha) {
                    it.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), paint)
                }
            }
            LayerKind.TEXT, LayerKind.EXIF -> {
                val text = if (layer.kind == LayerKind.TEXT) layer.text.trim()
                    else metadata.display(layer.fields, overrides)
                if (text.isBlank()) return null
                textMarker(text, layer, size, availableWidth, availableHeight, alpha)
            }
        }
    }

    private fun textMarker(
        text: String, layer: WatermarkLayer, size: Float, width: Float, height: Float, alpha: Int,
    ): Marker? {
        val textColor = color(layer.color, Color.WHITE)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.alpha = alpha
            val base = layer.fontFile?.let(::customFont) ?: Typeface.create(
                when (layer.type) { TypeStyle.SANS -> "sans-serif"; TypeStyle.SERIF -> "serif"; TypeStyle.MONO -> "monospace" },
                Typeface.NORMAL,
            )
            typeface = Typeface.create(base, if (layer.bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = minOf(size, height * .86f)
        }
        fun layout(): StaticLayout {
            val desired = Layout.getDesiredWidth(text, textPaint)
            val lineWidth = ceil(minOf(width, desired)).toInt().coerceAtLeast(1)
            return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, lineWidth)
                .setIncludePad(false)
                .setAlignment(when (layer.anchor.x) {
                    0f -> Layout.Alignment.ALIGN_NORMAL
                    1f -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                })
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }

        var textLayout = layout()
        while (textLayout.height > height && textPaint.textSize > 2f) {
            textPaint.textSize *= .86f
            textLayout = layout()
        }
        if (textLayout.height > height) return null
        if (layer.shadow) textPaint.setShadowLayer(textPaint.textSize * .09f, 0f, textPaint.textSize * .07f, Color.BLACK)
        val result = textLayout
        return Marker(result.width.toFloat(), result.height.toFloat(), layer.background, alpha) { canvas ->
            if (layer.outline) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = max(1f, textPaint.textSize * .065f)
                textPaint.color = if (Color.luminance(textColor) > .4f) Color.BLACK else Color.WHITE
                textPaint.alpha = alpha
                result.draw(canvas)
                textPaint.style = Paint.Style.FILL
                textPaint.color = textColor
                textPaint.alpha = alpha
            }
            result.draw(canvas)
        }
    }

    private fun drawTiled(canvas: Canvas, area: PixelRect, layer: WatermarkLayer, marker: Marker, reference: Float) {
        val state = canvas.save()
        val cx = (area.left + area.right) / 2f + area.width * layer.x
        val cy = (area.top + area.bottom) / 2f + area.height * layer.y
        canvas.rotate(layer.rotation, cx, cy)
        val radius = hypot(area.width, area.height) + max(area.width, area.height) * .3f
        val stepX = max(marker.width + reference * max(.07f, layer.margin * 2f), radius / 26)
        val stepY = max(marker.height + reference * max(.10f, layer.margin * 2f), radius / 26)
        var y = cy - radius
        var row = 0
        while (y < cy + radius) {
            var x = cx - radius + if (row % 2 == 0) 0f else stepX / 2f
            while (x < cx + radius) {
                drawMarker(canvas, marker, x, y, 0f)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restoreToCount(state)
    }

    private fun drawMarker(canvas: Canvas, marker: Marker, x: Float, y: Float, rotation: Float) {
        val state = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation, marker.width / 2f, marker.height / 2f)
        if (marker.background != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(marker.background, Color.BLACK)
                alpha = Color.alpha(color) * marker.alpha / 255
            }
            val padding = max(1f, marker.height * .14f)
            canvas.drawRoundRect(-padding, -padding, marker.width + padding, marker.height + padding,
                padding, padding, paint)
        }
        marker.draw(canvas)
        canvas.restoreToCount(state)
    }

    private fun customFont(name: String): Typeface? {
        if (File(name).name != name) return null
        fonts.get(name)?.let { return it }
        val file = File(File(context.filesDir, "fonts"), name)
        return runCatching { Typeface.Builder(file).build() }.getOrNull()?.also { fonts.put(name, it) }
    }

    private fun logo(name: String): Bitmap? {
        if (File(name).name != name) return null
        logos.get(name)?.let { return it }
        val file = File(File(context.filesDir, "logos"), name)
        return BitmapFactory.decodeFile(file.absolutePath)?.also { logos.put(name, it) }
    }

    fun brandThumbnail(brand: BrandMark, tone: BrandTone): Bitmap? {
        val artwork = brandArtwork(brand) ?: return null
        val width = minOf(220f, 95f * artwork.aspect).roundToInt().coerceAtLeast(1)
        val height = (width / artwork.aspect).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        artwork.draw(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), brand, tone, 255)
        return bitmap
    }

    private fun brandArtwork(brand: BrandMark): BrandArtwork? {
        val name = brand.asset ?: return null
        brands.get(brand)?.let { return it }
        return runCatching {
            val svg = SVG.getFromAsset(context.assets, "brands/$name.svg")
            val view = svg.documentViewBox
            val w = view?.width() ?: svg.documentWidth
            val h = view?.height() ?: svg.documentHeight
            require(w > 0f && h > 0f && w.isFinite() && h.isFinite())
            val scale = 1024f / max(w, h)
            BrandArtwork(
                svg.renderToPicture((w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)), w / h,
            )
        }.getOrNull()?.also { brands.put(brand, it) }
    }

    private data class BrandArtwork(val picture: Picture, val aspect: Float) {
        fun draw(canvas: Canvas, box: RectF, brand: BrandMark, tone: BrandTone, opacity: Int) {
            val tint = if (!brand.tintable) null else when (tone) {
                BrandTone.ORIGINAL -> null
                BrandTone.LIGHT -> 0xFFF5EFE6.toInt()
                BrandTone.DARK -> 0xFF242320.toInt()
            }
            if (tint == null && opacity == 255) {
                canvas.drawPicture(picture, box)
                return
            }
            val paint = Paint().apply {
                alpha = opacity
                if (tint != null) colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            }
            val state = canvas.saveLayer(box, paint)
            canvas.drawPicture(picture, box)
            canvas.restoreToCount(state)
        }
    }

    private data class Marker(
        val width: Float,
        val height: Float,
        val background: String?,
        val alpha: Int,
        val draw: (Canvas) -> Unit,
    )
}

fun color(value: String, fallback: Int): Int = runCatching { Color.parseColor(value) }.getOrDefault(fallback)
private fun PixelRect.asRect() = RectF(left, top, right, bottom)
