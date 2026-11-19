package app.kai.photomark

import android.graphics.Bitmap
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class EditorPanel(val label: String) {
    TEMPLATES("模板"), LAYERS("图层"), FRAME("边框"), METADATA("标注")
}

@Composable
fun EditorScreen(workspace: Workspace, importLogo: () -> Unit, importFont: () -> Unit, export: (ExportSettings, Boolean) -> Unit) {
    val group = workspace.current ?: return
    var panel by remember { mutableStateOf(EditorPanel.TEMPLATES) }
    var saving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var drag by remember(group.id, workspace.activeLayer) { mutableStateOf(Offset.Zero) }
    val draft = workspace.draft
    val infoResult by produceState<Result<PhotoInfo>?>(null, group) {
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
    val layout = pixels?.let { WatermarkLayout.calculate(it.size, draft.template.frame) }
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
        val panelFraction = if (panel == EditorPanel.TEMPLATES) .34f else .46f
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
                        EditorPanel.METADATA -> MetadataPanel(workspace, group, info, pixels)
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
        ExportOptions(workspace, currentPixels, { exporting = false }) { settings, all ->
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
    val templates = Templates.all + presets.mapNotNull {
        runCatching { storageJson.decodeFromString<WatermarkTemplate>(it.template) }.getOrNull()
    }
    LazyRow(modifier = Modifier.testTag("template-strip"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(templates, key = WatermarkTemplate::id) { template ->
            val selected = workspace.draft.template.id == template.id
            val rendered by produceState<Bitmap?>(null, source, pixels, metadata, template, overrides) {
                value = if (source != null && pixels != null && metadata != null) withContext(Dispatchers.Default) {
                    workspace.app.renderer.preview(source, WatermarkLayout.calculate(pixels.size, template.frame),
                        template, metadata, overrides, 192)
                } else null
            }
            Surface(onClick = { workspace.apply(template) }, color = if (selected) Raised else Panel,
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(save) { Text("保存为预设") }
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
    if (layer.kind != LayerKind.LOGO && layer.kind != LayerKind.BRAND) {
        Palette("水印颜色", layer.color) { workspace.layer(layer.copy(color = it)) }
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
    ChoiceMenu("画布比例", frame.ratio, FrameRatio.entries, { it.label }) { workspace.frame(frame.copy(ratio = it)) }
    Palette("边框颜色", frame.color, swatches) { workspace.frame(frame.copy(color = it)) }
    Toggle("照片细线边缘", frame.hairline) { workspace.frame(frame.copy(hairline = it)) }
    val pct: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    SliderRow("上边", frame.top, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(top = it), live = true) }
    SliderRow("下边", frame.bottom, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(bottom = it), live = true) }
    SliderRow("左边", frame.left, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(left = it), live = true) }
    SliderRow("右边", frame.right, 0f.. .75f, pct, workspace::finishAdjustment) { workspace.frame(frame.copy(right = it), live = true) }
}

@Composable
private fun MetadataPanel(workspace: Workspace, group: PhotoGroup, info: PhotoInfo?, pixels: PixelChoice?) {
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

    var editing by remember(group.id) { mutableStateOf(false) }
    var draft by remember(group.id, saved) { mutableStateOf(saved) }
    TextButton({ editing = !editing }) { Text(if (editing) "收起改写参数" else "改写水印参数") }
    for (field in MetadataField.entries) {
        if (editing) {
            OutlinedTextField(draft[field] ?: info.metadata.values[field].orEmpty(), { value ->
                draft = draft + (field to value.take(120))
            }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        } else {
            val value = saved[field] ?: info.metadata.values[field] ?: continue
            if (value.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(field.label, modifier = Modifier.weight(.32f), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(value, modifier = Modifier.weight(.68f), color = Ink, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (editing) Row {
        TextButton({ workspace.updateOverrides(group, emptyMap()); editing = false }) { Text("恢复原参数") }
        Button({ workspace.updateOverrides(group, draft); editing = false }) { Text("应用到这张照片") }
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

    val current = layer.brand.resolve(metadata, overrides) ?: return
    val original = current.graphic(BrandStyle.ORIGINAL) ?: return
    val graphic = current.graphic(layer.brandStyle) ?: return
    val styles = BrandStyle.entries.mapNotNull { style ->
        current.graphic(style)?.let { style to it }
    }.filter { (style, swatch) ->
        style == layer.brandStyle || style == BrandStyle.ORIGINAL || style == BrandStyle.SQUARE || swatch != original
    }
    Text("标志样式", style = MaterialTheme.typography.bodyMedium, color = Muted)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((style, swatch) in styles) key(style) {
            BrandStyleCard(workspace.app.renderer, current, swatch, style, layer.brandTone, style == layer.brandStyle) {
                workspace.layer(layer.copy(brandStyle = style))
            }
        }
    }
    if (layer.brandStyle in listOf(BrandStyle.WORDMARK, BrandStyle.SYMBOL) && graphic == original) {
        Text("这张照片使用原版标志", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (graphic.tintable) {
        ChoiceMenu("标志颜色", layer.brandTone, BrandTone.entries, BrandTone::label) {
            workspace.layer(layer.copy(brandTone = it))
        }
    }
}

@Composable
private fun BrandStyleCard(
    renderer: WatermarkRenderer, brand: BrandMark, graphic: BrandGraphic, style: BrandStyle,
    tone: BrandTone, selected: Boolean, choose: () -> Unit,
) {
    val artwork by produceState<Bitmap?>(null, brand, style, tone) {
        value = null
        value = withContext(Dispatchers.Default) { renderer.brandThumbnail(brand, tone, style) }
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
        Text(style.label, Modifier.padding(top = 5.dp), color = if (selected) Gold else Ink,
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
private fun ExportOptions(workspace: Workspace, pixels: PixelChoice, close: () -> Unit,
                          output: (ExportSettings, Boolean) -> Unit) {
    var settings by remember { mutableStateOf(ExportSettings()) }
    var all by remember { mutableStateOf(workspace.editing.size > 1) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val layout = WatermarkLayout.calculate(pixels.size, workspace.draft.template.frame, settings.longEdge)
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
            ChoiceMenu("成片尺寸", settings.longEdge, listOf(0, 4096, 3000, 2048, 1080), {
                if (it == 0) "原尺寸" else "长边 $it px"
            }) { settings = settings.copy(longEdge = it) }
            Text("当前照片 · ${layout.canvas} px", color = Gold, style = MaterialTheme.typography.bodySmall)
            ChoiceMenu("输出色域", settings.colorSpace, OutputColor.entries, { it.label }) {
                settings = settings.copy(colorSpace = it)
            }
            if (settings.format == OutputFormat.JPEG || settings.format == OutputFormat.WEBP) {
                ChoiceMenu("图片质量", settings.quality, listOf(85, 90, 95, 100), Int::toString) {
                    settings = settings.copy(quality = it)
                }
            }
            if (settings.format == OutputFormat.TIFF) {
                Text("TIFF 成片为 8 位。可保留品牌、机型和拍摄时间。", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            Toggle(if (settings.format == OutputFormat.TIFF) "保留基础信息" else "保留常用拍摄参数", settings.keepMetadata) { settings = settings.copy(keepMetadata = it) }
            if (pixels.kind == PixelKind.EMBEDDED || all) {
                Toggle("允许使用 RAW 内嵌预览尺寸", settings.allowEmbedded) {
                    settings = settings.copy(allowEmbedded = it)
                }
            }
            if (pixels.hdr || all) {
                Toggle("允许 HDR 照片转换为 SDR", settings.allowSdr) { settings = settings.copy(allowSdr = it) }
            }
            Text("成片移除 GPS 和设备序列号。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Button({ output(settings, all) }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                enabled = workspace.runningJob == null && !workspace.startInProgress &&
                    (all || pixels.kind != PixelKind.EMBEDDED || settings.allowEmbedded) &&
                    (all || !pixels.hdr || settings.allowSdr)) {
                Text(if (all) "导出 ${workspace.editing.size} 张" else "保存照片")
            }
        }
    }
}
