# Field notes — first run in the car

**2026-09-22, MY25 Volvo XC60, v0.1.0 (versionCode 1), internal test track, drive to work.**

The app installed and ran. All three tabs rendered live data over the car's own LTE, the
attribution screen is correct, and the Finnish locale was picked up. That is the whole of
the risky path — build, sign, upload, opt in, install, run on real hardware — working end
to end on the first attempt.

One drive then produced six findings, five of them real defects. **None of these were
reachable from the emulator**, which is the argument for doing this early rather than
after polishing.

Nothing below is fixed yet; this is the worklist for v0.2.

---

## 1. The position never updates — the serious one

Driving from home to work, the app showed **Kt 45: Helsinki – Kehä I the entire way**.

`MainScreen.onCreate` calls `load()` once, `load()` takes one `Locator.fix()`, and nothing
calls it again except the two retry actions on the error screens. So the app reports
conditions for wherever the car was when it launched, for as long as it stays open.

This was a **decision, not a slip** — `SPEC.md` §5 says "Car GPS, resolved when the app
opens", written before anyone had driven with it. It is wrong, and in the way that matters
most: a road-surface reading for a road you left twenty minutes ago is exactly the class of
answer this app exists to avoid.

It is also a **review risk**. Quality rule **WE-1** requires content relevant to the user's
current location, and a frozen position is arguable non-compliance at the blocking review
for open testing or production.

**Fix.** Re-resolve while the app is in the foreground and refetch when the car has moved
meaningfully. Points to settle:

- Distance threshold rather than a timer — the interesting change is "the nearest station
  or section is now a different one", not "60 seconds passed". Something around 2 km.
- The backend caches road data for 300 s, so refetching faster than that only burns the
  car's data allowance for identical bytes.
- Refresh on resume too: the app will often be reopened mid-drive rather than left up.
- `requestLocationUpdates` in place of one-shot `getCurrentLocation`, released when the
  screen stops.

---

## 2. English leaking into a Finnish UI, from two different causes

Visible as **"clear · 4 m/s"** on the Sää tab and **"The sensor has a fault"** on the Tie
tab, both surrounded by Finnish.

### 2a. The app never asks the backend for a language

`Api.kt` calls `/v1/forecast?lat&lon&hours` with no `lang`. The backend defaults to `en`.
Verified live:

```
no lang   → smartsymboltext = 'clear'
&lang=fi  → smartsymboltext = 'selkeää'
```

FMI localise the text themselves, so this is a one-line client fix: pass the car's locale
through on `/v1/forecast`, and on `/v1/observations` and `/v1/road` for consistency.

### 2b. The backend throws away the Finnish text Digitraffic already sends

`road.sensor_map()` reads `sensorValueDescriptionEn` and nothing else. Digitraffic in fact
send both:

```json
"KELI_1": { "sensorValueDescriptionEn": "The sensor has a fault",
            "sensorValueDescriptionFi": "Anturissa on vikaa" }
```

So the Finnish string was there all along and the proxy discarded it. `/v1/road` needs a
`lang` parameter that selects the field.

**No Swedish.** Digitraffic publish `...Fi` and `...En` only, so `sv` has to fall back —
English is the better fallback than Finnish for a Swedish speaker. Worth stating in the
spec rather than leaving to chance.

---

## 3. A faulty sensor is displayed as a road condition

The Tie tab read:

```
8,0 °C
Ei liukkausriskiä
Tienpinta
─────────────────
The sensor has a fault        ← where "Kuiva" belongs
Ilma 8,8 °C · Kastepiste 7,8 °C
```

Confirmed live: `station.condition` really is `"The sensor has a fault"` at
`vt3_Helsinki_Pirkkola`, with `KELI_1` value `0.0`. The app passes the description straight
into the title slot reserved for the surface state.

The ice-risk line above it is **not** affected — it is derived from FMI's road temperature,
a different source — so nothing unsafe was shown. But a fault message sitting in the
condition slot, directly under a reassuring "Ei liukkausriskiä", is precisely the
presentation `SPEC.md` §2 says must never happen: absent data must not read as an answer.

**Fix.** Treat a `KELI` fault as missing: show the condition row as unavailable, as §6
already specifies for a null station. The fault detail belongs nowhere on this screen.

---

## 4. The station id is shown raw

`vt3_Helsinki_Pirkkola` on screen. Already solved in the design preview
(`prettyStation` → `Vt 3 Helsinki Pirkkola`) and simply not yet in the app.

---

## 5. The Finnish reliability line reads badly

