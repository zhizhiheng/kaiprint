package app.kai.photomark

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.UUID

enum class Screen { LIBRARY, EDITOR, PRESETS, EXPORTS }

@Serializable
private data class SavedEdit(
    val photos: List<ExportInput>,
    val draft: EditDraft,
    val active: Int,
)

class Workspace(application: Application) : AndroidViewModel(application) {
    val app = application as PhotoMarkApp
    private val dao = app.database.dao()
    private val prefs = app.getSharedPreferences("workspace", 0)
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val details = LruCache<PhotoGroup, PhotoInfo>(48)
    private val undo = ArrayDeque<EditDraft>()
    private val redo = ArrayDeque<EditDraft>()
    private var device = emptyList<PhotoSource>()
    private var imported = emptyList<PhotoSource>()
    private var galleryGeneration = 0
    private var draftStoreGeneration = 0
    private var adjustmentStart: EditDraft? = null

    var screen by mutableStateOf(Screen.LIBRARY)
    var access by mutableStateOf(app.photos.permissionState())
        private set
    var gallery by mutableStateOf(emptyList<PhotoGroup>())
        private set
    var selected by mutableStateOf(emptySet<String>())
        private set
    var editing by mutableStateOf(emptyList<PhotoGroup>())
        private set
    var activeIndex by mutableStateOf(0)
        private set
    var draft by mutableStateOf(EditDraft(Templates.gallery))
        private set
    var activeLayer by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var hasDraft by mutableStateOf(prefs.contains("edit"))
        private set
    var message by mutableStateOf<String?>(null)
    var selectedJob by mutableStateOf<String?>(null)
    var runningJob by mutableStateOf(app.activeExport.value)
        private set
    var startInProgress by mutableStateOf(false)
        private set
    var parameterSets by mutableStateOf(
        runCatching {
            prefs.getString("parameterSets", null)?.let { storageJson.decodeFromString<List<ParameterSet>>(it) }
        }.getOrNull().orEmpty(),
    )
        private set
    /** The last export choices. Consent to use a RAW preview or convert HDR is asked for again each time. */
    var exportSettings by mutableStateOf(
        runCatching { prefs.getString("export", null)?.let { storageJson.decodeFromString<ExportSettings>(it) } }
            .getOrNull()?.copy(allowEmbedded = false, allowSdr = ExportSettings().allowSdr) ?: ExportSettings(),
    )
        private set

    /** Bumped when photo details must be read again, such as after location access is granted. */
    var detailsGeneration by mutableStateOf(0)
        private set

    val presets: StateFlow<List<PresetRow>> = dao.observePresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs: StateFlow<List<ExportJobRow>> = dao.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun items(id: String) = dao.observeItems(id)
    val current get() = editing.getOrNull(activeIndex)

    init {
        refreshLibrary()
        viewModelScope.launch {
            app.activeExport.collect { runningJob = it }
        }
    }

    fun checkAccess() {
        if (access != app.photos.permissionState() || access != LibraryAccess.NONE) refreshLibrary()
    }

    fun refreshLibrary() {
        access = app.photos.permissionState()
        val generation = ++galleryGeneration
        if (access == LibraryAccess.NONE) {
            device = emptyList()
            hasMore = false
        }
        loading = true
        viewModelScope.launch {
            val page = capture("无法读取相册") { withContext(Dispatchers.IO) { app.photos.libraryPage(0, PAGE_SIZE) } }
            if (generation != galleryGeneration) return@launch
            device = page.orEmpty()
            hasMore = device.size == PAGE_SIZE
            mergeGallery(generation)
            loading = false
        }
    }

