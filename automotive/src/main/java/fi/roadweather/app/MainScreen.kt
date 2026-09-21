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

    private var road: Road? = null
    private var observation: Observation? = null
    private var forecast: List<ForecastPoint> = emptyList()

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (Locator.hasPermission(carContext)) load() else requestLocation()
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

    private fun load() {
        loading = true
        failed = false
        outsideCoverage = false
        invalidate()
        lifecycleScope.launch {
            val fix = Locator.fix(carContext)
            if (fix == null) {
                loading = false; failed = true; invalidate(); return@launch
            }
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
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeTab = tabContentId
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
        loading -> message(R.string.loading, null, null)
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
                .setTitle(r?.station?.condition ?: carContext.getString(R.string.unavailable))
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
                    .setTitle(name)
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
