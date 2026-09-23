# FIRoadWeather — v1 spec

What the app shows and how it behaves. Decided with Nico 2026-09-21. The *why* behind
the data lives in `claude-docs/FIRoadWeather/RESEARCH.md`; this is the build contract.

`FIWeatherWatch` shipped without a spec, straight from its research doc, and that worked.
The car client gets one anyway because template choice per screen is a real design step
and AAOS quality review fails on presentation, not on data.

---

## 1. Shape

A **Tab template** with three tabs, in the order Road, Weather, Warnings.

**About is not a tab** (Nico, 2026-09-22). Three tabs is the budget worth spending, and
attribution does not deserve one of them. Verified against the AAR: the tab strip's own
header slot cannot hold it — `TabTemplate.setHeaderAction` validates against
`ACTIONS_CONSTRAINTS_TABS`, which is `ACTIONS_CONSTRAINTS_HEADER` plus
`addRequiredActionType(65538)`, and `Action.TYPE_APP_ICON == 65538`. That slot is the app
icon or nothing. The **ActionStrip on each tab's own `ListTemplate`** takes custom actions
with click listeners (`ACTIONS_CONSTRAINTS_SIMPLE`), so an **ⓘ** there pushes the About
screen. The licences are still satisfied: the credit is in the app, one tap away.

**The app opens on Weather** (Nico, 2026-09-22): it is what you want when you get in the
car, and since v1.1 it carries the road reading beside the air one, it is the overview.
Road stays first in the tab strip and becomes the detail view behind it — one constant in
`MainScreen` decides the landing tab, so the order and the default can be changed
independently and cheaply.

| Tab | Template | Purpose |
|---|---|---|
| **Road** | List | Surface temperature, ice risk, condition, 0–12 h outlook |
| **Weather** | List | Weather and road now, then twelve hours ahead — **the landing tab** |
| **Warnings** | List | FMI land warnings, then road works and notices nearby |
| *About* | Message | Reached by an **ⓘ** in the action strip, not by a tab |

No map in v1 — that would pull in `MAP_TEMPLATES`, the Maps SDK and the map-specific
quality rules (WE-2, WE-5, MR-1) for a forecast strip and a temperature readout that are
naturally a Pane and a List.

---

## 2. Road tab

> **Shipped in 0.4.0.** The layout below is the v1 design; §2a records what the
> tab actually renders now, after three drives' worth of corrections.

### 2a. As built (0.4.0)

```
Kt 51 Hki Lapinlahti
Tiesääasema · klo 17.23 · 2,2 km sijainnistasi
──────────────────────────────────────────────
Tienpinta  11,9 °C          ← red below zero
Ei liukkausriskiä
Ilma 11,3 °C · Kastepiste 10,3 °C
──────────────────────────────────────────────
Keli  Kuiva                 ← coloured by KELI code 5/6/7/9
Jäätyy -0,1 °C · suolaa 0,0 g/m²
──────────────────────────────────────────────
Kt 51: Helsinki - Kehä III
Ennuste — arvio, ei tiesääasemaa tällä välillä
──────────────────────────────────────────────
2 h   11,0 °C   Märkä
4 h   10,0 °C   Kostea
6 h    9,0 °C   Kostea
12 h   7,0 °C   Märkä
```

Three rules this encodes, each bought with a defect:

- **A label leads its row, never trails it.** Trailing, it is orphaned by the
  divider and reads as a heading for the next row — "Tienpinta" appeared to
  caption "ei saatavilla" while actually reporting the temperature above it.
- **No `SectionedItemList`.** The host indents section headers further left
  than rows and the Volvo bezel clips them. There is no padding or inset API
  anywhere in the library, so the answer is not to use headers.
- **`0h` is not a forecast.** Fintraffic tag it `type=OBSERVATION`. It is
  filtered out, which buys a fourth real step.

Row counts come from `ConstraintManager.getContentLimit(CONTENT_LIMIT_TYPE_LIST)`
rather than the six copied from Android Auto's documented minimum.

