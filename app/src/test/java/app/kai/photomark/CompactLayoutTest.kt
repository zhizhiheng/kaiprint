package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactLayoutTest {
    private val landscape = PixelSize(1200, 800)
    private val full = PhotoMetadata(mapOf(
        MetadataField.MAKE to "Nikon", MetadataField.MODEL to "ZR", MetadataField.FOCAL to "50 mm",
        MetadataField.APERTURE to "f/4", MetadataField.SHUTTER to "1/125 s", MetadataField.ISO to "ISO 400",
    ))

    @Test fun parameterStripShrinksToTheSideMarginWhenAPhotoHasNoDetails() {
        val template = Templates.gallery
        val filled = WatermarkLayout.calculate(landscape, template, metadata = full)
        val bare = WatermarkLayout.calculate(landscape, template, metadata = PhotoMetadata())
        val side = (template.frame.left * landscape.shortEdge)
        assertTrue(LayerArea.BOTTOM in bare.collapsed)
        assertEquals(side, bare.canvas.height - bare.photo.bottom, 1f)
        assertTrue(filled.canvas.height > bare.canvas.height)
        assertTrue(filled.collapsed.isEmpty())
        // The photo itself is never cropped or scaled by the change.
        assertEquals(landscape.width.toFloat(), bare.photo.width, 0f)
    }

    @Test fun aStripWithAnyContentAndAnOptedOutFrameKeepTheirSpace() {
        val modelOnly = PhotoMetadata(mapOf(MetadataField.MODEL to "ZR"))
        assertTrue(WatermarkLayout.calculate(landscape, Templates.gallery, metadata = modelOnly).collapsed.isEmpty())
        val credited = Templates.frostedGallery.copy(captions = mapOf(MetadataField.AUTHOR to "Kai"))
        assertTrue(WatermarkLayout.calculate(landscape, credited, metadata = PhotoMetadata()).collapsed.isEmpty())
        val kept = Templates.gallery.copy(frame = Templates.gallery.frame.copy(collapseEmpty = false))
        assertTrue(WatermarkLayout.calculate(landscape, kept, metadata = PhotoMetadata()).collapsed.isEmpty())
        // Without photo details, as in the template strip before loading, nothing collapses.
        assertTrue(WatermarkLayout.calculate(landscape, Templates.gallery).collapsed.isEmpty())
    }

    @Test fun darkStripAndSidebarCollapseToTheirOwnMargins() {
        val dark = WatermarkLayout.calculate(landscape, Templates.dark, metadata = PhotoMetadata())
        assertEquals(landscape, dark.canvas)
        val editorial = WatermarkLayout.calculate(landscape, Templates.editorial, metadata = PhotoMetadata())
        assertEquals(Templates.editorial.frame.top * landscape.shortEdge, editorial.canvas.width - editorial.photo.right, 1f)
    }

    @Test fun automaticPlaceCountsAsContent() {
        val template = WatermarkTemplate("t", "t", Frame(bottom = .2f), listOf(
            WatermarkLayer("place", LayerKind.EXIF, fields = listOf(MetadataField.LOCATION), area = LayerArea.BOTTOM),
        ))
        val located = PhotoMetadata(latitude = 31.2, longitude = 121.5)
        assertTrue(LayerArea.BOTTOM in WatermarkLayout.calculate(landscape, template, metadata = located).collapsed)
        val automatic = template.copy(place = PlaceStyle.CITY)
        assertFalse(LayerArea.BOTTOM in WatermarkLayout.calculate(landscape, automatic, metadata = located).collapsed)
    }

    @Test fun templateStripShowsTheShapeCreditAndPlaceAlreadyChosen() {
        val current = Templates.gallery.copy(
            frame = Templates.gallery.frame.copy(ratio = FrameRatio.THREE_FOUR),
            captions = mapOf(MetadataField.AUTHOR to "Kai"), place = PlaceStyle.CITY,
        )
        val adopted = Templates.adopt(Templates.archiveCard, current)
        assertEquals(FrameRatio.THREE_FOUR, adopted.frame.ratio)
        assertEquals(current.captions, adopted.captions)
        assertEquals(PlaceStyle.CITY, adopted.place)
        val preset = Templates.archiveCard.copy(id = "mine", title = "我的")
        assertEquals(preset, Templates.adopt(preset, current))
    }

    @Test fun templateFilterGroups() {
        assertEquals(Templates.Kind.FROSTED, Templates.kind(Templates.frostedJournal))
        assertEquals(Templates.Kind.PALETTE, Templates.kind(Templates.colorAtlas))
        assertEquals(Templates.Kind.IN_PHOTO, Templates.kind(Templates.fieldNotes))
        assertEquals(Templates.Kind.BORDER, Templates.kind(Templates.modelPlate))
        assertTrue(Templates.Kind.entries.drop(1).all { kind -> Templates.all.any { Templates.kind(it) == kind } })
    }

    @Test fun modelPlateReservesARowForTheMarkAboveTheParameters() {
        val layout = WatermarkLayout.calculate(PixelSize(6048, 4032), Templates.modelPlate, metadata = full)
        val mark = layout.area(Templates.modelPlate.layers.first { it.kind == LayerKind.BRAND })
        val line = layout.area(Templates.modelPlate.layers.first { it.kind == LayerKind.EXIF })
        assertEquals(layout.photo.bottom, mark.top, 0f)
        assertEquals(mark.bottom, line.top, 0f)
        assertTrue(line.height > 0f)
    }
}
