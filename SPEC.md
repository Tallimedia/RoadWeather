# FIRoadWeather — v1 spec

What the app shows and how it behaves. Decided with Nico 2026-09-21. The *why* behind
the data lives in `claude-docs/FIRoadWeather/RESEARCH.md`; this is the build contract.

`FIWeatherWatch` shipped without a spec, straight from its research doc, and that worked.
The car client gets one anyway because template choice per screen is a real design step
and AAOS quality review fails on presentation, not on data.

---

## 1. Shape

A **Tab template** with three tabs. Road leads: it is the differentiator, and the only
thing here that no other weather app in the car has.

| Tab | Template | Purpose |
|---|---|---|
| **Road** | Pane + List | Surface temperature, ice risk, condition, 0–12 h outlook |
| **Weather** | Pane + List | Current conditions and the short forecast, from FMI |
| **About** | Pane | Attribution, disclaimer, version |

No map in v1 — that would pull in `MAP_TEMPLATES`, the Maps SDK and the map-specific
quality rules (WE-2, WE-5, MR-1) for a forecast strip and a temperature readout that are
naturally a Pane and a List.

---

## 2. Road tab

**Headline: road surface temperature, with the ice-risk line directly beneath it.**
That pairing is the whole product. Air temperature above zero says nothing about the
road — the surface radiates to the sky and runs colder, and bridges colder still — so
the surface number leads and air temperature is secondary.

```
        -1.2 °C          ← road surface, largest element on the screen
  Frost or black ice     ← ice_risk.reason, colour-coded by level
  ─────────────────────
  Road            Moist
  Air             +1.4 °C
  Dew point       -1.0 °C
  kt51 Hki Lapinlahti · 2.3 km · 4 min ago
```

- **Ice risk colours** follow `ice_risk.level`: `high` red, `moderate` amber, `low`
  neutral, `none` neutral, **`unknown` explicitly greyed and labelled**. Absent data
  must never render as reassurance — it is the one failure here that could hurt someone.
- **Station provenance is shown, always** — name, distance, age. The reading is from the
  nearest station, not from under the car, and the app says so. Same principle as
  FIWeatherWatch.
- **Outlook List** below: one row per step from `section.outlook` — `0h`, `2h`, `4h`,
  `6h`, `12h` — each showing road condition, surface temperature, and `reliability` when
  it is not `SUCCESSFUL`. Digitraffic is candid about whether a road station backed the
  forecast; the app passes that candour on rather than hiding it.
- **Section name** as the List header, e.g. *Kt 51: Helsinki – Kehä III*.

---

## 3. Weather tab

From `/v1/observations` and `/v1/forecast`, both already live.

```
  Helsinki Kaisaniemi · 0.6 km
        10.8 °C
  N 2.2 m/s  gust 3.8
  Humidity 94%   Pressure 1013.9
  ─────────────────────
  12:00  12 °C  clear      3 m/s
  15:00  11 °C  cloudy     4 m/s
  ...
```

Forecast rows come from `points[]`. `smartsymboltext` gives a plain-language condition, so
**v1 ships without a weather icon set** and satisfies WE-3 with text. FMI's open
`smartsymbol` graphics are the obvious v1.1 upgrade; note night variants are the day code
**+100**.

---

## 4. About tab

Carries the attribution both licences oblige, **in the app** and not only in the store
listing:

- Finnish Meteorological Institute — CC BY 4.0
- `Source: Fintraffic / digitraffic.fi, license CC 4.0 BY`
- Independent; not affiliated with or endorsed by either.
- The safety line: general information, not a substitute for judgement, official warnings
  or driving to the conditions.
- App version and a link to `roadweather.tallimedia.com`.

---

## 5. Location

**Car GPS, resolved when the app opens.** Road conditions are about where the car is now;
a configured place would be close to useless in a moving vehicle.

| State | Behaviour |
|---|---|
| Permission granted | Fetch `/v1/road`, `/v1/observations`, `/v1/forecast` for the fix |
| Permission denied | Message template explaining the app needs location, with a way to grant it. **No silent fallback to a default place** — a road reading for somewhere else is worse than none. |
| No fix yet | Loading state, then the last cached reading with its age if one exists |
| Outside Finland | `/v1/road` returns **404**; show "Finland only", not an error |

---

## 6. Failure and degraded states

These are the normal cases in a car, not the edges. Every one is a designed state.

| Condition | Shown |
|---|---|
| No network | Last cached reading with its age, clearly marked stale |
| No cache and no network | Message template: no connection |
| `/v1/road` 404 | "Outside Finland" |
| `station` null (Digitraffic down or no station near) | Road tab still renders from FMI; condition row reads "unavailable" |
| `section` null | Outlook List omitted entirely, not shown empty |
| `ice_risk.level == "unknown"` | Greyed, "no road surface temperature nearby" |
| `surface` empty | Road tab falls back to the Weather tab's content with an explanation |

**The backend already degrades this way** — any of the three sources may be missing
without failing the request — so the client only has to render what arrives.

---

## 7. Data contract

Single host, `roadweather.tallimedia.com`, same service as the watch backend.

| Endpoint | Used by |
|---|---|
| `GET /v1/road?lat&lon` | Road tab — `surface`, `station`, `section.outlook`, `ice_risk` |
| `GET /v1/observations?lat&lon` | Weather tab, current |
| `GET /v1/forecast?lat&lon&hours` | Weather tab, forecast strip |

All timestamps are `epochtime`, UTC. The raw FMI `time` field is **local** and is never
used. Coordinates are never logged server-side — the Play Data safety declaration depends
on it (`PLAY-CONSOLE-SETUP.md` §3).

---

## 8. Build parameters

| | |
|---|---|
| Application ID | `fi.roadweather.app` |
| minSdk / targetSdk | 29 / 35 · compileSdk 37 · **target car runs API 33** |
| Car App Library | `androidx.car.app:app-automotive` 1.7.0 |
| Category | `androidx.car.app.category.WEATHER` |
| Languages | **fi, sv, en** — default en, matching the store titles |
| Dev AVD | `FRW_api33_portrait` — exact API match to the car |

---

## 9. Explicitly out of v1

Named so they do not creep in, and so the store listing does not promise them:

- FMI warnings and notifications (IN-1 makes notifying a deliberate design act)
- Lightning proximity (`RESEARCH.md` §5.4 — gated on the latency spike)
- Any map
- Settings of any kind — AAOS restricts app-owned activities to parked flows
- Weather icons — text first, `smartsymbol` graphics in v1.1
