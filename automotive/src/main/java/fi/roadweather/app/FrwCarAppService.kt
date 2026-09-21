package fi.roadweather.app

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The entry point the templates host binds to.
 *
 * The WEATHER category in the manifest is what makes this a weather app to
 * Play and to the host; without it the app is not distributable to cars.
 */
class FrwCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds run against the emulator's host, which is not in
            // the shipped allow-list. Release builds must never do this.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = FrwSession()
}
