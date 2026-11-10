# Changelog

## 0.8.2 — 2026-11-10

**No code changes — version bump only, for the first production release.** Same reason
as 0.7.0: Play rejects a repeated `versionCode` across every track forever, and 16
(v0.8.1) was already used on closed testing. The EU trader/DSA declaration surfaced as
predicted during the production submission flow, submitted as an individual. Identical
to 0.8.1 otherwise. **Sent for Google's production review 2026-11-10** — the full
review, not closed testing's lighter pass.

## 0.8.1 — 2026-10-28

**Fixed a real crash, found in the real car.** Reported by Nico, same-day fix: open the
rain radar, go back to the Weather tab, open the radar again — the app crashed outright
to the car's home launcher. Reproduced on the first try in the simulator with the exact
repro steps, which gave a clean stack trace: `IllegalArgumentException` from native
`Surface.lockCanvas`, thrown from `RadarScreen.onSurfaceAvailable`.

Root cause: `RadarScreen` registered itself as the car's `SurfaceCallback` in `onCreate`
but never released that registration when the screen was popped. On reopening, the host
handed the *new* `RadarScreen` instance a `Surface` that reported `isValid == true` but
was still stale underneath — `lockCanvas()` threw, uncaught, and took the whole app down.
`AppManager.setSurfaceCallback`'s own docs name `null` as how to release it; `onStop` now
does exactly that. Also wrapped `lockCanvas()` itself in a try/catch as a second line of
defence — an unforeseen edge case on some other head unit now skips one frame instead of
crashing.

Verified fixed across four consecutive open/back/open cycles in the simulator before
building this release — not just the one repro that found it. **Uploaded to Play
Console 2026-10-28.**

## 0.8.0 — 2026-10-27

**Rain radar — a new screen on the Weather tab.** FMI's national reflectivity composite
(`suomi_dbz_eureffin`) centred on the car, with a Maanmittauslaitos (National Land Survey
of Finland) background map underneath — roads, place names, water, crisp at any zoom,
not the flat grey basemap an earlier internal build tried first. Reached from a "Rain
radar" row on the Weather tab, gated to hosts negotiating Car App API level 7 or higher;
hidden rather than crashing on an older host.

Built on `MapWithContentTemplate`, not `Pane.setImage()` — a throwaway test earlier in
development found `Pane.setImage()` only renders a 195–239px thumbnail, nowhere near a
real radar view. The app draws the composited tile directly into the `Surface` the host
provides via a registered `SurfaceCallback`.

New backend endpoint, `/v1/road/radar`, live on `roadweather.tallimedia.com` — reuses the
compositing engine already proven in production for `FIBikeWeather`'s `/v1/bike/radar`,
now also serving the car's larger tile and a Finland-detailed basemap stitched from
Maanmittauslaitos's open tile service.

Full technical account — including two real bugs found building it (a `TabTemplate` can't
hold more than 4 tabs; a pushed `Screen` must register `lifecycle.addObserver(this)`
itself or its lifecycle callbacks are silently dead code) — in `claude-docs`'s
`RESEARCH.md` §5.5 and this repo's own `FIELD-NOTES.md`.

Not yet field-tested in the real car — verified end-to-end in the simulator and against
the live production backend. **Uploaded to Play Console's closed-testing track
2026-10-27, in review.**

## 0.7.1 — 2026-10-19

**New app icon — Play listing and the in-app launcher badge both replaced.** The old
"0°" + road-perspective design (`store/make_store_art.py`) is retired. New mark: a
road-hazard triangle merging into a road, with a snowflake centred inside —
Nico's concept, several rounds of real-size testing to get there (see
`FIELD-NOTES.md` for the full process, including why AI-generated line art kept
failing a test that mattered: a big bitmap resized down to 48px loses fine detail
regardless of how good the source looks, so every candidate had to be checked at
**actual** launcher size, not just inspected at full resolution).

**Known, accepted tradeoff:** the snowflake reads clearly from `xhdpi` (96px) up,
including the largest `xxxhdpi` (192px) bucket most modern screens actually use, but
blurs into an indistinct dot at the smallest `mdpi` bucket (48px). Not fixed —
AAOS head units are higher-density displays, so `mdpi` is the bucket this app is
least likely to actually render at. Revisit only if that assumption turns out wrong
in the field.

Source asset is the artist's own exported PNG/JPEG (not the hand-coded PIL version
from earlier in the same session, which was rejected for visible rendering
artifacts — visible line-join seams on the triangle). No app logic changed.

## 0.7.0 — 2026-10-19

