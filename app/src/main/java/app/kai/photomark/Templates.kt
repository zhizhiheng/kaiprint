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
        Frame(.065f, .065f, .065f, .17f, "#F5F2EB", hairline = true),
        listOf(WatermarkLayer("exif", LayerKind.EXIF, fields = cameraAndExposure, area = LayerArea.BOTTOM,
            anchor = Anchor.CENTER, size = .031f, color = "#222221", type = TypeStyle.MONO)),
    )

    val dark = WatermarkTemplate(
        "dark", "黑底参数条",
        Frame(bottom = .135f, color = "#151619"),
        listOf(WatermarkLayer("exif", LayerKind.EXIF, fields = cameraAndExposure, area = LayerArea.BOTTOM,
            anchor = Anchor.CENTER, size = .032f, color = "#ECEAE1", type = TypeStyle.MONO)),
    )

    val editorial = WatermarkTemplate(
        "editorial", "杂志侧栏",
        Frame(.06f, .06f, .39f, .06f, "#EEE8DB"),
        listOf(
            WatermarkLayer("camera", LayerKind.EXIF, fields = listOf(MetadataField.MODEL), area = LayerArea.RIGHT,
                anchor = Anchor.TOP_LEFT, size = .043f, color = "#242320", bold = true),
            WatermarkLayer("lens", LayerKind.EXIF, fields = listOf(MetadataField.LENS) + exposure, area = LayerArea.RIGHT,
                anchor = Anchor.BOTTOM_LEFT, size = .025f, color = "#48433D", type = TypeStyle.MONO),
        ),
    )

    val proof = WatermarkTemplate(
        "proof", "交付预览",
        layers = listOf(WatermarkLayer("proof", LayerKind.TEXT, "预览样片", anchor = Anchor.CENTER,
            size = .057f, rotation = -28f, opacity = .28f, tile = true, bold = true, outline = true)),
    )

    val zr = WatermarkTemplate(
        "zr", "ZR 电影卡",
        Frame(.045f, .045f, .045f, .22f, "#131315"),
        listOf(
            WatermarkLayer("zr", LayerKind.TEXT, "ZR", area = LayerArea.BOTTOM, anchor = Anchor.CENTER_LEFT,
                size = .108f, margin = .04f, color = "#F4ECE2", bold = true),
            WatermarkLayer("rule", LayerKind.LINE, area = LayerArea.BOTTOM, anchor = Anchor.TOP_CENTER,
                size = .18f, margin = .018f, color = "#B94B41"),
            WatermarkLayer("exif", LayerKind.EXIF, fields = exposure, area = LayerArea.BOTTOM,
                anchor = Anchor.CENTER_RIGHT, size = .026f, margin = .04f, color = "#E6DAD0", type = TypeStyle.MONO),
        ),
    )

    val all = listOf(gallery, signature, dark, editorial, proof, zr)
}
