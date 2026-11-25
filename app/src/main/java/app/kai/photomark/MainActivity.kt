package app.kai.photomark

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Ink = Color(0xFFF5F1E9)
internal val Muted = Color(0xFFB0B6AE)
internal val Ground = Color(0xFF161918)
internal val Panel = Color(0xFF202421)
internal val Raised = Color(0xFF29302B)
internal val Stroke = Color(0xFF3B433C)
internal val Gold = Color(0xFFD9B979)
internal val GoldDark = Color(0xFF302616)
internal val Sage = Color(0xFF91A895)
internal val Darkroom = Color(0xFF101311)
internal val Danger = Color(0xFFFFA396)

class MainActivity : ComponentActivity() {
    private val workspace: Workspace by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated screen already holds the photos from its first launch.
        if (savedInstanceState == null) receive(intent)
        else if (savedInstanceState.getBoolean("editingPhoto")) workspace.reopen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Gold, onPrimary = GoldDark,
                primaryContainer = Color(0xFF3C301B), onPrimaryContainer = Gold,
                secondary = Sage, onSecondary = Color(0xFF18251C),
                secondaryContainer = Color(0xFF354338), onSecondaryContainer = Color(0xFFE4EFE2),
                background = Ground, onBackground = Ink, surface = Panel, onSurface = Ink,
                surfaceVariant = Raised, onSurfaceVariant = Muted, outline = Stroke,
                outlineVariant = Stroke, error = Danger,
            )) {
                PhotoMark(workspace)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("editingPhoto", workspace.screen == Screen.EDITOR)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        intent?.getStringExtra(ExportService.SHOW_JOB)?.let { workspace.showJob(it) }
        val uris = sharedUris(intent)
        val preset = uris.singleOrNull()?.takeIf { uri ->
            intent?.type in setOf(PresetArchive.MIME, "image/svg+xml", "application/json") ||
                uri.lastPathSegment.orEmpty().endsWith(".kaiphoto", ignoreCase = true)
        }
        if (preset != null) workspace.importPreset(preset) else workspace.receiveShared(uris)
    }
}

