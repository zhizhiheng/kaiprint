package app.kai.photomark

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class KaiPhotoProjectTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoMarkApp
    private val archive = PresetArchive(app)
    private val template = Templates.gallery.copy(title = "风景手记")
    private val overrides = mapOf(MetadataField.AUTHOR to "林凯", MetadataField.ISO to "")

    private fun photo(folder: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): PhotoSource {
        val name = when (format) {
            Bitmap.CompressFormat.PNG -> "湖.png"
            Bitmap.CompressFormat.JPEG -> "湖边的日出.JPG"
            else -> "湖.webp"
        }
        val file = File(folder, name)
        val bitmap = Bitmap.createBitmap(24, 20, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(folder.name.hashCode() or 0xff000000.toInt())
            file.outputStream().use { assertTrue(bitmap.compress(format, 94, it)) }
        } finally {
            bitmap.recycle()
        }
        return PhotoSource(Uri.fromFile(file).toString(), name, "样图", folder.path)
    }

    private fun original(project: ImportedPreset): File = File(Uri.parse(requireNotNull(project.photo).uri).path!!)

    private fun withDirectory(test: (File) -> Unit) {
        val root = File(app.cacheDir, "project-test-${java.util.UUID.randomUUID()}")
        check(root.mkdirs())
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun projectKeepsSingleOriginalBytesAndEmptyOverridesAndDeduplicatesStorage() = withDirectory { temp ->
        val pack = File(temp, "project.kaiphoto")
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
            val source = photo(temp, format)
            archive.writeProject(EditableFileSnapshot(template, source, overrides), Uri.fromFile(pack))
            ZipFile(pack).use { zip ->
                val header = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertEquals(2, header.getInt("formatVersion"))
                assertEquals("project", header.getString("kind"))
                val info = header.getJSONObject("photo")
                assertEquals(source.name, info.getString("name"))
                assertTrue(info.getString("sha256").matches(Regex("[a-f0-9]{64}")))
                assertArrayEquals(File(temp, source.name).readBytes(), zip.getInputStream(zip.getEntry(info.getString("path"))).readBytes())
            }
            val first = archive.read(Uri.fromFile(pack))
            try {
                assertEquals(template, first.template)
                assertEquals(overrides, first.overrides)
                val again = archive.read(Uri.fromFile(pack))
                try {
                    assertArrayEquals(File(temp, source.name).readBytes(), original(again).readBytes())
                    assertEquals(original(first), original(again))
                } finally { archive.discard(again) }
                assertTrue(original(first).isFile)
            } finally { archive.discard(first) }
            assertFalse(original(first).exists())
        }
    }

    @Test fun corruptOrIncompleteProjectDoesNotInstallAnyOriginalsOrAssets() = withDirectory { temp ->
        val scratch = File(temp, "valid.kaiphoto")
        archive.writeProject(EditableFileSnapshot(template, photo(temp), overrides), Uri.fromFile(scratch))
        val entries = ZipFile(scratch).use { zip ->
            zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }
        }
        val header = entries.getValue("manifest.json").decodeToString()
        val document = JSONObject(header)
        val descriptor = document.getJSONObject("photo")
        val path = descriptor.getString("path")
        val root = File(app.filesDir, "projects")
        val before = root.list().orEmpty().toSet()
        val count = descriptor.getLong("bytes")
        val invalidHeaders = listOf(
            header.replace(descriptor.getString("sha256"), "0".repeat(64)),
            header.replace("\"bytes\":$count", "\"bytes\":${count + 1}"),
            header.replace("湖边的日出.JPG", "../photo.jpg"),
            header.replace("\"AUTHOR\":\"林凯\"", "\"UNKNOWN\":\"林凯\""),
            header.replace("\"AUTHOR\":\"林凯\"", "\"AUTHOR\":true"),
            header.replace("\"ISO\":\"\"", "\"ISO\":\"${"a".repeat(121)}\""),
        )
        val invalidPacks = invalidHeaders.map { entries + ("manifest.json" to it.encodeToByteArray()) } + listOf(
            entries - path,
            entries + ("photo/original.png" to ByteArray(4)),
            entries + (path to ByteArray(count.toInt())),
            entries + ("template.json" to storageJson.encodeToString(template.copy(layers = listOf(
                WatermarkLayer("missing-logo", LayerKind.LOGO, logoFile = "missing.png")))).encodeToByteArray()),
        )
        val broken = File(temp, "invalid.kaiphoto")
        for (variant in invalidPacks) {
            ZipOutputStream(broken.outputStream()).use { out ->
                for ((name, contents) in variant.entries.reversed()) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(contents)
                    out.closeEntry()
                }
            }
            assertThrows(Exception::class.java) { archive.read(Uri.fromFile(broken)) }
            assertEquals(before, root.list().orEmpty().toSet())
        }
    }

    @Test fun importedProjectOpensInEditorWithItsOwnPhotoAndPerPhotoData() = withDirectory { temp ->
        val prefs = app.getSharedPreferences("workspace", 0)
        val old = prefs.getString("edit", null)
        val workspace = compose.runOnIdle { ViewModelProvider(compose.activity)[Workspace::class.java] }
        val pack = File(temp, "editor.kaiphoto")
        val source = photo(temp)
        val external = template.copy(id = java.util.UUID.randomUUID().toString())
        val personal = external.copy(title = "本地作品")
        val dao = app.database.dao()
        val row = PresetRow(personal.id, personal.title, storageJson.encodeToString(personal), System.currentTimeMillis())
        runBlocking { dao.upsertPreset(row) }
        archive.writeProject(EditableFileSnapshot(external, source, overrides), Uri.fromFile(pack))
        var importedFile: File? = null
        try {
            compose.runOnIdle { workspace.importPreset(Uri.fromFile(pack)) }
            compose.waitUntil(20_000) { workspace.screen == Screen.EDITOR && workspace.draft.template.title == template.title }
            val reopened = compose.runOnIdle { requireNotNull(workspace.editableFileSnapshot()) }
            assertEquals(source.name, reopened.photo.name)
            assertEquals(external.id, reopened.template.id)
            assertFalse(compose.runOnIdle { workspace.canUpdateLibraryPreset(external.id) })
            compose.runOnIdle { workspace.updatePreset(row) }
            assertEquals(row, runBlocking { dao.preset(personal.id) })
            assertEquals(template.layers, reopened.template.layers)
            assertEquals(overrides, reopened.overrides)
            importedFile = File(Uri.parse(reopened.photo.uri).path!!)
            assertArrayEquals(File(temp, source.name).readBytes(), importedFile.readBytes())
            compose.waitUntil(5_000) { prefs.getString("edit", null)?.contains(reopened.photo.uri) == true }
        } finally {
            prefs.edit().putString("edit", old).commit()
            importedFile?.parentFile?.deleteRecursively()
            runBlocking { dao.deletePreset(personal.id) }
        }
    }

    @Test fun readCliAndBrowserProjectsAndExportAndroidProjectWhenFixturesAvailable() {
        if (InstrumentationRegistry.getArguments().getString("kaiPhotoProjectInterop") != "true") return
        val folder = File(app.filesDir, "test-fixtures")
        for (name in listOf("cli-project", "cli-nef", "web-project")) {
            val result = archive.read(Uri.fromFile(File(folder, "$name.kaiphoto")))
            try {
                val embedded = requireNotNull(result.photo)
                assertEquals(if (name == "cli-nef") PhotoFormat.NEF else PhotoFormat.JPEG, embedded.format)
                assertEquals(4, result.template.layers.size)
                assertEquals("", result.overrides[MetadataField.ISO])
                assertEquals("跨端作品", result.overrides[MetadataField.TITLE])
                assertEquals(if (name == "web-project") "网页签名" else "林凯", result.overrides[MetadataField.AUTHOR])
                if (name == "web-project") {
                    val adjusted = result.overrides + (MetadataField.AUTHOR to "安卓签名")
                    archive.writeProject(EditableFileSnapshot(result.template, embedded, adjusted),
                        Uri.fromFile(File(folder, "android-project.kaiphoto")))
                }
            } finally { archive.discard(result) }
        }
    }
}
