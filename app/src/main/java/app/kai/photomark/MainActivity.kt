package app.kai.photomark

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Ink = Color(0xFFF5F1EB)
internal val Muted = Color(0xFFB3B6C1)
internal val Ground = Color(0xFF101115)
internal val Panel = Color(0xFF1A1C23)
internal val Raised = Color(0xFF262831)
internal val Stroke = Color(0xFF3A3C47)
internal val Gold = Color(0xFFF4CE82)
internal val GoldDark = Color(0xFF2D210B)
internal val Danger = Color(0xFFFFA396)

class MainActivity : ComponentActivity() {
    private val workspace: Workspace by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated screen already holds the photos from its first launch.
        if (savedInstanceState == null) receive(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Gold, onPrimary = GoldDark,
                primaryContainer = Color(0xFF3C301B), onPrimaryContainer = Gold,
                secondary = Color(0xFFD1C4AA), onSecondary = GoldDark,
                secondaryContainer = Color(0xFF312B23), onSecondaryContainer = Gold,
                background = Ground, onBackground = Ink, surface = Panel, onSurface = Ink,
                surfaceVariant = Raised, onSurfaceVariant = Muted, outline = Stroke,
                outlineVariant = Stroke, error = Danger,
            )) {
                PhotoMark(workspace)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        intent?.getStringExtra(ExportService.SHOW_JOB)?.let { workspace.showJob(it) }
        workspace.receiveShared(sharedUris(intent))
    }
}

@Composable
private fun PhotoMark(workspace: Workspace) {
    val snackbars = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(150), workspace::importFiles)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), workspace::importFiles)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(workspace::importFolder)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        workspace.refreshLibrary()
    }
    val logo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(workspace::addLogo) }
    val font = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(workspace::addFont) }
    val presetImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(workspace::importPreset)
    }
    var presetToExport by remember { mutableStateOf<PresetRow?>(null) }
    val presetExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) presetToExport?.let { workspace.exportPreset(it, uri) }
        presetToExport = null
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(workspace::writeDiagnostics)
    }

    LifecycleResumeEffect(workspace) {
        workspace.checkAccess()
        onPauseOrDispose { }
    }
    val message = workspace.message
    LaunchedEffect(message) {
        if (message != null) {
            workspace.message = null
            snackbarScope.launch { snackbars.showSnackbar(message) }
        }
    }

    fun browse() {
        val request = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.launch(request)
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
                Screen.LIBRARY -> LibraryScreen(workspace, ::browse,
                    { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    { files.launch(arrayOf("*/*")) }, { folder.launch(null) })
                Screen.EDITOR -> EditorScreen(workspace, { logo.launch(arrayOf("image/*")) }, { font.launch(arrayOf("*/*")) }) { settings, all ->
                    workspace.enqueue(settings, all)
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Screen.PRESETS -> PresetScreen(workspace,
                    { presetImport.launch(arrayOf("application/zip", "application/octet-stream")) },
                    { preset -> presetToExport = preset; presetExport.launch("光印-${preset.name}.zip") })
                Screen.EXPORTS -> ExportScreen(workspace) { diagnostics.launch("photomark-diagnostics.csv") }
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
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, color = Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        trailing()
    }
}

@Composable
private fun LibraryScreen(
    workspace: Workspace, browse: () -> Unit, pick: () -> Unit, files: () -> Unit, folder: () -> Unit,
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
        AppHeading("光印", if (workspace.access == LibraryAccess.PARTIAL) "已授权的照片" else null) {
            if (workspace.hasDraft) TextButton(workspace::reopen) { Text("继续编辑") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.Add, "导入选项", tint = Gold) }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("从系统相册选取") }, onClick = { more = false; pick() })
                    DropdownMenuItem(text = { Text("导入文件（含 RAW）") }, onClick = { more = false; files() })
                    DropdownMenuItem(text = { Text("导入文件夹") }, onClick = { more = false; folder() })
                    if (BuildConfig.FLAVOR == "library") {
                        DropdownMenuItem(text = { Text("管理相册权限") }, onClick = { more = false; browse() })
                        DropdownMenuItem(text = { Text("刷新相册") }, onClick = { more = false; workspace.refreshLibrary() })
                    }
                }
            }
        }
        if (gallery.isEmpty() && !workspace.loading) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.kaiprint_mark), null,
                        Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)))
                    Text("先选几张照片", color = Ink, style = MaterialTheme.typography.headlineSmall)
                    Text("为一张照片调好样式，再用于整组。", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = pick, contentPadding = PaddingValues(horizontal = 27.dp, vertical = 13.dp)) {
                        Text("从相册选照片")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = files) { Text("导入文件") }
                        TextButton(onClick = folder) { Text("导入文件夹") }
                    }
                    if (BuildConfig.FLAVOR == "library" && workspace.access != LibraryAccess.FULL) {
                        OutlinedButton(onClick = browse) { Text("启用应用内相册") }
                    }
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
    val result by produceState<android.graphics.Bitmap?>(null, photo) {
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
private fun PresetScreen(workspace: Workspace, importArchive: () -> Unit, export: (PresetRow) -> Unit) {
    val presets by workspace.presets.collectAsState()
    var delete by remember { mutableStateOf<PresetRow?>(null) }
    Column(Modifier.fillMaxSize()) {
        AppHeading("水印预设") { TextButton(importArchive) { Text("导入预设包") } }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (presets.isNotEmpty()) item { Text("我的预设", style = MaterialTheme.typography.titleMedium) }
            items(presets, key = PresetRow::id) { preset ->
                var menu by remember { mutableStateOf(false) }
                Surface(onClick = { workspace.loadPreset(preset) }, shape = RoundedCornerShape(15.dp), color = Panel) {
                    Row(Modifier.fillMaxWidth().padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(preset.name, Modifier.weight(1f), color = Ink)
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "管理 ${preset.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("导出预设包") }, onClick = { menu = false; export(preset) })
                                DropdownMenuItem(text = { Text("删除预设") }, onClick = { menu = false; delete = preset })
                            }
                        }
                    }
                }
            }
            item { Text("内置模板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            items(Templates.all, key = WatermarkTemplate::id) { template ->
                val sample = Color(color(template.frame.color, 0xFF191A20.toInt()))
                val framed = template.frame != Frame()
                Surface(onClick = {
                    if (workspace.editing.isNotEmpty()) workspace.apply(template)
                    else workspace.message = "选照片后可使用「${template.title}」"
                }, shape = RoundedCornerShape(15.dp), color = Panel) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(55.dp, 44.dp).clip(RoundedCornerShape(6.dp)).background(if (framed) sample else Raised)
                            .padding(if (framed) 6.dp else 2.dp)
                            .background(if (framed) Raised else sample, RoundedCornerShape(3.dp)))
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

@Composable
private fun ExportScreen(workspace: Workspace, diagnostics: () -> Unit) {
    val jobs by workspace.jobs.collectAsState()
    val selected = jobs.firstOrNull { it.id == workspace.selectedJob }
    if (selected == null) {
        Column(Modifier.fillMaxSize()) {
            AppHeading("导出记录")
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
