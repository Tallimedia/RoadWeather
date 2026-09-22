package fi.roadweather.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * A single position fix.
 *
 * `getCurrentLocation` rather than `getLastKnownLocation`: a stale fix from a
 * previous drive would silently report conditions for wherever the car was
 * parked, which is exactly the failure this app must not have. The last-known
 * fix is only used as a fallback while a fresh one is still coming.
 */
object Locator {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * How long to wait for a fresh fix before falling back.
     *
     * `getCurrentLocation` does not time out on its own. In a garage, a tunnel
     * or a multi-storey car park it simply never calls back, and the app would
     * sit on "Loading…" indefinitely — which is how this was found, on an
     * emulator with no GPS.
     */
    private const val FIX_TIMEOUT_MS = 8_000L

    @SuppressLint("MissingPermission")
    suspend fun fix(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        // A stale fix is a worse answer than a fresh one but a far better one
        // than no answer, and the reading carries its own age on screen.
        return withTimeoutOrNull(FIX_TIMEOUT_MS) { current(manager, provider) }
            ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager, provider: String): Location? {
        return suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager, provider, signal, Executors.newSingleThreadExecutor(),
            ) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }
    }

    /**
     * Follow the car while the app is in the foreground.
     *
     * v0.1.0 took a single fix in `onCreate` and never took another, so a
     * drive from home to work reported the same road the whole way
     * (`FIELD-NOTES.md` §1). One fix is right for a watch on a wrist and
     * wrong for a car.
     *
     * The interval is deliberately loose. What matters is not "the car moved"
     * but "the nearest station or road section is now a different one", and
     * the backend caches road data for five minutes anyway — asking more often
     * only spends the car's data on bytes that cannot have changed.
     */
    private const val UPDATE_INTERVAL_MS = 60_000L
    private const val UPDATE_DISTANCE_M = 500f

    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startUpdates(context: Context, onFix: (Location) -> Unit) {
        if (!hasPermission(context)) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return
        stopUpdates(context)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val l = LocationListener { location -> onFix(location) }
        listener = l
        runCatching {
            manager.requestLocationUpdates(
                provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, l, Looper.getMainLooper(),
            )
        }
    }

    fun stopUpdates(context: Context) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        listener?.let { runCatching { manager?.removeUpdates(it) } }
        listener = null
    }
}