**No code changes — version bump only, for the first closed-testing release.** Play
rejected re-uploading versionCode 12 (already used on the internal track; version
codes must be unique and increasing across every track, not just the one being
uploaded to), and this is also the first release aimed at testers outside the one car
— marking it 0.7.0 rather than a silent 0.6.2 reflects that it's the closed-beta
launch build, not a fix. Identical to 0.6.1 otherwise.

**Submitted for Google review 2026-10-19**, alongside every other unsent app-content
change (country targeting, AAOS form factor opt-in, content rating, data safety, ads
declaration, store listing) — this was the first review submission for the app at
all, not just this release. `PLAY-CONSOLE-SETUP.md` §8 has the full account of what
that actually involved, including two corrections made before submitting: target
audience had defaulted to 16+ somewhere (fixed back to the documented 13+), and the
closed-track feedback address moved to `afterdiver@gmail.com` since
`iot@tallimedia.com` turned out to be receive-only (no SMTP on the domain at all).

## 0.6.1 — 2026-10-15

**Weather icons bumped from 24dp to 36dp.** Raised by Nico while scoping v1.0 screen
diversity (`RESEARCH.md` §11): the `WeatherIcon.kt` drawables shipped in v0.6.0 at
24dp, the smallest of Android Auto's own three documented icon tiers (24/36/44dp),
below the 36dp the weather-app UX guidelines say icons **should** meet. Confirmed on
both the AAOS portrait (800×1280, non-Play, the exact match to the real XC60) and
landscape (1024×768, Play) emulator images before shipping — larger icons render
cleanly on both, no row overflow, no regression to the content-limit row budget.

No logic changed — only `android:width`/`android:height` on all 9 `ic_wx_*.xml`
drawables; `android:viewportWidth`/`viewportHeight` (the path coordinate system)
untouched.

## 0.6.0 — 2026-10-05

