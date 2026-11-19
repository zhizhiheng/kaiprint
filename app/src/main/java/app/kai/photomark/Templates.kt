package app.kai.photomark

object Templates {
    private val exposure = listOf(MetadataField.FOCAL, MetadataField.APERTURE, MetadataField.SHUTTER, MetadataField.ISO)
    private val cameraAndExposure = listOf(MetadataField.MODEL) + exposure

    val signature = WatermarkTemplate(
        "signature", "角落签名",
        layers = listOf(
            WatermarkLayer("signature", LayerKind.TEXT, "摄影师署名", size = .037f,
                opacity = .78f, type = TypeStyle.SERIF, shadow = true),
        ),
    )

    val gallery = WatermarkTemplate(
        "gallery", "画廊白框",
        Frame(.065f, .065f, .065f, .18f, "#F5F2EB", hairline = true),
        listOf(
            footerLogo(BrandTone.ORIGINAL),
            WatermarkLayer("exif", LayerKind.EXIF, fields = cameraAndExposure, area = LayerArea.BOTTOM,
                anchor = Anchor.CENTER_RIGHT, size = .033f, margin = .065f, color = "#292826",
                type = TypeStyle.MONO, column = LayerColumn.RIGHT),
        ),
    )

    val dark = WatermarkTemplate(
        "dark", "黑底参数条",
        Frame(bottom = .14f, color = "#151619"),
        listOf(
            footerLogo(BrandTone.LIGHT, size = .145f),
            WatermarkLayer("exif", LayerKind.EXIF, fields = cameraAndExposure, area = LayerArea.BOTTOM,
                anchor = Anchor.CENTER_RIGHT, size = .03f, margin = .045f, color = "#ECEAE1",
                type = TypeStyle.MONO, column = LayerColumn.RIGHT),
        ),
    )

    val editorial = WatermarkTemplate(
        "editorial", "杂志侧栏",
        Frame(.06f, .06f, .39f, .06f, "#EEE8DB"),
        listOf(
            WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.RIGHT, anchor = Anchor.TOP_LEFT,
                size = .26f, margin = .046f),
            WatermarkLayer("camera", LayerKind.EXIF, fields = listOf(MetadataField.MODEL), area = LayerArea.RIGHT,
                anchor = Anchor.CENTER_LEFT, size = .043f, color = "#242320", bold = true),
            WatermarkLayer("lens", LayerKind.EXIF, fields = listOf(MetadataField.LENS) + exposure, area = LayerArea.RIGHT,
                anchor = Anchor.BOTTOM_LEFT, size = .027f, color = "#48433D", type = TypeStyle.MONO),
        ),
    )

    val proof = WatermarkTemplate(
        "proof", "交付预览",
        layers = listOf(WatermarkLayer("proof", LayerKind.TEXT, "预览样片", anchor = Anchor.CENTER,
            size = .057f, rotation = -28f, opacity = .28f, tile = true, bold = true, outline = true)),
    )

    val zr = WatermarkTemplate(
        "zr", "ZR 电影卡",
        Frame(.045f, .045f, .045f, .225f, "#131315"),
        listOf(
            footerLogo(BrandTone.LIGHT, size = .16f, margin = .047f),
            WatermarkLayer("rule", LayerKind.LINE, area = LayerArea.BOTTOM, anchor = Anchor.TOP_RIGHT,
                size = .08f, margin = .028f, color = "#BA5043", column = LayerColumn.RIGHT),
            WatermarkLayer("camera", LayerKind.EXIF, fields = listOf(MetadataField.MODEL), area = LayerArea.BOTTOM,
                anchor = Anchor.CENTER_RIGHT, size = .046f, margin = .047f, color = "#F5EDE3",
                bold = true, column = LayerColumn.RIGHT),
            WatermarkLayer("exif", LayerKind.EXIF, fields = exposure, area = LayerArea.BOTTOM,
                anchor = Anchor.BOTTOM_RIGHT, size = .028f, margin = .036f, color = "#C9BFB6",
                type = TypeStyle.MONO, column = LayerColumn.RIGHT),
        ),
    )

    val brandStamp = WatermarkTemplate(
        "brand-stamp", "品牌角标",
        layers = listOf(
            WatermarkLayer("brand", LayerKind.BRAND, anchor = Anchor.BOTTOM_LEFT, size = .17f, margin = .048f,
                brandTone = BrandTone.LIGHT, background = "#74000000", column = LayerColumn.LEFT),
            WatermarkLayer("exif", LayerKind.EXIF, fields = cameraAndExposure, anchor = Anchor.BOTTOM_RIGHT,
                size = .031f, margin = .05f, type = TypeStyle.MONO, shadow = true, column = LayerColumn.RIGHT),
        ),
    )

    val all = listOf(gallery, zr, brandStamp, signature, dark, editorial, proof)

    fun upgradeLegacyZr(template: WatermarkTemplate): WatermarkTemplate {
        if (template.id != zr.id) return template
        val legacy = template.layers.firstOrNull { it.id == "zr" && it.kind == LayerKind.TEXT && it.text == "ZR" }
            ?: return template
        val replacement = zr.layers.first()
        return template.copy(layers = template.layers.map { layer ->
            if (layer.id == legacy.id) replacement.copy(
                area = legacy.area, anchor = legacy.anchor, x = legacy.x, y = legacy.y,
                visible = legacy.visible, opacity = legacy.opacity, rotation = legacy.rotation,
            ) else layer
        })
    }

    private fun footerLogo(tone: BrandTone, size: Float = .18f, margin: Float = .065f) =
        WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.BOTTOM, anchor = Anchor.CENTER_LEFT,
            size = size, margin = margin, brandTone = tone, column = LayerColumn.LEFT)
}
