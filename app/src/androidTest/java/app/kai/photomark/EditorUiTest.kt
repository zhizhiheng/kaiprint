package app.kai.photomark

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun selectsPhotoAppliesZrTemplateAndExportsFromEditor() {
        assumeTrue(BuildConfig.FLAVOR == "library")
        val activity = compose.activity
        val name = "UI-ZR-${System.currentTimeMillis()}.jpg"
        val resolver = activity.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/光印测试/")
        }))
        try {
            val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(70, 110, 155))
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            resolver.openFileDescriptor(uri, "rw")!!.use { file ->
                ExifInterface(file.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_MAKE, "NIKON CORPORATION")
                    setAttribute(ExifInterface.TAG_MODEL, "NIKON ZR")
                    saveAttributes()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            compose.onNodeWithContentDescription("导入选项").performClick()
            compose.onNodeWithText("刷新相册").performClick()
            compose.waitUntil(15_000) {
                runCatching {
                    compose.onNodeWithContentDescription(name).assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            compose.onNodeWithContentDescription(name).performClick()
            compose.waitUntilAtLeastOneExists(hasText("编辑水印"), 10_000)
            compose.onNodeWithTag("template-strip").performScrollToKey("zr")
            compose.onNodeWithText("ZR 电影卡").performClick()
            compose.waitUntil(15_000) {
                runCatching {
                    compose.onNodeWithContentDescription("水印效果预览").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            compose.onNodeWithText("标注").performClick()
            compose.waitUntilAtLeastOneExists(hasText("自动 · Nikon Z", substring = true), 10_000)
            compose.onNodeWithText("横版字标").performClick().assertIsSelected()
            compose.onNodeWithText("尼康黄底方标").performClick().assertIsSelected()
            compose.onNodeWithText("底边靠左", substring = true).performClick()
            compose.onNodeWithText("底边居中").performClick()
            compose.onNodeWithText("底边居中", substring = true).assertIsDisplayed()
            compose.onNodeWithText("镜头").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("自动 · Nikon Z", substring = true).performScrollTo().performClick()
            compose.onNodeWithText("Canon").performClick()
            compose.onNodeWithText("Canon", substring = true).assertIsDisplayed()
            compose.onNodeWithText("导出").performClick()
            compose.onNodeWithText("保存照片").performClick()
            compose.waitUntilAtLeastOneExists(hasText("已导出 1 / 1 张"), 30_000)
        } finally {
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf(name.substringBeforeLast('.') + "%"), null)?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0).toString()
                    resolver.delete(android.net.Uri.withAppendedPath(collection, id), null, null)
                }
            }
        }
    }
}