**Weather icons on the forecast rows — the item `SPEC.md` §9 flagged as a likely gap
at the blocking review** (WE-3: "forecast information must include easily readable
icons and symbols"). FMI's `smartsymbol` code was already in the backend's
`/v1/forecast` response (`smartsymbol: 107` alongside the existing
`smartsymboltext`), so this needed no backend change — only parsing the numeric code
client-side (`Api.kt`) and mapping it to a drawable (`WeatherIcon.kt`), then wiring it
onto each forecast `Row` via `setImage()`.

Checked against the AAR before writing any XML: `Row.Builder.setImage(CarIcon)`
validates against `CarIconConstraints.UNCONSTRAINED`, and the `ListTemplate` row
constraints used here (`ROW_CONSTRAINTS_SIMPLE`) have `setImageAllowed(true)` — icons
on these rows are a supported, unconstrained path, not something to find out about in
the field.

**First pass: 9 drawables, not the ~15 distinct conditions `SPEC.md` estimated.**
FMI's 107 codes collapse onto them as: drizzle and hail fold into the rain icon; all
three sleet tiers and all three snow intensities share one snow icon (`WeatherIcon.kt`
has the full table and the reasoning). The exact wording is still in the text line
under the icon either way — this collapses *symbol* granularity, not information.

The "Now" row never gets an icon: confirmed live against `/v1/observations` that FMI
only assigns a `smartsymbol` to forecast points, never to a raw observation — there is
no code to show.

**Untested in the car — this is what the beta upload is for.** The build compiled
clean (a malformed vector path fails at `aapt2`, not silently), so the icons are
syntactically valid; whether they're legible at the actual row-icon size on the real
800×1280 screen is the open question this version exists to answer.

## 0.5.3 — 2026-09-30

**The Warnings tab error is found and fixed, with certainty this time.** The v0.5.2
try/catch did its job: the next drive surfaced the exact exception instead of a silent
failure — `"CarSpan type is not allowed: ForegroundCarColorSpan"`.

Confirmed byte-for-byte against the Car App Library AAR (`javap -c -p`, no source
available): `Row.Builder.setTitle()` validates its `CarText` against
`CarTextConstraints.TEXT_AND_ICON`, whose allowed span set is `{DistanceSpan, DurationSpan,
CarIconSpan}` — no colour span. `Row.Builder.addText()` validates against
`TEXT_WITH_COLORS_AND_ICON`, which does include `ForegroundCarColorSpan`. The Warnings
tab's severity colouring (`colourSpan(title, cls)` / `colourSpan(titleText, sevCls)`) was
being applied to row **titles** — the one field that has never allowed it.

**Fix:** moved severity colour off the row title and onto the row's first `addText()` line
in both the FMI alert rows and the Fintraffic road-notice rows. Titles are now always plain
text; the colour signal is unchanged in meaning, just relocated to the line already proven
safe elsewhere in the app (`riskText()`, `staleNote()`, `conditionColoured()`).

**Same bug, fixed pre-emptively in the Road and Weather tabs before it could ever fire
there.** `roadTemplate()`'s `coloured()` (sub-zero road temperature) and `keliColoured()`
(winter KELI states), and `weatherRow()`'s reuse of `coloured()` for air temperature, were
all colouring row titles the identical way. None had crashed yet only because no sub-zero
reading has occurred this season — this would have broken the same way the first cold
night. Titles for these rows are now plain; the road-surface and forecast-step rows already
carry a coloured `addText()` line (`riskText()`, `conditionColoured()`) so no safety signal
is lost. The condition row gained one: the KELI state is now shown coloured on its own
`addText()` line rather than in the title. Air temperature in `weatherRow()` is left
uncoloured — it was never a safety indicator on its own, unlike road surface temperature,
which keeps its warning via `riskText()`.

The now-unused `coloured()` colour-in-title helper (superseded by plain `temp()` at every
call site) was removed.

## 0.5.2 — 2026-09-29

**Warnings still did not open in 0.5.1** — reported the morning after upload, no stack
trace available (no emulator or car access from here to reproduce it). Road, Weather and
About all confirmed working, including no locks and correct live position tracking.

Checked the one concrete theory against the library's actual bytecode before writing
anything: does `ListTemplate.Builder.build()` reject an empty `ItemList`? **No** —
`RowListConstraints.validateRows` iterates zero times over an empty list and returns
cleanly, ruled out directly rather than assumed.

Without a reproducible cause, **`warningsTemplate()` is now wrapped so nothing inside it
can take the tab down.** Any exception renders as a one-row error message with the
exception text, instead of the tab silently failing to open or the app locking up. Also
made unconditionally defensive regardless of what caused this: the function now always
emits at least one row (a loading row while data is in flight, the existing "no warnings"
row once it is not), rather than relying on the empty-list case being safe just because
the library happens to allow it today.

**If the error text shows something concrete on the next test, that closes this properly.**
Until then this is a containment fix, not a confirmed one.

## 0.5.1 — 2026-09-28

**Reverts the ⓘ action from 0.5.0.** Found in the field within hours of upload: no info
button ever appeared, Weather stopped switching after one tap, and Warnings crashed the
app outright. About is a tab again — Road, Weather, Warnings, About — exactly the pattern
that was stable across six previous releases.

Two separate defects in the 0.5.0 approach, best understood together:

- **The icon never rendered at all.** A `ListTemplate`'s own `ActionStrip` is not honoured
  once that template is wrapped as `TabContents` inside a `TabTemplate` — only the
  `TabTemplate`'s single header action (the app icon) is drawn. This was true from the very
  first render, before any tab switch.
- **Every tab switch built a brand-new `Action` with a brand-new `OnClickListener` closure.**
  Two closures are never equal to each other even when functionally identical, so the host
  almost certainly counted every tab switch as a full new step against the five-template
  quota rather than a free refresh — several times more expensive than plain tab switching
  had ever been. Two taps drained it; Warnings, hit last, crashed.

Neither was caught by compiling, because both are host-rendering and host-comparison
behaviour, not type errors. `AboutScreen.kt` — the pushed-`Screen` version — stays in the
repo unused, documented as unverified, rather than deleted; the investigative work is not
wasted even though this release doesn't use it.

## 0.5.0 — 2026-09-27

**Warnings tab**, replacing About in the strip. FMI land warnings by severity, then nearby
road notices by distance, matching `SPEC.md` §4b and the design settled on the demo weeks
ago. Backend (`/v1/warnings`) has been live since then; this is the app side.

- **About moved to an ⓘ action**, pushed as a real `Screen` — the app's first use of
  `ScreenManager.push()`. Every template before this was drawn by `MainScreen` switching on
  its own tab state; nothing was ever pushed. The host supplies the back chevron once the
  stack holds more than one screen.
- **The restriction is the headline, not the category** — "One carriageway closed",
  "Speed limit 50 km/h", not "Road work". Two restriction types turned up in live data that
  weren't in the first pass (`NARROW_OR_CLOSED_PEDESTRIAN_AND_BICYLE_PATH`,
  `OPEN_FIRE_HEATER_IN_USE`); both degrade to a generic fallback rather than dropping the
  notice, and both now have proper fi/sv/en labels.
- **"Until 2 Oct" vs "Long-term"** — some road works run for a year or more; a bare date
  would mislead about how long a closure actually is.
- **A real empty state, not a placeholder** — most places most days will show nothing, and
  that is stated plainly rather than left blank.
- Row budget uses the same live `ConstraintManager` query as the other two tabs, not a
  hardcoded count.

**Not yet field-tested.** The push/pop navigation is genuinely new ground for this app —
its own dedicated drive, not bundled with anything else.

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