### 2b. Original v1 design

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
- **Every number says what it is** (Nico, 2026-09-22 — the data was right but unexplained).
  A Row has only two secondary lines, so the explanation that does not fit a row goes in
  the section header: *"Road surface now — measured on the road, not the air"* glosses the
  whole group at no cost in rows. Two raw numbers were replaced by the derived ones the
  backend already serves from Digitraffic and which a driver can act on:

  | Was | Is |
  |---|---|
  | `Dew point -1.0 °C` | `0.6 °C above the dew point — frost forms at 0`, and below zero it flips to *"0.1 °C below the dew point — moisture condensing on the surface"* and turns amber |
  | — | `Freezes at -0.1 °C` with the salt load beneath it, so the reader sees why it is not zero |
  | `· reliability NO_DATA_FROM_ROADSTATION` | `Estimated — no sensor on this stretch` |
  | `0h` / `2h` | `Now` / `In 2 h` |
  | `kt51_Hki_Lapinlahti` | `Kt 51 Hki Lapinlahti`, labelled *Nearest road sensor* |

---

## 3. Weather tab

> **Shipped in 0.4.0.** As built:

```
Helsinki Kaisaniemi
Havainto klo 17.20 · 0,7 km sijainnistasi
──────────────────────────────────────────────
Nyt      11,4 °C
Tuuli NE 3,0 m/s · puuska 5,8
Pilvistä
──────────────────────────────────────────────
klo 18   11,4 °C
Tuuli NE 4,3 m/s · puuska 7,4
Pilvistä · sade 65 %
──────────────────────────────────────────────
klo 19 · klo 22 · klo 01 …
```

`Nyt` and every forecast step share one shape, so the temperature, wind and
condition columns line up and can be compared by scanning down.

**Hourly for the first two steps, then three-hourly.** FMI aligns points to
the requested step, so a pure three-hourly request left a gap of up to three
hours before the first row — 2 h 14 min on the 09:46 drive. The app asks for
`step=60` and picks indices 0, 1, 4, 7, 10.

**The distance is recomputed from the live fix**, not carried from the
backend: the served figure is only true for the coordinates that request held
and freezes until the next fetch, up to 2 km stale. Station coordinates travel
in both payloads for this.

### 3a. Original v1 design

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

## 4. About screen

Reached by the **ⓘ** action, not a tab (§1). A **MessageTemplate**, not a list. As four full-height Rows it dominated the screen for
content nobody reads twice (Nico, 2026-09-22); a MessageTemplate is one icon and one
block of text, and still carries the attribution both licences oblige, **in the app** and
not only in the store listing:

- Finnish Meteorological Institute — CC BY 4.0
- `Source: Fintraffic / digitraffic.fi, license CC 4.0 BY`
- Independent; not affiliated with or endorsed by either.
- The safety line: general information, not a substitute for judgement, official warnings
  or driving to the conditions.
- App version and a link to `roadweather.tallimedia.com`.

---

## 4b. Warnings tab

Added 2026-09-22 at Nico's suggestion, on `/v1/warnings`. The research behind the source
choice is `RESEARCH.md` §5.6; the short version is that **FMI alone would show an empty
tab**. Checked live that day, the CAP feed held six alerts and every one was marine, on a
land-and-road-only app. Fintraffic's road works are what make the tab worth opening.

```
  Warnings
  ─────────────────────
  (only when one exists — severity-coloured, never a placeholder)
  Road works and notices · within 25 km
  2.5 km   Tie 45, Helsinki
           One carriageway closed
           Road work · until 30 Sep 2027
  2.6 km   Tie 4, Helsinki
           Multiple lanes closed
  4.8 km   Tie 100 Hakamäentie
           Road closed · detour
```

- **Alerts are matched by point-in-polygon, never by event name.** A driver is not inside
  a sea-area polygon, so marine warnings filter themselves out without a keyword list that
  would go stale — and **WE-1** wants location relevance rather than a national list.
- **Notices are ranked by distance alone**, not by category. A closed lane 2 km away
  matters more than an announcement 20 km off, whatever it is called. Default radius
  25 km; beyond that it is not on the route in any useful sense.
- **The restriction is the headline**, not the word "roadwork": `SPEED_LIMIT 50 km/h`,
  `SINGLE_CARRIAGEWAY_CLOSED`, `ROAD_CLOSED · DETOUR`, `TRAFFIC_LIGHTS`. Fintraffic serve
  these as an enum with quantities, so they translate cleanly into fi/sv/en.