    fun more() {
        if (loading || !hasMore) return
        loading = true
        val generation = galleryGeneration
        val offset = device.size
        viewModelScope.launch {
            val next = capture("无法继续读取相册") {
                withContext(Dispatchers.IO) { app.photos.libraryPage(offset, PAGE_SIZE) }
            }.orEmpty()
            if (generation != galleryGeneration) return@launch
            device = device + next
            hasMore = next.size == PAGE_SIZE
            mergeGallery(generation)
            loading = false
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importSources {
            uris.mapNotNull { uri ->
                persist(uri)
                runCatching { app.photos.openDocument(uri) }.getOrNull()
            }
        }
    }

    fun importFolder(uri: Uri) {
        importSources {
            persist(uri)
            app.photos.importFolder(uri)
        }
    }

    private fun importSources(read: () -> List<PhotoSource>) {
        viewModelScope.launch {
            val sources = capture("导入失败，请检查文件访问权限") { withContext(Dispatchers.IO) { read() } }
                ?: return@launch
            showImported(sources, "所选位置没有可导入的照片")
        }
    }

    /** Opens photos another app shared with KaiPrint in the editor. */
    fun receiveShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val accepted = uris.take(MAX_SHARED_PHOTOS)
        val open = editing.flatMap { listOfNotNull(it.primary.uri, it.developed?.uri) }.toSet()
        if (accepted.size >= 10) message = "正在打开分享的 ${accepted.size} 张照片"
        viewModelScope.launch {
            val root = File(app.filesDir, "shared")
            val sources = capture("无法读取分享的照片，请检查存储空间") {
                val keep = open + referencedSources()
                val (removed, copied) = withContext(Dispatchers.IO) {
                    pruneShared(root, keep) to app.copyShared(accepted, File(root, id()))
                }
                imported = imported.filterNot { it.uri in removed }
                copied
            } ?: return@launch
            if (uris.size > accepted.size) message = "一次最多接收 $MAX_SHARED_PHOTOS 张，已打开前 $MAX_SHARED_PHOTOS 张"
            showImported(sources, "分享的文件里没有可识别的照片")
        }
    }

    /** Source photos that an open edit or an unfinished export still needs. */
    private suspend fun referencedSources(): Set<String> = withContext(Dispatchers.IO) {
        val saved = runCatching {
            prefs.getString("edit", null)?.let { storageJson.decodeFromString<SavedEdit>(it).photos }
        }.getOrNull().orEmpty()
        val pending = dao.unfinishedInputs().mapNotNull {
            runCatching { storageJson.decodeFromString<ExportInput>(it) }.getOrNull()
        }
        (saved + pending).flatMap { listOfNotNull(it.photo.uri, it.companion?.uri) }.toSet()
    }

    private suspend fun showImported(sources: List<PhotoSource>, empty: String) {
        if (sources.isEmpty()) {
            message = empty
            return
        }
        imported = (sources + imported).distinctBy(PhotoSource::uri)
        val groups = withContext(Dispatchers.IO) { app.photos.groups(sources) }
        begin(groups)
        mergeGallery(galleryGeneration)
    }

    private suspend fun mergeGallery(generation: Int) {
        val sources = imported + device
        val groups = capture("无法整理照片") { withContext(Dispatchers.IO) { app.photos.groups(sources) } } ?: return
        if (generation == galleryGeneration) {
            gallery = groups
            selected = selected.mapNotNull { id ->
                groups.firstOrNull { it.id == id || it.developed?.uri == id }?.id
            }.toSet()
        }
    }