@Composable
private fun PhotoMark(workspace: Workspace) {
    val snackbars = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(150), workspace::importFiles)
    val pickPhotos = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val permissions = photoPermissionActions(workspace, pickPhotos)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), workspace::importFiles)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(workspace::importFolder)
    }
    val logo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(workspace::addLogo) }
    val font = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(workspace::addFont) }
    val presetImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(workspace::importPreset)
    }
    var pendingDesign by rememberSaveable { mutableStateOf<String?>(null) }
    var replacingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val selectOriginal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = replacingPhoto
        replacingPhoto = null
        if (uri != null && id != null) workspace.replaceCurrentPhoto(id, uri)
    }
    var chooseDesignSource by rememberSaveable { mutableStateOf(false) }
    val designPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val encoded = pendingDesign
        pendingDesign = null
        if (uri != null && encoded != null) {
            workspace.importForDesign(listOf(uri), storageJson.decodeFromString<WatermarkTemplate>(encoded))
        }
    }
    fun openDesign(template: WatermarkTemplate) {
        if (workspace.editing.isEmpty() && workspace.hasDraft) workspace.reopen()
        if (workspace.editing.isNotEmpty()) {
            workspace.apply(template)
        } else {
            pendingDesign = storageJson.encodeToString(template)
            chooseDesignSource = true
        }
    }
    if (chooseDesignSource) {
        AlertDialog(
            onDismissRequest = { chooseDesignSource = false; pendingDesign = null },
            title = { Text("选择预览照片") },
            text = { Text("用自己的照片排版，也可以先在示例照片上设计水印。") },
            confirmButton = { TextButton({
                chooseDesignSource = false
                designPhoto.launch(arrayOf("*/*"))
            }) { Text("选择照片") } },
            dismissButton = { TextButton({
                val encoded = pendingDesign
                chooseDesignSource = false
                pendingDesign = null
                if (encoded != null) workspace.openSampleDesign(storageJson.decodeFromString<WatermarkTemplate>(encoded))
            }) { Text("使用示例照片") } },
        )
    }
    var presetToExport by rememberSaveable { mutableStateOf<String?>(null) }
    val presetExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PresetArchive.MIME)) { uri ->
        if (uri != null) presetToExport?.let { workspace.exportPreset(it, uri) }
        presetToExport = null
    }
    var editableToSave by rememberSaveable { mutableStateOf<String?>(null) }
    var embedPhoto by rememberSaveable { mutableStateOf(false) }
    val editableExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PresetArchive.MIME)) { uri ->
        val encoded = editableToSave
        editableToSave = null
        if (uri != null && encoded != null) {
            workspace.exportEditableFile(storageJson.decodeFromString<EditableFileSnapshot>(encoded), uri, embedPhoto)
        }
    }
    fun saveEditable(snapshot: EditableFileSnapshot, includePhoto: Boolean) {
        editableToSave = storageJson.encodeToString(snapshot)
        embedPhoto = includePhoto
        val title = if (includePhoto) snapshot.photo.stem else snapshot.template.title
        val stem = title.substringAfterLast('/').substringAfterLast('\\').trim().take(65).ifBlank { "光印" }
        editableExport.launch("$stem.kaiphoto")
    }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(workspace::writeDiagnostics)
    }

    val message = workspace.message
    LaunchedEffect(message) {
        if (message != null) {
            workspace.message = null
            snackbarScope.launch { snackbars.showSnackbar(message) }
        }
    }

    BackHandler(workspace.screen != Screen.LIBRARY || workspace.selected.isNotEmpty()) {
        when {
            workspace.screen == Screen.EXPORTS && workspace.selectedJob != null -> workspace.selectedJob = null
            workspace.screen != Screen.LIBRARY -> workspace.screen = Screen.LIBRARY
            else -> workspace.clearSelection()
        }
    }

    Scaffold(
        containerColor = Ground,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            if (workspace.screen != Screen.EDITOR) {
                NavigationBar(containerColor = Panel) {
                    NavigationBarItem(workspace.screen == Screen.LIBRARY, { workspace.screen = Screen.LIBRARY },
                        { Icon(Icons.Default.DateRange, null) }, label = { Text("相册") })
                    NavigationBarItem(workspace.screen == Screen.PRESETS, { workspace.screen = Screen.PRESETS },
                        { Icon(Icons.Default.FavoriteBorder, null) }, label = { Text("预设") })
                    NavigationBarItem(workspace.screen == Screen.EXPORTS, { workspace.screen = Screen.EXPORTS },
                        { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("导出") })
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (workspace.screen) {
                Screen.LIBRARY -> LibraryScreen(workspace, permissions.browse, pickPhotos,
                    { files.launch(arrayOf("*/*")) }, { folder.launch(null) }, { presetImport.launch(arrayOf("*/*")) })
                Screen.EDITOR -> EditorScreen(workspace, { logo.launch(arrayOf("image/*")) }, { font.launch(arrayOf("*/*")) },
                    permissions.readLocation, {
                        replacingPhoto = workspace.current?.id
                        selectOriginal.launch(arrayOf("*/*"))
                    }, ::saveEditable, workspace::enqueue)
                Screen.PRESETS -> PresetScreen(workspace, ::openDesign,
                    { presetImport.launch(arrayOf("*/*")) },
                    { preset -> presetToExport = preset.id; presetExport.launch("光印-${preset.name}.kaiphoto") })
                Screen.EXPORTS -> ExportScreen(workspace, permissions.enableNotifications) { diagnostics.launch("photomark-diagnostics.csv") }
            }
        }
    }
}

@Composable
internal fun AppHeading(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif, fontSize = 28.sp, color = Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Sage)
        }
        trailing()
    }
}