- **Empty is a designed state, and a real one.** Utsjoki returns zero of both. The tab
  says so plainly rather than showing a spinner or an encouraging placeholder.
- **IN-1 does not apply.** It governs notifications, not display. Pushing an alert at the
  driver stays out of scope; showing one on a tab is unconstrained.

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
| `GET /v1/forecast?lat&lon&hours&step` | Weather tab, forecast strip |
| `GET /v1/warnings?lat&lon&lang&radius_km` | Warnings tab — `warnings`, `road_notices` |

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

Requested by Nico 2026-09-22, refined against a live mockup the same day. **Not v1.**
The layout below is what the preview at `roadweather.tallimedia.com/demo` renders from
live data; it is a build contract, not a sketch.

### What the tab shows

```
  Now, where you are                     ← section header
  ☾  10.7 °C                             ← FMI observation + smartsymbol
     Clear · rain 0 %
     Wind N 2.6 m/s · gust 4.8
  ▦  Road 10.2 °C                        ← the same place, from /v1/road
     Dry · no ice risk
     Kt 51 Hki Lapinlahti · 2.2 km
  Next 12 hours
  ☁  06.00   9 °C
     Clear
     N 4 m/s · rain 0 %
  ... four rows, three-hour steps
```

Weather now and road now sit together, which is the point: the driver asked one question
and both halves of the answer are on the first screen. The Road tab stops being somewhere
you must go and becomes the detail behind a summary.

| Wanted | Source | Status |
|---|---|---|
| Current temperature | `/v1/observations` `temperature` | Served |
| Wind | `windspeedms`, `windgust`, `windcompass8` | Served — **optional**, Utsjoki reports none, so the line is omitted rather than filled with dashes |
| Rain probability | `pop` | **Added to `/v1/forecast` 2026-09-22** |
| Rain amount | `precipitation1h` | Served — appended only when non-zero |
| Road temperature, surface state, ice risk | `/v1/road` | Served |
| Weather icon | `smartsymbol` + FMI's open icon set | Needs drawables |
| Next 12 hours | `/v1/forecast?hours=21&step=180` | Served — `points[0]` is the current hour, so the outlook starts at `[1]` |

### The template budget, measured rather than assumed

Read off the shipped `androidx.car.app:app-automotive:1.7.0` AAR, because this is what
decides whether a layout is a design or a wish:

| | |
|---|---|
| A tab holds | exactly **one** template (`TabContents`) |
| `Row` | optional icon + title + **2** secondary lines (`ROW_CONSTRAINTS_SIMPLE`, `maxTextLinesPerRow = 2`) |
| `GridItem` | image + title + **1** text line. No second line exists |
| Safe item budget | **6**, from Android Auto's documented minimum — **superseded**: 0.4.0 queries `ConstraintManager.getContentLimit(CONTENT_LIMIT_TYPE_LIST)` at runtime instead of assuming this number. This car returned more than 6 (§2a). Query it here too rather than reusing this row |
| `TabTemplate` | `@RequiresCarApi(6)` — which is why `minCarApiLevel` is 6 |
| Template quota | **5 templates per task**, and a changed row title counts as a new one, not a free refresh — found the expensive way in 0.2.0/0.4.1 and written up in `RESEARCH.md` §3a. Any design here that redraws on a timer or on location has to be built around that from the start, not discovered by it |
| `SectionedItemTemplate`, `RowSection`, `GridSection` | present in 1.7.0 but **`@ExperimentalCarApi`** |

**The consequence, and it killed the first mockup.** Rows and grid tiles cannot share a
tab on the stable API — mixing them needs `SectionedItemTemplate`, which is still
experimental. The first preview drew two rows above a six-tile forecast grid. It looked
right and was not buildable.

### The one open choice: "Now" as rows or as tiles

Both are live in the preview behind a switch, because the trade-off is not obvious on
paper:

| | **Stacked rows** (recommended) | **Side-by-side tiles** |
|---|---|---|
| Template | `ListTemplate` — stable | `SectionedItemTemplate` — experimental |
| Literally side by side | No | Yes |
| Wind on the first screen | Yes | **No** — a `GridItem` has one text line, and it is spent on the condition |
| Station provenance | Yes | No |
| Forecast rows below | 4 (12 h) | 6 (18 h) |

