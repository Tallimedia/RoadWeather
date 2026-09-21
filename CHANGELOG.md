# Changelog

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
