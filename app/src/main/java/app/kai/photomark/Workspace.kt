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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class Screen { LIBRARY, EDITOR, PRESETS, EXPORTS }

@Serializable
private data class SavedEdit(
    val photos: List<ExportInput>,
    val draft: EditDraft,
    val active: Int,
    val savedPreset: WatermarkTemplate? = null,
)

class Workspace(application: Application) : AndroidViewModel(application) {
    val app = application as PhotoMarkApp
    private val dao = app.database.dao()
    private val prefs = app.getSharedPreferences("workspace", 0)
    private class PhotoCache {
        val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
        val details = LruCache<PhotoGroup, PhotoInfo>(48)
    }
    @Volatile private var photoCache = PhotoCache()
    var locationAccess by mutableStateOf(app.photos.locationAccess)
        private set
    private val undo = ArrayDeque<EditDraft>()
    private val redo = ArrayDeque<EditDraft>()
    private val presetWhenOpened = mutableMapOf<String, WatermarkTemplate>()
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

    private fun invalidatePhotos() {
        // In-flight reads can finish only into the detached cache.
        photoCache = PhotoCache()
        detailsGeneration++
    }

    fun checkAccess() {
        // Partial access can select a different set while the grant itself stays unchanged.
        val now = app.photos.permissionState()
        if (now == access && now != LibraryAccess.PARTIAL && locationAccess == app.photos.locationAccess &&
            current?.let { it.primary.uri.startsWith("content:") || it.developed?.uri?.startsWith("content:") == true } == true) {
            // A document provider or the system picker may revoke its individual URI independently.
            invalidatePhotos()
        }
        if (now != access || now != LibraryAccess.NONE || locationAccess != app.photos.locationAccess) refreshLibrary()
    }

