package fi.roadweather.app

/**
 * Maps FMI's `smartsymbol` code (`opendata.fmi.fi`, `symbols/SmartSymbol/README.md`
 * in `fmidev/opendata-resources`) to a drawable. Night is the same code +100 — not
 * always a distinct symbol per FMI's own table, but the live backend emits it anyway
 * (e.g. 107 for overcast after dark), so the +100 offset is always stripped first.
 *
 * **v0.6 first pass: 9 drawables, not all ~15 distinct conditions FMI's 107 codes
 * collapse to.** Drizzle and hail fold into rain; all three sleet tiers and all three
 * snow intensities share one snow icon. Reasoning: WE-3 requires icons on forecast
 * rows at all — it does not require every intensity to read differently at a glance,
 * and the addText line under the icon already carries the exact wording
 * (`symbolText`). Expand this table once the mechanism itself is confirmed legible in
 * the car, rather than before.
 */
object WeatherIcon {
    fun forCode(code: Int?): Int {
        val base = code?.let { if (it > 100) it - 100 else it }
        val isNight = code != null && code > 100
        return when (base) {
            1, 2 -> if (isNight) R.drawable.ic_wx_clear_night else R.drawable.ic_wx_clear
            4, 6 -> if (isNight) R.drawable.ic_wx_partly_cloudy_night else R.drawable.ic_wx_partly_cloudy
            7 -> R.drawable.ic_wx_cloudy
            9 -> R.drawable.ic_wx_fog
            71, 74, 77 -> R.drawable.ic_wx_thunder
            in 41..49, in 51..59 -> R.drawable.ic_wx_snow
            11, 14, 17, 21, 24, 27, in 31..39, 61, 64, 67 -> R.drawable.ic_wx_rain
            else -> R.drawable.ic_wx_cloudy
        }
    }
}
