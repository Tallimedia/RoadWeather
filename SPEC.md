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

## 9. v1.1 — the Weather tab, properly

Requested by Nico 2026-09-22. **Not v1.** Recorded here with the data verified so the
next build is a build, not another research pass.

The v1 Weather tab is deliberately thin — a temperature, a wind line and five forecast
rows in plain text. v1.1 makes it a weather page worth opening on its own:

| Wanted | Source | Status |
|---|---|---|
| Current temperature | `/v1/observations` `temperature` | Already served |
| Wind | `windspeedms`, `windgust`, `windcompass8` | Already served |
| **Rain probability** | `pop` | **Verified working, not yet in `/v1/forecast`** |
| Rain amount | `precipitation1h` | Already served |
| **Weather icon** | `smartsymbol` + FMI's open icon set | Needs drawables |
| Next few hours | `/v1/forecast` points | Already served |

**`pop` checked live 2026-09-22** against Helsinki over five days: range 0–68.4 %, and it
tracks the symbol and the amount sensibly — 68 % with overcast and 0.0 mm, 56 % with
showers and 0.4 mm. So probability and amount say genuinely different things and both
earn their place. Thunderstorm probability (`probabilitythunderstorm`) also resolves, if
it ever becomes interesting.

**Backend change:** add `pop` to the `/v1/forecast` parameter list. That is the whole
backend cost.

**Icons.** FMI publish the `smartsymbol` set openly in
[fmidev/opendata-resources](https://github.com/fmidev/opendata-resources/tree/master/symbols).
Codes run 1–107 with **night variants at +100** — confirmed again in the same pull, where
`104` and `106` came back for after-dark rows. They do not need one drawable each: the
107 codes collapse to roughly fifteen distinct conditions, and a `when` mapping to a small
vector set is far less work and far more legible on a car screen than 107 bitmaps.

Quality rule **IU-1** forbids images in car apps *except* "a single static image for
content context", and names a weather icon as the example — so this is explicitly allowed
rather than merely tolerated.

### One expectation to set now

**"Graphical" cannot mean a chart.** The Car App Library gives no drawing canvas: the only
template with a `Surface` is `MapWithContentTemplate`, which is for maps and drags in the
map-specific quality rules (WE-2, WE-5, MR-1). A temperature curve or a rain graph is not
available to a templated app at any effort level.

What *is* available: an icon per row, a headline value, and up to six rows of structured
text per list. So v1.1 looks like a clean icon-led list, not like a phone weather app.
Worth agreeing before it is built rather than after.

---

## 10. Explicitly out of v1

Named so they do not creep in, and so the store listing does not promise them:

- FMI warnings and notifications (IN-1 makes notifying a deliberate design act)
- Lightning proximity (`RESEARCH.md` §5.4 — gated on the latency spike)
- Any map
- Settings of any kind — AAOS restricts app-owned activities to parked flows
- Weather icons — text first, `smartsymbol` graphics in v1.1
