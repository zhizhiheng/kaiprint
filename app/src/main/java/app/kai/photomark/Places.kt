package app.kai.photomark

import android.content.Context
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How the location line of a watermark is filled. Only the printed text changes; GPS export is separate. */
@Serializable
enum class PlaceStyle(val label: String) {
    MANUAL("手填地点"),
    CITY("照片所在城市"),
    CITY_COUNTRY("城市 · 国家或地区"),
    COORDINATES("坐标（约 1 公里）"),
}

data class Place(val latitude: Double, val longitude: Double, val country: String, val population: Int,
                 val major: Boolean, val chinese: String, val name: String) {
    val label get() = chinese.ifEmpty { name }
}

/** An offline nearest-city lookup over the GeoNames cities15000 list bundled in assets. */
class PlaceIndex(lines: Sequence<String>) {
    private val cells = HashMap<Int, MutableList<Place>>()

    init {
        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 7) continue
            val place = Place(f[0].toInt() / 1e4, f[1].toInt() / 1e4, f[2], f[3].toIntOrNull() ?: 0,
                f[4] == "1", f[5], f[6])
            cells.getOrPut(cell(place.latitude, place.longitude)) { mutableListOf() } += place
        }
    }

    val size get() = cells.values.sumOf { it.size }

    /**
     * The place a photo was taken in. A large city nearby wins over a small town only when the town is much
     * farther away, so a shot in central Shanghai reads 上海 while one in Versailles keeps its own name.
     */
    fun locate(latitude: Double, longitude: Double): Place? {
        val row = floor(latitude).toInt()
        val column = floor(longitude).toInt()
        return (row - 1..row + 1).flatMap { r -> (column - 1..column + 1).flatMap { c ->
            cells[key(r, Math.floorMod(c + 180, 360) - 180)].orEmpty()
        } }.map { it to distance(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= RADIUS_KM }
            .maxByOrNull { (place, km) -> place.population.coerceAtLeast(1) / (1 + (km / 3.0).pow(3)) }
            ?.first
    }

    private fun cell(latitude: Double, longitude: Double) = key(floor(latitude).toInt(), floor(longitude).toInt())
    private fun key(row: Int, column: Int) = row * 1000 + column

    companion object {
        const val RADIUS_KM = 60.0

        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}

object Places {
    @Volatile private var index: PlaceIndex? = null

    fun index(context: Context): PlaceIndex = index ?: synchronized(this) {
        index ?: context.assets.open("places/cities.tsv").bufferedReader().useLines { PlaceIndex(it) }
            .also { index = it }
    }

    /** The text for [style], or null when the photo carries no position or no city is near enough. */
    fun describe(style: PlaceStyle, latitude: Double?, longitude: Double?, lookup: () -> PlaceIndex): String? {
        if (style == PlaceStyle.MANUAL || latitude == null || longitude == null) return null
        if (style == PlaceStyle.COORDINATES) return coordinates(latitude, longitude)
        val place = lookup().locate(latitude, longitude) ?: return null
        if (style == PlaceStyle.CITY) return place.label
        val country = Locale("", place.country).getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
            .takeIf { it.isNotBlank() && it != place.country } ?: place.country
        return if (country == place.label) place.label else "${place.label} · $country"
    }

    fun coordinates(latitude: Double, longitude: Double): String {
        fun part(value: Double, positive: Char, negative: Char) =
            String.format(Locale.ROOT, "%.2f°%c", abs(value), if (value >= 0) positive else negative)
        return "${part(latitude, 'N', 'S')} ${part(longitude, 'E', 'W')}"
    }
}
