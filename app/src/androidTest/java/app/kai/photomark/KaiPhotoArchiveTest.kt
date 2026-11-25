package app.kai.photomark

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class KaiPhotoArchiveTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp
    private val archives = PresetArchive(app)
    private val template = Templates.modelPlate.copy(title = "城市 · 旅拍",
        captions = mapOf(MetadataField.AUTHOR to "林凯"), place = PlaceStyle.CITY)
    private val json = storageJson.encodeToString(template)
    private val manifest = """{"format":"kaiphoto","formatVersion":1,"template":"template.json"}"""

    private fun bundle(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.writeEntry(name, content.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun assertImports(file: File): WatermarkTemplate {
        val actual = archives.read(Uri.fromFile(file)).template
        assertNotEquals(template.id, actual.id)
        assertEquals(template, actual.copy(id = template.id))
        return actual
    }

    @Test fun nativePackageDeclaresItsFormatAndPreservesTheEditableLayers() {
        val file = File.createTempFile("export-", ".kaiphoto", app.cacheDir)
        try {
            archives.write(template, Uri.fromFile(file))
            ZipFile(file).use { zip ->
                val header = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                val fields = JSONObject(header)
                assertEquals("kaiphoto", fields.getString("format"))
                assertEquals(1, fields.getInt("formatVersion"))
                assertEquals("template.json", fields.getString("template"))
                val encoded = zip.getInputStream(zip.getEntry("template.json")).bufferedReader().use { it.readText() }
                assertEquals(template, storageJson.decodeFromString<WatermarkTemplate>(encoded))
            }
            assertImports(file)
            val args = InstrumentationRegistry.getArguments()
            val sampleDir = File(app.filesDir, "preset-gallery")
            if (args.getString("exportKaiPhoto") == "true") {
                sampleDir.mkdirs()
                file.copyTo(File(sampleDir, "native-roundtrip.kaiphoto"), overwrite = true)
            }
            args.getString("cliKaiPhotoTitle")?.let { expectedTitle ->
                val imported = archives.read(Uri.fromFile(File(sampleDir, "cli-roundtrip.kaiphoto")))
                assertEquals(expectedTitle, imported.template.title)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun detectsLegacyArchiveAndStandaloneJsonFromTheirContents() {
        val file = File.createTempFile("external-", ".data", app.cacheDir)
        try {
            bundle(file, "template.json" to json)
            val fromZip = assertImports(file)
            file.writeText(" \n" + json)
            val fromJson = assertImports(file)
            assertNotEquals(fromZip.id, fromJson.id)
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsUnsupportedOrIncompleteNativeManifestsWithoutLegacyFallback() {
        val file = File.createTempFile("external-", ".zip", app.cacheDir)
        try {
            for (invalid in listOf(
                manifest.replace("\"kaiphoto\"", "\"another-format\""),
                manifest.replace("\"formatVersion\":1", "\"formatVersion\":2"),
                manifest.replace("\"formatVersion\":1,", ""),
                manifest.replace("\"template\":\"template.json\"", "\"template\":\"missing.json\""),
                "{}",
            )) {
                bundle(file, "template.json" to json, "manifest.json" to invalid)
                assertThrows(invalid, Exception::class.java) { archives.read(Uri.fromFile(file)) }
            }
            bundle(file, "manifest.json" to manifest)
            assertThrows(Exception::class.java) { archives.read(Uri.fromFile(file)) }
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsDuplicateEntries() {
        val file = File.createTempFile("duplicate-", ".kaiphoto", app.cacheDir)
        try {
            bundle(file, "manifest.json" to manifest, "template.json" to json, "README.md" to "a", "Readme.md" to "b")
            // ZipOutputStream requires distinct names. Rename the second entry in both directory records.
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            file.writeBytes(bytes.replace("Readme.md", "README.md").toByteArray(Charsets.ISO_8859_1))
            assertThrows(Exception::class.java) { archives.read(Uri.fromFile(file)) }
        } finally {
            file.delete()
        }
    }

    @Test fun enforcesParameterCountAndPositionLimitsOnNativeLayers() {
        val file = File.createTempFile("layer-limits-", ".kaiphoto", app.cacheDir)
        val valid = WatermarkLayer("row", LayerKind.EXIF, fields = MetadataField.entries.toList(), x = 1f)
        fun withLayer(layer: WatermarkLayer) {
            bundle(file, "manifest.json" to manifest,
                "template.json" to storageJson.encodeToString(template.copy(layers = listOf(layer))))
        }
        try {
            withLayer(valid)
            assertEquals(valid, archives.read(Uri.fromFile(file)).template.layers.single())
            for (invalid in listOf(valid.copy(fields = valid.fields + MetadataField.ISO), valid.copy(x = 1.01f))) {
                withLayer(invalid)
                assertThrows(Exception::class.java) { archives.read(Uri.fromFile(file)) }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun requiresDecodablePixelsForPackagedLogos() {
        val file = File.createTempFile("logo-", ".kaiphoto", app.cacheDir)
        val logo = WatermarkLayer("logo", LayerKind.LOGO, logoFile = "custom.png")
        val withLogo = storageJson.encodeToString(template.copy(layers = listOf(logo)))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(Color.GREEN)
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        fun withPng(bytes: ByteArray) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.writeEntry("manifest.json", manifest.toByteArray())
                zip.writeEntry("template.json", withLogo.toByteArray())
                zip.writeEntry("assets/custom.png", bytes)
            }
        }
        try {
            // A complete PNG signature and IHDR identify pixel dimensions but carry no image data.
            withPng(png.copyOf(33))
            assertThrows(Exception::class.java) { archives.read(Uri.fromFile(file)) }
            withPng(png)
            val imported = archives.read(Uri.fromFile(file))
            val saved = File(File(app.filesDir, "logos"), requireNotNull(imported.template.layers.single().logoFile))
            try {
                assertArrayEquals(png, saved.readBytes())
            } finally {
                archives.discard(imported)
            }
            assertFalse(saved.exists())
        } finally {
            file.delete()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun openWithImportsTheContentUriAndShowsTheSavedPreset() {
        val title = "导入测试-${UUID.randomUUID()}"
        val mime = "application/vnd.kaiprint.kaiphoto+zip"
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(app.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.kaiphoto")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/光印测试/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            archives.write(template.copy(title = title), uri)
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(app.packageName)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val handler = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(MainActivity::class.java.name, handler?.activityInfo?.name)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntilAtLeastOneExists(hasText(title), 20_000)
                val row = runBlocking { app.database.dao().observePresets().first() }.single { it.name == title }
                assertEquals(template.layers, storageJson.decodeFromString<WatermarkTemplate>(row.template).layers)
            }
        } finally {
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM PresetRow WHERE name = ?", arrayOf(title))
            app.contentResolver.delete(uri, null, null)
        }
    }
}