    fun refreshLibrary() {
        val scope = app.photos.permissionState()
        val location = app.photos.locationAccess
        if (scope != access || scope == LibraryAccess.PARTIAL || location != locationAccess) {
            invalidatePhotos()
            if (scope != access || scope == LibraryAccess.PARTIAL) {
                device = emptyList()
                gallery = emptyList()
                hasMore = false
            }
        }
        access = scope
        locationAccess = location
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

    fun importFiles(uris: List<Uri>) = importForDesign(uris, Templates.gallery)

    fun importForDesign(uris: List<Uri>, template: WatermarkTemplate) {
        if (uris.isEmpty()) return
        importSources(template) {
            uris.mapNotNull { uri ->
                persist(uri)
                runCatching { app.photos.openDocument(uri) }.getOrNull()
            }
        }
    }

    /** Replace just the selected source. Late picker or provider responses must never target another photo. */
    fun replaceCurrentPhoto(expectedId: String, uri: Uri) {
        if (current?.id != expectedId) return
        val session = editing
        viewModelScope.launch {
            val replacement = capture("无法打开选择的原片，已保留当前照片") { withContext(Dispatchers.IO) {
                val source = app.photos.openDocument(uri) ?: throw IOException("无法识别这张照片")
                val group = PhotoGroup(source)
                app.photos.info(group)
                group
            } } ?: return@launch
            if (editing !== session || current?.id != expectedId || screen != Screen.EDITOR) return@launch
            if (editing.any { it.id != expectedId && it.id == replacement.id }) {
                message = "所选原片已在这组照片中，已保留当前照片"
                return@launch
            }
            persist(uri)
            fun retarget(state: EditDraft): EditDraft {
                val previous = state.overrides[expectedId]
                val values = if (previous == null) state.overrides else (state.overrides - expectedId) + (replacement.id to previous)
                return state.copy(overrides = values, pixelChoices = state.pixelChoices - expectedId)
            }
            draft = retarget(draft)
            for (i in undo.indices) undo[i] = retarget(undo[i])
            for (i in redo.indices) redo[i] = retarget(redo[i])
            adjustmentStart = adjustmentStart?.let(::retarget)
            editing = editing.toMutableList().also { it[activeIndex] = replacement }
            ProjectPhotoStore.remember(this@Workspace, editing)
            imported = (listOf(replacement.primary) + imported).distinctBy(PhotoSource::uri)
            invalidatePhotos()
            saveSession()
            mergeGallery(galleryGeneration)
        }
    }

    fun openSampleDesign(template: WatermarkTemplate) {
        val origin = screen
        val session = editing
        viewModelScope.launch {
            val source = capture("无法打开示例照片") { withContext(Dispatchers.IO) { studioPhoto() } } ?: return@launch
            if (screen == origin && editing === session) begin(listOf(PhotoGroup(source)), template)
        }
    }

    private fun studioPhoto(): PhotoSource {
        val directory = File(app.filesDir, "studio").also { it.mkdirs() }
        val photo = File(directory, "高地 · 示例照片.jpg")
        if (!photo.isFile) {
            val temporary = File.createTempFile("sample-", ".jpg", directory)
            try {
                val image = Uri.parse("android.resource://${app.packageName}/${R.drawable.studio_highlands}")
                val stream = app.contentResolver.openInputStream(image) ?: throw IOException("示例照片无法读取")
                stream.use { raw -> temporary.outputStream().use { output -> raw.copyTo(output) } }
                ExifInterface(temporary).apply {
                    setAttribute(ExifInterface.TAG_ARTIST, "Andrew Ridley")
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "KaiPrint layout sample. Camera and exposure values are illustrative.")
                    setAttribute(ExifInterface.TAG_MAKE, "NIKON CORPORATION")
                    setAttribute(ExifInterface.TAG_MODEL, "NIKON ZR")
                    setAttribute(ExifInterface.TAG_LENS_MODEL, "NIKKOR Z 24-70mm f/4 S")
                    setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "50/1")
                    setAttribute(ExifInterface.TAG_F_NUMBER, "4/1")
                    setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/125")
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:20 17:42:10")
                    saveAttributes()
                }
                Files.move(temporary.toPath(), photo.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
        return PhotoSource(Uri.fromFile(photo).toString(), photo.name, "创作示例", directory.path, mime = "image/jpeg")
    }

    fun importFolder(uri: Uri) {
        importSources {
            persist(uri)
            app.photos.importFolder(uri)
        }
    }

    private fun importSources(template: WatermarkTemplate = Templates.gallery, read: () -> List<PhotoSource>) {
        viewModelScope.launch {
            val sources = capture("导入失败，请检查文件访问权限") { withContext(Dispatchers.IO) { read() } }
                ?: return@launch
            showImported(sources, "所选位置没有可导入的照片", template)
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

    private suspend fun showImported(sources: List<PhotoSource>, empty: String, template: WatermarkTemplate = Templates.gallery) {
        if (sources.isEmpty()) {
            message = empty
            return
        }
        imported = (sources + imported).distinctBy(PhotoSource::uri)
        val groups = withContext(Dispatchers.IO) { app.photos.groups(sources) }
        begin(groups, template)
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

    fun begin(groups: List<PhotoGroup>, template: WatermarkTemplate = Templates.gallery,
              overrides: Map<String, Map<MetadataField, String>> = emptyMap(), libraryPreset: Boolean = true) {
        if (groups.isEmpty()) return
        editing = groups.distinctBy(PhotoGroup::id)
        ProjectPhotoStore.remember(this, editing)
        activeIndex = 0
        draft = EditDraft(template, overrides)
        presetWhenOpened.clear()
        if (libraryPreset) rememberPreset(template)
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
            ProjectPhotoStore.remember(this, editing)
            activeIndex = saved.active.coerceIn(0, maxOf(0, editing.lastIndex))
            draft = saved.draft.copy(template = Templates.upgradeLegacyZr(saved.draft.template))
            saved.savedPreset?.let(::rememberPreset)
        }
        if (editing.isNotEmpty()) screen = Screen.EDITOR
    }

    fun changePhoto(index: Int) {
        activeIndex = index.coerceIn(0, maxOf(0, editing.lastIndex))
        saveSession()
    }

    fun canUpdateLibraryPreset(id: String) = id in presetWhenOpened

    private fun rememberPreset(template: WatermarkTemplate) {
        if (template.id != Templates.blank.id && Templates.all.none { it.id == template.id }) {
            presetWhenOpened[template.id] = template
        }
    }

    fun apply(template: WatermarkTemplate) {
        rememberPreset(template)
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
        if (atLayerLimit()) return
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

    fun duplicateLayer(source: WatermarkLayer) {
        if (atLayerLimit()) return
        val layers = draft.template.layers.toMutableList()
        val index = layers.indexOfFirst { it.id == source.id }
        if (index < 0) return
        val copy = layers[index].copy(id = id())
        layers.add(index + 1, copy)
        edit(draft.copy(template = draft.template.copy(layers = layers)))
        activeLayer = copy.id
    }

    private fun atLayerLimit(): Boolean {
        if (draft.template.layers.size < 24) return false
        message = "每个模板最多保存 24 个图层"
        return true
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
        val session = SavedEdit(editing.map(::input), draft, activeIndex, presetWhenOpened[draft.template.id])
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
            capture("无法导入此 .kaiphoto 文件") {
                val archive = PresetArchive(app)
                var staged: ImportedPreset? = null
                var adopted = false
                val imported = try {
                    // Capture installed resources even if returning from IO is interrupted.
                    withContext(Dispatchers.IO) { staged = archive.read(uri) }
                    val document = requireNotNull(staged)
                    val photo = document.photo
                    if (photo == null) {
                        withContext(NonCancellable) {
                            val template = document.template
                            dao.upsertPreset(PresetRow(template.id, template.title, storageJson.encodeToString(template), System.currentTimeMillis()))
                            adopted = true
                        }
                    } else {
                        begin(listOf(PhotoGroup(photo)), document.template, mapOf(photo.uri to document.overrides), libraryPreset = false)
                        ProjectPhotoStore.adopted(requireNotNull(document.photoLease))
                        adopted = true
                        message = "已打开作品「${document.template.title}」"
                        val keep = referencedSources()
                        withContext(Dispatchers.IO) { ProjectPhotoStore.prune(app, keep) }
                        return@capture
                    }
                    document
                } finally {
                    if (!adopted) staged?.let { document ->
                        withContext(NonCancellable + Dispatchers.IO) { archive.discard(document) }
                    }
                }
                val template = imported.template
                val missing = listOfNotNull(
                    imported.conversion,
                    "${imported.missingFonts} 个字体改用默认字体".takeIf { imported.missingFonts > 0 },
                    "${imported.missingLogos} 个 Logo 缺失".takeIf { imported.missingLogos > 0 },
                )
                message = "已导入预设「${template.title}」" + if (missing.isEmpty()) "" else "，" + missing.joinToString("，")
                screen = Screen.PRESETS
            }
        }
    }

    fun editableFileSnapshot(): EditableFileSnapshot? = current?.let { photo ->
        EditableFileSnapshot(draft.template, photo.primary, draft.overrides[photo.id].orEmpty())
    }

    fun exportEditableFile(snapshot: EditableFileSnapshot, uri: Uri, includePhoto: Boolean) {
        viewModelScope.launch {
            capture("无法保存 .kaiphoto 文件，请检查可用空间及照片访问权限") {
                withContext(Dispatchers.IO) {
                    val archive = PresetArchive(app)
                    if (includePhoto) archive.writeProject(snapshot, uri)
                    else {
                        val preview = app.renderer.sample(snapshot.template)
                        try { archive.write(snapshot.template, uri, preview) } finally { preview.recycle() }
                    }
                }
                message = if (includePhoto) "已保存含原照的作品" else "已保存纯模板"
            }
        }
    }

    fun exportPreset(presetId: String, uri: Uri) {
        viewModelScope.launch {
            capture("无法保存预设包") {
                withContext(Dispatchers.IO) {
                    val preset = dao.preset(presetId) ?: throw PresetFormatException("这个预设已被删除")
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
        val source = draft.template
        val photos = editing
        val template = source.copy(id = id(), title = title)
        viewModelScope.launch {
            capture("保存预设失败") {
                dao.upsertPreset(PresetRow(template.id, title, storageJson.encodeToString(template), System.currentTimeMillis()))
                if (editing === photos && draft.template === source) {
                    presetWhenOpened[template.id] = template
                    draft = draft.copy(template = template)
                    saveSession()
                }
                message = "已保存预设「$title」"
            }
        }
    }

    fun updatePreset(row: PresetRow) {
        val template = draft.template
        if (row.id != template.id || Templates.all.any { it.id == row.id } || row.id == Templates.blank.id) return
        val opened = presetWhenOpened[row.id] ?: return
        val photos = editing
        viewModelScope.launch {
            capture("更新预设失败") {
                val latest = storageJson.decodeFromString<WatermarkTemplate>(row.template)
                if (opened != latest || dao.updatePreset(row.id, row.template,
                        storageJson.encodeToString(template), System.currentTimeMillis()) == 0) {
                    message = "预设已更改或删除，可以把当前设计另存为新预设"
                } else {
                    if (editing === photos && presetWhenOpened[row.id] == opened) {
                        presetWhenOpened[row.id] = template
                        saveSession()
                    }
                    message = "已更新预设「${row.name}」"
                }
            }
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { capture("无法删除预设") { dao.deletePreset(id) } }
    }

    suspend fun thumbnail(group: PhotoGroup): Bitmap? = withContext(Dispatchers.IO) {
        val key = group.developed?.uri ?: group.id
        val thumbs = photoCache.thumbs
        thumbs.get(key) ?: runCatching { app.photos.thumbnail(group, 360) }.getOrNull()?.also { thumbs.put(key, it) }
    }

    suspend fun info(group: PhotoGroup): PhotoInfo = withContext(Dispatchers.IO) {
        val details = photoCache.details
        details.get(group) ?: app.photos.info(group).also { details.put(group, it) }
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
        } catch (error: PresetFormatException) {
            this.message = error.message ?: message
            null
        } catch (_: Exception) {
            this.message = message
            null
        }

    private fun id() = UUID.randomUUID().toString()

    companion object {
        private const val PAGE_SIZE = 120
    }
}