`· ennustetta ei tue tiesääasema` is a literal translation of a phrase that was already
clumsy in English. The preview now says *"Estimated — no sensor on this stretch"*; Finnish
wants something like **"Arvio — ei tiesääasemaa tällä välillä"**.

---

## 6. Not a defect, but worth a look: 4 h road surface at 19,0 °C

The outlook ran `0h 8,4 °C · 2h 8,0 °C · 4h 19,0 °C` while the air forecast for the same
window was 12,7 → 14,3 °C. Sun on asphalt genuinely does run ten degrees above air, so
**this is plausible rather than wrong** — but it is a large step between two adjacent rows
and it comes from Digitraffic, not from us. Worth re-checking on a cloudy day before
deciding it is real.

---

## What the drive did not turn up

Worth recording, because these were the things most likely to break:

- No crash, no ANR, no blank template.
- The car's own LTE served every request; no timeout path was hit.
- Location permission had already been granted and was not re-prompted.
- The tab strip, the Finnish locale and the attribution screen all behaved.
- `minCarApiLevel 6` and `TabTemplate` are accepted by the real Google Automotive App
  Host, not just by the emulator.

---

## Benchmark: Foreca, in the same car

Photographed alongside, 2026-09-22. Foreca is the incumbent worth measuring against — a
Finnish forecasting company, already installed, and the app a Finnish XC60 driver would
otherwise reach for.

### It corrects our spec

**Foreca has a settings screen** — *Lämpötila / Tuulen nopeus / Sade* (units), *Palaute*
with a support address, *Käyttöehdot ja tietoja*, *Ohjelmistolisenssit*.

**Photographed while parked** (Nico, confirmed). That matters, and it narrows the
conclusion: *settings while parked* is precisely the documented exception, so this is
Foreca doing the permitted thing rather than evidence of anything more. A first reading of
the photo took the back-chevron header as proof of a pushed **template** screen, which a
photograph cannot actually distinguish from an ordinary parked **activity**.

What it does establish is still useful. `SPEC.md` §10 said settings were out because "AAOS
restricts app-owned activities to parked flows", which implied they were unavailable.
`RESEARCH.md` §1 already recorded that setup, **settings** and sign-in are the three
explicit *exceptions* to that restriction, so the two documents contradicted each other.
That is now fixed.

They stay out of v1 anyway, but for the honest reason: a Finland-only app has one sensible
answer for each unit. **WE-4** separately forbids making forecast intervals configurable.

**Still unknown, and worth two minutes:** open Foreca's settings *while driving*. If the
car refuses, it is a parked activity and settings are parked-only in practice. If it
opens, it is templated and usable in motion. Either answer is worth having before anyone
plans a settings screen here.

### What Foreca does that we should copy

- **A feedback address in the app.** `androidsupport@foreca.com`, one row in settings.
  Trivial, and during a closed beta it is the difference between a tester telling you
  something and not bothering.
- **Terms and software licences behind one entry**, not spending a tab on them. The same
  conclusion `SPEC.md` §1 reaches with ⓘ → About, arrived at independently — though
  whether Foreca's is a template or a parked activity is not visible from a photo.

  **Our About is unaffected either way.** It is a `MessageTemplate` pushed from an
  ActionStrip action: a template, not an app-owned activity, so the parked restriction
  does not reach it and the attribution stays reachable in motion.

### What we do that Foreca cannot

Worth stating plainly, because it is the whole premise and the benchmark confirms it:
Foreca has no road surface temperature, no ice risk, no Fintraffic road condition, no
road works. It is a very good **weather** app in a car. The gap it leaves is the road.

### Still to compare on a second look

- How Foreca handles **position while driving** — whether it re-resolves, which is
  defect 1 above and the thing most worth stealing if they have solved it well.
- Whether its forecast rows carry more per row than ours, and how it fills the same
  six-row budget.

---

# Second run — v0.2.0, 2026-09-22 afternoon

## What the fixes did

All five defects from the morning are gone, confirmed on screen:

- **Position follows the car.** 14:47 `Kt 50 Vantaa Suutarila Länsi` / *Kt 45: Kehä I –
  Tuusula*; 14:51 `Kt 50 Vantaa Vantaankoski` / *Kt 50: Hämeenlinnanväylä – Porvoonväylä*.
  Different station and different section four minutes apart — the 2 km threshold works.
- **Finnish throughout**: *selkeää*, *enimmäkseen selkeää*, *Kuiva*.
- **Station names readable**: `Kt 50 Vantaa Vantaankoski`.
- **No fault text** in the condition row.

## 7. New, and introduced by v0.2.0: the template quota

Two symptoms, and the evidence points at one cause.

