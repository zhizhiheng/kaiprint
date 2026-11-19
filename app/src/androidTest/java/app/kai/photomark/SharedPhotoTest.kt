package app.kai.photomark

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedPhotoTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @OptIn(ExperimentalTestApi::class)
    @Test fun opensPhotosSharedFromAnotherAppAndExportsForXiaohongshu() {
        val tag = "SHARE-${System.currentTimeMillis()}"
        val photos = listOf(insertPhoto("$tag-A.jpg"), insertPhoto("$tag-B.jpg"))
        val sharedRoot = File(context.filesDir, "shared")
        try {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/jpeg")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntilAtLeastOneExists(hasText("编辑水印"), 20_000)
                compose.waitUntilAtLeastOneExists(hasContentDescription("$tag-B.jpg"), 30_000)
                val copies = sharedRoot.walkTopDown().filter { it.isFile && it.name.startsWith(tag) }.toList()
                assertEquals(2, copies.size)

                compose.onNodeWithText("小红书 3:4").performScrollTo().performClick()
                compose.onNodeWithText("导出").performClick()
                compose.onNodeWithText("导出 2 张").performClick()
                compose.waitUntilAtLeastOneExists(hasText("已导出 2 / 2 张"), 60_000)
            }
            val outputs = outputsFor(tag)
            assertEquals(2, outputs.size)
            for (output in outputs) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(output)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                assertEquals("canvas $output", bounds.outWidth * 4, bounds.outHeight * 3)
                assertTrue(bounds.outWidth >= 360)
            }
        } finally {
            (photos + outputsFor(tag)).forEach { runCatching { resolver.delete(it, null, null) } }
            sharedRoot.walkTopDown().filter { it.isFile && it.name.startsWith(tag) }.forEach { it.delete() }
        }
    }

    private fun insertPhoto(name: String): Uri {
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/光印测试/")
        }))
        val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(120, 90, 60))
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        resolver.openFileDescriptor(uri, "rw")!!.use { file ->
            ExifInterface(file.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_MAKE, "FUJIFILM")
                setAttribute(ExifInterface.TAG_MODEL, "X-T5")
                saveAttributes()
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun outputsFor(tag: String): List<Uri> = resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
        arrayOf("$tag%", "Pictures/光印/"), null)?.use { cursor ->
        buildList { while (cursor.moveToNext()) add(Uri.withAppendedPath(collection, cursor.getLong(0).toString())) }
    }.orEmpty()
}
