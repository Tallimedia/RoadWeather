package fi.roadweather.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Backend base. Same service the watch uses. See BuildConfig.API_BASE. */
private val BASE = BuildConfig.API_BASE

/** Raised for 404, which means "outside Finland" rather than a failure. */
class OutsideCoverage : Exception()

data class Surface(
    val roadTempC: Double?, val airTempC: Double?, val dewPointC: Double?,
    val station: String?, val distanceKm: Double?, val ageSeconds: Int?,
)

data class Station(val condition: String?, val freezingPointC: Double?)

data class OutlookStep(
    val at: String?, val roadCondition: String?, val surface: String?,
    val roadTempC: Double?, val reliable: Boolean,
)

data class Section(val description: String?, val outlook: List<OutlookStep>)

/** Levels mirror the backend exactly; `UNKNOWN` must never render as safe. */
enum class RiskLevel { HIGH, MODERATE, LOW, NONE, UNKNOWN }

data class Road(
    val surface: Surface?, val station: Station?, val section: Section?,
    val risk: RiskLevel,
)

data class Observation(
    val station: String?, val distanceKm: Double?, val tempC: Double?,
    val windMs: Double?, val gustMs: Double?, val compass: String?,
    val humidityPct: Double?, val ageSeconds: Int?,
)

data class ForecastPoint(
    val epoch: Long, val tempC: Double?, val symbolText: String?, val windMs: Double?,
)

object Api {

    suspend fun road(lat: Double, lon: Double): Road = withContext(Dispatchers.IO) {
        val o = get("/v1/road?lat=$lat&lon=$lon")
        Road(
            surface = o.obj("surface")?.let {
                Surface(
                    it.dbl("road_temp_c"), it.dbl("air_temp_c"), it.dbl("dew_point_c"),
                    it.str("station"), it.dbl("distance_km"), it.intOrNull("age_seconds"),
                )
            },
            station = o.obj("station")?.let {
                Station(it.str("condition"), it.dbl("freezing_point_c"))
            },
            section = o.obj("section")?.let { s ->
                val steps = s.optJSONArray("outlook")
                Section(
                    s.str("description"),
                    (0 until (steps?.length() ?: 0)).map { i ->
                        val f = steps!!.getJSONObject(i)
                        OutlookStep(
                            f.str("at"), f.str("road_condition"), f.str("surface"),
                            f.dbl("road_temp_c"),
                            // Digitraffic is candid about whether a road station
                            // backed the forecast; we pass that candour on.
                            reliable = f.str("reliability") == "SUCCESSFUL",
                        )
                    },
                )
            },
            risk = when (o.obj("ice_risk")?.str("level")) {
                "high" -> RiskLevel.HIGH
                "moderate" -> RiskLevel.MODERATE
                "low" -> RiskLevel.LOW
                "none" -> RiskLevel.NONE
                else -> RiskLevel.UNKNOWN
            },
        )
    }

    suspend fun observation(lat: Double, lon: Double): Observation = withContext(Dispatchers.IO) {
        val o = get("/v1/observations?lat=$lat&lon=$lon")
        Observation(
            o.str("stationname"), o.dbl("distance"), o.dbl("temperature"),
            o.dbl("windspeedms"), o.dbl("windgust"), o.str("windcompass8"),
            o.dbl("humidity"), o.intOrNull("age_seconds"),
        )
    }

    suspend fun forecast(lat: Double, lon: Double, hours: Int = 12): List<ForecastPoint> =
        withContext(Dispatchers.IO) {
            val arr = get("/v1/forecast?lat=$lat&lon=$lon&hours=$hours").optJSONArray("points")
            (0 until (arr?.length() ?: 0)).map { i ->
                val p = arr!!.getJSONObject(i)
                ForecastPoint(
                    p.optLong("epochtime"), p.dbl("temperature"),
                    p.str("smartsymboltext"), p.dbl("windspeedms"),
                )
            }
        }

    private fun get(path: String): JSONObject {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 404) {
                // A bare 404 is NOT proof of being outside Finland. Traefik
                // answers 404 for an unrouted hostname, and a misconfigured
                // deployment would otherwise tell a driver in Helsinki that
                // Finland has no data. Only the backend's own message counts.
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (body.contains("outside Finland")) throw OutsideCoverage()
                error("HTTP 404 from ${conn.url.host}")
            }
            if (code !in 200..299) error("HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

// JSONObject returns the string "null" and 0.0 for absent values, which would
// render as a real reading. These force absent to stay absent.
private fun JSONObject.obj(k: String): JSONObject? = if (isNull(k)) null else optJSONObject(k)
private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONObject.dbl(k: String): Double? = if (isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }
private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)
