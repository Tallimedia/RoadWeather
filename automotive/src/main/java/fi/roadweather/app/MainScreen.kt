package fi.roadweather.app

import android.Manifest
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
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

    private var road: Road? = null
    private var observation: Observation? = null
    private var forecast: List<ForecastPoint> = emptyList()

    init {
        lifecycle.addObserver(this)
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

    private fun roadTemplate(): Template {
        val r = road
        val surface = r?.surface
        val now = ItemList.Builder()

        // The headline pairing the whole app exists for: surface temperature,
        // with the ice verdict immediately under it.
        now.addItem(
            Row.Builder()
                .setTitle(temp(surface?.roadTempC))
                .addText(riskText(r?.risk ?: RiskLevel.UNKNOWN))
                .addText(carContext.getString(R.string.road_surface))
                .build(),
        )
        now.addItem(
            Row.Builder()
                .setTitle(
                    // A broken sensor is missing data, not a surface state.
                    // v0.1.0 printed "The sensor has a fault" here, right
                    // under a reassuring ice-risk line (FIELD-NOTES.md §3).
                    r?.station?.takeUnless { it.conditionFault }?.condition
                        ?: carContext.getString(R.string.unavailable),
                )
                .addText(
                    "${carContext.getString(R.string.air)} ${temp(surface?.airTempC)}  ·  " +
                        "${carContext.getString(R.string.dew_point)} ${temp(surface?.dewPointC)}",
                )
                .build(),
        )
        // Provenance, always: the reading is from the nearest station, not
        // from under the car, and the app says so.
        surface?.station?.let { name ->
            now.addItem(
                Row.Builder()
                    .setTitle(prettyStation(name))
                    .addText(provenance(surface.distanceKm, surface.ageSeconds))
                    .build(),
            )
        }

        val template = ListTemplate.Builder()
            .addSectionedList(
                SectionedItemList.create(now.build(), carContext.getString(R.string.now)),
            )

        val steps = r?.section?.outlook.orEmpty().take(3)
        if (steps.isNotEmpty()) {
            val out = ItemList.Builder()
            steps.forEach { s ->
                val row = Row.Builder()
                    .setTitle("${s.at.orEmpty()}   ${temp(s.roadTempC)}")
                    .addText(humanise(s.surface ?: s.roadCondition))
                if (!s.reliable) row.addText(carContext.getString(R.string.no_road_station))
                out.addItem(row.build())
            }
            template.addSectionedList(
                SectionedItemList.create(
                    out.build(),
                    r?.section?.description ?: carContext.getString(R.string.outlook),
                ),
            )
        }
        return template.build()
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

    private fun weatherTemplate(): Template {
        val o = observation
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle(temp(o?.tempC))
                .addText(
                    listOfNotNull(
                        o?.compass,
                        o?.windMs?.let { "%.1f m/s".format(it) },
                        o?.gustMs?.let { "gust %.1f".format(it) },
                    ).joinToString("  "),
                )
                .addText(
                    (o?.station ?: "") + provenance(o?.distanceKm, o?.ageSeconds).let { " · $it" },
                )
                .build(),
        )
        forecast.take(5).forEach { p ->
            items.addItem(
                Row.Builder()
                    .setTitle("${hhmm(p.epoch)}   ${temp(p.tempC)}")
                    .addText(
                        listOfNotNull(
                            p.symbolText,
                            p.windMs?.let { "%.0f m/s".format(it) },
                        ).joinToString("  ·  "),
                    )
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setSingleList(items.build())
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

    private fun provenance(distanceKm: Double?, ageSeconds: Int?): String =
        listOfNotNull(
            distanceKm?.let { "%.1f km".format(it) },
            ageSeconds?.let { "${it / 60} min" },
        ).joinToString(" · ")

    private fun hhmm(epoch: Long): String {
        val local = java.time.Instant.ofEpochSecond(epoch)
            .atZone(java.time.ZoneId.systemDefault())
        return "%02d:%02d".format(local.hour, local.minute)
    }
}