**The error.** After several minutes of driving, the Tie tab was replaced by a warning
triangle and *"Tätä tehtävää ei voi suorittaa ajon aikana"*. The tab strip stayed; only
the content was refused.

**The left shift.** In every v0.2.0 screenshot the content sits too far left, with
fragments of another view at the edge — `/Nyt`, `>Kt 45`. That is what a **push
transition caught mid-slide** looks like, and it appears on the Tie and Sää tabs alike.

### Why they are the same thing

From the template restrictions, quoted:

> The host limits the number of templates to display for a given task to a maximum of
> **five** … In general, if an app pushes a new template that is of the same type and
> contains **the same main content** as the previous template, the new template is not
> counted against the quota.

v0.1.0 produced exactly one template in its life, so it could never hit this. **v0.2.0
reloads as the car moves, and every reload changes the main content** — new station name,
new temperatures, new row titles. Each one is therefore a *new template step*, not a
refresh: hence the slide animation, and hence running out.

The quota resets only on an end-of-task template such as `NavigationTemplate`, on a
launcher or notification intent, or partially when a `Screen` is popped. **None of those
happen in this app.** It has one screen and never navigates.

### The part that is not yet certain

The documentation says an exhausted quota makes the host *"display an error message to the
user before closing the app"*. The app did **not** close — the tab strip survived and only
the content area was refused, and the message is the distraction-restriction wording
rather than a quota one. So either AAOS's host behaves differently from the documented
Android Auto behaviour, or something else refused that one template.

**Questions that would settle it**, next time it happens:

1. Did the other tabs still work, or was the whole app stuck?
2. Did it clear when the car stopped, or only on relaunch?
3. Roughly how many tab taps and how long into the drive?

### Why the obvious fix does not work

Refreshing less often only delays it. A 30-minute drive at any useful threshold still
produces more than five content changes, and tab taps consume the same quota. **The
refresh has to become an actual refresh.**

That means the main content must stay the same across updates, which for a `ListTemplate`
means a **stable row count and stable row titles** — only the secondary lines changing.
Today the temperature *is* the row title, which guarantees a new template every time.

### The design decision this forces

`SPEC.md` §2 says the surface temperature is the largest element on the screen, and the
row title is what makes it large. Making refreshes free means inverting that:

```
  before                          after
  16,9 °C            ← title      Tienpinta          ← title, never changes
  Ei liukkausriskiä               16,9 °C · ei liukkausriskiä
  Tienpinta
```

The number stops being the headline. That is a real loss and it is **Nico's call**, not a
detail to decide while fixing a bug. The alternative is to accept the quota and refresh
only when the app is brought to the foreground — correct, cheap, and back to a position
that goes stale mid-drive.

### Resolved — and the design does not have to change

Nico, on the same drive: **no tab worked after the error, and it came back to normal
after about ten minutes.**

"No tab worked" is what settles it. A distraction restriction refuses one template; a
spent quota refuses *every* new template, which is exactly a dead tab strip. The recovery
fits too — the quota resets on a launcher intent, so leaving the app and returning
restores it, and ten minutes of driving is ample for that to happen by accident.

That pointed at the code, where the cause turned out to be narrower and much stupider
than "the data changes":

```kotlin
loading -> message(R.string.loading, null, null)   // a MessageTemplate
```

**Every reload swapped the inner template to a `MessageTemplate` and back**, so one
position update cost *two* steps of a five-step quota. Two and a half reloads — about
five kilometres — and the app was finished. v0.1.0 never noticed because it loaded once
at startup and then never again.

Fixed in **v0.3.0**, and none of it costs the design:

- **A reload keeps the previous reading on screen.** The loading state is now pushed only
  on a cold start, when there is genuinely nothing to show. One template per update
  instead of three.
- **The app pushes nothing when the result would look the same.** It compares a signature
  of what would be drawn — station, rounded values, risk, section, forecast start — and
  skips `invalidate()` when it matches. A refetch on the far side of a village usually
  returns the same station and the same numbers.

The row-title inversion from the previous section is **not needed** and is not being
done. The surface temperature stays the headline (`SPEC.md` §2 stands).

**Still unverified:** whether the remaining one-template-per-genuine-change rate stays
inside the quota over a long drive with tab switching. If v0.3.0 still dies, the title
change is the next lever — but it should not come to that, since a commute produces only
a handful of genuinely different readings.

---

# Third run — v0.3.0 verification, 2026-09-22

**Confirms the quota fix.** 09:18–09:46, 28 minutes, six station changes and four section
changes crossing Kt 51 → Vt 1 → Kt 50, plenty of tab switching, **zero errors**. The fault
handling worked in the wild too: a sensor at Suutarila Länsi read "ei saatavilla" instead
of dumping its error text on screen. §7 closed.