@Composable
private fun LibraryScreen(
    workspace: Workspace, browse: () -> Unit, pick: () -> Unit, files: () -> Unit, folder: () -> Unit, openArchive: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    val gallery = workspace.gallery
    val albums = gallery.map { it.primary.folder }.distinct().sorted()
    val filters = gallery.flatMap { listOfNotNull(it.primary.format.label, it.developed?.format?.label) }.distinct().sorted()
    val visible = gallery.filter { group ->
        (album == null || group.primary.folder == album) &&
            (filter == null || group.primary.format.label == filter || group.developed?.format?.label == filter)
    }

    Column(Modifier.fillMaxSize()) {
        AppHeading("光印", if (workspace.access == LibraryAccess.PARTIAL) "已授权的照片 · ${gallery.size} 张" else "KaiPrint Studio") {
            if (workspace.hasDraft) TextButton(workspace::reopen) { Text("继续编辑") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.Add, "导入选项", tint = Gold) }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("从系统相册选取") }, onClick = { more = false; pick() })
                    DropdownMenuItem(text = { Text("导入文件（含 RAW）") }, onClick = { more = false; files() })
                    DropdownMenuItem(text = { Text("导入文件夹") }, onClick = { more = false; folder() })
                    DropdownMenuItem(text = { Text("打开 .kaiphoto") }, onClick = { more = false; openArchive() })
                    if (BuildConfig.FLAVOR == "library") {
                        DropdownMenuItem(text = { Text(if (workspace.access == LibraryAccess.PARTIAL) "调整可访问的照片" else "管理相册权限") }, onClick = { more = false; browse() })
                        DropdownMenuItem(text = { Text("刷新相册") }, onClick = { more = false; workspace.refreshLibrary() })
                    }
                }
            }
        }
        if (gallery.isEmpty() && !workspace.loading) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(onClick = { workspace.openSampleDesign(Templates.gallery) }, color = Panel,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 250.dp).aspectRatio(1.56f)) {
                        Image(painterResource(R.drawable.studio_highlands), "苏格兰高地示例照片",
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,
                            Color.Black.copy(alpha = .72f)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("试用示例照片", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                            Text("Quiraing  /  Andrew Ridley", color = Ink.copy(alpha = .84f),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Ink,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp))
                    }
                }
                Text("照片工作台", style = MaterialTheme.typography.titleLarge, color = Ink,
                    modifier = Modifier.padding(top = 5.dp), fontWeight = FontWeight.SemiBold)
                Button(onClick = pick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(14.dp)) { Text("从相册选照片") }
                OutlinedButton(onClick = openArchive, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(13.dp)) { Text("打开 .kaiphoto") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = files) { Text("导入文件") }
                    TextButton(onClick = folder) { Text("导入文件夹") }
                }
                if (BuildConfig.FLAVOR == "library" && workspace.access != LibraryAccess.FULL) {
                    HorizontalDivider(color = Stroke)
                    TextButton(onClick = browse) { Text(if (workspace.access == LibraryAccess.PARTIAL) "调整可访问的照片" else "启用应用内相册", color = Sage) }
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (workspace.access == LibraryAccess.PARTIAL) item { TextButton(browse) { Text("调整可访问的照片") } }
                item { FilterChip(album == null, { album = null }, { Text("全部相册") }) }
                items(albums) { name -> FilterChip(album == name, { album = if (album == name) null else name },
                    { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
            if (filters.size > 1) LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(filters) { format ->
                    FilterChip(filter == format, { filter = if (filter == format) null else format }, { Text(format) })
                }
            }
            if (workspace.selected.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(workspace::clearSelection) { Icon(Icons.Default.Close, "清空选择") }
                Text("已选 ${workspace.selected.size} 张", Modifier.weight(1f), color = Ink)
                TextButton({ workspace.selectVisible(visible) }) { Text("全选") }
                Button(workspace::openSelection) { Text("编辑") }
            }
            val dateFormat = remember { SimpleDateFormat("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE) }
            val days = visible.groupBy {
                if (it.primary.timeMillis > 0) dateFormat.format(Date(it.primary.timeMillis)) else "导入的照片"
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(102.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for ((day, images) in days) {
                    item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                        Text(day, color = Muted, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    items(images, key = PhotoGroup::id) { photo ->
                        val checked = photo.id in workspace.selected
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).then(
                            if (checked) Modifier.border(2.dp, Gold, RoundedCornerShape(10.dp)) else Modifier
                        ).combinedClickable(
                            onClickLabel = if (workspace.selected.isEmpty()) "编辑照片" else "切换选择",
                            onLongClickLabel = "多选",
                            onLongClick = { workspace.toggle(photo) },
                            onClick = {
                                if (workspace.selected.isEmpty()) workspace.begin(listOf(photo)) else workspace.toggle(photo)
                            },
                        )) {
                            Thumbnail(workspace, photo, Modifier.fillMaxSize())
                            Text(photo.label, color = Ink, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                                    .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp))
                            if (checked) {
                                Icon(Icons.Default.Check, "已选中", tint = GoldDark,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(21.dp)
                                        .background(Gold, RoundedCornerShape(12.dp)).padding(3.dp))
                            }
                        }
                    }
                }
                if (workspace.hasMore || workspace.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (workspace.loading) CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                        else {
                            LaunchedEffect(gallery.lastOrNull()?.id) { workspace.more() }
                            TextButton(onClick = workspace::more) { Text("加载更多") }
                        }
                    }
                }
                if (visible.isEmpty() && !workspace.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("此筛选范围暂无照片", color = Muted, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun Thumbnail(workspace: Workspace, photo: PhotoGroup, modifier: Modifier = Modifier) {
    val result by produceState<android.graphics.Bitmap?>(null, photo, workspace.detailsGeneration) {
        value = null
        value = workspace.thumbnail(photo)
    }
    Box(modifier.background(Raised), contentAlignment = Alignment.Center) {
        result?.let {
            Image(it.asImageBitmap(), photo.primary.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Text(photo.primary.format.label, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PresetScreen(workspace: Workspace, openDesign: (WatermarkTemplate) -> Unit, importArchive: () -> Unit, export: (PresetRow) -> Unit) {
    val presets by workspace.presets.collectAsState()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Templates.Kind.ALL) }
    // Index new or changed rows off the UI thread. Filtering and thumbnails share the decoded template.
    val decoded by produceState<Map<PresetRow, WatermarkTemplate?>>(emptyMap(), presets) {
        val previous = value
        value = withContext(Dispatchers.Default) {
            presets.associateWith { row ->
                if (row in previous) previous[row] else
                    runCatching { storageJson.decodeFromString<WatermarkTemplate>(row.template) }.getOrNull()
            }
        }
    }
    val term = query.trim()
    val personal = remember(presets, decoded, term, filter) {
        presets.filter { row ->
            row.name.contains(term, ignoreCase = true) && row in decoded &&
                (filter == Templates.Kind.ALL || decoded[row]?.let(Templates::kind) == filter)
        }
    }
    val builtIns = remember(term, filter) {
        Templates.all.filter {
            it.title.contains(term, ignoreCase = true) && (filter == Templates.Kind.ALL || Templates.kind(it) == filter)
        }
    }
    var delete by remember { mutableStateOf<PresetRow?>(null) }
    Column(Modifier.fillMaxSize()) {
        AppHeading("水印预设", "内置 ${Templates.all.size} 款  ·  个人 ${presets.size} 款") { TextButton(importArchive) { Text("导入模板") } }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedButton(onClick = { openDesign(Templates.blank) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Text("空白创作", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索预设名称") },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }), trailingIcon = {
                        if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "清除搜索") }
                    })
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Templates.Kind.entries, key = { it.name }) { kind ->
                        FilterChip(filter == kind, { filter = kind }, { Text(kind.label) })
                    }
                }
            }
            if (personal.isEmpty() && builtIns.isEmpty() && decoded.keys.containsAll(presets)) item {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没有符合条件的预设", color = Muted)
                    TextButton({ query = ""; filter = Templates.Kind.ALL }) { Text("清除筛选") }
                }
            }
            if (personal.isNotEmpty()) item { Text("我的预设", style = MaterialTheme.typography.titleMedium) }
            items(personal, key = PresetRow::id) { preset ->
                var menu by remember { mutableStateOf(false) }
                val template = decoded[preset]
                Surface(onClick = {
                    if (template != null) openDesign(template) else workspace.message = "此预设暂时无法打开"
                }, shape = RoundedCornerShape(15.dp), color = Panel) {
                    Row(Modifier.fillMaxWidth().padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (template != null) PresetThumbnail(workspace, template)
                        Text(preset.name, Modifier.weight(1f).padding(start = 12.dp), color = Ink)
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "管理 ${preset.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("导出 .kaiphoto") }, onClick = { menu = false; export(preset) })
                                DropdownMenuItem(text = { Text("删除预设") }, onClick = { menu = false; delete = preset })
                            }
                        }
                    }
                }
            }
            if (builtIns.isNotEmpty()) item {
                Text("内置模板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(builtIns, key = WatermarkTemplate::id) { template ->
                Surface(onClick = { openDesign(template) }, shape = RoundedCornerShape(15.dp), color = Panel) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PresetThumbnail(workspace, template)
                        Text(template.title, color = Ink)
                    }
                }
            }
        }
    }
    delete?.let { preset ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除预设") },
            text = { Text("删除「${preset.name}」？") },
            confirmButton = { TextButton({ workspace.deletePreset(preset.id); delete = null }) { Text("删除", color = Danger) } },
            dismissButton = { TextButton({ delete = null }) { Text("取消") } })
    }
}

