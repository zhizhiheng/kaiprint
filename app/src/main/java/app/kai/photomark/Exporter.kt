package app.kai.photomark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

private const val EXPORT_PATH = "Pictures/光印/"

class Exporter(private val app: PhotoMarkApp) {
    private val resolver = app.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val dao = app.database.dao()

    suspend fun exportItem(
        item: ExportItemRow,
        template: WatermarkTemplate,
        settings: ExportSettings,
    ) {
        val input = storageJson.decodeFromString<ExportInput>(item.input)
        val filename = resultName(item, input, settings.format)
        val existing = item.pendingUri?.let { uri -> pendingStatus(Uri.parse(uri))?.let { Uri.parse(uri) to it } }
            ?: findExisting(filename)
        if (existing != null && !existing.second) {
            dao.updateItem(item.copy(state = ItemState.DONE.name, outputUri = existing.first.toString(), pendingUri = null))
            return
        }

        var reservation = existing?.first
        val scratch = File(app.cacheDir, "export-${item.jobId}-${item.position}.${settings.format.extension}")
        var published = false
        val jobContext = currentCoroutineContext()
        try {
            val info = app.photos.info(PhotoGroup(input.photo, input.companion))
            val choice = selectPixels(info, input.pixelKind, settings)
            val layout = WatermarkLayout.calculate(choice.size, template.frame, settings.longEdge)
            jobContext.ensureActive()
            val bitmap = app.renderer.renderForExport(choice, layout, template, info.metadata, input.overrides,
                settings.colorSpace, settings.format) {
                jobContext.ensureActive()
            }
            try {
                encode(scratch, bitmap, settings, info.metadata)
            } finally {
                bitmap.recycle()
            }
            if (settings.keepMetadata && settings.format != OutputFormat.TIFF) writeExif(scratch, info.metadata, layout.canvas)
            jobContext.ensureActive()

            val record = reservation ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, settings.format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, EXPORT_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: throw ExportFailure("ALBUM", "无法在相册中创建成片")
            reservation = record
            dao.updateItem(item.copy(state = ItemState.RUNNING.name, pendingUri = record.toString()))
            resolver.openOutputStream(record, "wt")?.buffered()?.use { output ->
                scratch.inputStream().buffered().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        jobContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw ExportFailure("ALBUM", "无法写入相册")
            jobContext.ensureActive()
            withContext(NonCancellable) {
                val rows = resolver.update(record, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                if (rows != 1) throw ExportFailure("ALBUM", "相册未能发布成片")
                published = true
                dao.updateItem(item.copy(state = ItemState.DONE.name, pendingUri = null, outputUri = record.toString()))
            }
        } finally {
            scratch.delete()
            if (!published) reservation?.let { uri ->
                runCatching { if (pendingStatus(uri) == true) resolver.delete(uri, null, null) }
            }
        }
    }

    private fun pendingStatus(uri: Uri): Boolean? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) != 0 else null
        }
    }.getOrNull()

    private fun findExisting(filename: String): Pair<Uri, Boolean>? {
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val values = arrayOf(EXPORT_PATH, filename)
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, values)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, columns, args, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), columns, selection, values, null)
        }
        return cursor?.use {
            if (it.moveToFirst()) Uri.withAppendedPath(collection, it.getLong(0).toString()) to (it.getInt(1) != 0)
            else null
        }
    }

    private fun encode(file: File, bitmap: Bitmap, settings: ExportSettings, metadata: PhotoMetadata) {
        if (settings.format == OutputFormat.TIFF) {
            val success = RasterCodec.writeTiff(file, bitmap, settings.colorSpace, metadata.takeIf { settings.keepMetadata })
            if (!success) throw ExportFailure("ENCODE", "TIFF 编码失败")
            return
        }
        val compress = when (settings.format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            OutputFormat.TIFF -> error("TIFF 使用原生编码")
        }
        file.outputStream().buffered().use { stream ->
            if (!bitmap.compress(compress, settings.quality, stream)) throw ExportFailure("ENCODE", "图片编码失败")
        }
    }

    private fun writeExif(file: File, metadata: PhotoMetadata, size: PixelSize) {
        val exif = ExifInterface(file)
        for ((tag, raw) in metadata.exif) {
            val value = if (tag == ExifInterface.TAG_FOCAL_LENGTH && '/' !in raw) {
                raw.toDoubleOrNull()?.let { "${(it * 1000).toLong()}/1000" } ?: raw
            } else raw
            exif.setAttribute(tag, value)
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, size.width.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, size.height.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
        exif.saveAttributes()
    }

    private fun resultName(item: ExportItemRow, input: ExportInput, format: OutputFormat): String {
        val stem = input.photo.stem.replace(Regex("[^\\p{L}\\p{N}_.-]"), "_").take(48).ifBlank { "photo" }
        val position = String.format(Locale.ROOT, "%03d", item.position + 1)
        return "${stem}_${item.jobId.take(10)}_$position.${format.extension}"
    }
}

