package fi.roadweather.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Backend base. Same service the watch uses. See BuildConfig.API_BASE. */
private val BASE = BuildConfig.API_BASE

/**
 * The language to ask the backend for.
 *
 * v0.1.0 asked for none, so the backend served its `en` default and "clear"
 * appeared in the middle of an otherwise Finnish screen in the car. FMI and
 * Fintraffic both localise their own text, so this is the whole fix — nothing
 * is translated here.
 *
 * Anything outside fi/sv/en gets `en`: the backend validates the parameter and
 * would reject, say, `de` outright rather than fall back.
 */
private val LANG: String
    get() = when (java.util.Locale.getDefault().language) {
        "fi" -> "fi"
        "sv" -> "sv"
        else -> "en"
    }

/** Raised for 404, which means "outside Finland" rather than a failure. */
class OutsideCoverage : Exception()

data class Surface(
    val roadTempC: Double?, val airTempC: Double?, val dewPointC: Double?,
    val station: String?, val distanceKm: Double?, val ageSeconds: Int?,
    /** When the surface temperature was measured, for the head row. */
    val measuredAt: Long?,
)

data class Station(
    val condition: String?, val freezingPointC: Double?,
    /** The road-state sensor is reporting its own failure, not a reading. */
    val conditionFault: Boolean = false,
    val name: String? = null, val distanceKm: Double? = null,
    /** Where the station is, so the distance can be kept true while moving. */
    val lat: Double? = null, val lon: Double? = null,
    val saltGM2: Double? = null,
    /**
     * Raw KELI code, 0-9, per Digitraffic's published table. Colour and
     * branching key on this rather than on [condition]: the description is
     * localised, so matching its text breaks silently the day a translation
     * is edited, and cannot tell "Kuura" from "Kuiva" without knowing every
     * language.
     */
    val conditionCode: Int? = null,
)

data class OutlookStep(
    val at: String?, val roadCondition: String?, val surface: String?,
    val roadTempC: Double?, val reliable: Boolean,
    /** Digitraffic tag the 0h step OBSERVATION, not FORECAST. */
    val isObservation: Boolean = false,
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
    val epoch: Long? = null,
    val lat: Double? = null, val lon: Double? = null,
)

data class ForecastPoint(
    val epoch: Long, val tempC: Double?, val symbolText: String?, val windMs: Double?,
    val gustMs: Double? = null, val compass: String? = null, val popPct: Double? = null,
)

/** Upstream sometimes reports sensor failure in the value field itself. */
internal fun looksLikeFault(text: String?): Boolean {
    val t = text?.lowercase() ?: return false
    return "fault" in t || "vika" in t || "error" in t
}

/** One FMI CAP alert whose polygon contains the car's position. Rare — the
 * backend already filters to local, land-relevant alerts (RESEARCH.md §5.6). */
data class WeatherWarning(
    val event: String?, val headline: String?, val colour: String?,
)

data class Restriction(val type: String?, val quantity: Double?, val unit: String?)

/** A road work or traffic announcement near the car, closest first. */
data class RoadNotice(
    val type: String?, val title: String?, val road: Int?, val roadName: String?,
    val municipality: String?, val distanceKm: Double, val severity: String?,
    val restrictions: List<Restriction>, val ends: String?,
)

data class Warnings(val warnings: List<WeatherWarning>, val roadNotices: List<RoadNotice>)

object Api {

    suspend fun road(lat: Double, lon: Double): Road = withContext(Dispatchers.IO) {
        val o = get("/v1/road?lat=$lat&lon=$lon&lang=$LANG")
        Road(
            surface = o.obj("surface")?.let {
                Surface(
                    it.dbl("road_temp_c"), it.dbl("air_temp_c"), it.dbl("dew_point_c"),
                    it.str("station"), it.dbl("distance_km"), it.intOrNull("age_seconds"),
                    it.obj("at")?.optLong("road_temp_c")?.takeIf { v -> v != 0L },
                )
            },
            station = o.obj("station")?.let {
                // `condition_fault` arrives from a current backend. The text
                // check is the belt to that braces: this build must not print
                // "The sensor has a fault" as a road state even if it is
                // talking to a backend that has not been redeployed yet.
                Station(
                    it.str("condition"), it.dbl("freezing_point_c"),
                    it.optBoolean("condition_fault", false) || looksLikeFault(it.str("condition")),
                    it.str("name"), it.dbl("distance_km"),
                    it.dbl("lat"), it.dbl("lon"),
                    it.dbl("salt_g_m2"),
                    it.dbl("condition_code")?.toInt(),
                )
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
                            isObservation = f.str("type") == "OBSERVATION",
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
        val o = get("/v1/observations?lat=$lat&lon=$lon&lang=$LANG")
        Observation(
            o.str("stationname"), o.dbl("distance"), o.dbl("temperature"),
            o.dbl("windspeedms"), o.dbl("windgust"), o.str("windcompass8"),
            o.dbl("humidity"), o.intOrNull("age_seconds"),
            o.optLong("epochtime").takeIf { it != 0L },
            o.dbl("station_lat"), o.dbl("station_lon"),
        )
    }

    /**
     * Hourly for the near term, one call.
     *
     * FMI aligns points to the requested step, so a pure 3-hourly request can
     * leave a gap of up to three hours before the first row — 2 h 14 min on
     * the drive that found this (FIELD-NOTES.md, 2026-09-23). `weatherV04`
     * on the design preview settled the fix: ask for hourly (`step=60`) and
     * have the caller pick every third point beyond the first two.
     */
    suspend fun forecast(lat: Double, lon: Double, hours: Int = 14): List<ForecastPoint> =
        withContext(Dispatchers.IO) {
            val arr = get("/v1/forecast?lat=$lat&lon=$lon&hours=$hours&step=60&lang=$LANG")
                .optJSONArray("points")
            (0 until (arr?.length() ?: 0)).map { i ->
                val p = arr!!.getJSONObject(i)
                ForecastPoint(
                    p.optLong("epochtime"), p.dbl("temperature"),
                    p.str("smartsymboltext"), p.dbl("windspeedms"),
                    p.dbl("hourlymaximumgust"), p.str("windcompass8"), p.dbl("pop"),
                )
            }
        }

    suspend fun warnings(lat: Double, lon: Double): Warnings = withContext(Dispatchers.IO) {
        val o = get("/v1/warnings?lat=$lat&lon=$lon&lang=$LANG")
        val alerts = o.optJSONArray("warnings")
        val notices = o.optJSONArray("road_notices")
        Warnings(
            warnings = (0 until (alerts?.length() ?: 0)).map { i ->
                val w = alerts!!.getJSONObject(i)
                WeatherWarning(w.str("event"), w.str("headline"), w.str("colour"))
            },
            roadNotices = (0 until (notices?.length() ?: 0)).map { i ->
                val n = notices!!.getJSONObject(i)
                val restrictions = n.optJSONArray("restrictions")
                RoadNotice(
                    n.str("type"), n.str("title"), n.intOrNull("road"), n.str("road_name"),
                    n.str("municipality"), n.dbl("distance_km") ?: 0.0, n.str("severity"),
                    (0 until (restrictions?.length() ?: 0)).map { j ->
                        val r = restrictions!!.getJSONObject(j)
                        Restriction(r.str("type"), r.dbl("quantity"), r.str("unit"))
                    },
                    n.str("ends"),
                )
            },
        )
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
