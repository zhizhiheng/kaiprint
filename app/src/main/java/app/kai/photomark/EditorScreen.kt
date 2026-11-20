package app.kai.photomark

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class EditorPanel(val label: String) {
    TEMPLATES("模板"), LAYERS("图层"), FRAME("边框"), METADATA("标注")
}

@Composable
fun EditorScreen(
    workspace: Workspace, importLogo: () -> Unit, importFont: () -> Unit, requestLocation: () -> Unit,
    export: (ExportSettings, Boolean) -> Unit,
) {
    val group = workspace.current ?: return
    var panel by remember { mutableStateOf(EditorPanel.TEMPLATES) }
    var saving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var drag by remember(group.id, workspace.activeLayer) { mutableStateOf(Offset.Zero) }
    val draft = workspace.draft
    val infoResult by produceState<Result<PhotoInfo>?>(null, group, workspace.detailsGeneration) {
        value = null
        value = try {
            Result.success(workspace.info(group))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val info = infoResult?.getOrNull()
    val preferred = workspace.draft.pixelChoices[group.id]
    val override = draft.overrides[group.id].orEmpty()
    val pixels = info?.choices?.let { choices ->
        choices.firstOrNull { it.kind == preferred } ?: choices.firstOrNull { it.kind != PixelKind.EMBEDDED }
            ?: choices.firstOrNull()
    }
    val sourceResult by produceState<Result<Bitmap>?>(null, pixels) {
        value = null
        if (pixels != null) {
            value = try {
                Result.success(withContext(Dispatchers.IO) { workspace.app.photos.load(pixels, 2048) })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    val source = sourceResult?.getOrNull()
    val layer = draft.template.layers.firstOrNull { it.id == workspace.activeLayer }
    val template = if (drag == Offset.Zero || layer == null) draft.template else draft.template.copy(
        layers = draft.template.layers.map { if (it == layer) it.copy(x = it.x + drag.x, y = it.y + drag.y) else it },
    )
    val layout = pixels?.let { WatermarkLayout.calculate(it.size, template, metadata = info?.metadata, overrides = override) }
    val preview by produceState<Bitmap?>(null, source, template, pixels, override, info?.metadata) {
        if (source == null || layout == null) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) {
                workspace.app.renderer.preview(source, layout, template, info.metadata, override, 1280)
            }
        }
    }

    LaunchedEffect(layer?.id, panel) {
        if (layer == null || panel != EditorPanel.LAYERS) moving = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ workspace.screen = Screen.LIBRARY }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回相册") }
            Column(Modifier.weight(1f)) {
                Text("编辑水印", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(group.primary.name, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(workspace::undo, enabled = workspace.canUndo) { Text("撤销") }
            TextButton(workspace::redo, enabled = workspace.canRedo) { Text("重做") }
            Button({ exporting = true }, enabled = pixels != null && source != null,
                modifier = Modifier.padding(end = 8.dp)) { Text("导出") }
        }
        PreviewStage(
            source = source, preview = preview, layout = layout,
            loading = infoResult == null || (pixels != null && sourceResult == null),
            failure = if (sourceResult?.isFailure == true) "此来源暂时无法解码" else "没有可用的照片预览",
            layer = if (moving) layer else null,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onMove = { drag = it },
            onMoveFinished = {
                if (layer != null && drag != Offset.Zero) workspace.layer(layer.copy(
                    x = (layer.x + drag.x).coerceIn(-.95f, .95f),
                    y = (layer.y + drag.y).coerceIn(-.95f, .95f),
                ))
                drag = Offset.Zero
            },
            onMoveCancelled = { drag = Offset.Zero },
        )
        if (workspace.editing.size > 1) {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(workspace.editing, key = PhotoGroup::id) { photo ->
                    val current = photo.id == group.id
                    val index = workspace.editing.indexOf(photo)
                    Thumbnail(workspace, photo, Modifier.size(46.dp, 34.dp).clip(RoundedCornerShape(6.dp)).then(
                        if (current) Modifier.border(2.dp, Gold, RoundedCornerShape(6.dp)) else Modifier
                    ).clickable(role = Role.Button, onClickLabel = "查看第 ${index + 1} 张") { workspace.changePhoto(index) })
                }
            }
        }
        val panelFraction = if (panel == EditorPanel.TEMPLATES) .40f else .46f
        Surface(color = Panel, modifier = Modifier.fillMaxWidth().fillMaxHeight(panelFraction),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorPanel.entries.forEach { item ->
                        TextButton({ panel = item }, modifier = Modifier.weight(1f)) {
                            Text(item.label, color = if (item == panel) Gold else Muted,
                                fontWeight = if (item == panel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                HorizontalDivider(color = Stroke)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (panel) {
                        EditorPanel.TEMPLATES -> TemplatePanel(workspace, source, pixels, info?.metadata, override) { saving = true }
                        EditorPanel.LAYERS -> LayersPanel(workspace, layer, moving, { moving = it }, importLogo, importFont,
                            info?.metadata ?: PhotoMetadata(), override)
                        EditorPanel.FRAME -> FramePanel(workspace, source)
                        EditorPanel.METADATA -> MetadataPanel(workspace, group, info, pixels, requestLocation)
                    }
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saving = false }, title = { Text("保存水印预设") },
            text = { OutlinedTextField(name, { name = it.take(60) }, label = { Text("预设名称") }, singleLine = true) },
            confirmButton = { Button({ workspace.savePreset(name); saving = false }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton({ saving = false }) { Text("取消") } })
    }
    val currentPixels = pixels
    if (exporting && currentPixels != null) {
        ExportOptions(workspace, info, override, requestLocation, { exporting = false }) { settings, all ->
            exporting = false
            export(settings, all)
        }
    }
}

@Composable
private fun PreviewStage(
    source: Bitmap?, preview: Bitmap?, layout: WatermarkLayout?, loading: Boolean, failure: String,
    layer: WatermarkLayer?, modifier: Modifier, onMove: (Offset) -> Unit,
    onMoveFinished: () -> Unit, onMoveCancelled: () -> Unit,
) {
    var compare by remember { mutableStateOf(false) }
    var zoom by remember(source) { mutableStateOf(1f) }
    var pan by remember(source) { mutableStateOf(Offset.Zero) }
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnMoveFinished by rememberUpdatedState(onMoveFinished)
    val latestOnMoveCancelled by rememberUpdatedState(onMoveCancelled)
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
        pan = if (zoom == 1f) Offset.Zero else pan + panChange
    }

    BoxWithConstraints(modifier.background(Ground).padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center) {
        val active = if (compare) source else preview
        if (active != null) {
            val ratio = active.width.toFloat() / active.height
            val shownRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val fit = if (ratio >= shownRatio) Modifier.fillMaxWidth().aspectRatio(ratio)
                else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                val gesture = if (layer == null || layout == null) Modifier.transformable(transform)
                else Modifier.pointerInput(layer.id, layout) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero },
                        onDragEnd = { latestOnMoveFinished() },
                        onDragCancel = { latestOnMoveCancelled() },
                    ) { change, amount ->
                        change.consume()
                        val area = layout.area(layer)
                        val layerWidth = size.width * area.width / layout.canvas.width
                        val layerHeight = size.height * area.height / layout.canvas.height
                        if (layerWidth > 0 && layerHeight > 0) {
                            total += Offset(amount.x / layerWidth, amount.y / layerHeight)
                            latestOnMove(total)
                        }
                    }
                }
                Image(active.asImageBitmap(), if (compare) "原图" else "水印效果预览",
                    modifier = fit.then(gesture).graphicsLayer {
                        scaleX = if (layer == null) zoom else 1f
                        scaleY = scaleX
                        translationX = if (layer == null) pan.x else 0f
                        translationY = if (layer == null) pan.y else 0f
                    },
                    contentScale = ContentScale.Fit)
            }
            if (source != null) {
                Text(if (layer != null) "拖动图层" else "原图对比",
                    color = Ink, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .75f),
                        RoundedCornerShape(10.dp))
                        .then(if (layer == null) {
                            // Click supports TalkBack, while touch compares only while pressed.
                            Modifier.clickable(role = Role.Button, onClickLabel = "切换原图对比") { compare = !compare }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown().consume()
                                        compare = true
                                        try { waitForUpOrCancellation()?.consume() } finally { compare = false }
                                    }
                                }
                        } else Modifier)
                        .padding(horizontal = 11.dp, vertical = 9.dp))
            }
        } else if (loading || source != null) {
            CircularProgressIndicator(Modifier.size(29.dp), strokeWidth = 2.dp)
        } else {
            Text(failure, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TemplatePanel(
    workspace: Workspace, source: Bitmap?, pixels: PixelChoice?, metadata: PhotoMetadata?,
    overrides: Map<MetadataField, String>, save: () -> Unit,
) {
    val presets by workspace.presets.collectAsState()
    var filter by remember { mutableStateOf(Templates.Kind.ALL) }
    val templates = (Templates.all + presets.mapNotNull {
        runCatching { storageJson.decodeFromString<WatermarkTemplate>(it.template) }.getOrNull()
    }).filter { filter == Templates.Kind.ALL || Templates.kind(it) == filter }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("template-filter"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in Templates.Kind.entries) {
            FilterChip(filter == kind, { filter = kind }, { Text(kind.label, maxLines = 1) })
        }
    }
    val current = workspace.draft.template
    LazyRow(modifier = Modifier.testTag("template-strip"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(templates, key = WatermarkTemplate::id) { choice ->
            val selected = workspace.draft.template.id == choice.id
            // The thumbnail shows the template as tapping it would apply it: same canvas shape, credit and place.
            val template = if (selected) current else Templates.adopt(choice, current)
            val rendered by produceState<Bitmap?>(null, source, pixels, metadata, template, overrides) {
                value = if (source != null && pixels != null && metadata != null) withContext(Dispatchers.Default) {
                    workspace.app.renderer.preview(source, WatermarkLayout.calculate(pixels.size, template, metadata = metadata, overrides = overrides),
                        template, metadata, overrides, 192)
                } else null
            }
            Surface(onClick = { workspace.apply(choice) }, color = if (selected) Raised else Panel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.then(if (selected) Modifier.border(1.dp, Gold, RoundedCornerShape(10.dp)) else Modifier)) {
                Column(Modifier.padding(5.dp).size(94.dp, 99.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(5.dp)).background(Ground),
                        contentAlignment = Alignment.Center) {
                        rendered?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                    Text(template.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp, bottom = 3.dp),
                        color = if (selected) Gold else Ink, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { PostTargets(workspace) }
        TextButton(save) { Text("保存为预设") }
    }
}

@Composable
private fun PostTargets(workspace: Workspace) {
    val frame = workspace.draft.template.frame
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("post-targets"),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("发布到", color = Muted, style = MaterialTheme.typography.bodySmall)
        for (target in PostTarget.entries) {
            val chosen = frame.ratio == target.ratio
            FilterChip(chosen, {
                workspace.frame(frame.copy(ratio = if (chosen) FrameRatio.ORIGINAL else target.ratio))
            }, { Text("${target.label} ${target.ratio.label}", maxLines = 1) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayersPanel(
    workspace: Workspace, selected: WatermarkLayer?, moving: Boolean, setMoving: (Boolean) -> Unit,
    importLogo: () -> Unit, importFont: () -> Unit,
    metadata: PhotoMetadata, overrides: Map<MetadataField, String>,
) {
    val layers = workspace.draft.template.layers
    var add by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("图层 · ${layers.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Box {
            TextButton({ add = true }) { Text("添加图层") }
            DropdownMenu(add, { add = false }) {
                for (kind in LayerKind.entries) {
                    DropdownMenuItem(text = { Text(kind.label) }, onClick = {
                        add = false
                        if (kind == LayerKind.LOGO) importLogo() else workspace.addLayer(kind)
                    })
                }
            }
        }
    }
    if (layers.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            layers.forEach { layer ->
                FilterChip(selected?.id == layer.id, { workspace.activeLayer = layer.id },
                    { Text(layer.label.take(16), maxLines = 1) })
            }
        }
    }
    val layer = selected ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Switch(layer.visible, { workspace.layer(layer.copy(visible = it)) })
        Text(if (layer.visible) "可见" else "隐藏", modifier = Modifier.weight(1f), color = Muted,
            style = MaterialTheme.typography.bodySmall)
        TextButton({ setMoving(!moving) }) { Text(if (moving) "结束移动" else "拖动") }
        TextButton({ workspace.reorderLayer(layer.id, -1) }, enabled = layers.indexOf(layer) > 0) { Text("下移") }
        TextButton({ workspace.reorderLayer(layer.id, 1) }, enabled = layers.indexOf(layer) < layers.lastIndex) { Text("上移") }
        IconButton({ workspace.deleteLayer(layer.id) }) { Icon(Icons.Default.Close, "删除图层", tint = Danger) }
    }

    if (layer.kind == LayerKind.TEXT) {
        OutlinedTextField(layer.text, { workspace.layer(layer.copy(text = it.take(240))) }, label = { Text("水印文字") },
            modifier = Modifier.fillMaxWidth(), maxLines = 4)
    }
    if (layer.kind == LayerKind.EXIF) {
        MetadataChips(layer.fields) { workspace.layer(layer.copy(fields = it)) }
    }
    if (layer.kind == LayerKind.BRAND) {
        BrandControls(workspace, layer, metadata, overrides)
    }
    if (layer.kind == LayerKind.PALETTE) {
        ChoiceMenu("色卡样式", layer.paletteStyle, PaletteStyle.entries, PaletteStyle::label) {
            workspace.layer(layer.copy(paletteStyle = it))
        }
        ChoiceMenu("最多色数", layer.paletteCount.coerceIn(3, 6), (3..6).toList(), Int::toString) {
            workspace.layer(layer.copy(paletteCount = it))
        }
    }
    ChoiceMenu("作用区域", layer.area, LayerArea.entries, { it.label }) { workspace.layer(layer.copy(area = it)) }
    ChoiceMenu("排版范围", layer.column, LayerColumn.entries, LayerColumn::label) {
        workspace.layer(layer.copy(column = it, x = 0f, y = 0f))
    }
    if (layer.area != LayerArea.PHOTO && layer.area != LayerArea.CANVAS) {
        Text("在边框面板调整对应边的留白。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    ChoiceMenu("对齐位置", layer.anchor, Anchor.entries, { it.label }) {
        workspace.layer(layer.copy(anchor = it, x = 0f, y = 0f))
    }
    SliderRow("水平偏移", layer.x, -.95f.. .95f, { "${(it * 100).roundToInt()}%" }, workspace::finishAdjustment) { workspace.layer(layer.copy(x = it), live = true) }
    SliderRow("垂直偏移", layer.y, -.95f.. .95f, { "${(it * 100).roundToInt()}%" }, workspace::finishAdjustment) { workspace.layer(layer.copy(y = it), live = true) }
    SliderRow("大小", layer.size, .009f.. .75f, { "${(it * 100).roundToInt()}%" }, workspace::finishAdjustment) { workspace.layer(layer.copy(size = it), live = true) }
    SliderRow("边距", layer.margin, 0f.. .3f, { "${(it * 100).roundToInt()}%" }, workspace::finishAdjustment) { workspace.layer(layer.copy(margin = it), live = true) }
    SliderRow("透明度", layer.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }, workspace::finishAdjustment) { workspace.layer(layer.copy(opacity = it), live = true) }
    SliderRow("旋转", layer.rotation, -180f..180f, { "${it.roundToInt()}°" }, workspace::finishAdjustment) { workspace.layer(layer.copy(rotation = it), live = true) }
    if (workspace.draft.template.frame.background == FrameBackground.FROSTED && layer.canUseGlassInk &&
        (layer.kind != LayerKind.BRAND || layer.brand.resolve(metadata, overrides)?.graphic(layer.brandStyle)?.tintable == true)) {
        Toggle("随雾面明暗换色", layer.autoGlassInk) { workspace.layer(layer.copy(autoGlassInk = it)) }
    }
    if (layer.kind in listOf(LayerKind.TEXT, LayerKind.EXIF, LayerKind.LINE)) {
        Palette("水印颜色", layer.color) { workspace.layer(layer.copy(color = it, autoGlassInk = false)) }
    }
    if (layer.kind == LayerKind.TEXT || layer.kind == LayerKind.EXIF) {
        ChoiceMenu("字体", layer.type, TypeStyle.entries, { it.label }) {
            workspace.layer(layer.copy(type = it, fontFile = null))
        }
        TextButton(importFont) { Text(if (layer.fontFile == null) "导入自定义字体" else "更换自定义字体") }
        Toggle("粗体", layer.bold) { workspace.layer(layer.copy(bold = it)) }
        Toggle("描边", layer.outline) { workspace.layer(layer.copy(outline = it)) }
        Toggle("阴影", layer.shadow) { workspace.layer(layer.copy(shadow = it)) }
    }
    if (layer.kind != LayerKind.LINE) {
        Toggle("平铺", layer.tile) { workspace.layer(layer.copy(tile = it)) }
        Toggle("半透明底色", layer.background != null) { workspace.layer(layer.copy(background = if (it) "#99000000" else null)) }
    }
}

@Composable
private fun FramePanel(workspace: Workspace, bitmap: Bitmap?) {
    val swatches by produceState(emptyList<String>(), bitmap) {
        value = if (bitmap == null) emptyList() else withContext(Dispatchers.Default) { sampledColors(bitmap) }
    }
    val frame = workspace.draft.template.frame
    ChoiceMenu("画布比例", frame.ratio, FrameRatio.entries, { ratio ->
        PostTarget.entries.filter { it.ratio == ratio }.joinToString("、") { it.label }
            .let { if (it.isEmpty()) ratio.label else "${ratio.label} · $it" }
    }) { workspace.frame(frame.copy(ratio = it)) }
    ChoiceMenu("边框背景", frame.background, FrameBackground.entries, FrameBackground::label) {
        workspace.frame(frame.copy(background = it))
    }
    Palette("边框颜色", frame.color, swatches) { workspace.frame(frame.copy(color = it)) }
    Toggle("照片细线边缘", frame.hairline) { workspace.frame(frame.copy(hairline = it)) }
    Toggle("没有参数时收起空白边", frame.collapseEmpty) { workspace.frame(frame.copy(collapseEmpty = it)) }
    Text("缺少拍摄参数的照片，参数条缩到与侧边相同的留白。关闭后保留设定的留白。", color = Muted,
        style = MaterialTheme.typography.bodySmall)
    val pct: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    if (frame.background == FrameBackground.FROSTED) {
        SliderRow("模糊程度", frame.blur, .02f.. .24f, pct, workspace::finishAdjustment) {
            workspace.frame(frame.copy(blur = it), live = true)
        }
        SliderRow("底色强度", frame.tintStrength, 0f.. .9f, pct, workspace::finishAdjustment) {
            workspace.frame(frame.copy(tintStrength = it), live = true)
        }
    }
    SliderRow("上边", frame.top, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(top = it), live = true) }
    SliderRow("下边", frame.bottom, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(bottom = it), live = true) }
    SliderRow("左边", frame.left, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(left = it), live = true) }
    SliderRow("右边", frame.right, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(right = it), live = true) }
}

@Composable
private fun MetadataPanel(
    workspace: Workspace, group: PhotoGroup, info: PhotoInfo?, pixels: PixelChoice?, requestLocation: () -> Unit,
) {
    if (info == null) {
        Text("等待读取拍摄参数", color = Muted)
        return
    }
    val saved = workspace.draft.overrides[group.id].orEmpty()
    val layers = workspace.draft.template.layers
    val brandLayers = layers.filter { it.kind == LayerKind.BRAND }
    val parameterLayers = layers.filter { it.kind == LayerKind.EXIF }
    if (brandLayers.isEmpty()) {
        TextButton({ workspace.addLayer(LayerKind.BRAND) }) { Text("添加品牌标志") }
    } else for (layer in brandLayers) key(layer.id) {
        BrandControls(workspace, layer, info.metadata, saved)
    }
    HorizontalDivider(color = Stroke)
    Text("显示哪些参数", style = MaterialTheme.typography.titleSmall, color = Ink)
    if (parameterLayers.isEmpty()) {
        TextButton({ workspace.addLayer(LayerKind.EXIF) }) { Text("添加拍摄参数") }
    } else {
        var selectedId by remember(workspace.draft.template.id) { mutableStateOf<String?>(null) }
        val active = parameterLayers.firstOrNull { it.id == selectedId }
            ?: parameterLayers.firstOrNull { it.id == "exif" }
            ?: parameterLayers.first()
        if (parameterLayers.size > 1) {
            ChoiceMenu("参数图层", active, parameterLayers, {
                if (it.id == "camera") "机型行" else if (it.id == "exif") "拍摄参数行" else
                    it.fields.firstOrNull()?.label ?: it.kind.label
            }) { selectedId = it.id }
        }
        MetadataChips(active.fields) { workspace.layer(active.copy(fields = it)) }
    }
    if (workspace.editing.size > 1) {
        Text("同一套选项应用到整组，每张照片读取自己的相机参数。", color = Muted,
            style = MaterialTheme.typography.bodySmall)
    }
    var showingCaptions by remember { mutableStateOf(false) }
    TextButton({ showingCaptions = !showingCaptions }) {
        Text(if (showingCaptions) "收起标题、署名和地点" else "标题、署名和地点")
    }
    val captions = workspace.draft.template.captions
    if (showingCaptions) {
        for (field in MetadataField.captionFields) {
            OutlinedTextField(
                captions[field].orEmpty(), { workspace.caption(field, it) },
                label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        }
        Text("随模板保存。单张照片可以在下方单独改写。", color = Muted,
            style = MaterialTheme.typography.bodySmall)
        PlaceControls(workspace, info.metadata, requestLocation)
    }
    HorizontalDivider(color = Stroke)
    if (pixels != null) {
        ChoiceMenu("像素来源", pixels, info.choices, { "${it.kind.label} · ${it.size}" }) {
            workspace.choosePixels(group, it.kind)
        }
    }
    info.metadata.rawCompression?.let {
        val compression = when (it) { 3 -> "无损压缩"; 13 -> "高效率"; 14 -> "高效率★"; else -> "编码 $it" }
        Text("NEF 压缩 · $compression", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    if (pixels?.kind == PixelKind.EMBEDDED) Text("此来源仅提供内嵌预览尺寸。", color = Gold,
        style = MaterialTheme.typography.bodySmall)

    ParameterSets(workspace, group, saved)
    var editing by remember(group.id) { mutableStateOf(false) }
    var draft by remember(group.id, saved) { mutableStateOf(saved) }
    TextButton({ editing = !editing }) { Text(if (editing) "收起改写参数" else "改写水印参数") }
    for (field in MetadataField.entries) {
        if (editing) {
            OutlinedTextField(draft[field] ?: captions[field] ?: info.metadata.values[field].orEmpty(), { value ->
                draft = draft + (field to value.take(120))
            }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        } else {
            val value = saved[field] ?: captions[field] ?: info.metadata.values[field] ?: continue
            if (value.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(field.label, modifier = Modifier.weight(.32f), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(value, modifier = Modifier.weight(.68f), color = Ink, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (editing) {
        Row {
            TextButton({ workspace.updateOverrides(group, emptyMap()); editing = false }) { Text("恢复原参数") }
            Button({ workspace.updateOverrides(group, draft); editing = false }) { Text("应用到这张照片") }
        }
        if (workspace.editing.size > 1) {
            OutlinedButton({ workspace.overrideAll(draft); editing = false }, enabled = draft.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text("应用到全部 ${workspace.editing.size} 张") }
            Text(if (draft.isEmpty()) "先改写至少一项参数。"
                else "把这张改写过的 ${draft.size} 项写入整组，其余各项保持每张照片自己的参数。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlaceControls(workspace: Workspace, metadata: PhotoMetadata, requestLocation: () -> Unit) {
    val template = workspace.draft.template
    ChoiceMenu("地点来源", template.place, PlaceStyle.entries, PlaceStyle::label) {
        workspace.place(it)
        if (it != PlaceStyle.MANUAL) requestLocation()
    }
    val resolved by produceState<String?>(null, template.place, metadata) {
        value = withContext(Dispatchers.IO) { workspace.app.renderer.place(template, metadata) }
    }
    val manual = template.captions[MetadataField.LOCATION].orEmpty()
    val fallback = if (manual.isBlank()) "地点留空" else "印手填的「$manual」"
    val note = when {
        template.place == PlaceStyle.MANUAL -> "只印手填地点。照片自带的位置不会出现在画面上。"
        !metadata.hasLocation -> "这张照片没有可读的位置，$fallback。系统选图和部分分享会去掉位置。"
        resolved == null -> "附近 ${PlaceIndex.RADIUS_KM.toInt()} 公里内没有收录的城市，$fallback。"
        else -> "这张照片印「$resolved」。精确坐标是否写入成片，在导出时单独选择。"
    }
    Text(note, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("place-note"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParameterSets(workspace: Workspace, group: PhotoGroup, saved: Map<MetadataField, String>) {
    var naming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("参数组", style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
        TextButton({ naming = true }, enabled = saved.isNotEmpty()) { Text("保存这张的改写") }
    }
    if (workspace.parameterSets.isEmpty()) {
        Text("胶片、手动镜头等没有拍摄参数的照片，可以把机型和镜头存成参数组，再一次写入整组。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag("parameter-sets")) {
        for (set in workspace.parameterSets) key(set.id) {
            var open by remember { mutableStateOf(false) }
            Box {
                Surface(onClick = { open = true }, shape = RoundedCornerShape(10.dp), color = Raised,
                    modifier = Modifier.border(1.dp, Stroke, RoundedCornerShape(10.dp))) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
                        Text(set.name, color = Ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        if (set.summary.isNotEmpty()) Text(set.summary, color = Muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(150.dp))
                    }
                }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem(text = { Text("套用到这张") }, onClick = {
                        open = false
                        workspace.applyParameterSet(set, listOf(group))
                    })
                    if (workspace.editing.size > 1) DropdownMenuItem(text = { Text("套用到全部 ${workspace.editing.size} 张") },
                        onClick = {
                            open = false
                            workspace.applyParameterSet(set, workspace.editing)
                        })
                    DropdownMenuItem(text = { Text("删除参数组", color = Danger) }, onClick = {
                        open = false
                        workspace.deleteParameterSet(set.id)
                    })
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf(saved[MetadataField.MODEL].orEmpty().take(30)) }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text("保存参数组") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(30) }, label = { Text("名称") }, singleLine = true)
                    Text(saved.keys.joinToString("、") { it.label }, color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button({ workspace.saveParameterSet(name, saved); naming = false }, enabled = name.isNotBlank()) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton({ naming = false }) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataChips(fields: List<MetadataField>, onChange: (List<MetadataField>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (field in MetadataField.entries) {
            FilterChip(
                selected = field in fields,
                onClick = { onChange(if (field in fields) fields - field else fields + field) },
                label = { Text(field.label) },
            )
        }
    }
}

@Composable
private fun BrandControls(
    workspace: Workspace, layer: WatermarkLayer, metadata: PhotoMetadata, overrides: Map<MetadataField, String>,
) {
    val detected = BrandMark.AUTO.resolve(metadata, overrides)
    ChoiceMenu("品牌标志", layer.brand, BrandMark.entries, { mark ->
        if (mark == BrandMark.AUTO) "自动 · ${detected?.label ?: "未识别"}" else mark.label
    }) { workspace.layer(layer.copy(brand = it)) }

    if (layer.brand != BrandMark.NONE) {
        val placement = BrandPlacement.entries.firstOrNull { it.matches(layer) }
        ChoiceMenu<BrandPlacement?>("标志位置", placement, BrandPlacement.entries, { it?.label ?: "自定义位置" }) {
            if (it != null) workspace.layer(it.place(layer))
        }
    }
    val current = layer.brand.resolve(metadata, overrides) ?: return
    val original = current.graphic(BrandStyle.ORIGINAL) ?: return
    val graphic = current.graphic(layer.brandStyle, metadata, overrides) ?: return
    val styles = BrandStyle.entries.mapNotNull { style ->
        current.graphic(style, metadata, overrides)?.let { style to it }
    }.filter { (style, swatch) ->
        style == layer.brandStyle || style == BrandStyle.ORIGINAL || style == BrandStyle.SQUARE || swatch != original
    }
    Text("标志样式", style = MaterialTheme.typography.bodyMedium, color = Muted)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((style, swatch) in styles) key(style) {
            BrandStyleCard(workspace.app.renderer, current, swatch, style, layer.brandTone, style == layer.brandStyle,
                metadata, overrides) {
                workspace.layer(layer.copy(brandStyle = style))
            }
        }
    }
    if (layer.brandStyle in listOf(BrandStyle.WORDMARK, BrandStyle.SYMBOL) && graphic == original) {
        Text("这张照片使用原版标志", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (layer.brandStyle == BrandStyle.MODEL) {
        Text(if (graphic.model) "按每张照片的机型匹配，没有机型标志的照片使用品牌标志。"
            else "这台相机暂无机型标志，使用品牌标志。可在图层中导入自己的 SVG。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (graphic.tintable) {
        ChoiceMenu("标志颜色", layer.brandTone, BrandTone.entries, BrandTone::label) {
            workspace.layer(layer.copy(brandTone = it, autoGlassInk = false))
        }
    }
}

@Composable
private fun BrandStyleCard(
    renderer: WatermarkRenderer, brand: BrandMark, graphic: BrandGraphic, style: BrandStyle,
    tone: BrandTone, selected: Boolean, metadata: PhotoMetadata, overrides: Map<MetadataField, String>,
    choose: () -> Unit,
) {
    val artwork by produceState<Bitmap?>(null, brand, graphic, tone) {
        value = null
        value = withContext(Dispatchers.Default) { renderer.brandThumbnail(brand, tone, style, metadata, overrides) }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(84.dp).clip(shape).background(if (selected) Raised else Panel)
            .border(1.dp, if (selected) Gold else Stroke, shape)
            .selectable(selected, role = Role.RadioButton, onClick = choose)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val swatch = if (tone == BrandTone.LIGHT && graphic.tintable) Color(0xFF383944) else Color(0xFFD8D3C9)
        Box(
            Modifier.fillMaxWidth().height(43.dp).background(swatch, RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            artwork?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        val label = when {
            style == BrandStyle.SQUARE && brand in listOf(BrandMark.NIKON, BrandMark.NIKON_Z) -> "尼康黄底方标"
            else -> style.label
        }
        Text(label, Modifier.padding(top = 5.dp), color = if (selected) Gold else Ink,
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun <T> ChoiceMenu(label: String, current: T, entries: List<T>, display: (T) -> String, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton({ open = true }) { Text(display(current) + if (entries.size > 1) "  ▾" else "") }
            DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                entries.forEach { item -> DropdownMenuItem(text = { Text(display(item)) }, onClick = { onChoose(item); open = false }) }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                      display: (Float) -> String, finish: () -> Unit, changed: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(display(local), color = Ink, style = MaterialTheme.typography.labelSmall)
        }
        Slider(local, { local = it; changed(it) }, valueRange = range, onValueChangeFinished = finish,
            modifier = Modifier.height(40.dp).semantics { contentDescription = label })
    }
}

@Composable
private fun Toggle(label: String, state: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(state, changed, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun Palette(label: String, selected: String, sampled: List<String> = emptyList(), change: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, value) in listOf("白色" to "#FFFFFF", "暖白" to "#F5F2EB", "炭黑" to "#131315",
            "浅灰" to "#D4D5D8", "金色" to "#F4CE82", "朱红" to "#B94B41") + sampled.mapIndexed { index, color ->
                "照片取色 ${index + 1}" to color
            }) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                .background(Color(color(value, 0xFFFFFFFF.toInt())))
                .border(if (value.equals(selected, true)) 3.dp else 1.dp,
                    if (value.equals(selected, true)) Gold else Stroke, RoundedCornerShape(9.dp))
                .semantics { contentDescription = name }.clickable(role = Role.Button) { change(value) })
        }
        TextButton({ custom = true }) { Text("自定义") }
    }
    if (custom) {
        var value by remember { mutableStateOf(selected) }
        val valid = Regex("#[A-Fa-f0-9]{6}").matches(value)
        AlertDialog(onDismissRequest = { custom = false }, title = { Text(label) },
            text = { OutlinedTextField(value, { value = it.take(7) }, label = { Text("颜色代码") }, placeholder = { Text("#FFFFFF") }) },
            confirmButton = { Button({ change(value); custom = false }, enabled = valid) { Text("应用") } },
            dismissButton = { TextButton({ custom = false }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportOptions(workspace: Workspace, info: PhotoInfo?, overrides: Map<MetadataField, String>,
                          requestLocation: () -> Unit, close: () -> Unit,
                          output: (ExportSettings, Boolean) -> Unit) {
    var settings by remember { mutableStateOf(workspace.exportSettings) }
    var all by remember { mutableStateOf(workspace.editing.size > 1) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val group = workspace.current ?: return
    val plan = info?.let {
        runCatching {
            planExport(it, workspace.draft.pixelChoices[group.id], workspace.draft.template, settings, overrides,
                Build.VERSION.SDK_INT)
        }
    }
    val current = plan?.getOrNull()
    val summary by produceState<ExportSummary?>(null, settings, all, workspace.draft) {
        value = null
        if (all && workspace.editing.size > 1) value = workspace.exportSummary(settings, true)
    }
    val sizes = (SizeLimit.choices + settings.size).distinct()
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)
            .padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("导出成片", style = MaterialTheme.typography.headlineSmall)
            if (workspace.editing.size > 1) {
                ChoiceMenu("导出范围", all, listOf(true, false), {
                    if (it) "全部 ${workspace.editing.size} 张" else "当前照片"
                }) { all = it }
            }
            ChoiceMenu("文件格式", settings.format, OutputFormat.entries, { it.label }) { settings = settings.copy(format = it) }
            ChoiceMenu("成片尺寸", settings.size, sizes, SizeLimit::label) { settings = settings.withSize(it) }
            ChoiceMenu("输出色域", settings.colorSpace, OutputColor.entries, { it.label }) {
                settings = settings.copy(colorSpace = it)
            }
            if (settings.format == OutputFormat.JPEG || settings.format == OutputFormat.WEBP) {
                ChoiceMenu("图片质量", settings.quality, listOf(85, 90, 95, 100), Int::toString) {
                    settings = settings.copy(quality = it)
                }
            }
            HorizontalDivider(color = Stroke)
            ExportPreview(current, plan?.exceptionOrNull(), settings)
            if (all && workspace.editing.size > 1) BatchSummary(summary, workspace.editing.size)
            HorizontalDivider(color = Stroke)
            Toggle(if (settings.format == OutputFormat.TIFF) "保留基础信息" else "保留常用拍摄参数", settings.keepMetadata) { settings = settings.copy(keepMetadata = it) }
            if (settings.keepMetadata) {
                ChoiceMenu("作者（Artist）", settings.credit, CreditSource.entries, CreditSource::label) {
                    settings = settings.copy(credit = it)
                }
                if (settings.credit != CreditSource.NONE) {
                    OutlinedTextField(settings.copyright, { settings = settings.copy(copyright = it.take(200)) },
                        label = { Text("版权（留空沿用原文件）") }, placeholder = { Text("(C) 2026 Your Name") },
                        modifier = Modifier.fillMaxWidth().testTag("copyright"), singleLine = true)
                }
                if (settings.format != OutputFormat.TIFF) {
                    Toggle("在文件中保留拍摄位置（GPS）", settings.keepLocation) {
                        settings = settings.copy(keepLocation = it)
                        if (it) requestLocation()
                    }
                }
            }
            if (current?.pixels?.kind == PixelKind.EMBEDDED || all ||
                plan?.exceptionOrNull()?.let { it is ExportFailure && it.code == "RAW_PREVIEW" } == true) {
                Toggle("允许使用 RAW 内嵌预览尺寸", settings.allowEmbedded) {
                    settings = settings.copy(allowEmbedded = it)
                }
            }
            val hdr = info?.choices?.any { it.hdr } == true
            if (hdr || all) {
                if (settings.format == OutputFormat.JPEG && Build.VERSION.SDK_INT >= 34) {
                    Toggle("保留 Ultra HDR（JPEG 增益图）", settings.keepHdr) { settings = settings.copy(keepHdr = it) }
                }
                Toggle("允许 HDR 照片转换为 SDR", settings.allowSdr) { settings = settings.copy(allowSdr = it) }
            }
            Text(if (settings.keepLocation) "成片移除设备序列号。" else "成片移除 GPS 和设备序列号。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Button({ output(settings, all) }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                enabled = workspace.runningJob == null && !workspace.startInProgress && (all || current != null)) {
                Text(if (all) "导出 ${workspace.editing.size} 张" else "保存照片")
            }
        }
    }
}

/** The current photo's result, worked out the same way the exporter will write it. */
@Composable
private fun ExportPreview(plan: ExportPlan?, failure: Throwable?, settings: ExportSettings) {
    val small = MaterialTheme.typography.bodySmall
    if (plan == null) {
        Text(failure?.message ?: "正在读取当前照片", color = if (failure != null) Danger else Muted, style = small)
        return
    }
    Column(Modifier.fillMaxWidth().testTag("export-preview"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("当前照片", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text("照片本体 ${plan.pixels.size} → 成片 ${plan.layout.canvas} px", color = Gold, style = small)
        if (plan.scaled) Text("照片本体缩放为 ${plan.photo} px，边框按同一比例缩放。", color = Muted, style = small)
        else if (plan.layout.canvas != plan.pixels.size) Text("照片像素不缩放，边框在外侧增加画布。", color = Muted, style = small)
        Text("像素来源 · ${plan.pixels.kind.label}" + if (plan.hdr != HdrHandling.NONE) " · ${plan.hdr.label}" else "",
            color = if (plan.hdr == HdrHandling.SDR || plan.pixels.kind == PixelKind.EMBEDDED) Gold else Muted, style = small)
        val written = plan.fields
        Text(when {
            !settings.keepMetadata -> "文件不写入拍摄信息。"
            written.isEmpty() -> "原文件没有可写入的拍摄信息。"
            else -> "写入文件：" + written.joinToString("、")
        }, color = Muted, style = small)
        plan.tags[ExifInterface.TAG_ARTIST]?.let {
            Text("作者 · $it", color = Muted, style = small)
        }
        plan.tags[ExifInterface.TAG_COPYRIGHT]?.let {
            Text("版权 · $it", color = Muted, style = small)
        }
        for (note in plan.notes) Text(note, color = Gold, style = small)
    }
}

@Composable
private fun BatchSummary(summary: ExportSummary?, total: Int) {
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth().testTag("batch-summary"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("整组 $total 张", color = Ink, style = MaterialTheme.typography.titleSmall)
        if (summary == null) {
            Text("正在逐张检查…", color = Muted, style = small)
            return
        }
        if (summary.checked < summary.total) {
            Text("已检查前 ${summary.checked} 张，其余导出时逐张处理。", color = Muted, style = small)
        }
        Text("像素来源 · " + summary.kinds.entries.sortedBy { it.key.ordinal }
            .joinToString(" · ") { "${it.key.label} ${it.value} 张" }.ifEmpty { "无" }, color = Muted, style = small)
        val lines = listOfNotNull(
            "${summary.sdr} 张 HDR 转为 SDR".takeIf { summary.sdr > 0 },
            "${summary.keptHdr} 张保留 Ultra HDR".takeIf { summary.keptHdr > 0 },
            "${summary.scaled} 张按尺寸限制缩小".takeIf { summary.scaled > 0 },
            "${summary.noCamera} 张没有相机信息，可用参数组补填".takeIf { summary.noCamera > 0 },
            "${summary.located} 张写入拍摄位置".takeIf { summary.located > 0 },
        )
        for (line in lines) Text(line, color = Muted, style = small)
        for ((reason, count) in summary.blocked) Text("$count 张不会导出：$reason", color = Danger, style = small)
    }
}
