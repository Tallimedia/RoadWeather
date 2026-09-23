# Play Console setup — FIRoadWeather

Step-by-step for creating and configuring the Play Console entry. Written 2026-09-21,
against the Console as it stands then. Values here are decided, not suggestions — the
reasoning behind each lives in `claude-docs/FI-WEATHER-ROADMAP.md` (D0-C, D0-E, D10, D11)
and `claude-docs/FIRoadWeather/RESEARCH.md`.

**Account context:** personal account, created August 2011, developer name **Tallimedia**.
Exempt from the 12-testers/14-days rule, so this app can go straight to production once
it passes review.

---

## 1. Create app

| Field | Value | Notes |
|---|---|---|
| **App name** | `Finnish RoadWeather` | 19/30. This is the name in the **default language** only; Finnish and Swedish names are added later as translations. **Changeable later.** |
| **Package name** | `fi.roadweather.app` | Click *Check availability*. **Permanent — cannot be changed, deleted or reused, ever.** |
| **Default language** | **English (United States)** | See below |
| **App or game** | **App** | Changeable later |
| **Free or paid** | **Free** | See the warning below |
| **Contact email** | an address you actually monitor | Shown publicly on the listing |
| Declarations | Tick Developer Program Policies, US export laws, Play App Signing ToS | |

### Why English as the default language

The default language is the **fallback** shown to anyone whose locale has no translation.
Finnish is the primary market, but making Finnish the default means that if distribution
ever widens beyond Finland, everyone outside it sees Finnish. English as the default with
**fi-FI** and **sv-SE** added as translations gives Finns and Swedish speakers their own
listing *and* a sensible fallback for everyone else. It costs nothing.

### ⚠️ Free becomes one-way at first publish

Editable on the app's pricing page **right up until the app is published**. After that,
a free app **can never be made paid** (a paid one can always be made free). This is not a
problem here — the decision (D11) is that if the backend is ever charged for, it happens
through `tallimedia.com` via a merchant of record, never Play Billing. But it does mean
**Free** must be chosen deliberately rather than as a default.

---

## 2. Immediately after creation — two things that are easy to miss

### Android Automotive OS form factor — added early, finished late

**Test and release → Advanced settings → Form factors → + Add form factor → Android
Automotive OS.** It is not on the dashboard setup checklist, so nothing prompts you and
it is easy to miss entirely. Without it the app can never reach a car.

**But adding it is not completing it.** Observed in the Console 2026-09-21, the section
has three steps and the third is padlocked:

| | Step | State |
|---|---|---|
| 1 | Upload AAOS screenshots to the store listings | **Optional** — the Console says so explicitly |
| 2 | Publish an AAOS app bundle or APK **to a test track** | Needs a built, signed AAB |
| 3 | 🔒 Accept the review policy confirming the app meets AAOS quality rules | **Locked until the steps above are done** |

**So Play Console setup cannot be finished before the app is built.** Plan for two
passes: everything in §1 and §3–5 now, then come back for the form factor once there is
a bundle on the internal test track.

When the section asks how to manage AAOS releases, choose **"Käytä erillistä
julkaisukanavaa: Android Automotive OS"** — a **separate release track**, not the mobile
one. This is required, not preference: only *parked* apps may ride the mobile track,
while **templated apps built on the Car App Library must use the AAOS dedicated track**.
It is also the only sensible answer here — there is no mobile app, and the bundle will
declare `android.hardware.type.automotive` as `required="true"`, so it cannot install on
a phone. These are the tracks the Publishing API addresses as `automotive:production`,
`automotive:beta` and `automotive:qa`.

### EU trader status (DSA)

Required for EU distribution. **Declare non-trader** while the app is free and
non-commercial.

This matters more than it looks: a trader's name and address are **published on the EU
store listing**, and on a personal account the address on file is a home address. If the
backend is ever monetised, this needs revisiting first — see D11.