    private fun persist(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun toggle(source: PhotoGroup) {
        selected = if (source.id in selected) selected - source.id else selected + source.id
    }

    fun selectVisible(visible: List<PhotoGroup>) {
        selected = visible.mapTo(mutableSetOf()) { it.id }
    }

    fun clearSelection() { selected = emptySet() }

    fun openSelection() {
        begin(gallery.filter { it.id in selected })
        clearSelection()
    }

    fun begin(groups: List<PhotoGroup>) {
        if (groups.isEmpty()) return
        editing = groups.distinctBy(PhotoGroup::id)
        activeIndex = 0
        draft = EditDraft(Templates.gallery)
        activeLayer = null
        undo.clear()
        redo.clear()
        adjustmentStart = null
        updateHistory()
        screen = Screen.EDITOR
        saveSession()
    }

    fun reopen() {
        if (editing.isEmpty()) {
            val saved = runCatching {
                prefs.getString("edit", null)?.let { storageJson.decodeFromString<SavedEdit>(it) }
            }.getOrNull() ?: return
            editing = saved.photos.map { PhotoGroup(it.photo, it.companion) }
            activeIndex = saved.active.coerceIn(0, maxOf(0, editing.lastIndex))
            draft = saved.draft.copy(template = Templates.upgradeLegacyZr(saved.draft.template))
        }
        if (editing.isNotEmpty()) screen = Screen.EDITOR
    }

    fun changePhoto(index: Int) {
        activeIndex = index.coerceIn(0, maxOf(0, editing.lastIndex))
        saveSession()
    }

    fun apply(template: WatermarkTemplate) {
        // Built-in templates carry no canvas ratio, so switching between them keeps the chosen post shape.
        edit(draft.copy(template = Templates.adopt(template, draft.template)))
        activeLayer = null
        if (editing.isNotEmpty()) screen = Screen.EDITOR
    }

    fun frame(frame: Frame, live: Boolean = false) = edit(draft.copy(template = draft.template.copy(frame = frame)), live)

    fun caption(field: MetadataField, value: String) {
        val template = draft.template
        val captions = if (value.isEmpty()) template.captions - field else template.captions + (field to value.take(120))
        edit(draft.copy(template = template.copy(captions = captions)))
    }

    fun place(style: PlaceStyle) = edit(draft.copy(template = draft.template.copy(place = style)))

    fun dateStyle(style: DateStyle) = edit(draft.copy(template = draft.template.copy(dateStyle = style)))

    fun layer(layer: WatermarkLayer, live: Boolean = false) {
        val layers = draft.template.layers.map { if (it.id == layer.id) layer else it }
        edit(draft.copy(template = draft.template.copy(layers = layers)), live)
    }

    fun finishAdjustment() {
        val previous = adjustmentStart ?: return
        adjustmentStart = null
        if (previous != draft) {
            rememberUndo(previous)
            saveSession()
        }
    }

    fun addLayer(kind: LayerKind, logo: String? = null) {
        if (draft.template.layers.size >= 24) {
            message = "每个模板最多保存 24 个图层"
            return
        }
        val layer = when (kind) {
            LayerKind.TEXT -> WatermarkLayer(id(), kind, "摄影师署名", type = TypeStyle.SERIF, shadow = true)
            LayerKind.EXIF -> WatermarkLayer(id(), kind,
                fields = listOf(MetadataField.MODEL, MetadataField.APERTURE, MetadataField.SHUTTER, MetadataField.ISO),
                anchor = Anchor.BOTTOM_LEFT, type = TypeStyle.MONO, shadow = true)
            LayerKind.BRAND -> WatermarkLayer(id(), kind, size = .16f, anchor = Anchor.BOTTOM_LEFT,
                brandTone = BrandTone.LIGHT)
            LayerKind.LOGO -> WatermarkLayer(id(), kind, logoFile = logo, size = .16f)
            LayerKind.LINE -> WatermarkLayer(id(), kind, size = .16f, anchor = Anchor.BOTTOM_LEFT)
            LayerKind.PALETTE -> WatermarkLayer(id(), kind, size = .38f, anchor = Anchor.BOTTOM_RIGHT)
        }
        edit(draft.copy(template = draft.template.copy(layers = draft.template.layers + layer)))
        activeLayer = layer.id
    }

    fun deleteLayer(id: String) {
        edit(draft.copy(template = draft.template.copy(layers = draft.template.layers.filterNot { it.id == id })))
        if (activeLayer == id) activeLayer = null
    }

    fun reorderLayer(id: String, direction: Int) {
        val layers = draft.template.layers.toMutableList()
        val from = layers.indexOfFirst { it.id == id }
        if (from == -1 || from + direction !in layers.indices) return
        val target = layers.removeAt(from)
        layers.add(from + direction, target)
        edit(draft.copy(template = draft.template.copy(layers = layers)))
    }

    fun updateOverrides(photo: PhotoGroup, values: Map<MetadataField, String>) {
        val overrides = draft.overrides.toMutableMap()
        if (values.isEmpty()) overrides.remove(photo.id) else overrides[photo.id] = values
        edit(draft.copy(overrides = overrides))
    }

    /** Writes the given fields onto every photo in the edit, keeping each photo's other changes. */
    fun overrideAll(values: Map<MetadataField, String>) {
        if (values.isEmpty()) return
        edit(draft.withOverrides(editing.map(PhotoGroup::id), values))
        message = "已写入 ${editing.size} 张照片"
    }

    fun applyParameterSet(set: ParameterSet, photos: List<PhotoGroup>) {
        edit(draft.withOverrides(photos.map(PhotoGroup::id), set.values))
        message = if (photos.size == 1) "已套用「${set.name}」" else "已为 ${photos.size} 张照片套用「${set.name}」"
    }

    fun saveParameterSet(name: String, values: Map<MetadataField, String>) {
        val title = name.trim().take(30)
        if (title.isBlank() || values.isEmpty()) return
        val existing = parameterSets.filterNot { it.name == title }
        if (existing.size >= ParameterSet.LIMIT) {
            message = "最多保存 ${ParameterSet.LIMIT} 个参数组"
            return
        }
        storeParameterSets(listOf(ParameterSet(id(), title, values)) + existing)
        message = "已保存参数组「$title」"
    }

    fun deleteParameterSet(id: String) = storeParameterSets(parameterSets.filterNot { it.id == id })

    private fun storeParameterSets(sets: List<ParameterSet>) {
        parameterSets = sets
        prefs.edit().putString("parameterSets", storageJson.encodeToString(sets)).apply()
    }

    fun choosePixels(photo: PhotoGroup, kind: PixelKind) {
        edit(draft.copy(pixelChoices = draft.pixelChoices + (photo.id to kind)))
    }

    private fun edit(next: EditDraft, live: Boolean = false) {
        if (live) {
            if (draft != next && adjustmentStart == null) adjustmentStart = draft
            draft = next
            return
        }
        finishAdjustment()
        if (draft == next) return
        rememberUndo(draft)
        draft = next
        saveSession()
    }

    private fun rememberUndo(previous: EditDraft) {
        undo.addLast(previous)
        if (undo.size > 30) undo.removeFirst()
        redo.clear()
        updateHistory()
    }

    fun undo() {
        finishAdjustment()
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(draft)
        draft = previous
        updateHistory()
        saveSession()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(draft)
        draft = next
        updateHistory()
        saveSession()
    }

    private fun updateHistory() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
    }

