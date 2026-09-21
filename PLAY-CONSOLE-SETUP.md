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

---

## 3. App content declarations

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

### Data safety — the one with a real trap

The app sends **coordinates** to the backend to find the nearest station. Under Google's
definition, transmitting data off the device counts as **collection** — *unless* it
qualifies for the **ephemeral processing** exemption: accessed in memory, used only to
service the current request, and **not written to persistent storage**.

That exemption is the honest answer here, **but it is only true if the backend does not
log coordinates.** So it is an engineering requirement on `/v1/road`, not just a form
answer:

> **`/v1/road` must not write lat/lon to logs, access logs, metrics labels or the cache
> key in any retrievable form.**

Get this right and the Data safety section is "no data collected", which matches the
privacy policy. Get it wrong and the form contradicts reality — one of the more common
causes of enforcement action.

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
- **Countries:** Finland. The data is Finnish-only; listing it worldwide invites
  one-star reviews from people it cannot serve.
- **Contact details:** email, plus `https://www.tallimedia.com/`

---

## 6. Do not do yet

- **Do not upload a bundle** until the app is built and signed. The first AAB must go
  through the Console by hand — the Publishing API cannot create an app or upload its
  first bundle.
- **Do not enrol in anything paid.** Free, and staying that way.
- The **upload keystore** must exist and be backed up somewhere that outlives the laptop
  before the first upload.
