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
private const val TAB_WARNINGS = "warnings"
private const val TAB_ABOUT = "about"

/**
 * The four tabs — Road, Weather, Warnings, About. [TabTemplate] owns their
 * contents directly; switching tabs never pushes a screen.
 *
 * v0.5.0 tried moving About behind an ⓘ [ActionStrip] action on each tab
 * instead, pushed via [ScreenManager]. Reverted in v0.5.1 after it crashed in
 * the field: the icon never rendered at all — a `ListTemplate`'s own
 * `ActionStrip` is not honoured once that template is wrapped as
 * [TabContents], only `TabTemplate`'s single header action is — and building
 * a fresh `Action`/`OnClickListener` closure on every render meant no two
 * renders of the same tab were ever equal, so the host almost certainly
 * counted every tab switch as a full new step against the five-template
 * quota rather than a free refresh. Two taps emptied it; Warnings, hit last,
 * crashed outright (`FIELD-NOTES.md`, 2026-09-28). Back to four tabs until a
 * properly host-verified way to reach About exists.
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
    private var warnings: Warnings? = null

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
    /**
     * "No connection, showing what we last had" — SPEC.md §6's designed
     * state for a dropout, not an error card. Only appears on the head row,
     * which already had a free second text line, so it costs nothing from
     * the row budget.
     *
     * Can lag a failure by one redraw: `failed` is deliberately excluded from
     * `signature()`, so a blip alone does not force a repaint — only the next
     * legitimate one (a tab tap, or new data on recovery) picks it up. That
     * is the trade this whole mechanism exists for: noticing a dropout a few
     * seconds late costs nothing, spending the quota on every blip costs the
     * app.
     */
    private fun staleNote(): CharSequence? {
        if (!failed) return null
        return colourSpan(carContext.getString(R.string.no_connection_stale), CarColor.YELLOW)
    }

    /**
     * The host's real row budget for a list, read **fresh on every call**.
     *
     * Was `by lazy` — queried once, the first time the screen built a
     * template, and cached for the screen's whole lifetime. Because the app
     * is always launched while parked, that first query always captured the
     * generous parked-time limit. AAOS tightens what it permits once the car
     * starts moving; `getContentLimit` exists precisely so an app can ask
     * again and adapt, not so it can be asked once and assumed forever.
     *
     * Caching it meant: launch parked, render 7-8 rows under the parked
     * limit, car starts moving, the platform's actual permitted content
     * shrinks, our last template — still sized to the stale parked number —
     * is now non-compliant, and the host has to block the screen rather than
     * keep showing something it is no longer allowed to. Stopping relaxes
     * the restriction back to parked levels, and the same stale template
     * becomes compliant again "by accident" — which is exactly the
     * lock-while-moving/recover-on-stop pattern found in the field on
     * 2026-09-24 (`FIELD-NOTES.md`).
     *
     * The call itself is cheap and local — no network round trip — so there
     * is no cost to asking on every `onGetTemplate()` instead of once.
     */
    private val listRowLimit: Int
        get() = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6).coerceAtLeast(4)

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
     * `failed` is deliberately **not** part of this. A dropout changes no
     * data — the reading on screen is still the last good one — so letting it
     * move the signature would push a template for nothing, twice per blip.
     * The stale marker appears at the next redraw that happens anyway: a tab
     * tap, or the recovery that brings new data.
     */
    private fun signature(): String = listOf(
        activeTab, permissionDenied, outsideCoverage,
        road?.surface?.station, road?.surface?.roadTempC, road?.risk,
        road?.station?.condition, road?.station?.conditionFault,
        road?.section?.description, road?.section?.outlook?.size,
        observation?.station, observation?.tempC, forecast.firstOrNull()?.epoch,
        warnings?.warnings?.size, warnings?.roadNotices?.size,
        warnings?.roadNotices?.firstOrNull()?.distanceKm,
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
                warnings = Api.warnings(fix.latitude, fix.longitude)
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
        builder.addTab(tab(TAB_WARNINGS, R.string.tab_warnings, R.drawable.ic_warnings))
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
        permissionDenied -> message(R.string.err_location_denied, R.string.grant) { requestLocation() }
        // Only when there is nothing yet. On a reload the previous reading
        // stays on screen: swapping to a MessageTemplate and back spends two
        // steps of a five-template quota every 2 km, which is what took the
        // whole app out mid-drive (FIELD-NOTES.md §7).
        loading && !hasContent -> message(R.string.loading, null, null)
        outsideCoverage -> message(R.string.err_outside_finland, R.string.retry) { load() }
        // A dropout must not throw away a good reading. Swapping to a
        // MessageTemplate and back costs two steps of a five-template quota
        // per blip — the exact pattern that took the app out in 0.2.0 — and
        // on a rural road there are several. The last reading stays up,
        // marked stale, which is also what SPEC.md §6 always specified.
        failed && !hasContent -> message(R.string.err_no_connection, R.string.retry) { load() }
        activeTab == TAB_WEATHER -> weatherTemplate()
        activeTab == TAB_WARNINGS -> warningsTemplate()
        activeTab == TAB_ABOUT -> aboutTemplate()
        else -> roadTemplate()
    }

    /**
     * Reverted from a pushed [AboutScreen] in v0.5.1 (see the class doc for
     * why). Same content that screen had; [AboutScreen] itself stays in the
     * repo, unused, until a rendering-verified way to reach it exists.
     */
    private fun aboutTemplate(): Template {
        val items = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.about_title))
                    .addText(BuildConfig.VERSION_NAME)
                    .build(),
            )
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
        return ListTemplate.Builder().setSingleList(items.build()).build()
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
                    .apply { staleNote()?.let { addText(it) } }
                    .build(),
            )
        }

        // The headline pairing the whole app exists for: surface temperature,
        // with the ice verdict immediately under it. The title itself can't
        // carry colour — `Row.setTitle()` validates against
        // `CarTextConstraints.TEXT_AND_ICON`, which has no `ForegroundCarColorSpan`
        // in its allowed set (confirmed via bytecode; `Row.addText()` validates
        // against `TEXT_WITH_COLORS_AND_ICON`, which does) — so the sub-zero
        // warning lives entirely in the coloured `riskText()` line below.
        items.addItem(
            Row.Builder()
                .setTitle(labelled(carContext.getString(R.string.road_surface), temp(surface?.roadTempC)))
                .addText(riskText(r?.risk ?: RiskLevel.UNKNOWN))
                .addText(
                    "${carContext.getString(R.string.air)} ${temp(surface?.airTempC)}  ·  " +
                        "${carContext.getString(R.string.dew_point)} ${temp(surface?.dewPointC)}",
                )
                .build(),
        )

        // A broken sensor is missing data, not a surface state. v0.1.0
        // printed "The sensor has a fault" here, right under a reassuring
        // ice-risk line (FIELD-NOTES.md §3). The four winter KELI states get
        // colour, but only in the addText line below — the title can't carry
        // it (see the road-surface row above). Every reading on Finnish
        // roads today is Dry/Moist/Wet, so this is unexercised until spike S6.
        val conditionText = station?.takeUnless { it.conditionFault }?.condition
            ?: carContext.getString(R.string.unavailable)
        val conditionLine = mutableListOf<String>()
        station?.freezingPointC?.let { conditionLine.add(carContext.getString(R.string.freezes_at, temp(it))) }
        station?.saltGM2?.let { conditionLine.add(carContext.getString(R.string.salt, fi1(it))) }
        items.addItem(
            Row.Builder()
                .setTitle(labelled(carContext.getString(R.string.condition), conditionText))
                .addText(keliColoured(conditionText, station?.conditionCode))
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
                    .setTitle(labelled(s.at.orEmpty().replace("h", " h"), temp(s.roadTempC)))
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
     * same shape — icon, title, wind line, condition line — so the
     * temperature/wind/condition columns are directly comparable scanning
     * down the screen. Icons added in v0.6 (`WeatherIcon.kt`, WE-3); "Nyt"
     * never gets one since observations carry no `smartsymbol`.
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
                    .apply { staleNote()?.let { addText(it) } }
                    .build(),
            )
        }

        // No icon on "Now": `/v1/observations` is a raw sensor reading, not a
        // symbolic condition — FMI only assigns a `smartsymbol` to forecast
        // points, never to an observation (confirmed live, 2026-10-05).
        items.addItem(weatherRow(carContext.getString(R.string.now), o?.tempC, o?.compass,
            o?.windMs, o?.gustMs, null, null, null))

        // Radar needs MapWithContentTemplate, @RequiresCarApi(7) — hidden
        // rather than crashing on an older host (RESEARCH.md §5.5).
        val radarEntry = carContext.carAppApiLevel >= 7
        if (radarEntry) {
            items.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.radar_row_title))
                    .addText(carContext.getString(R.string.radar_row_subtitle))
                    .setOnClickListener {
                        val fix = currentFix
                        if (fix != null) {
                            carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                                .push(RadarScreen(carContext, fix.latitude, fix.longitude))
                        }
                    }
                    .build(),
            )
        }

        // Hourly for the first two steps, then every third (3-hourly beyond
        // that). FMI aligns points to the requested step, so pure 3-hourly
        // left a gap of up to three hours before the first row — 2 h 14 min
        // on the drive that found this (FIELD-NOTES.md, 2026-09-23).
        val budget = (listRowLimit - (if (radarEntry) 3 else 2)).coerceAtLeast(1)
        listOf(0, 1, 4, 7, 10).take(budget).mapNotNull { forecast.getOrNull(it) }.forEach { p ->
            items.addItem(weatherRow(hhmm(p.epoch), p.tempC, p.compass, p.windMs, p.gustMs,
                p.symbolText, p.popPct, p.symbolCode))
        }
        return ListTemplate.Builder()
            .setSingleList(items.build())
            .build()
    }

    private fun weatherRow(
        label: String, tempCValue: Double?, compass: String?, windMs: Double?, gustMs: Double?,
        condition: String?, popPct: Double?, symbolCode: Int?,
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
            .setTitle(labelled(label, temp(tempCValue)))
            .apply { if (windParts.isNotEmpty()) addText(windParts.joinToString(" · ")) }
            .apply { if (condLine.isNotEmpty()) addText(condLine.joinToString(" · ")) }
            .apply {
                if (symbolCode != null) {
                    setImage(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, WeatherIcon.forCode(symbolCode)),
                        ).build(),
                    )
                }
            }
            .build()
    }

    // --------------------------------------------------------------- about

    /**
     * FMI land warnings by severity, then nearby road notices by distance —
     * `SPEC.md` §4b. FMI alone is usually empty here (the feed is mostly
     * marine, and this app is land-only); road works are what makes the tab
     * worth opening most days.
     */
    /**
     * The whole body is wrapped, deliberately. v0.5.1 sent this tab out
     * untested against the real host — no emulator or car was available —
     * and it failed to open in the field on 2026-09-29 with no stack trace
     * to work from (`FIELD-NOTES.md`). v0.5.2's try/catch caught and
     * surfaced the real cause the next drive: "CarSpan type is not allowed:
     * ForegroundCarColorSpan", thrown from the severity-coloured row titles
     * below (`colourSpan(title, cls)` / `colourSpan(titleText, sevCls)`
     * passed to `.setTitle()`). Confirmed byte-for-byte:
     * `Row.Builder.setTitle()` validates against
     * `CarTextConstraints.TEXT_AND_ICON`, whose allowed-span set is
     * `{DistanceSpan, DurationSpan, CarIconSpan}` — no `ForegroundCarColorSpan`.
     * `Row.Builder.addText()` validates against
     * `TEXT_WITH_COLORS_AND_ICON`, which does allow it. Fixed in v0.5.3 by
     * moving severity colour off the row title and onto the first
     * `addText()` line instead; the same rule fixed the identical latent
     * bug in `coloured()`/`keliColoured()` title usage in `roadTemplate()`
     * and `weatherRow()`, which had just never fired yet (no sub-zero
     * reading this season — spike S6). The try/catch itself stays, as a
     * backstop against whatever the next unverifiable-from-a-desk failure
     * turns out to be.
     */
    private fun warningsTemplate(): Template = try {
        warningsList()
    } catch (e: Exception) {
        ListTemplate.Builder()
            .setSingleList(
                ItemList.Builder()
                    .addItem(
                        Row.Builder()
                            .setTitle(carContext.getString(R.string.warnings_error))
                            .addText(e.message ?: e.javaClass.simpleName)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun warningsList(): Template {
        val w = warnings
        val alerts = w?.warnings.orEmpty()
        val notices = w?.roadNotices.orEmpty()
        val items = ItemList.Builder()

        // Always at least one row — a template with zero rows is not known
        // to be rejected by the library (checked directly, see above), but
        // there is no reason to rely on that when a real row costs nothing.
        when {
            w == null -> items.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.loading)).build(),
            )
            alerts.isEmpty() && notices.isEmpty() -> items.addItem(
                // A real, designed state, not a placeholder (SPEC.md §4b) —
                // Utsjoki returns zero of both on a normal day.
                Row.Builder().setTitle(carContext.getString(R.string.warnings_none)).build(),
            )
        }

        val budget = listRowLimit
        var used = 0
        alerts.take(2).forEach { a ->
            if (used >= budget) return@forEach
            val cls = when (a.colour) {
                "red", "orange" -> CarColor.RED
                "yellow" -> CarColor.YELLOW
                else -> null
            }
            val title = a.event ?: carContext.getString(R.string.warnings_alert_fallback)
            items.addItem(
                Row.Builder()
                    .setTitle(title)
                    .apply {
                        val headline = a.headline
                        when {
                            headline != null && cls != null -> addText(colourSpan(headline, cls))
                            headline != null -> addText(headline)
                        }
                    }
                    .build(),
            )
            used++
        }
        notices.forEach { n ->
            if (used >= budget) return@forEach
            val what = restrictionLine(n)
            val where = listOfNotNull(
                n.road?.let { carContext.getString(R.string.road_number, it) },
                n.roadName, n.municipality,
            ).joinToString(" · ").ifEmpty { n.title.orEmpty() }
            val sevCls = when (n.severity) {
                "HIGHEST" -> CarColor.RED
                "HIGH" -> CarColor.YELLOW
                else -> null
            }
            val titleText = "${fi1(n.distanceKm)} km   $where"
            val whatText = what.ifEmpty {
                if (n.type == "ROAD_WORK") carContext.getString(R.string.warnings_road_work)
                else carContext.getString(R.string.warnings_announcement)
            }
            items.addItem(
                Row.Builder()
                    .setTitle(titleText)
                    .addText(if (sevCls != null) colourSpan(whatText, sevCls) else whatText)
                    .apply { untilText(n)?.let { addText(it) } }
                    .build(),
            )
            used++
        }

        return ListTemplate.Builder()
            .setSingleList(items.build())
            .build()
    }

    /** The restriction is the headline, not the category — "One carriageway
     * closed" says what a driver needs, "roadwork" does not. */
    private fun restrictionLine(n: RoadNotice): String {
        val parts = n.restrictions
            .filterNot { it.type == "SPEED_LIMIT_LENGTH" } // a qualifier, not a fact on its own
            .mapNotNull { r ->
                val label = restrictionLabel(r.type) ?: return@mapNotNull null
                if (r.quantity != null) {
                    "$label ${r.quantity.toInt()}${r.unit?.let { " $it" } ?: ""}"
                } else label
            }
        return parts.take(2).joinToString(" · ")
    }

    private fun restrictionLabel(type: String?): String? = when (type) {
        "SPEED_LIMIT" -> carContext.getString(R.string.restr_speed_limit)
        "SINGLE_LANE_CLOSED" -> carContext.getString(R.string.restr_single_lane_closed)
        "MULTIPLE_LANES_CLOSED" -> carContext.getString(R.string.restr_multiple_lanes_closed)
        "SINGLE_CARRIAGEWAY_CLOSED" -> carContext.getString(R.string.restr_carriageway_closed)
        "ROAD_CLOSED" -> carContext.getString(R.string.restr_road_closed)
        "SINGLE_ALTERNATE_LINE_TRAFFIC" -> carContext.getString(R.string.restr_alternating)
        "NARROW_LANES" -> carContext.getString(R.string.restr_narrow_lanes)
        "TRAFFIC_LIGHTS" -> carContext.getString(R.string.restr_traffic_lights)
        "DETOUR" -> carContext.getString(R.string.restr_detour)
        "INTERMITTENT_SHORT_TERM_STOPS", "INTERMITTENT_STOPS_AND_CLOSURE_EFFECTIVE" ->
            carContext.getString(R.string.restr_stops)
        "SLOW_MOVING_MAINTENANCE_VEHICLE" -> carContext.getString(R.string.restr_slow_vehicle)
        "VEHICLE_WIDTH_LIMIT" -> carContext.getString(R.string.restr_width_limit)
        "VEHICLE_HEIGHT_LIMIT" -> carContext.getString(R.string.restr_height_limit)
        "NARROW_OR_CLOSED_PEDESTRIAN_AND_BICYLE_PATH" -> carContext.getString(R.string.restr_path_closed)
        "OPEN_FIRE_HEATER_IN_USE" -> carContext.getString(R.string.restr_open_flame)
        else -> null
    }

    /** Some of these run to 2027; "until 2 Oct" reads very differently from a
     * two-year closure, and "long-term" says so without a full date row. */
    private fun untilText(n: RoadNotice): CharSequence? {
        val endsRaw = n.ends ?: return null
        val end = runCatching { java.time.Instant.parse(endsRaw) }.getOrNull() ?: return null
        val days = java.time.Duration.between(java.time.Instant.now(), end).toDays()
        return when {
            days > 120 -> carContext.getString(R.string.warnings_long_term)
            days < 1 -> carContext.getString(R.string.warnings_until_today, hhmm(end.epochSecond))
            else -> {
                val local = end.atZone(java.time.ZoneId.systemDefault())
                carContext.getString(
                    R.string.warnings_until_date, local.dayOfMonth,
                    monthShort(local.monthValue),
                )
            }
        }
    }

    private fun monthShort(month: Int): String {
        val keys = arrayOf(
            R.string.month_1, R.string.month_2, R.string.month_3, R.string.month_4,
            R.string.month_5, R.string.month_6, R.string.month_7, R.string.month_8,
            R.string.month_9, R.string.month_10, R.string.month_11, R.string.month_12,
        )
        return carContext.getString(keys[(month - 1).coerceIn(0, 11)])
    }

    // -------------------------------------------------------------- format

    private fun temp(c: Double?): String = c?.let { "%.1f °C".format(it) } ?: "–"

    /** One decimal, locale-formatted — the same convention as `temp()`. */
    private fun fi1(v: Double): String = "%.1f".format(v)

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
