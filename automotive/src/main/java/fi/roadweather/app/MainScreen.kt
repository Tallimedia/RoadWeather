package fi.roadweather.app

import android.Manifest
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * `vt3_Helsinki_Pirkkola` → `Vt 3 Helsinki Pirkkola`.
 *
 * Fintraffic name stations for their own systems. A driver reads a road
 * number, and the raw identifier was on screen in v0.1.0.
 */
internal fun prettyStation(raw: String): String =
    raw.replace('_', ' ')
        .replace(Regex("^([a-z]{1,2})(\\d+)")) { m ->
            m.groupValues[1].replaceFirstChar { it.uppercase() } + " " + m.groupValues[2]
        }

/** How far the car must travel before the reading on screen is stale. */
private const val MOVED_ENOUGH_M = 2_000f

private const val TAB_ROAD = "road"
private const val TAB_WEATHER = "weather"
private const val TAB_ABOUT = "about"

/**
 * Every screen of the app, because [TabTemplate] owns its tab contents rather
 * than pushing separate screens.
 *
 * Templates are immutable and rebuilt on [invalidate], so state lives here and
 * [onGetTemplate] is a pure render of it.
 */
class MainScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var activeTab = TAB_ROAD
    private var loading = true
    private var permissionDenied = false
    private var outsideCoverage = false
    private var failed = false

    /** Where the data on screen is for, so a new fix can be judged against it. */
    private var loadedLat: Double? = null
    private var loadedLon: Double? = null

    /**
     * The most recent fix, updated on every callback regardless of the
     * 2 km reload threshold below.
     *
     * The backend's own `distance_km` is only accurate for the coordinates
     * that request carried, and freezes at whatever it was when the data was
     * last fetched — up to 2 km stale by the time the *next* fetch happens.
     * Station positions travel with the payload precisely so distances can
     * be recomputed against this fix at render time instead, which is exact
     * as of the last redraw rather than the last fetch (Nico, 2026-09-23:
     * "always true for the distance").
     */
    private var currentFix: android.location.Location? = null

    private var road: Road? = null
    private var observation: Observation? = null
    private var forecast: List<ForecastPoint> = emptyList()

    init {
        lifecycle.addObserver(this)
    }

    /**
     * The host's real row budget for a list, read once and cached.
     *
     * Every row count in this screen was a guess from Android Auto's
     * published minimum (six) rather than a measurement of this car, which
     * showed seven rows scrolling without complaint on 2026-09-23. Asking
     * beats assuming: `ConstraintManager.getContentLimit` returns what the
     * host actually enforces, and falls back to the documented floor only if
     * the manager is unavailable.
     */
    private val listRowLimit: Int by lazy {
        runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6).coerceAtLeast(4)
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (Locator.hasPermission(carContext)) load() else requestLocation()
    }

    /**
     * Follow the car while the screen is up.
     *
     * v0.1.0 resolved the position once and showed the same road for a whole
     * commute (`FIELD-NOTES.md` §1). Updates are bound to the visible
     * lifecycle so nothing keeps the GPS awake behind a different app.
     */
    override fun onStart(owner: LifecycleOwner) {
        Locator.startUpdates(carContext) { fix -> onNewFix(fix) }
    }

    override fun onStop(owner: LifecycleOwner) {
        Locator.stopUpdates(carContext)
    }

    /**
     * Reload only once the car has actually gone somewhere else.
     *
     * A threshold rather than a timer, because the question is "is a
     * different station or road section nearest now", not "has a minute
     * passed". Two kilometres is below the spacing of the road network's
     * station grid, so a genuine change of nearest station is caught while a
     * queue at a junction is not.
     */
    private fun onNewFix(fix: android.location.Location) {
        currentFix = fix
        if (loading) return
        val lat = loadedLat
        val lon = loadedLon
        if (lat != null && lon != null) {
            val moved = FloatArray(1)
            android.location.Location.distanceBetween(lat, lon, fix.latitude, fix.longitude, moved)
            if (moved[0] < MOVED_ENOUGH_M) return
        }
        load(fix)
    }

    /**
     * Distance in km from the live fix to a station, falling back to the
     * backend's own figure when either coordinate is unavailable — no
     * signal yet, or a backend that has not shipped station positions.
     */
    private fun liveDistanceKm(lat: Double?, lon: Double?, fallback: Double?): Double? {
        val fix = currentFix
        if (fix == null || lat == null || lon == null) return fallback
        val out = FloatArray(1)
        android.location.Location.distanceBetween(fix.latitude, fix.longitude, lat, lon, out)
        return out[0] / 1000.0
    }

    private fun requestLocation() {
        carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
            if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissionDenied = false
                load()
            } else {
                // No silent fallback to a default place: a road reading for
                // somewhere else is worse than none (SPEC.md §5).
                permissionDenied = true
                loading = false
                invalidate()
            }
        }
    }

    /**
     * @param known a fix already in hand, from the location stream. Avoids
     *   asking for a second one that would only repeat it.
     */
    private val hasContent: Boolean
        get() = road != null || observation != null

    /**
     * What the screen would draw, reduced to a string.
     *
     * The host counts a new template against a five-per-task quota unless it
     * is the same type with the same main content, and there is no way to ask
     * whether a given change qualifies. So the app stops guessing and simply
     * does not push a template when the visible result would be identical —
     * a refetch on the far side of a village usually returns the same station
     * and the same rounded numbers.
     */
    private fun signature(): String = listOf(
        activeTab, permissionDenied, outsideCoverage, failed,
        road?.surface?.station, road?.surface?.roadTempC, road?.risk,
        road?.station?.condition, road?.station?.conditionFault,
        road?.section?.description, road?.section?.outlook?.size,
        observation?.station, observation?.tempC, forecast.firstOrNull()?.epoch,
    ).joinToString("|")

    private var rendered: String? = null

    private fun load(known: android.location.Location? = null) {
        loading = true
        failed = false
        outsideCoverage = false
        // A reload keeps the previous reading visible; only a cold start has
        // nothing to show and needs the loading state pushed.
        if (!hasContent) invalidate()
        lifecycleScope.launch {
            val fix = known ?: Locator.fix(carContext)
            if (fix == null) {
                loading = false; failed = true; invalidate(); return@launch
            }
            loadedLat = fix.latitude
            loadedLon = fix.longitude
            currentFix = fix
            try {
                road = Api.road(fix.latitude, fix.longitude)
                observation = Api.observation(fix.latitude, fix.longitude)
                forecast = Api.forecast(fix.latitude, fix.longitude)
            } catch (_: OutsideCoverage) {
                outsideCoverage = true
            } catch (_: Exception) {
                failed = true
            }
            loading = false
            val now = signature()
            if (now != rendered) {
                rendered = now
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val builder = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeTab = tabContentId
                rendered = signature()
                invalidate()
            }
        })
        builder.setHeaderAction(Action.APP_ICON)
        builder.addTab(tab(TAB_ROAD, R.string.tab_road, R.drawable.ic_road))
        builder.addTab(tab(TAB_WEATHER, R.string.tab_weather, R.drawable.ic_weather))
        builder.addTab(tab(TAB_ABOUT, R.string.tab_about, R.drawable.ic_info))
        // Without this the host throws "Template requires setting content ID
        // for the active tab when not in Loading state" — the tab list and the
        // contents are matched by id, not by position.
        builder.setActiveTabContentId(activeTab)
        builder.setTabContents(TabContents.Builder(content()).build())
        return builder.build()
    }

    private fun tab(id: String, title: Int, icon: Int): Tab =
        Tab.Builder()
            .setContentId(id)
            .setTitle(carContext.getString(title))
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .build()

    private fun content(): Template = when {
        activeTab == TAB_ABOUT -> aboutTemplate()
        permissionDenied -> message(R.string.err_location_denied, R.string.grant) { requestLocation() }
        // Only when there is nothing yet. On a reload the previous reading
        // stays on screen: swapping to a MessageTemplate and back spends two
        // steps of a five-template quota every 2 km, which is what took the
        // whole app out mid-drive (FIELD-NOTES.md §7).
        loading && !hasContent -> message(R.string.loading, null, null)
        outsideCoverage -> message(R.string.err_outside_finland, R.string.retry) { load() }
        failed -> message(R.string.err_no_connection, R.string.retry) { load() }
        activeTab == TAB_WEATHER -> weatherTemplate()
        else -> roadTemplate()
    }

    private fun message(text: Int, actionLabel: Int?, onAction: (() -> Unit)?): Template {
        val b = MessageTemplate.Builder(carContext.getString(text))
        if (actionLabel != null && onAction != null) {
            b.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(actionLabel))
                    .setOnClickListener { onAction() }
                    .build(),
            )
        }
        return b.build()
    }

    // ---------------------------------------------------------------- road

    /**
     * v0.4.0 layout, settled on the design preview against Nico's photos
     * from the field (FIELD-NOTES.md §7–8).
     *
     * No `SectionedItemList` / section headers at all: they render further
     * left than an ordinary row and the Volvo's bezel clips them — visible
     * in every v0.3.0 screenshot as "/Nyt" and ">Kt 50". Every row, including
     * the ones that used to be headers, goes into one flat `ItemList`.
     *
     * Every label leads its own row rather than trailing the previous one: a
     * trailing label is orphaned by the divider beneath it and reads as a
     * heading for the *next* row — "Tienpinta" appeared to caption
     * "ei saatavilla" although it was reporting the surface temperature
     * above it, which was fine.
     */
    private fun roadTemplate(): Template {
        val r = road
        val surface = r?.surface
        val station = r?.station
        val items = ItemList.Builder()

        // Where and when, once, before any number — same opening move as the
        // weather tab, so both tabs answer "whose reading is this" first.
        val stationName = station?.name ?: surface?.station
        if (stationName != null) {
            val parts = mutableListOf(carContext.getString(R.string.road_station))
            surface?.measuredAt?.let { parts.add(hhmm(it)) }
            val distanceKm = liveDistanceKm(
                station?.lat, station?.lon, station?.distanceKm ?: surface?.distanceKm,
            )
            distanceKm?.let { parts.add(carContext.getString(R.string.from_you, fi1(it))) }
            items.addItem(
                Row.Builder()
                    .setTitle(prettyStation(stationName))
                    .addText(parts.joinToString(" · "))
                    .build(),
            )
        }

        // The headline pairing the whole app exists for: surface temperature,
        // with the ice verdict immediately under it. Sub-zero is coloured —
        // it is the number a driver scans for, and at -3 °C it used to look
        // exactly like +12 °C.
        items.addItem(
            Row.Builder()
                .setTitle(labelled(carContext.getString(R.string.road_surface), coloured(surface?.roadTempC)))
                .addText(riskText(r?.risk ?: RiskLevel.UNKNOWN))
                .addText(
                    "${carContext.getString(R.string.air)} ${temp(surface?.airTempC)}  ·  " +
                        "${carContext.getString(R.string.dew_point)} ${temp(surface?.dewPointC)}",
                )
                .build(),
        )

        // A broken sensor is missing data, not a surface state. v0.1.0
        // printed "The sensor has a fault" here, right under a reassuring
        // ice-risk line (FIELD-NOTES.md §3). The four winter KELI states are
        // coloured; every reading on Finnish roads today is Dry/Moist/Wet,
        // so this is unexercised until spike S6.
        val conditionText = station?.takeUnless { it.conditionFault }?.condition
            ?: carContext.getString(R.string.unavailable)
        val conditionLine = mutableListOf<String>()
        station?.freezingPointC?.let { conditionLine.add(carContext.getString(R.string.freezes_at, temp(it))) }
        station?.saltGM2?.let { conditionLine.add(carContext.getString(R.string.salt, fi1(it))) }
        items.addItem(
            Row.Builder()
                .setTitle(labelled(carContext.getString(R.string.condition), keliColoured(conditionText, station?.conditionCode)))
                .apply { if (conditionLine.isNotEmpty()) addText(conditionLine.joinToString(" · ")) }
                .build(),
        )

        // 0h is Fintraffic's own OBSERVATION, not a FORECAST — a second "now"
        // reading from a different source than the row above. Dropped, which
        // buys a fourth genuine forecast step (0.3.0 only showed three).
        val steps = r?.section?.outlook.orEmpty().filterNot { it.isObservation }
        if (steps.isNotEmpty()) {
            val section = r?.section?.description ?: carContext.getString(R.string.forecast_label)
            val allEstimated = steps.none { it.reliable }
            items.addItem(
                Row.Builder()
                    .setTitle(section)
                    .apply {
                        if (allEstimated) addText(carContext.getString(R.string.forecast_estimated))
                    }
                    .build(),
            )
            // 3 fixed rows precede this block (station, surface, condition),
            // plus the section-header row just added.
            val budget = (listRowLimit - 4).coerceAtLeast(1)
            steps.take(budget).forEach { s ->
                val state = humanise(s.surface ?: s.roadCondition)
                val row = Row.Builder()
                    .setTitle(labelled(s.at.orEmpty().replace("h", " h"), coloured(s.roadTempC)))
                    .addText(conditionColoured(state))
                if (!allEstimated && !s.reliable) row.addText(carContext.getString(R.string.no_road_station))
                items.addItem(row.build())
            }
        }
        return ListTemplate.Builder().setSingleList(items.build()).build()
    }

    /** Colour follows the level; `UNKNOWN` is never green and never silent. */
    private fun riskText(level: RiskLevel): CharSequence {
        val (res, colour) = when (level) {
            RiskLevel.HIGH -> R.string.risk_high to CarColor.RED
            RiskLevel.MODERATE -> R.string.risk_moderate to CarColor.YELLOW
            RiskLevel.LOW -> R.string.risk_low to CarColor.DEFAULT
            RiskLevel.NONE -> R.string.risk_none to CarColor.DEFAULT
            RiskLevel.UNKNOWN -> R.string.risk_unknown to CarColor.DEFAULT
        }
        val text = carContext.getString(res)
        return SpannableString(text).apply {
            setSpan(
                ForegroundCarColorSpan.create(colour), 0, length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }
    }

    // ------------------------------------------------------------- weather

    /**
     * v0.4.0 layout: where and when the reading is from, once, at the top —
     * "Sää nyt · <station>" was found to say "now" three times over (the tab
     * label, the head row and the "Nyt" row below it), so the head row keeps
     * only the station name. Then "Nyt" and every forecast step share the
     * same shape — icon position (v0.6), title, wind line, condition line —
     * so the temperature/wind/condition columns are directly comparable
     * scanning down the screen.
     */
    private fun weatherTemplate(): Template {
        val o = observation
        val items = ItemList.Builder()

        if (o?.station != null) {
            val parts = mutableListOf(carContext.getString(R.string.observation))
            o.epoch?.let { parts.add(hhmm(it)) }
            liveDistanceKm(o.lat, o.lon, o.distanceKm)
                ?.let { parts.add(carContext.getString(R.string.from_you, fi1(it))) }
            items.addItem(
                Row.Builder()
                    .setTitle(o.station)
                    .addText(parts.joinToString(" · "))
                    .build(),
            )
        }

        items.addItem(weatherRow(carContext.getString(R.string.now), o?.tempC, o?.compass,
            o?.windMs, o?.gustMs, null, null))

        // Hourly for the first two steps, then every third (3-hourly beyond
        // that). FMI aligns points to the requested step, so pure 3-hourly
        // left a gap of up to three hours before the first row — 2 h 14 min
        // on the drive that found this (FIELD-NOTES.md, 2026-09-23).
        val budget = (listRowLimit - 2).coerceAtLeast(1)
        listOf(0, 1, 4, 7, 10).take(budget).mapNotNull { forecast.getOrNull(it) }.forEach { p ->
            items.addItem(weatherRow(hhmm(p.epoch), p.tempC, p.compass, p.windMs, p.gustMs,
                p.symbolText, p.popPct))
        }
        return ListTemplate.Builder()
            .setSingleList(items.build())
            .build()
    }

    private fun weatherRow(
        label: String, tempCValue: Double?, compass: String?, windMs: Double?, gustMs: Double?,
        condition: String?, popPct: Double?,
    ): Row {
        val windParts = mutableListOf<String>()
        listOfNotNull(compass, windMs?.let { "%.1f m/s".format(it) })
            .takeIf { it.isNotEmpty() }
            ?.let { windParts.add(carContext.getString(R.string.wind, it.joinToString(" "))) }
        gustMs?.let { windParts.add(carContext.getString(R.string.gust, "%.1f".format(it))) }

        val condLine = listOfNotNull(
            condition?.replaceFirstChar { it.uppercase() },
            popPct?.let { carContext.getString(R.string.rain, it.toInt()) },
        )
        return Row.Builder()
            .setTitle(labelled(label, coloured(tempCValue)))
            .apply { if (windParts.isNotEmpty()) addText(windParts.joinToString(" · ")) }
            .apply { if (condLine.isNotEmpty()) addText(condLine.joinToString(" · ")) }
            .build()
    }

    // --------------------------------------------------------------- about

    private fun aboutTemplate(): Template {
        val items = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.about_title))
                    .addText(BuildConfig.VERSION_NAME)
                    .build(),
            )
            // Attribution in the app, not only in the store listing. Both
            // licences require it.
            .addItem(
                Row.Builder()
                    .setTitle("Data")
                    .addText(carContext.getString(R.string.about_sources))
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("—")
                    .addText(carContext.getString(R.string.about_independent))
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("—")
                    .addText(carContext.getString(R.string.about_safety))
                    .build(),
            )
        return ListTemplate.Builder()
            .setSingleList(items.build())
            .build()
    }

    // -------------------------------------------------------------- format

    private fun temp(c: Double?): String = c?.let { "%.1f °C".format(it) } ?: "–"

    /** One decimal, locale-formatted — the same convention as `temp()`. */
    private fun fi1(v: Double): String = "%.1f".format(v)

    /** Colours a temperature red when it is at or below freezing. */
    private fun coloured(c: Double?): CharSequence {
        val text = temp(c)
        if (c == null || c >= 0.0) return text
        return colourSpan(text, CarColor.RED)
    }

    /** The four winter KELI/road-condition states get colour; the rest do not.
     * Untestable until spike S6 — every reading today is Dry/Moist/Wet. */
    private fun conditionColoured(text: String): CharSequence {
        val colour = when (text) {
            carContext.getString(R.string.cond_frosty),
            carContext.getString(R.string.cond_snowy),
            carContext.getString(R.string.cond_slushy),
            carContext.getString(R.string.cond_poor),
                -> CarColor.YELLOW
            carContext.getString(R.string.cond_icy),
            carContext.getString(R.string.cond_very_poor),
                -> CarColor.RED
            else -> return text
        }
        return colourSpan(text, colour)
    }

    /**
     * Colours the current road state by Digitraffic's KELI code, not by its
     * text. The codes come from their published table at
     * `/api/weather/v1/sensors`: 5 Kuura (frost), 6 Lumi (snow),
     * 7 Jää (ice), 9 Sohjoinen (slush). 0-4 and 8 are dry/moist/wet/salted
     * and carry no warning.
     *
     * Keyed on the code because the description arrives localised — matching
     * its text would break the day a translation changed, and could not tell
     * "Kuura" from "Kuiva" without knowing every language. Unexercised until
     * the first freeze; every station in the country reads dry, moist or wet
     * today (spike S6).
     */
    private fun keliColoured(text: String, code: Int?): CharSequence = when (code) {
        7 -> colourSpan(text, CarColor.RED)
        5, 6, 9 -> colourSpan(text, CarColor.YELLOW)
        else -> text
    }

    private fun colourSpan(text: String, colour: CarColor): CharSequence =
        SpannableString(text).apply {
            setSpan(
                ForegroundCarColorSpan.create(colour), 0, length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }

    /** "Label  value" as one CharSequence, so a colour span on `value` survives. */
    private fun labelled(label: String, value: CharSequence): CharSequence {
        val builder = android.text.SpannableStringBuilder()
        builder.append(label).append("  ").append(value)
        return builder
    }

    /**
     * Digitraffic returns SCREAMING_SNAKE enums. Known ones are translated;
     * anything unknown is title-cased rather than dropped — a state we have
     * not seen before is still information, and winter will bring several
     * this codebase has never met.
     */
    private fun humanise(raw: String?): String = when (raw) {
        null -> "–"
        "DRY" -> carContext.getString(R.string.cond_dry)
        "MOIST" -> carContext.getString(R.string.cond_moist)
        "WET" -> carContext.getString(R.string.cond_wet)
        "SLUSHY" -> carContext.getString(R.string.cond_slushy)
        "SNOWY" -> carContext.getString(R.string.cond_snowy)
        "ICY", "PARTLY_ICY" -> carContext.getString(R.string.cond_icy)
        "FROSTY" -> carContext.getString(R.string.cond_frosty)
        "NORMAL_CONDITION" -> carContext.getString(R.string.cond_normal)
        "POOR_CONDITION" -> carContext.getString(R.string.cond_poor)
        "EXTREMELY_POOR_CONDITION" -> carContext.getString(R.string.cond_very_poor)
        else -> raw.split('_').joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun hhmm(epoch: Long): String {
        val local = java.time.Instant.ofEpochSecond(epoch)
            .atZone(java.time.ZoneId.systemDefault())
        return "%02d:%02d".format(local.hour, local.minute)
    }
}
