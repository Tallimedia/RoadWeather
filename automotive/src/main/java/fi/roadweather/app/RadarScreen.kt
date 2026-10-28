package fi.roadweather.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** How far the car must travel before a new radar tile is fetched. Mirrors
 * [MOVED_ENOUGH_M] in [MainScreen] — same reasoning, separate screen. */
private const val RADAR_MOVED_ENOUGH_M = 2_000f

/**
 * Rain radar: FMI's national reflectivity composite centred on the car,
 * drawn directly into the host-provided [android.view.Surface].
 *
 * Reached from a row on the Weather tab, not a fifth [androidx.car.app.model.TabTemplate]
 * tab — [MapWithContentTemplate] cannot be used as tab content; verified
 * against `TabContentsConstraints` before building this (RESEARCH.md §5.5).
 * `@RequiresCarApi(7)`, so [MainScreen] only shows the entry row when
 * `carContext.carAppApiLevel >= 7`; nothing here needs to re-check that.
 *
 * National reflectivity, not HydroClass: HydroClass exists only per-radar-site
 * with no national mosaic, so most of a drive away from one of Finland's ~10
 * sites would render blank (RESEARCH.md §5.5). A v2 question, not a v1 one.
 */
class RadarScreen(
    carContext: CarContext,
    private var lat: Double,
    private var lon: Double,
) : Screen(carContext), DefaultLifecycleObserver, SurfaceCallback {

    init {
        // Without this, onCreate/onStart/onStop below are dead code — a
        // Screen does not auto-register itself as an observer of its own
        // lifecycle. Missed here at first despite MainScreen doing the same
        // thing correctly; the bug was silent (no crash, no log output at
        // all) because nothing ever called load() in the first place.
        lifecycle.addObserver(this)
    }

    private var surfaceContainer: SurfaceContainer? = null
    private var bitmap: Bitmap? = null
    private var loading = true
    private var failed = false
    private var updatedAt: String? = null

    /**
     * The position the most recent [load] call targeted — set synchronously
     * when that call starts, not when it finishes. Gating on this rather
     * than on a "last successful load" position closes a race: the first
     * [onStart] location callback used to fire before [onCreate]'s own
     * [load] had finished, with nothing yet recorded to compare against, so
     * every screen open fired two concurrent fetches. On this emulator the
     * loser hung rather than erroring, leaving "Loading…" on screen forever.
     */
    private var requestedLat = lat
    private var requestedLon = lon

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        load()
    }

    override fun onStart(owner: LifecycleOwner) {
        Locator.startUpdates(carContext) { fix ->
            val moved = FloatArray(1)
            android.location.Location.distanceBetween(
                requestedLat, requestedLon, fix.latitude, fix.longitude, moved,
            )
            if (moved[0] < RADAR_MOVED_ENOUGH_M) return@startUpdates
            lat = fix.latitude
            lon = fix.longitude
            load()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Locator.stopUpdates(carContext)
        // Confirmed crash, real car, 2026-10-28: open radar, back, open radar
        // again — the host handed the *second* RadarScreen instance a Surface
        // that passed isValid but threw IllegalArgumentException from native
        // lockCanvas, killing the app outright. Root cause: the first
        // instance never released its SurfaceCallback registration when
        // popped, leaving the host's surface session in a state the next
        // registration couldn't cleanly take over. AppManager.setSurfaceCallback's
        // own docs name null as how to release it — this is that release.
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    private fun load() {
        requestedLat = lat
        requestedLon = lon
        loading = true
        failed = false
        invalidate()
        lifecycleScope.launch {
            try {
                val png = Api.radarPng(lat, lon)
                bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                updatedAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(System.currentTimeMillis())
                failed = bitmap == null
            } catch (e: Exception) {
                Log.e("RadarScreen", "radar load failed", e)
                failed = true
            }
            loading = false
            draw()
            invalidate()
        }
    }

    // ------------------------------------------------------------ surface

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = null
    }

    /**
     * Centre-crop the square tile into whatever rectangle the host handed
     * over. The tile is always square (backend `size x size`); the surface
     * usually is not, so a plain stretch would distort the coastline.
     */
    private fun draw() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return
        // Defence in depth alongside the onStop cleanup below: isValid can
        // pass while the host's surface session is still stale underneath
        // (confirmed live, real car, 2026-10-28 — see onStop), so lockCanvas
        // itself can still throw. Skipping one frame beats crashing the app.
        val canvas: Canvas = try {
            surface.lockCanvas(null) ?: return
        } catch (e: IllegalArgumentException) {
            Log.e("RadarScreen", "lockCanvas failed on a surface that reported valid", e)
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val bmp = bitmap ?: return
            val dstW = container.width.toFloat()
            val dstH = container.height.toFloat()
            val srcAspect = bmp.width.toFloat() / bmp.height.toFloat()
            val dstAspect = dstW / dstH
            val src = if (srcAspect > dstAspect) {
                // Source wider than destination: crop its sides.
                val cropW = (bmp.height * dstAspect).toInt()
                val x0 = (bmp.width - cropW) / 2
                Rect(x0, 0, x0 + cropW, bmp.height)
            } else {
                val cropH = (bmp.width / dstAspect).toInt()
                val y0 = (bmp.height - cropH) / 2
                Rect(0, y0, bmp.width, y0 + cropH)
            }
            val dst = Rect(0, 0, container.width, container.height)
            canvas.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    // ------------------------------------------------------------ template

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(statusRow())
            .apply {
                if (failed) {
                    addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.retry))
                            .setOnClickListener { load() }
                            .build(),
                    )
                }
            }
            .build()
        return MapWithContentTemplate.Builder()
            .setContentTemplate(PaneTemplate.Builder(pane).build())
            .setActionStrip(ActionStrip.Builder().addAction(Action.BACK).build())
            .build()
    }

    private fun statusRow(): Row {
        val title = when {
            failed && bitmap == null -> carContext.getString(R.string.radar_error)
            loading && bitmap == null -> carContext.getString(R.string.radar_loading)
            updatedAt != null -> carContext.getString(R.string.radar_frame_at, updatedAt)
            else -> carContext.getString(R.string.radar_title)
        }
        return Row.Builder()
            .setTitle(title)
            .addText(carContext.getString(R.string.radar_attribution))
            .build()
    }
}
