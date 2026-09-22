# Changelog

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