Rows win on information per screen and on not shipping an experimental API to a car.
Tiles win only on the literal arrangement. **Nico to pick from the preview.**

### Charts — possible, and gated by review rather than by the API

An earlier version of this section said a rain graph was "not available at any effort
level". **That was wrong**, and the correction matters because it reopens a real option.

What is true: there is no live drawing surface. The only template with a `Surface` is
`MapWithContentTemplate`, there is no `WebView` in any template, and on AAOS a templated
app may not provide functionality through its own activities except setup, settings and
sign-in while parked — so an iframe or an embedded web chart is genuinely out.

What is *not* true is that the pixels are unreachable. Checked against the AAR:
`CarIconConstraints.DEFAULT` permits `{TYPE_BITMAP, TYPE_RESOURCE}`, so
`IconCompat.createWithBitmap()` is an accepted `CarIcon`. A chart rendered to a bitmap —
by the backend as a PNG, which costs almost nothing since the backend already exists — can
go in `Pane.setImage()`, `Row.setImage(…, IMAGE_TYPE_LARGE)` or a `GridItem` image.

The real gate is **IU-1**, whose default is that app elements display no images, with four
exceptions. The third is the one that matters here:

> Apps can display images and photographs to aid in driving decision-making.

A precipitation chart has a genuine argument under that clause. It is not a certainty —
it is a reviewer's judgement, and the review is blocking at open testing and production.
Two further constraints are firm: **SA-1 forbids animation**, so a static chart and not a
radar loop (canvas animation is allowed only while parked); and a bitmap does not follow
the car's theme, so it has to be rendered for a black background or served twice.

**Unverified, and it decides whether this is worth anything:** how large the image slots
actually render on a **800 × 1280 portrait** screen. `Pane.setImage` is the biggest slot in
the library, but pane images are usually laid out as a side panel in landscape, and a
postage stamp on the right of a portrait screen would make the whole idea pointless.

**Cheap way to find out, in this order:** the internal test track is **not reviewed**, so a
throwaway build with a chart bitmap answers the size and legibility question in the real
car at zero risk of a rejection. Only if it looks good does the IU-1 argument need making.

**Icons.** FMI publish the `smartsymbol` set openly in
[fmidev/opendata-resources](https://github.com/fmidev/opendata-resources/tree/master/symbols).
Codes run 1–107 with **night variants at +100**. They do not need one drawable each: the
107 codes collapse to roughly fifteen distinct conditions, and a `when` mapping to a small
vector set is far less work and far more legible on a car screen than 107 bitmaps.

**Icons are required, not merely permitted** — and an earlier draft of this paragraph had
it wrong. It claimed IU-1 "names a weather icon as the example"; IU-1 in fact says *"a
single static image for content context, **such as album art or the advertiser's corporate
logo**"*, which is about a backdrop, not a per-row symbol.

The rule that actually governs this is **WE-3**, from the weather category itself:

> Forecast information must include easily readable icons and symbols.

So the forecast rows are *obliged* to carry icons. That reframes them from a v1.1
nicety into a **likely gap at the blocking review** for open testing or production —
worth closing before either, not after.

## 10. Explicitly out of v1

Named so they do not creep in, and so the store listing does not promise them:

- FMI warnings and notifications (IN-1 makes notifying a deliberate design act)
- Lightning proximity (`RESEARCH.md` §5.4 — gated on the latency spike)
- Any map
- **Settings — by choice, not because they are forbidden.** An earlier draft of this line
  said AAOS restricts app-owned activities to parked flows, implying settings were
  unavailable. That was misleading: `RESEARCH.md` §1 records that setup, settings and
  sign-in are the three *explicit exceptions* to that restriction, and Foreca's car app
  ships a settings screen (units, feedback address, terms, software licences) in this very
  Volvo — seen **while parked**, which is exactly the exception and so proves availability
  rather than anything about driving. They are excluded because a Finland-only app has
  nothing worth configuring —
  °C, m/s and mm each have exactly one sensible answer here — not because the platform
  says no. **WE-4** does forbid one specific setting: forecast intervals must not be
  customisable via templates.
- Weather icons — text first, `smartsymbol` graphics in v1.1