    private fun input(group: PhotoGroup) = ExportInput(
        group.primary, group.developed, draft.pixelChoices[group.id], draft.overrides[group.id].orEmpty(),
    )

    private fun saveSession() {
        val session = SavedEdit(editing.map(::input), draft, activeIndex)
        val generation = ++draftStoreGeneration
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) { storageJson.encodeToString(session) }
            if (generation == draftStoreGeneration) {
                prefs.edit().putString("edit", encoded).apply()
                hasDraft = true
            }
        }
    }

    fun addLogo(uri: Uri) {
        viewModelScope.launch {
            val file = capture("无法打开这个 Logo 文件") {
                withContext(Dispatchers.IO) {
                    val directory = File(app.filesDir, "logos")
                    directory.mkdirs()
                    val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
                    val svg = name.endsWith(".svg", true) || app.contentResolver.getType(uri) == "image/svg+xml"
                    val bitmap = if (svg) {
                        app.contentResolver.openInputStream(uri)?.use(::renderSvg) ?: throw IOException("无法打开 SVG")
                    } else {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val size = PixelSize(info.size.width, info.size.height).fit(1600)
                            decoder.setTargetSize(size.width, size.height)
                        }
                    }
                    val file = File(directory, "${id()}.png")
                    try {
                        file.outputStream().use {
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Logo 编码失败")
                        }
                    } catch (error: Exception) {
                        file.delete()
                        throw error
                    } finally {
                        bitmap.recycle()
                    }
                    file
                }
            } ?: return@launch
            addLayer(LayerKind.LOGO, file.name)
        }
    }

    fun addFont(uri: Uri) {
        val layerId = activeLayer ?: return
        viewModelScope.launch {
            val file = capture("无法读取字体，请选 TTF 或 OTF 文件") {
                withContext(Dispatchers.IO) {
                    val directory = File(app.filesDir, "fonts").apply { mkdirs() }
                    val scratch = File.createTempFile("font-", ".tmp", app.cacheDir)
                    try {
                        val copied = app.contentResolver.openInputStream(uri) ?: throw IOException("字体不可用")
                        copied.use { source ->
                            scratch.outputStream().use { source.copyBounded(it, 16 * 1024 * 1024) }
                        }
                        val magic = scratch.inputStream().use { stream -> ByteArray(4).also { stream.read(it) } }
                        val extension = when {
                            String(magic, Charsets.US_ASCII) == "OTTO" -> "otf"
                            magic.contentEquals(byteArrayOf(0, 1, 0, 0)) -> "ttf"
                            else -> throw IOException("字体格式不支持")
                        }
                        requireNotNull(android.graphics.Typeface.Builder(scratch).build())
                        scratch.copyTo(File(directory, "${id()}.$extension"))
                    } finally {
                        scratch.delete()
                    }
                }
            } ?: return@launch
            val layer = draft.template.layers.firstOrNull { it.id == layerId }
            if (layer == null) file.delete() else layer(layer.copy(fontFile = file.name))
        }
    }

    fun importPreset(uri: Uri) {
        viewModelScope.launch {
            capture("无法导入此预设包") {
                val imported = withContext(Dispatchers.IO) { PresetArchive(app).read(uri) }
                val template = imported.template
                dao.upsertPreset(PresetRow(template.id, template.title, storageJson.encodeToString(template), System.currentTimeMillis()))
                val missing = listOfNotNull(
                    "${imported.missingFonts} 个字体改用默认字体".takeIf { imported.missingFonts > 0 },
                    "${imported.missingLogos} 个 Logo 缺失".takeIf { imported.missingLogos > 0 },
                )
                message = "已导入预设「${template.title}」" + if (missing.isEmpty()) "" else "，" + missing.joinToString("，")
            }
        }
    }

    fun exportPreset(preset: PresetRow, uri: Uri) {
        viewModelScope.launch {
            capture("无法保存预设包") {
                withContext(Dispatchers.IO) {
                    val template = storageJson.decodeFromString<WatermarkTemplate>(preset.template)
                    val preview = runCatching { app.renderer.sample(template) }.getOrNull()
                    try {
                        PresetArchive(app).write(template, uri, preview)
                    } finally {
                        preview?.recycle()
                    }
                }
                message = "预设包已保存"
            }
        }
    }

    fun savePreset(name: String) {
        val title = name.trim().take(60)
        if (title.isBlank()) return
        val template = draft.template.copy(id = id(), title = title)
        viewModelScope.launch {
            capture("保存预设失败") {
                dao.upsertPreset(PresetRow(template.id, title, storageJson.encodeToString(template), System.currentTimeMillis()))
                message = "已保存预设「$title」"
            }
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { capture("无法删除预设") { dao.deletePreset(id) } }
    }

    fun loadPreset(preset: PresetRow) {
        val template = runCatching { storageJson.decodeFromString<WatermarkTemplate>(preset.template) }.getOrNull()
        if (template == null) {
            message = "此预设暂时无法打开"
        } else if (editing.isNotEmpty()) {
            apply(template)
        } else {
            message = "选照片后可使用「${preset.name}」"
        }
    }

    suspend fun thumbnail(group: PhotoGroup): Bitmap? = withContext(Dispatchers.IO) {
        val key = group.developed?.uri ?: group.id
        thumbs.get(key) ?: runCatching { app.photos.thumbnail(group, 360) }.getOrNull()?.also { thumbs.put(key, it) }
    }

    suspend fun info(group: PhotoGroup): PhotoInfo = withContext(Dispatchers.IO) {
        details.get(group) ?: app.photos.info(group).also { details.put(group, it) }
    }

    fun locationGranted() {
        details.evictAll()
        detailsGeneration++
    }

    /** Checks up to [limit] photos of the edit the way the exporter will. */
    suspend fun exportSummary(settings: ExportSettings, all: Boolean, limit: Int = 300): ExportSummary {
        val photos = if (all) editing else listOfNotNull(current)
        val template = draft.template
        val choices = draft.pixelChoices
        val overrides = draft.overrides
        val results = photos.take(limit).map { group ->
            val info = runCatching { info(group) }
            info.getOrNull()?.metadata to info.mapCatching {
                planExport(it, choices[group.id], template, settings, overrides[group.id].orEmpty(), android.os.Build.VERSION.SDK_INT)
            }
        }
        return ExportSummary.of(results, photos.size)
    }

    fun enqueue(settings: ExportSettings, all: Boolean) {
        if (startInProgress || runningJob != null || editing.isEmpty()) return
        exportSettings = settings
        prefs.edit().putString("export", storageJson.encodeToString(settings)).apply()
        val images = if (all) editing else listOfNotNull(current)
        val id = id()
        val template = draft.template
        val inputs = images.map(::input)
        startInProgress = true
        viewModelScope.launch {
            try {
                capture("无法创建导出任务，请保留应用在前台") {
                    app.database.withTransaction {
                        dao.insertJob(ExportJobRow(id, System.currentTimeMillis(),
                            storageJson.encodeToString(template), storageJson.encodeToString(settings)))
                        dao.insertItems(inputs.mapIndexed { index, input ->
                            ExportItemRow(id, index, storageJson.encodeToString(input), input.photo.name, input.photo.format.label)
                        })
                    }
                    selectedJob = id
                    screen = Screen.EXPORTS
                    ExportService.start(app, id)
                }
            } finally {
                startInProgress = false
            }
        }
    }

    fun resume(id: String, retry: Boolean = false) {
        if (runningJob != null) return
        try {
            ExportService.start(app, id, retry)
        } catch (_: Exception) {
            message = "无法继续导出，请保持应用在前台后重试"
        }
    }

    fun pause() { ExportService.pause(app) }

    fun showJob(id: String) {
        selectedJob = id
        screen = Screen.EXPORTS
    }

    fun writeDiagnostics(uri: Uri) {
        val id = selectedJob ?: return
        viewModelScope.launch {
            capture("诊断记录保存失败") {
                withContext(Dispatchers.IO) {
                    val text = dao.items(id).joinToString("\n", prefix = "format,state,error\n") {
                        "${it.format},${it.state},${it.errorCode.orEmpty()}"
                    }
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                        ?: throw IOException("无法写入文件")
                }
                message = "已保存诊断记录"
            }
        }
    }

    private suspend fun <T> capture(message: String, action: suspend () -> T): T? =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            this.message = message
            null
        }

    private fun id() = UUID.randomUUID().toString()

    companion object {
        private const val PAGE_SIZE = 120
    }
}
