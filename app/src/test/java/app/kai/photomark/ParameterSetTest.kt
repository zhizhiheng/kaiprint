package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ParameterSetTest {
    private val roll = mapOf(
        MetadataField.MAKE to "Leica", MetadataField.MODEL to "M6",
        MetadataField.LENS to "Summicron 35mm f/2", MetadataField.ISO to "ISO 400",
    )

    @Test fun batchWriteKeepsEachPhotosOtherOverrides() {
        val draft = EditDraft(Templates.gallery, overrides = mapOf(
            "a" to mapOf(MetadataField.SHUTTER to "1/125 s", MetadataField.MODEL to "old"),
            "c" to mapOf(MetadataField.TITLE to "untouched"),
        ))
        val written = draft.withOverrides(listOf("a", "b"), roll)
        assertEquals(roll + (MetadataField.SHUTTER to "1/125 s"), written.overrides["a"])
        assertEquals(roll, written.overrides["b"])
        assertEquals(mapOf(MetadataField.TITLE to "untouched"), written.overrides["c"])
    }

    @Test fun emptyValuesHideAFieldAndNothingToWriteChangesNothing() {
        val draft = EditDraft(Templates.gallery)
        val hidden = draft.withOverrides(listOf("a"), mapOf(MetadataField.LENS to ""))
        val metadata = PhotoMetadata(mapOf(MetadataField.MODEL to "Z 8", MetadataField.LENS to "NIKKOR"))
        assertEquals("Z 8", metadata.display(listOf(MetadataField.MODEL, MetadataField.LENS), hidden.overrides.getValue("a")))
        assertSame(draft, draft.withOverrides(listOf("a"), emptyMap()))
        assertEquals(120, draft.withOverrides(listOf("a"), mapOf(MetadataField.TITLE to "x".repeat(300)))
            .overrides.getValue("a").getValue(MetadataField.TITLE).length)
    }

    @Test fun setsSurviveStorageAndSummariseTheCamera() {
        val sets = listOf(ParameterSet("1", "M6 胶片", roll), ParameterSet("2", "只有日期", mapOf(MetadataField.DATE to "1998-05-01")))
        assertEquals(sets, storageJson.decodeFromString<List<ParameterSet>>(storageJson.encodeToString(sets)))
        assertEquals("M6 · Summicron 35mm f/2 · ISO 400", sets[0].summary)
        assertEquals("1998-05-01", sets[1].summary)
    }
}
