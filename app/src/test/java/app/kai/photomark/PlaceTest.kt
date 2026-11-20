package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaceTest {
    private val index by lazy {
        val app = File(".").canonicalFile.let { if (it.name == "app") it else File(it, "app") }
        File(app, "src/main/assets/places/cities.tsv").useLines { PlaceIndex(it) }
    }

    private fun city(latitude: Double, longitude: Double) =
        Places.describe(PlaceStyle.CITY, latitude, longitude) { index }

    @Test fun photosNameTheCityTheyWereTakenIn() {
        assertTrue(index.size > 30_000)
        assertEquals("上海", city(31.2397, 121.4998))
        assertEquals("杭州", city(30.2425, 120.1500))
        assertEquals("京都", city(34.9949, 135.7850))
        assertEquals("伦敦", city(51.5138, -0.0984))
        assertEquals("巴黎", city(48.8584, 2.2945))
        assertEquals("香港", city(22.2930, 114.1694))
        // A town keeps its own name next to a capital that is farther away. Without a Chinese name it reads in Latin.
        assertEquals("Versailles", city(48.8049, 2.1204))
    }

    @Test fun countryAndCoordinateStyles() {
        assertEquals("伦敦 · 英国", Places.describe(PlaceStyle.CITY_COUNTRY, 51.5138, -0.0984) { index })
        assertEquals("31.24°N 121.50°E", Places.describe(PlaceStyle.COORDINATES, 31.2397, 121.4998) { index })
        assertEquals("33.87°S 151.21°E", Places.coordinates(-33.8688, 151.2093))
        assertEquals("40.71°N 74.01°W", Places.coordinates(40.7128, -74.0060))
    }

    @Test fun nothingIsPrintedWithoutAPositionOrANearbyCity() {
        assertNull(Places.describe(PlaceStyle.CITY, null, null) { index })
        assertNull(Places.describe(PlaceStyle.MANUAL, 31.2, 121.5) { index })
        assertNull(city(0.0, -140.0))
        assertNull(city(-75.0, 0.0))
    }

    @Test fun automaticPlaceReplacesTheTypedOneOnlyWhenFound() {
        val template = Templates.frostedJournal.copy(captions = mapOf(MetadataField.LOCATION to "西湖"),
            place = PlaceStyle.CITY)
        val metadata = PhotoMetadata(latitude = 30.2425, longitude = 120.15)
        assertEquals("杭州", template.shown(metadata, "杭州").values[MetadataField.LOCATION])
        assertEquals("西湖", template.shown(metadata, null).values[MetadataField.LOCATION])
        assertEquals(template, storageJson.decodeFromString<WatermarkTemplate>(storageJson.encodeToString(template)))
        assertEquals(PlaceStyle.MANUAL, storageJson.decodeFromString<WatermarkTemplate>("""{"id":"a","title":"b"}""").place)
    }
}
