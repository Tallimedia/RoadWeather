# Changelog

## 0.4.2 — 2026-09-24

Found in the field, not yet confirmed fixed: the app locked with the system's own
"Ei käytettävissä ajon aikana" screen the moment the car started moving, every time,
recovering the moment it stopped. Reported after launching the app fully while parked —
not a launch-time restriction, an already-open screen going non-compliant.

- **`listRowLimit` was `by lazy`** — queried once, the first time a template was built,
  cached for the screen's whole lifetime. Since the app is always launched parked, that
  first query always captured whatever `ConstraintManager.getContentLimit()` returns while
  parked. It is now queried fresh on every template build instead.
- **Honest uncertainty, not a confirmed fix.** Checked against Google's own docs: a
  templated app cannot listen for driving-state changes at all —
  `CarUxRestrictionsManager` needs `android.car.permission.CAR_DRIVING_STATE`, a
  `@SystemApi` restricted to the platform, bundled system apps and privileged partners.
  So it isn't certain the content limit genuinely varies live with driving state, or that
  removing the cache is the actual mechanism behind the lock — only that caching it was
  wrong regardless, and querying fresh costs nothing. Needs a real drive to confirm.

## 0.4.1 — 2026-09-23

Found ahead of a long-drive test, not yet in the field: the failure path
still swapped template types.

- **A connection dropout no longer discards the reading on screen.**
  `failed -> message(...)` fired on every blip, spending two steps of the
  five-template quota per drop — the same pattern that took the app out in
  0.2.0, just triggered by weak coverage instead of movement. The error card
  now only appears on a cold start with nothing to show yet; a dropout with
  a reading already up marks it "No connection" on the head row instead and
  keeps going.
- **`failed` is excluded from the redraw signature on purpose.** A blip alone
  must not force a repaint; the stale note appears at the next redraw that
  happens anyway (a tab tap, or recovery), so noticing it a few seconds late
  costs nothing, where spending the quota on every blip costs the app.
- Matches `SPEC.md` §6, written on 2026-09-21 and unimplemented until now:
  "No network → last cached reading with its age, clearly marked stale."

## 0.4.0 — 2026-09-23

Layout, settled on the design preview against field photos before any Kotlin
was written.

- **Labels lead their own row.** A label placed last is orphaned by the
  divider beneath it and reads as a heading for the *next* row — which is how
  "Tienpinta" came to caption "ei saatavilla", making a working surface
  reading look like missing data.
- **No section headers anywhere.** The host indents them further left than
  ordinary rows and the Volvo's bezel clips them — visible in every 0.3.0
  screenshot as `/Nyt` and `>Kt 50`. Both tabs are now one flat list.
- **Both tabs open with where and when**: station, clock time, and distance
  recomputed from the live fix rather than frozen at the last fetch. Station
  coordinates now travel in both payloads to make that possible.
- **The `0h` step is gone.** Fintraffic tag it `type=OBSERVATION`, not
  `FORECAST` — it was a second "now" from a different source sitting under a
  forecast heading. Dropping it buys a fourth real step, out to 12 h.
- **Sub-zero temperatures are red**, and the four winter KELI states are
  coloured — keyed on Digitraffic's numeric code, not on the localised
  description, which would break the day a translation changed.
- **Weather forecast starts at the next hour.** FMI aligns points to the
  requested step, so 3-hourly alone left a gap of up to three hours before
  the first row — 2 h 14 min on the 09:46 drive. Now hourly twice, then
  3-hourly, with rain probability finally rendered.
- **Row counts come from the car.** `ConstraintManager.getContentLimit`
  replaces a guess copied from Android Auto's documented minimum.
- `gust` was a hardcoded English literal; it is a string resource now.

## 0.3.0 — 2026-09-22

- **The app no longer dies mid-drive.** The host allows five templates per task
  and only counts a redraw as free when it is the same type with the same main
  content. v0.2.0's reload swapped the content to a `MessageTemplate` while
  loading and back again, spending two steps every 2 km — about five kilometres
  before every tab stopped responding. A reload now keeps the previous reading
  on screen, and the loading state is pushed only on a cold start.
- **Nothing is pushed when the result would look the same.** A signature of the
  rendered content decides whether to redraw at all.

## 0.2.0 — 2026-09-22

First drive in the car produced five defects; this is all of them
(`FIELD-NOTES.md`).

- **Position follows the car.** `load()` ran once in `onCreate`, so a commute
  showed Kt 45 Kehä I from start to finish. `Locator` now streams updates
  bound to the visible lifecycle, and `MainScreen` reloads once the car has
  moved 2 km — a distance threshold rather than a timer, because the question
  is whether a different station is nearest, not whether a minute has passed.
  Also a **WE-1** risk resolved: the rule wants content relevant to the
  driver's current location.
- **The app asks for a language.** It sent none, so the backend served its
  `en` default into a Finnish UI. One parameter on all three endpoints.
- **A broken sensor reads as missing, not as a road state.** Checked both
  ways: the backend's new `condition_fault` flag, and a text check so this
  build is correct even against a backend that has not been redeployed.
- **Station names are readable** — `prettyStation`.
- **Better wording** in fi/sv/en when a section forecast has no station.

Play refuses a repeated `versionCode`, so **every upload gets a new one** — including
a rebuild of something never published, because otherwise two different binaries share
a number and a tester's report cannot be placed against either.

Design reasoning lives in `SPEC.md` and in `claude-docs/FIRoadWeather/RESEARCH.md`.

## v0.1.0 — 2026-09-21 · versionCode 1

First build. **Internal test track only.**

- **Road tab** — road surface temperature as the headline, with the derived ice-risk
  line beneath it, then condition, air temperature and dew point. Station name,
  distance and reading age always shown: the reading comes from the nearest road
  weather station, not from under the car.
- **Outlook** — road condition and surface temperature at 0/2/4/6/12 h for the nearest
  road section, marked when Digitraffic says no road station backed the forecast.
- **Weather tab** — current observation and a forecast strip from FMI.
- **About tab** — FMI and Fintraffic attribution, independence statement, safety wording.
- Finnish, Swedish and English.
- Location from car GPS, bounded at 8 s with a last-known fallback so the app cannot
  hang on "Loading…" in a garage or tunnel.

Known gaps, deliberate: no weather icons (plain-language `smartsymboltext` instead), no
warnings, no lightning, no map, no settings. See `SPEC.md` §9.
