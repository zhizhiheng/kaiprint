package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WebCatalogTest {
    @Test fun webAndAndroidShipTheSameEditablePresets() {
        val web = File("../web/assets/presets.json")
        val catalog = if (web.isFile) web else File("web/assets/presets.json")
        val expected = Templates.all.map { storageJson.encodeToJsonElement(WatermarkTemplate.serializer(), it) }
        if (System.getenv("KAIPRINT_UPDATE_WEB_CATALOG") == "1") {
            catalog.writeText(expected.joinToString(",\n", "[\n", "\n]\n") { "  $it" })
        }
        assertEquals(expected, storageJson.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(catalog.readText()))
    }
}