private val presetThumbnails = LruCache<WatermarkTemplate, Bitmap>(32)

@Composable
private fun PresetThumbnail(workspace: Workspace, template: WatermarkTemplate) {
    val bitmap by produceState(presetThumbnails.get(template), template) {
        value = presetThumbnails.get(template) ?: withContext(Dispatchers.Default) {
            try {
                workspace.app.renderer.sample(template, edge = 220).also { presetThumbnails.put(template, it) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
    Box(Modifier.size(78.dp, 56.dp).clip(RoundedCornerShape(5.dp)).background(Raised)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun ExportScreen(workspace: Workspace, enableNotifications: (() -> Unit)?, diagnostics: () -> Unit) {
    val jobs by workspace.jobs.collectAsState()
    val selected = jobs.firstOrNull { it.id == workspace.selectedJob }
    if (selected == null) {
        Column(Modifier.fillMaxSize()) {
            AppHeading("导出记录") {
                if (enableNotifications != null) TextButton(enableNotifications) { Text("开启进度通知") }
            }
            if (jobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("成片会显示在这里", color = Muted) }
            } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(jobs, key = ExportJobRow::id) { job ->
                    Surface(onClick = { workspace.selectedJob = job.id }, color = Panel, shape = RoundedCornerShape(13.dp)) {
                        Row(Modifier.fillMaxWidth().padding(17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(SimpleDateFormat("MM月dd日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(job.createdAt)), color = Ink)
                            Text(if (job.id == workspace.runningJob) "正在导出"
                            else if (job.state == RunState.FINISHED.name) "查看结果" else "可继续", color = Muted)
                        }
                    }
                }
            }
        }
        return
    }

    val flow = remember(selected.id) { workspace.items(selected.id) }
    val items by flow.collectAsState(initial = emptyList())
    val finished = items.count { it.state == ItemState.DONE.name }
    val failed = items.count { it.state == ItemState.FAILED.name }
    val running = workspace.runningJob == selected.id
    val hasPending = items.any { it.state == ItemState.WAITING.name || it.state == ItemState.RUNNING.name }
    val context = LocalContext.current
    val outputs = remember(items) {
        items.filter { it.state == ItemState.DONE.name }.mapNotNull { it.outputUri?.let(Uri::parse) }
    }
    var showShareGroups by remember(selected.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton({ workspace.selectedJob = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "导出记录") }
            Text("导出结果", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (items.isNotEmpty()) TextButton(diagnostics) { Text("保存诊断") }
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (running) "正在导出" else if (hasPending) "任务已暂停" else "任务结束", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (items.isEmpty()) 0f else (finished + failed) / items.size.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (enableNotifications != null && (running || hasPending)) {
                    TextButton(enableNotifications) { Text("开启进度通知") }
                }
                Text("已导出 $finished / ${items.size} 张" + if (failed > 0) " · $failed 张失败" else "", color = Muted)
                if (finished > 0) Text("成片位置 · Pictures/光印", color = Muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    when {
                        running -> OutlinedButton(workspace::pause) { Text("暂停") }
                        hasPending -> Button({ workspace.resume(selected.id) }, enabled = workspace.runningJob == null) { Text("继续导出") }
                        failed > 0 -> Button({ workspace.resume(selected.id, true) }, enabled = workspace.runningJob == null) { Text("重试失败项") }
                    }
                    if (outputs.isNotEmpty()) Box {
                        TextButton({
                            if (outputs.size <= MAX_SHARE) share(context, outputs) else showShareGroups = true
                        }) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Text(if (outputs.size <= MAX_SHARE) "分享" else "分组分享")
                        }
                        DropdownMenu(showShareGroups, { showShareGroups = false }) {
                            for (start in outputs.indices step MAX_SHARE) {
                                val end = minOf(start + MAX_SHARE, outputs.size)
                                val label = if (start + 1 == end) "第 ${end} 张" else "第 ${start + 1}–${end} 张"
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    showShareGroups = false
                                    share(context, outputs.subList(start, end))
                                })
                            }
                        }
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(items, key = { it.position }) { item ->
                Surface(color = Panel, shape = RoundedCornerShape(11.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.errorMessage?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
                        }
                        when (item.state) {
                            ItemState.DONE.name -> TextButton({
                                item.outputUri?.let {
                                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it), "image/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { context.startActivity(view) }
                                }
                            }) { Text("查看") }
                            ItemState.FAILED.name -> Text("失败", color = Danger)
                            ItemState.RUNNING.name -> Text(if (running) "处理中" else "等待恢复", color = Gold)
                            else -> Text("等待", color = Muted)
                        }
                    }
                }
            }
        }
    }
}

private fun share(context: android.content.Context, uris: List<Uri>) {
    val chooser = shareResults(context, uris) ?: return
    runCatching { context.startActivity(chooser) }
}
