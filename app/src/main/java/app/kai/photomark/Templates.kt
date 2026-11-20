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

    val modelPlate = WatermarkTemplate(
        "model-plate", "机型铭牌",
        Frame(.06f, .06f, .06f, .085f, "#F6F4EF", hairline = true),
        listOf(
            WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.BOTTOM, anchor = Anchor.CENTER,
                size = .24f, margin = .026f, brandStyle = BrandStyle.MODEL, reserveBrandSpace = true),
            WatermarkLayer("exif", LayerKind.EXIF, fields = listOf(MetadataField.LENS) + exposure,
                area = LayerArea.BOTTOM, anchor = Anchor.TOP_CENTER, size = .025f, margin = .018f,
                color = "#4B4841", type = TypeStyle.MONO),
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

    val frostedGallery = WatermarkTemplate(
        "frosted-gallery", "雾面展框",
        Frame(.082f, .082f, .082f, .265f, "#273544", hairline = true,
            background = FrameBackground.FROSTED, blur = .051f, tintStrength = .095f),
        listOf(
            footerLogo(BrandTone.LIGHT, size = .17f, margin = .046f).copy(brandStyle = BrandStyle.WORDMARK),
            annotation("camera", listOf(MetadataField.MODEL, MetadataField.LENS), Anchor.TOP_RIGHT,
                column = LayerColumn.RIGHT, size = .035f, bold = true),
            annotation("exif", exposure, Anchor.CENTER_RIGHT, column = LayerColumn.RIGHT, size = .029f),
            annotation("credit", listOf(MetadataField.AUTHOR, MetadataField.LOCATION), Anchor.BOTTOM_RIGHT,
                column = LayerColumn.RIGHT, size = .024f, color = "#D5E0E5"),
        ).map { it.copy(autoGlassInk = true) },
    )

    val frostedJournal = WatermarkTemplate(
        "frosted-journal", "雾面纪行",
        Frame(.075f, .12f, .075f, .225f, "#DDE9EC", hairline = true,
            background = FrameBackground.FROSTED, blur = .032f, tintStrength = .14f),
        listOf(
            WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.TOP, anchor = Anchor.CENTER_LEFT,
                size = .17f, margin = .04f, column = LayerColumn.LEFT, brandTone = BrandTone.LIGHT,
                brandStyle = BrandStyle.WORDMARK),
            annotation("camera", listOf(MetadataField.MODEL), Anchor.CENTER_RIGHT, area = LayerArea.TOP,
                column = LayerColumn.RIGHT, size = .039f),
            annotation("exif", exposure, Anchor.TOP_CENTER, size = .032f),
            annotation("travel", listOf(MetadataField.LOCATION, MetadataField.DATE), Anchor.CENTER,
                size = .025f, color = "#DDE8E7"),
            annotation("author", listOf(MetadataField.AUTHOR), Anchor.BOTTOM_CENTER,
                size = .027f, type = TypeStyle.SERIF),
        ).map { it.copy(autoGlassInk = true) },
    )

    val colorAtlas = WatermarkTemplate(
        "color-atlas", "色谱图鉴",
        Frame(.055f, .145f, .055f, .295f, "#F5F2E9"),
        listOf(
            WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.TOP, anchor = Anchor.CENTER_LEFT,
                size = .16f, margin = .047f, column = LayerColumn.LEFT, brandStyle = BrandStyle.WORDMARK),
            annotation("camera", listOf(MetadataField.MODEL), Anchor.CENTER_RIGHT, area = LayerArea.TOP,
                column = LayerColumn.RIGHT, size = .038f, color = "#252621", bold = true),
            annotation("title", listOf(MetadataField.TITLE), Anchor.TOP_LEFT, column = LayerColumn.LEFT_HALF,
                size = .052f, color = "#252621", type = TypeStyle.SERIF),
            WatermarkLayer("palette", LayerKind.PALETTE, area = LayerArea.BOTTOM, anchor = Anchor.TOP_RIGHT,
                column = LayerColumn.RIGHT_HALF, size = .34f, margin = .045f),
            annotation("lens", listOf(MetadataField.LENS), Anchor.CENTER_LEFT, size = .026f, color = "#52534B"),
            annotation("exif", exposure, Anchor.BOTTOM_LEFT, column = LayerColumn.LEFT_HALF,
                size = .026f, color = "#52534B"),
            annotation("credit", listOf(MetadataField.AUTHOR, MetadataField.LOCATION, MetadataField.DATE),
                Anchor.BOTTOM_RIGHT, column = LayerColumn.RIGHT_HALF, size = .024f, color = "#52534B"),
        ),
    )

    val fieldNotes = WatermarkTemplate(
        "field-notes", "极简片注",
        layers = listOf(
            WatermarkLayer("brand", LayerKind.BRAND, anchor = Anchor.BOTTOM_LEFT, column = LayerColumn.LEFT,
                size = .115f, margin = .048f, brandTone = BrandTone.LIGHT, brandStyle = BrandStyle.WORDMARK,
                background = "#790D171D"),
            annotation("camera", listOf(MetadataField.MODEL) + exposure, Anchor.BOTTOM_RIGHT,
                area = LayerArea.PHOTO, column = LayerColumn.RIGHT, size = .028f).copy(
                y = -.055f, background = "#790D171D", shadow = true),
            annotation("credit", listOf(MetadataField.AUTHOR, MetadataField.LOCATION, MetadataField.DATE),
                Anchor.BOTTOM_RIGHT, area = LayerArea.PHOTO, column = LayerColumn.RIGHT, size = .025f).copy(
                background = "#790D171D", shadow = true),
        ),
    )

    val archiveCard = WatermarkTemplate(
        "archive-card", "双栏档案",
        Frame(.048f, .13f, .048f, .30f, "#E8E4D9", hairline = true),
        listOf(
            WatermarkLayer("brand", LayerKind.BRAND, area = LayerArea.TOP, anchor = Anchor.CENTER_LEFT,
                size = .14f, margin = .045f, column = LayerColumn.LEFT),
            annotation("camera", listOf(MetadataField.MODEL), Anchor.CENTER_RIGHT, area = LayerArea.TOP,
                column = LayerColumn.RIGHT, size = .042f, color = "#252620", bold = true),
            annotation("title", listOf(MetadataField.TITLE), Anchor.TOP_LEFT, column = LayerColumn.LEFT_HALF,
                size = .047f, color = "#252620", type = TypeStyle.SERIF),
            annotation("exif", listOf(MetadataField.FOCAL, MetadataField.APERTURE), Anchor.TOP_RIGHT,
                column = LayerColumn.RIGHT_HALF, size = .027f, color = "#333843", type = TypeStyle.MONO),
            annotation("shutter", listOf(MetadataField.SHUTTER, MetadataField.ISO), Anchor.CENTER_RIGHT, column = LayerColumn.RIGHT_HALF,
                size = .027f, color = "#333843", type = TypeStyle.MONO),
            annotation("lens", listOf(MetadataField.LENS), Anchor.CENTER_LEFT, column = LayerColumn.LEFT_HALF,
                size = .027f, color = "#53524C"),
            annotation("credit", listOf(MetadataField.AUTHOR, MetadataField.LOCATION), Anchor.BOTTOM_LEFT,
                column = LayerColumn.LEFT_HALF, size = .026f, color = "#34352F"),
            annotation("date", listOf(MetadataField.DATE), Anchor.BOTTOM_RIGHT, column = LayerColumn.RIGHT_HALF,
                size = .025f, color = "#34352F", type = TypeStyle.MONO),
        ),
    )

    val all = listOf(gallery, modelPlate, zr, frostedGallery, frostedJournal, colorAtlas, fieldNotes, archiveCard,
        brandStamp, signature, dark, editorial, proof)

    /**
     * A built-in template picked from the strip keeps the canvas shape, titles, credit and location choice
     * already made, so its thumbnail and the edit after tapping it look the same.
     */
    fun adopt(template: WatermarkTemplate, current: WatermarkTemplate): WatermarkTemplate =
        if (template !in all) template else template.copy(
            captions = current.captions,
            place = current.place,
            frame = template.frame.copy(ratio = current.frame.ratio),
        )

    /** Groups for the template strip filter. */
    enum class Kind(val label: String) { ALL("全部"), IN_PHOTO("照片内"), BORDER("边框"), FROSTED("雾面"), PALETTE("色卡") }

    fun kind(template: WatermarkTemplate): Kind {
        val frame = template.frame
        return when {
            template.layers.any { it.kind == LayerKind.PALETTE } -> Kind.PALETTE
            frame.background == FrameBackground.FROSTED -> Kind.FROSTED
            listOf(frame.left, frame.top, frame.right, frame.bottom).all { it == 0f } -> Kind.IN_PHOTO
            else -> Kind.BORDER
        }
    }

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

    private fun annotation(
        id: String, fields: List<MetadataField>, anchor: Anchor, area: LayerArea = LayerArea.BOTTOM,
        column: LayerColumn = LayerColumn.FULL, size: Float = .031f, color: String = "#F5F0E7",
        type: TypeStyle = TypeStyle.SANS, bold: Boolean = false,
    ) = WatermarkLayer(id, LayerKind.EXIF, fields = fields, area = area, anchor = anchor, size = size,
        margin = .042f, color = color, type = type, bold = bold, column = column)
}
