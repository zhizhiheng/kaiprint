package app.kai.photomark

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CreativeStudioTest {
    // A first launch offers the sample; a returning user gets their existing draft and undo history.
    @get:Rule(order = 0) val freshDraft = object : ExternalResource() {
        private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("workspace", 0)
        private var previous: String? = null
        override fun before() {
            previous = prefs.getString("edit", null)
            prefs.edit().remove("edit").commit()
        }
        override fun after() {
            prefs.edit().putString("edit", previous).commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun blankStudioCopiesLayersAndCanUpdateOrForkAPreset() {
        val workspace = compose.runOnIdle { ViewModelProvider(compose.activity)[Workspace::class.java] }
        val dao = workspace.app.database.dao()
        val name = "原创-" + UUID.randomUUID().toString().take(8)
        val ids = mutableListOf<String>()
        try {
            compose.onNodeWithText("预设").performClick()
            compose.onNodeWithText("空白创作").performClick()
            compose.onNodeWithText("使用示例照片").performClick()
            compose.waitUntilAtLeastOneExists(hasText("编辑水印"), 20_000)
            val sample = compose.runOnIdle { requireNotNull(workspace.current) }
            assertEquals(File(workspace.app.filesDir, "studio/示例照片.jpg").path, android.net.Uri.parse(sample.id).path)
            assertTrue(runBlocking { workspace.info(sample) }.metadata.values[MetadataField.MODEL].orEmpty().contains("ZR"))

            compose.runOnIdle { assertEquals(Templates.blank, workspace.draft.template) }
            compose.onNodeWithText("添加图层").performScrollTo().performClick()
            compose.onNodeWithText("文字").performClick()
            compose.onNodeWithText("撤销").performClick()
            compose.runOnIdle { assertTrue(workspace.draft.template.layers.isEmpty()) }
            compose.onNodeWithText("重做").performClick()
            compose.onNodeWithText("复制图层").performScrollTo().performClick()
            compose.runOnIdle {
                val (original, duplicate) = workspace.draft.template.layers
                assertNotEquals(original.id, duplicate.id)
                assertEquals(original.copy(id = duplicate.id), duplicate)
                assertEquals(duplicate.id, workspace.activeLayer)
            }

            compose.onNodeWithText("保存为预设").performScrollTo().performClick()
            namePreset(name)
            compose.waitUntilAtLeastOneExists(hasText("更新此预设"), 10_000)
            val originalId = compose.runOnIdle { workspace.draft.template.id }.also(ids::add)
            compose.runOnIdle { workspace.caption(MetadataField.AUTHOR, "河岸") }
            compose.onNodeWithText("更新此预设").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                runBlocking { dao.preset(originalId) }?.let { row ->
                    storageJson.decodeFromString<WatermarkTemplate>(row.template).captions[MetadataField.AUTHOR] == "河岸"
                } == true
            }

            compose.onNodeWithText("另存为").performScrollTo().performClick()
            namePreset("$name-副本")
            compose.waitUntil(10_000) { workspace.draft.template.id != originalId }
            val copyId = compose.runOnIdle { workspace.draft.template.id }.also(ids::add)
            runBlocking {
                assertEquals(name, dao.preset(originalId)?.name)
                assertEquals("$name-副本", dao.preset(copyId)?.name)
            }
            compose.onNodeWithContentDescription("返回相册").performClick()
            compose.onNodeWithText("预设").performClick()
            val search = compose.onNode(hasSetTextAction() and hasText("搜索预设名称"))
            search.performTextInput(name)
            search.performImeAction()
            compose.waitUntilAtLeastOneExists(hasContentDescription("管理 $name"), 10_000)
            compose.onNodeWithText("边框").performClick()
            compose.waitUntilAtLeastOneExists(hasText("没有符合条件的预设"), 10_000)
            compose.onNodeWithText("清除筛选").performClick()
            search.performTextReplacement("胶片")
            search.performImeAction()
            compose.waitUntilAtLeastOneExists(hasText("胶片边码"), 10_000)
        } finally {
            runBlocking { ids.forEach { dao.deletePreset(it) } }
        }
    }

    @Test fun staleOriginalCannotOverwriteOrResurrectSavedDesign() {
        val workspace = compose.runOnIdle { ViewModelProvider(compose.activity)[Workspace::class.java] }
        val dao = workspace.app.database.dao()
        val original = Templates.filmRebate.copy(id = UUID.randomUUID().toString(), title = "并发编辑")
        val row = PresetRow(original.id, original.title, storageJson.encodeToString(original), 0)
        val latest = row.copy(template = storageJson.encodeToString(original.copy(captions = mapOf(MetadataField.AUTHOR to "先保存"))))
        runBlocking { dao.upsertPreset(row) }
        try {
            compose.runOnIdle {
                workspace.openSampleDesign(original)
            }
            compose.waitUntilAtLeastOneExists(hasText("编辑水印"), 20_000)
            compose.runOnIdle { workspace.caption(MetadataField.AUTHOR, "较早草稿") }
            runBlocking { dao.upsertPreset(latest) }
            compose.runOnIdle { workspace.updatePreset(latest) }
            compose.waitUntilAtLeastOneExists(hasText("预设已更改或删除，可以把当前设计另存为新预设"), 10_000)
            // An update using a stale DB snapshot must also fail atomically.
            runBlocking {
                assertEquals(0, dao.updatePreset(original.id, row.template, row.template, 1))
                assertEquals(latest.template, dao.preset(original.id)?.template)
                dao.deletePreset(original.id)
                assertEquals(0, dao.updatePreset(original.id, latest.template, row.template, 2))
            }
        } finally {
            runBlocking { dao.deletePreset(original.id) }
        }
    }

    private fun namePreset(name: String) {
        compose.onNode(hasSetTextAction() and hasText("预设名称")).performTextInput(name)
        compose.onNodeWithText("保存").performClick()
    }
}
