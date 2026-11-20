package app.kai.photomark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.annotation.RequiresApi
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class WatermarkRenderer(private val context: Context, private val photos: PhotoRepository) {
    private val glassGrain by lazy {
        val random = Random(1841)
        val side = 96
        val pixels = IntArray(side * side) {
            val grain = random.nextInt(-8, 9)
            if (grain >= 0) Color.argb(grain, 255, 255, 255) else Color.argb(-grain, 0, 0, 0)
        }
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }
    private val fonts = LruCache<String, Typeface>(12)
    private val brands = LruCache<String, BrandArtwork>(32)
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
        drawBackground(canvas, draft.frame, layout)
        drawPhoto(canvas, bitmap, layout.photo, draft.frame.cornerRadius(layout.photo, layout.reference))
        drawWatermark(canvas, output, layout, draft, metadata, overrides)
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
        keepHdr: Boolean = false,
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
            val corner = draft.frame.cornerRadius(layout.photo, layout.reference)
            drawBackground(canvas, draft.frame, layout, preserveTransparency = format != OutputFormat.JPEG)
            val regionEligible = choice.kind in listOf(PixelKind.STANDARD, PixelKind.COMPANION) &&
                choice.orientation == 1 && !choice.hdr &&
                choice.source.format in listOf(PhotoFormat.JPEG, PhotoFormat.PNG, PhotoFormat.WEBP, PhotoFormat.HEIF)
            val filled = regionEligible && drawRegions(canvas, choice, layout.photo, corner, outputColor, checkpoint)
            var gainmap: Gainmap? = null
            if (!filled) {
                val photoEdge = max(layout.photo.width, layout.photo.height).roundToInt()
                val fullSize = if (photoEdge >= choice.size.longEdge) 0 else photoEdge.coerceAtLeast(1)
                val bitmap = photos.load(choice, fullSize, outputColor, keepGainmap = keepHdr)
                try {
                    drawPhoto(canvas, bitmap, layout.photo, corner)
                    if (keepHdr && Build.VERSION.SDK_INT >= 34) gainmap = bitmap.gainmap
                } finally {
                    if (gainmap == null) bitmap.recycle()
                }
            }
            checkpoint()
            drawWatermark(canvas, output, layout, draft, metadata, overrides)
            if (keepHdr && gainmap != null && Build.VERSION.SDK_INT >= 34) {
                output.gainmap = extendGainmap(gainmap, layout, corner)
                gainmap.gainmapContents.recycle()
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    /**
     * Places the photo's gain map on a canvas-sized map. Borders and frames get the neutral value, so only the
     * photo brightens on an HDR screen and the frame stays the white it is on an SDR screen.
     */
    @RequiresApi(34)
    private fun extendGainmap(source: Gainmap, layout: WatermarkLayout, corner: Float): Gainmap {
        val contents = source.gainmapContents
        val scale = contents.width / layout.photo.width
        val width = (layout.canvas.width * scale).roundToInt().coerceAtLeast(1)
        val height = (layout.canvas.height * scale).roundToInt().coerceAtLeast(1)
        val single = contents.config == Bitmap.Config.ALPHA_8
        val map = Bitmap.createBitmap(width, height, if (single) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888)
        val canvas = Canvas(map)
        val neutral = FloatArray(3) { channel ->
            val low = ln(source.ratioMin[channel].toDouble().coerceAtLeast(1e-6))
            val high = ln(source.ratioMax[channel].toDouble().coerceAtLeast(1e-6))
            val position = if (high - low < 1e-9) 0.0 else ((0.0 - low) / (high - low)).coerceIn(0.0, 1.0)
            position.pow(source.gamma[channel].toDouble()).toFloat()
        }
        fun byte(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawColor(if (single) Color.argb(byte(neutral[0]), 0, 0, 0)
            else Color.argb(255, byte(neutral[0]), byte(neutral[1]), byte(neutral[2])), PorterDuff.Mode.SRC)
        val photo = layout.photo
        val placed = RectF(photo.left * scale, photo.top * scale, photo.right * scale, photo.bottom * scale)
        // The rounded-off corners show the frame, so they stay neutral like the rest of it.
        if (corner > 0f) canvas.clipPath(Path().apply { addRoundRect(placed, corner * scale, corner * scale, Path.Direction.CW) })
        canvas.drawBitmap(contents, null, placed,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        return Gainmap(map).apply {
            setRatioMin(source.ratioMin[0], source.ratioMin[1], source.ratioMin[2])
            setRatioMax(source.ratioMax[0], source.ratioMax[1], source.ratioMax[2])
            setGamma(source.gamma[0], source.gamma[1], source.gamma[2])
            setEpsilonSdr(source.epsilonSdr[0], source.epsilonSdr[1], source.epsilonSdr[2])
            setEpsilonHdr(source.epsilonHdr[0], source.epsilonHdr[1], source.epsilonHdr[2])
            displayRatioForFullHdr = source.displayRatioForFullHdr
            minDisplayRatioForHdrTransition = source.minDisplayRatioForHdrTransition
        }
    }

    private fun drawRegions(
        canvas: Canvas, choice: PixelChoice, photo: PixelRect, corner: Float, outputColor: OutputColor,
        checkpoint: () -> Unit,
    ): Boolean {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(choice.source.uri), "r")
        }.getOrNull() ?: return false
        descriptor.use {
            @Suppress("DEPRECATION")
            val decoder = runCatching { BitmapRegionDecoder.newInstance(it.fileDescriptor, false) }.getOrNull()
                ?: return false
            val unclipped = canvas.save()
            try {
                if (decoder.width != choice.size.width || decoder.height != choice.size.height) return false
                if (corner > 0f) canvas.clipPath(photo.outline(corner))
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
                canvas.restoreToCount(unclipped)
                decoder.recycle()
            }
        }
    }

    private fun drawBackground(
        canvas: Canvas, frame: Frame, layout: WatermarkLayout, preserveTransparency: Boolean = true,
    ) {
        if (preserveTransparency && layout.photo.left == 0f && layout.photo.top == 0f &&
            layout.photo.right.roundToInt() == layout.canvas.width && layout.photo.bottom.roundToInt() == layout.canvas.height &&
            frame.ratio == FrameRatio.ORIGINAL && !frame.hairline && frame.corner <= 0f
        ) return
        val warmWhite = 0xFFF5F2EB.toInt()
        canvas.drawColor(color(frame.color, warmWhite))
    }

    private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, photo: PixelRect, corner: Float) {
        val state = canvas.save()
        if (corner > 0f) canvas.clipPath(photo.outline(corner))
        canvas.drawBitmap(bitmap, null, photo.asRect(), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(state)
    }

    /** A soft shadow around the photo, heavier below it, so it reads as a print lying on the frame. */
    private fun drawPhotoShadow(canvas: Canvas, layout: WatermarkLayout, frame: Frame, corner: Float) {
        val spread = frame.shadow.coerceIn(0f, Frame.MAX_SHADOW) * layout.reference
        if (spread < .5f) return
        val state = canvas.save()
        if (corner > 0f) canvas.clipOutPath(layout.photo.outline(corner)) else canvas.clipOutRect(layout.photo.asRect())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x61000000
            maskFilter = BlurMaskFilter(spread, BlurMaskFilter.Blur.NORMAL)
        }
        val cast = layout.photo.asRect().apply { offset(0f, spread * .3f) }
        canvas.drawRoundRect(cast, corner, corner, paint)
        canvas.restoreToCount(state)
    }

    private fun drawWatermark(
        canvas: Canvas,
        output: Bitmap,
        layout: WatermarkLayout,
        template: WatermarkTemplate,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
    ) {
        val frost = template.frame.background == FrameBackground.FROSTED
        val corner = template.frame.cornerRadius(layout.photo, layout.reference)
        val paletteCount = template.layers.filter { it.kind == LayerKind.PALETTE && it.visible && it.opacity > 0f }
            .maxOfOrNull { it.paletteCount.coerceIn(3, 6) }
        val palette = if (frost || paletteCount != null) {
            val sample = photoSample(output, layout, if (frost) 256 else 112)
            try {
                val colors = paletteCount?.let { photoPalette(sample, it) }.orEmpty()
                if (frost) drawFrostedFrame(canvas, layout, template.frame, sample, corner)
                colors
            } finally {
                sample.recycle()
            }
        } else emptyList()
        drawPhotoShadow(canvas, layout, template.frame, corner)
        if (template.frame.hairline) drawFrameOutline(canvas, layout, corner)
        drawLayers(canvas, output, layout, template, template.shown(metadata, place(template, metadata)),
            template.shown(overrides), palette, corner)
    }

    /**
     * A preset rendered on a generated landscape with sample camera details, for preset packages. It contains
     * no photo of the person sharing the preset.
     */
    fun sample(template: WatermarkTemplate, edge: Int = 1200): Bitmap {
        val photo = Bitmap.createBitmap(1500, 1000, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawPaint(Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, 1000f,
                    intArrayOf(0xFF2E4A62.toInt(), 0xFFC98B5B.toInt(), 0xFF3B3A36.toInt()), floatArrayOf(0f, .62f, 1f),
                    Shader.TileMode.CLAMP)
            })
            drawCircle(1040f, 560f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2D49B.toInt() })
            drawRect(0f, 700f, 1500f, 1000f, Paint().apply { color = 0xFF2A2B28.toInt() })
        }
        val metadata = PhotoMetadata(mapOf(
            MetadataField.MAKE to "Nikon", MetadataField.MODEL to "Z6III", MetadataField.LENS to "NIKKOR Z 24-70mm f/4 S",
            MetadataField.FOCAL to "50 mm", MetadataField.APERTURE to "f/4", MetadataField.SHUTTER to "1/125 s",
            MetadataField.ISO to "ISO 400", MetadataField.DATE to "2026-11-20 17:42:10",
            MetadataField.FOCAL_EQUIVALENT to "50 mm 等效", MetadataField.EXPOSURE_BIAS to "-0.3 EV",
        ))
        try {
            val layout = WatermarkLayout.calculate(PixelSize(photo.width, photo.height), template, metadata = metadata)
            return preview(photo, layout, template, metadata, emptyMap(), edge)
        } finally {
            photo.recycle()
        }
    }

    /** The automatic location line, or null to keep the hand-written one. */
    fun place(template: WatermarkTemplate, metadata: PhotoMetadata): String? =
        runCatching { Places.describe(template.place, metadata.latitude, metadata.longitude) { Places.index(context) } }
            .getOrNull()

    private fun photoSample(output: Bitmap, layout: WatermarkLayout, maximumEdge: Int): Bitmap {
        val scaleX = output.width.toFloat() / layout.canvas.width
        val scaleY = output.height.toFloat() / layout.canvas.height
        val left = (layout.photo.left * scaleX).roundToInt().coerceIn(0, output.width - 1)
        val top = (layout.photo.top * scaleY).roundToInt().coerceIn(0, output.height - 1)
        val right = (layout.photo.right * scaleX).roundToInt().coerceIn(left + 1, output.width)
        val bottom = (layout.photo.bottom * scaleY).roundToInt().coerceIn(top + 1, output.height)
        val size = PixelSize(right - left, bottom - top).fit(maximumEdge)
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawBitmap(output, Rect(left, top, right, bottom),
                Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun drawFrostedFrame(canvas: Canvas, layout: WatermarkLayout, frame: Frame, sample: Bitmap, corner: Float) {
        blurPhotoSample(sample, frame.blur)
        val bounds = RectF(0f, 0f, layout.canvas.width.toFloat(), layout.canvas.height.toFloat())
        val state = canvas.save()
        if (corner > 0f) canvas.clipOutPath(layout.photo.outline(corner)) else canvas.clipOutRect(layout.photo.asRect())
        canvas.drawBitmap(sample, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        val tint = color(frame.color, 0xFF16232F.toInt())
        val alpha = (255 * frame.tintStrength.coerceIn(0f, .9f)).roundToInt()
        canvas.drawColor((tint and 0xFFFFFF) or (alpha shl 24))
        val pitch = layout.reference / 960f
        val noise = BitmapShader(glassGrain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(pitch, pitch) })
        }
        canvas.drawRect(bounds, Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = noise })
        if (frame.hairline) {
            val edge = layout.photo.asRect()
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            for (ring in 6 downTo 1) {
                shadow.strokeWidth = layout.reference * .004f * ring
                shadow.color = Color.argb(8 - ring, 8, 17, 24)
                canvas.drawRoundRect(edge, corner, corner, shadow)
            }
        }
        canvas.restoreToCount(state)
    }

    private fun drawFrameOutline(canvas: Canvas, layout: WatermarkLayout, corner: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x50484540
            style = Paint.Style.STROKE
            strokeWidth = max(1f, layout.reference * .00075f)
        }
        val rect = layout.photo.asRect()
        rect.inset(-paint.strokeWidth * 2, -paint.strokeWidth * 2)
        val radius = if (corner > 0f) corner + paint.strokeWidth * 2 else 0f
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun drawLayers(
        canvas: Canvas,
        output: Bitmap,
        layout: WatermarkLayout,
        template: WatermarkTemplate,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
        palette: List<Int>,
        corner: Float,
    ) {
        for (layer in template.layers) {
            if (!layer.visible || layer.opacity <= 0f || layer.area in layout.collapsed) continue
            val area = layout.area(layer)
            if (area.width < 1f || area.height < 1f) continue
            val marker = getMarker(layer, area, layout.reference, metadata, overrides, palette) ?: continue
            val state = canvas.save()
            canvas.clipRect(area.asRect())
            if (layer.area == LayerArea.PHOTO && corner > 0f) canvas.clipPath(layout.photo.outline(corner))
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
                val ink = if (layer.adaptsInk(template.frame)) {
                    // A photo varies far more across one line of text than blurred glass does.
                    val dense = layer.area == LayerArea.PHOTO
                    glassInk(output, layout, RectF(x, y, x + marker.width, y + marker.height),
                        if (dense) 5 else 3, if (dense) 9 else 5)
                } else null
                val visibleMarker = if (ink != null) {
                    val adapted = layer.copy(color = if (ink == BrandTone.LIGHT) "#FAF9F6" else "#11191E", brandTone = ink)
                    getMarker(adapted, area, layout.reference, metadata, overrides, palette, adaptedInk = true) ?: marker
                } else marker
                drawMarker(canvas, visibleMarker, x, y, layer.rotation)
            }
            canvas.restoreToCount(state)
        }
    }

    private fun glassInk(output: Bitmap, layout: WatermarkLayout, box: RectF, rows: Int, columns: Int): BrandTone {
        // Sample only the patch immediately behind this inscription, before painting it.
        val luminances = FloatArray(rows * columns)
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = (box.left + box.width() * (column + .5f) / columns) * output.width / layout.canvas.width
            val y = (box.top + box.height() * (row + .5f) / rows) * output.height / layout.canvas.height
            luminances[row * columns + column] = Color.luminance(output.getPixel(
                x.toInt().coerceIn(0, output.width - 1), y.toInt().coerceIn(0, output.height - 1)))
        }
        luminances.sort()
        // Above this the dark ink has more contrast than the light one, below it the light ink wins.
        return if (luminances[luminances.size / 2] > INK_SWITCH) BrandTone.DARK else BrandTone.LIGHT
    }

    private fun getMarker(
        layer: WatermarkLayer,
        area: PixelRect,
        reference: Float,
        metadata: PhotoMetadata,
        overrides: Map<MetadataField, String>,
        palette: List<Int>,
        adaptedInk: Boolean = false,
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
                val graphic = brand.graphic(layer.brandStyle, metadata, overrides) ?: return null
                val artwork = brandArtwork(graphic.file) ?: return null
                val aspect = artwork.aspectFor(graphic)
                // Model marks run from a short "Z8" to a long "EOS R5 Mark II". Sizing them to one height keeps
                // every body equally prominent, while the layer size still caps the usual Z-plus-number mark.
                val width = if (graphic.model) size * maxOf(1f, aspect / MODEL_ASPECT) else size
                val w = minOf(width, availableWidth, availableHeight * aspect)
                val h = w / aspect
                Marker(w, h, layer.background, alpha) { canvas ->
                    artwork.draw(canvas, RectF(0f, 0f, w, h), graphic, layer.brandTone, alpha)
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
            LayerKind.PALETTE -> {
                val colors = palette.take(layer.paletteCount.coerceIn(3, 6)).sortedBy(Color::luminance)
                if (colors.isEmpty()) return null
                val w = minOf(size, availableWidth)
                val h = minOf(w * .105f, availableHeight)
                val gap = if (layer.paletteStyle == PaletteStyle.BLOCKS) w * .025f else 0f
                val blockWidth = (w - (colors.size - 1) * gap) / colors.size
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                Marker(w, h, layer.background, alpha) { canvas ->
                    colors.forEachIndexed { index, swatch ->
                        val left = index * (blockWidth + gap)
                        paint.color = swatch
                        paint.alpha = alpha
                        canvas.drawRect(left, 0f, left + blockWidth, h, paint)
                    }
                }
            }
            LayerKind.TEXT, LayerKind.EXIF -> {
                val parts = if (layer.kind == LayerKind.TEXT) listOf(layer.text.trim())
                    else metadata.parts(layer.fields, overrides)
                if (parts.all(String::isBlank)) return null
                textMarker(parts, layer, size, availableWidth, availableHeight, alpha, adaptedInk)
            }
        }
    }

    private fun textMarker(
        parts: List<String>, layer: WatermarkLayer, size: Float, width: Float, height: Float, alpha: Int,
        adaptedInk: Boolean,
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
            letterSpacing = layer.letterSpacing.coerceIn(0f, MAX_LETTER_SPACING)
        }
        val separator = layer.separator.text
        var text = parts.joinToString(separator)
        if (parts.size > 1) {
            // Shrink a little before wrapping, then break between values so no single value trails alone.
            val desired = Layout.getDesiredWidth(text, textPaint)
            if (desired > width && desired * .8f <= width) textPaint.textSize *= width / desired
            else if (desired > width) text = balancedLines(parts, separator, textPaint, width)
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
        if (layer.shadow) {
            // Ink that turned dark for a bright patch gets a light halo; a black shadow would only smear it.
            if (adaptedInk && Color.luminance(textColor) < INK_SWITCH) {
                textPaint.setShadowLayer(textPaint.textSize * .13f, 0f, 0f, 0xB8FFFFFF.toInt())
            } else textPaint.setShadowLayer(textPaint.textSize * .09f, 0f, textPaint.textSize * .07f, Color.BLACK)
        }
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

    private fun balancedLines(parts: List<String>, separator: String, paint: TextPaint, width: Float): String {
        fun line(from: Int, to: Int) = parts.subList(from, to).joinToString(separator)
        val candidates = buildList {
            for (a in 1 until parts.size) add(listOf(line(0, a), line(a, parts.size)))
            for (a in 1 until parts.size) for (b in a + 1 until parts.size) {
                add(listOf(line(0, a), line(a, b), line(b, parts.size)))
            }
        }
        fun widest(lines: List<String>) = lines.maxOf { Layout.getDesiredWidth(it, paint) }
        val fitting = candidates.filter { widest(it) <= width }
        val best = if (fitting.isNotEmpty()) {
            fitting.minWith(compareBy<List<String>>({ it.size }, { widest(it) }))
        } else candidates.minBy(::widest)
        return best.joinToString("\n")
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

    fun brandThumbnail(
        brand: BrandMark, tone: BrandTone, style: BrandStyle = BrandStyle.ORIGINAL,
        metadata: PhotoMetadata = PhotoMetadata(), overrides: Map<MetadataField, String> = emptyMap(),
    ): Bitmap? {
        val graphic = brand.graphic(style, metadata, overrides) ?: return null
        val artwork = brandArtwork(graphic.file) ?: return null
        val aspect = artwork.aspectFor(graphic)
        val width = minOf(240f, 112f * aspect).roundToInt().coerceAtLeast(1)
        val height = (width / aspect).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        artwork.draw(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), graphic, tone, 255)
        return bitmap
    }

    private fun brandArtwork(file: String): BrandArtwork? {
        brands.get(file)?.let { return it }
        return runCatching {
            val svg = SVG.getFromAsset(context.assets, file)
            val view = svg.documentViewBox
            val w = view?.width() ?: svg.documentWidth
            val h = view?.height() ?: svg.documentHeight
            require(w > 0f && h > 0f && w.isFinite() && h.isFinite())
            val scale = 1024f / max(w, h)
            BrandArtwork(
                svg.renderToPicture((w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)), w / h,
            )
        }.getOrNull()?.also { brands.put(file, it) }
    }

    private data class BrandArtwork(val picture: Picture, val aspect: Float) {
        fun aspectFor(graphic: BrandGraphic) = if (graphic.squareBadge) 1f else aspect

        fun draw(canvas: Canvas, box: RectF, graphic: BrandGraphic, tone: BrandTone, opacity: Int) {
            val tint = if (!graphic.tintable) null else when (tone) {
                BrandTone.ORIGINAL -> null
                BrandTone.LIGHT -> lightInk
                BrandTone.DARK -> darkInk
            }
            if (!graphic.squareBadge) {
                drawPicture(canvas, box, tint, opacity)
                return
            }
            val state = if (opacity == 255) canvas.save()
                else canvas.saveLayer(box, Paint().apply { alpha = opacity })
            val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (tint == lightInk) darkInk else lightInk }
            val radius = box.width() * .12f
            canvas.drawRoundRect(box, radius, radius, backing)
            val w = minOf(box.width() * .76f, box.height() * .63f * aspect)
            val h = w / aspect
            val x = box.centerX()
            val y = box.centerY()
            drawPicture(canvas, RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), tint, 255)
            canvas.restoreToCount(state)
        }

        private fun drawPicture(canvas: Canvas, box: RectF, tint: Int?, opacity: Int) {
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

        private companion object {
            val lightInk = 0xFFF5EFE6.toInt()
            val darkInk = 0xFF242320.toInt()
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

/** The width-to-height ratio of a typical model mark such as Z6III, used to size longer marks alike. */
private const val MODEL_ASPECT = 3.2f

/** The background luminance at which light and dark ink have equal contrast. */
private const val INK_SWITCH = .19f

const val MAX_LETTER_SPACING = .4f

fun color(value: String, fallback: Int): Int = runCatching { Color.parseColor(value) }.getOrDefault(fallback)
private fun PixelRect.asRect() = RectF(left, top, right, bottom)
private fun PixelRect.outline(corner: Float) = Path().apply { addRoundRect(asRect(), corner, corner, Path.Direction.CW) }
