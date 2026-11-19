package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedImportTest {
    @Test fun keepsOriginalNamesSoRawAndJpegStillPair() {
        assertEquals("DSC_0001.NEF", safeFileName("DSC_0001.NEF", "image/x-nikon-nef", emptySet()))
        assertEquals("DSC_0001.JPG", safeFileName("DSC_0001.JPG", "image/jpeg", setOf("dsc_0001.nef")))
        val raw = PhotoSource("a", "DSC_0001.NEF", "f", "shared/1")
        val jpeg = PhotoSource("b", "DSC_0001.JPG", "f", "shared/1")
        assertEquals(raw.stem, jpeg.stem)
    }

    @Test fun addsAnExtensionFromTheTypeWhenTheNameHasNone() {
        assertEquals("image-123.jpg", safeFileName("image-123", "image/jpeg", emptySet()))
        assertEquals("IMG_4021.heic", safeFileName("IMG_4021", "image/heic", emptySet()))
        assertEquals("photo.dng", safeFileName(null, "image/x-adobe-dng", emptySet()))
        assertEquals(PhotoFormat.DNG, PhotoFormat.identify(safeFileName("raw", "image/x-adobe-dng", emptySet())))
    }

    @Test fun stripsPathsAndCharactersThatCannotBeFileNames() {
        assertEquals("evil.jpg", safeFileName("../../evil.jpg", "image/jpeg", emptySet()))
        assertEquals("a_b_.png", safeFileName("a:b?.png", "image/png", emptySet()))
        assertEquals("photo.jpg", safeFileName("..", "image/jpeg", emptySet()))
        val long = safeFileName("x".repeat(400) + ".jpg", "image/jpeg", emptySet())
        assertFalse(long.length > 124)
    }

    @Test fun renamesCollisionsWithinOneShare() {
        val taken = setOf("img.jpg", "img-2.jpg")
        assertEquals("IMG-3.jpg", safeFileName("IMG.jpg", "image/jpeg", taken))
        assertEquals("photo-2.jpg", safeFileName(null, "image/jpeg", setOf("photo.jpg")))
    }
}
