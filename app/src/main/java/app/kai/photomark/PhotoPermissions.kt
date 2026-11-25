package app.kai.photomark

import android.Manifest
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Permissions for an in-app photo library. The system pickers grant access to individual URIs. */
internal fun libraryReadPermissions(sdk: Int): Array<String> = when {
    sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Picker URIs can share the media authority, but do not expose MediaStore's original-item contract. */
internal fun isIndexedPhoto(uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return false
    val path = uri.pathSegments
    return path.lastOrNull()?.toLongOrNull() != null &&
        ((path.size == 4 && path[1] == "images" && path[2] == "media") || (path.size == 3 && path[1] == "file"))
}

internal fun PhotoGroup.hasIndexedPhoto(): Boolean = isIndexedPhoto(Uri.parse(primary.uri)) ||
    developed?.let { isIndexedPhoto(Uri.parse(it.uri)) } == true

internal data class PhotoPermissionActions(
    val browse: () -> Unit, val readLocation: () -> Unit, val enableNotifications: (() -> Unit)?,
)

@Composable
internal fun photoPermissionActions(workspace: Workspace, pickPhotos: () -> Unit): PhotoPermissionActions {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val requests = remember(context) { context.getSharedPreferences("permissionRequests", 0) }
    val libraryPermissions = remember { libraryReadPermissions(Build.VERSION.SDK_INT) }
    val notifications = remember(context) { context.getSystemService(NotificationManager::class.java) }
    fun notificationsOn() = notifications.areNotificationsEnabled() &&
        notifications.getNotificationChannel(ExportService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    var notificationsEnabled by remember { mutableStateOf(notificationsOn()) }
    var settingsHelp by rememberSaveable { mutableStateOf<String?>(null) }

    fun previouslyDenied(permission: String) = requests.getBoolean(permission, false) &&
        activity?.shouldShowRequestPermissionRationale(permission) == false
    fun noteDenial(permission: String, denied: Boolean) {
        requests.edit().putBoolean(permission, denied).apply()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        workspace.refreshLibrary()
        val denied = workspace.access == LibraryAccess.NONE
        noteDenial(libraryPermissions.first(), denied)
        if (denied) workspace.message = "可以继续从系统相册选照片，或从文件导入。"
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = notificationsOn()
        noteDenial(Manifest.permission.POST_NOTIFICATIONS, !granted)
        if (!granted) workspace.message = "可在「导出记录」查看进度。"
    }
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = workspace.app.photos.locationAccess
        noteDenial(Manifest.permission.ACCESS_MEDIA_LOCATION, !granted)
        workspace.checkAccess()
        if (!granted) workspace.message = "可以手填拍摄地点，或让地点留空。"
    }
    LifecycleResumeEffect(workspace) {
        workspace.checkAccess()
        notificationsEnabled = notificationsOn()
        onPauseOrDispose { }
    }
    fun browse() {
        when {
            workspace.access == LibraryAccess.FULL -> settingsHelp = "library"
            workspace.access == LibraryAccess.NONE && previouslyDenied(libraryPermissions.first()) -> settingsHelp = "library"
            else -> permissions.launch(libraryPermissions)
        }
    }
    fun requestLocation() {
        if (BuildConfig.FLAVOR != "library" || workspace.app.photos.locationAccess) return
        if (workspace.access == LibraryAccess.PARTIAL) settingsHelp = "locationScope"
        else if (previouslyDenied(Manifest.permission.ACCESS_MEDIA_LOCATION)) settingsHelp = "location"
        else mediaLocation.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !previouslyDenied(Manifest.permission.POST_NOTIFICATIONS)) {
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else settingsHelp = "notifications"
    }

    settingsHelp?.let { feature ->
        val notice = when (feature) {
            "library" -> "可在系统设置中更改光印能浏览的照片。也可以只从系统相册选取这次要编辑的照片。"
            "location" -> "可在系统设置中允许读取照片内的拍摄地点，也可以从原文件读取或手填。"
            "locationScope" -> "系统会再次确认照片的访问范围，然后决定是否提供原始拍摄地点。可以保持部分授权。也可返回，改用文件选择器打开原片。"
            else -> "可在系统设置中启用导出进度通知，或继续在导出记录中查看。"
        }
        AlertDialog(onDismissRequest = { settingsHelp = null }, title = { Text("管理权限") },
            text = { Text(notice) },
            confirmButton = { TextButton({
                settingsHelp = null
                if (feature == "locationScope") {
                    mediaLocation.launch(libraryPermissions + Manifest.permission.ACCESS_MEDIA_LOCATION)
                    return@TextButton
                }
                val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                val intent = if (feature == "notifications") {
                    val channel = notifications.getNotificationChannel(ExportService.CHANNEL)
                    val notificationSettings = if (notifications.areNotificationsEnabled() && channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, ExportService.CHANNEL)
                    } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    notificationSettings.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else appSettings
                try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
                    try { context.startActivity(appSettings) } catch (_: android.content.ActivityNotFoundException) {
                        workspace.message = "请从设备设置的应用列表打开光印。"
                    }
                }
            }) { Text(if (feature == "locationScope") "选择系统授权范围" else "打开系统设置") } },
            dismissButton = { TextButton({
                settingsHelp = null
                if (feature == "library") pickPhotos()
            }) { Text(if (feature == "library") "只选照片" else "返回") } })
    }

    return PhotoPermissionActions(::browse, ::requestLocation, if (notificationsEnabled) null else ::requestNotifications)
}