internal fun selectPixels(
    info: PhotoInfo,
    kind: PixelKind?,
    settings: ExportSettings,
): PixelChoice {
    val candidate = if (kind == null) info.choices.firstOrNull { it.kind != PixelKind.EMBEDDED }
        ?: info.choices.firstOrNull() else info.choices.firstOrNull { it.kind == kind }
    val choice = candidate ?: throw ExportFailure("SOURCE", "此文件缺少可用的像素来源")
    if (choice.kind == PixelKind.EMBEDDED && !settings.allowEmbedded) {
        throw ExportFailure("RAW_PREVIEW", "此文件只能使用内嵌预览，请允许按预览尺寸导出或导入伴随图")
    }
    if (choice.hdr && !settings.allowSdr) throw ExportFailure("HDR", "此照片需要允许转换为 SDR")
    return choice
}

class ExportFailure(val code: String, message: String) : IOException(message)

class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as PhotoMarkApp
    private val dao get() = app.database.dao()
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            work?.cancel()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(JOB_ID) ?: return START_NOT_STICKY
        if (work != null) return START_NOT_STICKY
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "照片导出", NotificationManager.IMPORTANCE_LOW))
        val serviceType = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            startForeground(NOTIFICATION_ID, notification("准备成片", 0, 0), serviceType)
        } catch (_: Exception) {
            app.activeExport.compareAndSet(id, null)
            stopSelf()
            return START_NOT_STICKY
        }
        app.activeExport.value = id
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) { runJob(id, intent.getBooleanExtra(RETRY_FAILURES, false)) }
            } finally {
                work = null
                app.activeExport.compareAndSet(id, null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        work?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runJob(id: String, retryFailures: Boolean) {
        try {
            val job = dao.job(id) ?: return
            val template = storageJson.decodeFromString<WatermarkTemplate>(job.template)
            val settings = storageJson.decodeFromString<ExportSettings>(job.settings)
            if (retryFailures) dao.resetFailures(id)
            dao.updateJobState(id, RunState.RUNNING.name)
            val items = dao.items(id)
            var completed = items.count { it.state in listOf(ItemState.DONE.name, ItemState.FAILED.name) }
            val exporter = Exporter(application as PhotoMarkApp)
            for (item in items.filter { it.state in listOf(ItemState.WAITING.name, ItemState.RUNNING.name) }) {
                currentCoroutineContext().ensureActive()
                notifications.notify(NOTIFICATION_ID, notification("正在导出照片", completed, items.size))
                val processing = item.copy(state = ItemState.RUNNING.name)
                dao.updateItem(processing)
                try {
                    exporter.exportItem(processing, template, settings)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val (code, message) = errorDetails(error)
                    dao.updateItem(item.copy(state = ItemState.FAILED.name, errorCode = code, errorMessage = message))
                } catch (_: OutOfMemoryError) {
                    dao.updateItem(item.copy(state = ItemState.FAILED.name, errorCode = "MEMORY",
                        errorMessage = "这张照片需要更多内存，可在导出设置中选择较小尺寸"))
                }
                completed++
            }
            dao.updateJobState(id, RunState.FINISHED.name)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { dao.updateJobState(id, RunState.PAUSED.name) }
            throw error
        } catch (_: Exception) {
            dao.updateJobState(id, RunState.PAUSED.name)
        }
    }

    private fun errorDetails(error: Exception): Pair<String, String> = when (error) {
        is ExportFailure -> error.code to error.message.orEmpty()
        is SecurityException -> "PERMISSION" to "无法访问源照片，请重新授权"
        is java.io.FileNotFoundException -> "SOURCE" to "找不到源照片或相册文件"
        is IOException -> when {
            error.message.orEmpty().contains("ENOSPC", true) -> "SPACE" to "设备存储空间不足"
            error.message.orEmpty().contains("尺寸") -> "SIZE" to error.message.orEmpty()
            else -> "IO" to "无法读取照片或写入成片"
        }
        else -> "PROCESSING" to "这张照片处理失败"
    }

    private fun notification(message: String, progress: Int, total: Int): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("光印")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentText(if (total == 0) message else "$message · $progress / $total")
            .setContentIntent(launch)
            .setProgress(total, progress, total == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "exports"
        private const val NOTIFICATION_ID = 10
        private const val PAUSE = "app.kai.photomark.PAUSE"
        private const val JOB_ID = "job_id"
        private const val RETRY_FAILURES = "retry_failures"

        fun start(context: Context, jobId: String, retryFailures: Boolean = false) {
            val active = (context.applicationContext as PhotoMarkApp).activeExport
            check(active.compareAndSet(null, jobId)) { "已有导出任务运行" }
            try {
                context.startForegroundService(Intent(context, ExportService::class.java).apply {
                    putExtra(JOB_ID, jobId)
                    putExtra(RETRY_FAILURES, retryFailures)
                })
            } catch (error: Exception) {
                active.compareAndSet(jobId, null)
                throw error
            }
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(PAUSE))
        }
    }
}