**Checked 2026-09-24, and it is not there yet.** The full `Kehittäjätili` (Developer
account) page was read top to bottom in Console — `Tietoa sinusta`, `Yhteystiedot`,
`Kehittäjän profiili`, `Yhdistetyt kehittäjätilit` — with no trader/DSA declaration
anywhere on it. Most likely explanation: Google surfaces this at the point of submitting
a release aimed at **open testing or production**, not as a standing page for an app that
has only ever been on the internal test track. **Nothing to do while internal-only** —
re-check when preparing the first release past internal testing, rather than searching
Console again before then.

---

## 3. App content declarations

**Status 2026-09-21/22: this whole section is complete.** Privacy policy URL, app access,
ads, content rating, target audience, health and Data safety are all answered and saved.
Kept below as the record of *what* was answered and why, since Play cross-checks these
against the privacy policy and against each other.


Dashboard → **Policy → App content**. Answers for this app:

| Declaration | Answer | Why |
|---|---|---|
| App access | **All functionality available without restrictions** | No accounts, no login, no paywall |
| Ads | **No ads** | And keep it that way — AD-1 forbids text ads in car apps |
| Content rating | Complete the questionnaire | No user content, no violence, no purchases → lowest rating |
| Target audience | **Anything from 13 upward — just not under 13** | Corrected 2026-09-21. Only the under-13 brackets trigger Families Policy Requirements (certified ad SDKs, neutral age screen, separate review). 13–15, 16–17 and 18+ carry no extra obligations, so there is no reason to exclude teenagers. Note 18+ does **not** block installation on its own — that is a separate opt-in feature, *Restrict Minor Access*. |
| News app | **No** | |
| COVID-19 contact tracing | **No** | |
| Data safety | See below — **read this one properly** | |
| Government app | **No** | |
| Financial features | **None** | |
| Health apps | **No** | |
| Privacy policy URL | `https://roadweather.tallimedia.com/privacy` | Must exist and be reachable before submission |

### Data safety — declare the location, mark it ephemeral

**Corrected 2026-09-21.** An earlier revision of this guide said the answer was "no data
collected". That was wrong in a way worth being precise about.

Google's definition: **"collect" means transmitting data off the device — including to
your own backend.** So the app collects precise location. The *ephemeral processing*
exemption does **not** mean you answer "no"; it means the data is declared in the form
but **does not appear in the public Data safety display**.

Ephemeral processing is "accessing and using it while the data is only stored in memory
and retained for no longer than necessary to service the specific request in real-time".
Google's own worked example is a weather app transmitting location to fetch current
conditions and discarding it — which is exactly this app.

| Question | Answer |
|---|---|
| Collects or shares user data | **Yes** |
| Data type | **Location → Precise location** |
| Shared with third parties | **No** — FMI and Fintraffic are asked about a *place*, not a user |
| **Processed ephemerally** | **Yes** |
| Required or optional | **Required** — the app shows nothing without it |
| Purpose | **App functionality** |
| Encrypted in transit | **Yes** (HTTPS) |
| Deletion requests supported | **No** — nothing is retained, so there is nothing to delete |

**The ephemeral claim is only honest because of an engineering constraint**, and it has
to stay that way:

> **`/v1/road` must never write lat/lon to logs, access logs, metrics labels or a
> retrievable cache key.**

The backend enforces this with a log filter that redacts `lat`/`lon`/`latlon` from
uvicorn's access log — added 2026-09-21 after finding that every coordinate query was
being written to disk. Remove that filter and this declaration becomes false.

`ROADWEATHER-PRIVACY.md` states the same thing in the same terms. Play cross-checks the
policy against this form, so the two must not drift.

---

## 4. Store listing

**Grow → Store presence → Main store listing.** Per language.

| Locale | App name | Notes |
|---|---|---|
| en-US (default) | `Finnish RoadWeather` | |
| fi-FI | `Suomen tiesää` | Not "Suomi Tiesää" — the genitive is the natural form |
| sv-SE | `Finlands vägväder` | `vägväder` is Trafikverket's own term |

Limits: app name 30, short description 80, full description 4000.

**Required in the description, in every language** — both licences oblige attribution:

- Finnish Meteorological Institute data, CC BY 4.0
- `Source: Fintraffic / digitraffic.fi, license CC 4.0 BY`

The same attribution has to appear **in the app**, not only in the listing.

### Graphics

| Asset | Spec |
|---|---|
| App icon | 512×512 PNG |
| Feature graphic | 1024×500 |
| **AAOS screenshots** | **Optional** per the Console. If supplied: ≥2 portrait (800×1280) and ≥2 landscape (1024×768) |

The icon and feature graphic are **generated, not hand-made** — run
`python3 store/make_store_art.py`, which writes `store/icon-512.png` and
`store/feature-1024x500.png` in the same dark-navy family as VolvoWatch and
FIWeatherWatch. Deliberately no screenshots: Play wants real captures, and a drawing of
a screen that does not exist yet is a lie that has to be redone.

Capture screenshots from the **generic** emulator AVDs once the app runs —
`FRW_api35_play_portrait` and `FRW_api35_play_landscape` are sized for exactly this.
**Volvo-skinned screenshots are rejected**, so do not photograph the car.

---

## 5. Store settings and distribution

- **App category:** Weather
- **Countries:** Finland. **Set and confirmed 2026-09-24.** Countries are configured once
  on the default Production channel (Puhelimet, Tabletit, Chrome OS, Android XR —
  Testaa ja julkaise → Tuotanto → `Maat/alueet`) and the AAOS channel **inherits it
  automatically**: switching the channel selector to "Vain Automotive OS" shows the same
  tab reading *"Sovellus tulee saataville samoilla alueilla kuin puhelin- ja
  tablettisovellus"* with Suomi already listed, `Kohdistettu`. No separate AAOS-specific
  setting exists, and none was needed — one shared country list per track, not per form
  factor. The data is Finnish-only; listing it worldwide invites one-star reviews from
  people it cannot serve.
- **Contact details:** email, plus `https://www.tallimedia.com/`

---

## 6. Do not do yet

- **Do not upload a bundle** until the app is built and signed. The first AAB must go
  through the Console by hand — the Publishing API cannot create an app or upload its
  first bundle.
- **Do not enrol in anything paid.** Free, and staying that way.
- The **upload keystore** must exist and be backed up somewhere that outlives the laptop
  before the first upload.

---

## 7. Getting the beta into the actual car

**There is no beta-testing setting on the Volvo screen.** Nothing is enabled in the car —
the app simply appears in the car's Play Store once the account is on the tester list.
Looking for a toggle on the centre display is a dead end, so here is the real sequence.

### In Play Console, once

1. **Testing → Internal testing → Testers.** Create an email list and add the Google
   account(s). This must be the **same account that is signed into the car**, not a
   developer-only address.
2. Same page: **copy the shareable opt-in link.** Internal testing is capped at 100
   testers, which is ample for a handful of XC60 owners.
3. Confirm the release is actually **live on the internal track**, not a draft.

### Once per tester, in a browser

4. Open the opt-in link **signed in as the car's Google account** and accept. Doing this
   while signed in as some other account is the usual reason the app never shows up.

### In the car

5. The XC60 must be signed into that same Google account — it hangs off the driver
   profile, not off a separate app-store login. *(Exact Volvo menu label unverified; it
   has not been done on this car yet.)*
6. Open **Google Play on the centre display** and search for the app by name, or find it
   in the Play library. Allow a little time after the release goes live for it to
   propagate.

### Things that will waste an evening if not known

- **It will never appear on a phone.** The manifest declares
  `android.hardware.type.automotive` as required, so the phone Play Store correctly
  treats the device as incompatible. The car is the only place it can install.
- **Sideloading is not an option, at all.** Production Volvos block developer mode, and
  Android Auto's developer mode explicitly does **not** cover apps built with the Car App
  Library. Internal App Sharing or the internal test track are the only routes
  (`developer.android.com/training/cars/testing`).
- **Country targeting does not gate internal testers**, so Finland-only is not a blocker
  here — but the **EU trader status declaration** is still outstanding and does gate
  wider release.