Three findings from the same drive, none of them the quota:

1. **Section headers clip under the Volvo's bezel.** `SectionedItemList` headers ("Nyt",
   the road name) render further left than ordinary rows and disappear off the left edge —
   visible in every screenshot as `/Nyt` and `>Kt 50`. Row content itself was fine.
2. **A label placed last is orphaned by the divider beneath it and reads as a heading for
   the next row.** "Tienpinta" appeared to caption "ei saatavilla" on the row below it,
   though it was actually the label for the surface temperature above it — a working
   reading made to look like missing data on the one tab where that is the one thing that
   must never happen (`SPEC.md` §2).
3. **`0h` in the outlook is not a forecast.** Confirmed live: Fintraffic tag it
   `type=OBSERVATION`, every other step `type=FORECAST`. It was sitting under a "Ennuste"
   heading, contradicting the reading above it by a few tenths, and had no `arvio` marker
   because it genuinely isn't an estimate — it just looked like one.

Also raised and resolved by discussion rather than a screenshot: the Weather tab's forecast
had a blind spot of up to three hours because FMI aligns points to the requested step; a
3-hourly request can start as late as 3 hours out. Fixed by requesting `step=60` and
picking hourly-then-3-hourly in the client.

These four findings are what became the v0.4.0 layout (`SPEC.md` §2a/§3a): no section
headers anywhere, every label leads its own row, `0h` dropped in favour of a fourth real
forecast step, and hourly-then-3-hourly forecasting.

Benchmarked against **Foreca** in the same car the same day — see the entry under the
first run, above. One correction made there stands: the Foreca settings screenshots were
taken parked, which is the documented exception for app-owned activities, so they only
prove settings-while-parked is possible, not anything about driving.

---

# Fourth run — v0.4.0, 2026-09-23

Confirms the v0.3.0 diagnosis and the redesign both hold, and turns up the next round.

- **Position genuinely follows the car**: station and section changed together, six times
  in 28 minutes, matching the actual route driven (Kt 51 → Vt 1 → Kt 50).
- **No error, no dead tab strip** — the quota fix from v0.3.0 survives a longer,
  more eventful drive.
- The redesigned Road tab reads correctly: label leads value, station name up top with
  distance and time, four forecast steps out to 12 h.

Two decisions made from this drive that shaped v0.4.0 before it was built, both already in
`SPEC.md` §2a/§3a: dropping the duplicate "Sää nyt / Nyt" head-title repetition down to one
"where and when" row per tab, and giving the Road tab the same "station first" opening the
Weather tab already had. Neither is a defect so much as a refinement asked for directly
against the live demo before the Kotlin was written — the design-preview loop working as
intended.

**Not yet re-verified on the road:** whether the bezel-clipping theory (§ Third run,
finding 1) actually cleared once section headers were removed. The next drive is the first
chance to confirm rows now sit far enough right.

---

# v0.4.1 — found ahead of the field, not in it

Unlike every fix above, this one was **not** caught by a screenshot. Reviewing the failure
path before Friday's planned long drive found that `failed -> message(...)` still swapped
`ListTemplate` for `MessageTemplate` on every connection drop — the identical quota cost
the v0.3.0 fix removed from the loading path, just triggered by weak coverage instead of
movement. Fixed the same way: the last reading stays on screen, marked stale, and `failed`
is excluded from the redraw signature so a blip alone cannot force a repaint.

**Unverified until the long drive**: whether a real multi-gap rural route confirms this,
and whether the "no connection" note is legible and not alarming at a glance.

---

# Stationary check — v0.4.1, 2026-09-24

Parked, not driving — screenshots only confirm layout, not the things that matter most
(position tracking, quota headroom). Logged because it closes one open item outright.

**The bezel-clipping theory holds.** Flagged as unconfirmed after the fourth run: every
row on both tabs now reads cleanly from the left edge in these screenshots — no `/Nyt`,
no `>Kt 51`. Removing section headers in favour of label-leading ordinary rows fixed it.

Also visually correct: the hourly-then-3-hourly forecast lands exactly on the designed
indices (14:00, 15:00, 18:00, 21:00, 00:00 from a 14:00 baseline — indices 0,1,4,7,10);
four road forecast steps with `0h` dropped; live distance populated and plausible from two
different stations (7,2 km, 1,1 km); every label leads its value.

**Still open, and this check cannot answer it:** whether position updates and the template
quota hold over the drive later today, and the real one on Friday.
