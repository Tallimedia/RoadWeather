# FIRoadWeather

Finnish weather and road conditions for Android Automotive OS — a templated
Car App Library app for cars with Google built-in, targeting a Volvo XC60.

Internally **FRW**. Published as *Finnish RoadWeather* / *Suomen tiesää* /
*Finlands vägväder*.

| | |
|---|---|
| Application ID | `fi.roadweather.app` |
| Backend | `roadweather.tallimedia.com` |
| minSdk / targetSdk | 29 / 35 (the target car runs Android 13, API 33) |

## Data sources

- **Finnish Meteorological Institute** — land forecast, observations and road-station
  weather. CC BY 4.0.
- **Fintraffic Digitraffic** — categorical road condition and 0–12 h road-condition
  forecasts. *Source: Fintraffic / digitraffic.fi, license CC 4.0 BY.*

Both are reached through the shared backend, never directly from the car.

## Documentation

Research, architecture and the programme roadmap live in the **claude-docs** repo,
not here:

- `FIRoadWeather/RESEARCH.md` — feasibility, data sources, AAOS constraints, publishing
- `FI-WEATHER-ROADMAP.md` — what to do next, across this and FIWeatherWatch

`RESEARCH.md` appears in this working copy but is owned and tracked by claude-docs, so
it is gitignored here.
