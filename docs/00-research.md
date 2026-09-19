# Personal CGM → Wear OS: technology research

Date: 2026-09-18
Target watch: OnePlus Watch 3 (Wear OS 5 out of the box, Wear OS 6 promised "during 2026")
Source: FreeStyle Libre via LibreLinkUp

---

## 0. TL;DR

- **The hard constraint is not the data — it's the watch face.** Glucose data from LibreLinkUp is
  a solved, well-documented problem. But Google has killed code-based watch faces, and the
  OnePlus Watch 3 is one of the devices where the legacy runtime is already gone. This decides
  the architecture, so it is treated first.
- **Recommended stack:** Android phone app (Kotlin) polls the LibreLinkUp cloud API → Wear OS
  Data Layer API (runs over Bluetooth) → Wear app that publishes a `ComplicationDataSourceService`
  → Watch Face Format (WFF) watch face that renders it.
- **Second hard constraint: complication freshness.** On WFF, the platform decides when your
  complication may refresh (5-minute floor). Gluroo — a commercial app actively shipping this
  exact pipeline — has a support page dedicated to stale readings because of it. The fix is the
  push model plus a platform-rendered age counter; see §3c. Design for this from the start.
- **Nightscout is optional and probably not needed** for a single-user setup. It is a server you
  have to run; it buys you history, alarms and an ecosystem, not watch connectivity.
- **"NightWatch" is a dead end.** It is an unmaintained 2015-era Android/Android Wear Nightscout
  client. What you are thinking of as "the open source bridge" is **Nightscout**, and the
  LibreLinkUp bridge for it is `timoschlueter/nightscout-librelink-up`.

---

## 1. Layer 1 — getting the glucose data

### Option A — LibreLinkUp cloud API (what you asked for) ✅ recommended

Abbott's LibreLinkUp "follower" service has a private REST API that has been reverse-engineered
and is stable enough that a dozen projects depend on it.

How it works: the person wearing the sensor uses the official **LibreLink** app; they invite a
follower by email; the follower account is what you authenticate as. You can invite yourself with
a second email address.

API shape:

| Item | Value |
|---|---|
| Base URL | `https://api-{region}.libreview.io` (`eu`, `eu2`, `us`, `de`, `fr`, `ca`, `au`, `ap`, `ae`, `jp`, `la`, `ru`, `cn`) |
| Login | `POST /llu/auth/login` → JWT in `data.authTicket.token`, valid ~6 months |
| Required headers | `product: llu.android`, `version: 4.x`, `content-type: application/json` |
| Authed headers | `authorization: Bearer <jwt>`, `Account-Id: <sha256-hex of userId>` |
| Current value | `GET /llu/connections` → `glucoseMeasurement` |
| History + current | `GET /llu/connections/{patientId}/graph` → `graphData[]` |
| Fields | `Timestamp`, `FactoryTimestamp`, `ValueInMgPerDl`, `TrendArrow`, `isHigh`, `isLow`, `MeasurementColor` |
| Update rate | `glucoseMeasurement` ≈ every 1 min; `graphData` aggregated to ~15 min |
| Rate limiting | HTTP 429/430 with `Retry-After`. 1-minute polling is the community-safe floor |

The `Account-Id` SHA-256 header was added by Abbott in 2024 and is the thing that breaks old
clients — any library you pick must have it.

**Latency reality check:** sensor → LibreLink app → Abbott cloud → LibreLinkUp is typically
1–5 minutes behind the sensor. This is inherent to the cloud path and no amount of polling fixes it.

**Libraries** (no mature Kotlin/Java one exists — see §5):

- `DRFR0ST/libre-link-unofficial-api` — TypeScript
- `DiaKEM/libre-link-up-api-client` — TypeScript
- `pylibrelinkup` — Python, actively maintained, good for spiking
- `libre_link_up_api_client` — Rust
- `libreview-unofficial.stoplight.io` — OpenAPI spec, can generate a Kotlin client
- `khskekec` gist — raw HTTP dump, the primary reference document

**Pros:** works with the stock, unmodified Libre app; no patched APK, no NFC, no BLE fight.
**Cons:** cloud dependency, added latency, unofficial API that Abbott can change, ToS gray area.

### Option B — direct BLE from the sensor

`Juggluco` (j-kaltes) and `xDrip+` connect to Libre 2/2+/3/3+ directly over Bluetooth LE.
Juggluco additionally has a **Wear OS app that can connect to the sensor directly**, skipping the
phone entirely.

**Pros:** ~1 min latency, fully offline, no Abbott account.
**Cons:** only one app can hold the sensor's BLE connection at a time — it competes with the
official LibreLink app, and for Libre 3 the sensor generally has to be activated/initialised
through the app that will own it. This is a bigger behavioural change than it looks.

Worth knowing about as a **later upgrade path**, not as stage 1.

### Option C — Nightscout as a hub

Self-host Nightscout (Node + MongoDB), run `timoschlueter/nightscout-librelink-up` or the newer
`nightscout/nightscout-connect` to pull LibreLinkUp into it, then read Nightscout's REST API from
the phone or watch.

Hosting in 2026: Heroku/Azure free tiers are gone. Realistic options are Fly.io, Railway (~$5/mo),
Northflank, or Docker on a VPS/home server.

**Pros:** stable documented API, full history, alarm engine, huge client ecosystem, decouples you
from Abbott's API changes (the bridge absorbs them).
**Cons:** a server to run and pay for, one more hop of latency, much more surface area for a
single-user project.

**Verdict:** skip for now. Design the phone app so Nightscout can be *added* as an extra sink later.

---

## 2. Layer 2 — phone → watch transport

### Option A — Wear OS Data Layer API ✅ recommended

`DataClient` (synced state) and `MessageClient` (fire-and-forget) from Google Play Services.
This **is** the Bluetooth link you asked for — Google Play Services runs it over BT and
transparently falls back to Wi-Fi/cloud relay when the watch is out of BT range. Data is buffered
and delivered when the connection returns.

Design: `DataClient` with a single "latest reading" DataItem (idempotent, survives disconnects) +
`MessageClient` for urgent alarm pushes. Listen on the watch with a `WearableListenerService`
scoped to one path prefix.

### Option B — standalone watch app doing its own HTTPS

The watch has Wi-Fi and gets proxied internet over Bluetooth when paired. It can poll LibreLinkUp
itself.

**Pros:** works with the phone off/absent.
**Cons:** doubles the cloud polling (rate limits), puts credentials on the watch, meaningfully
worse battery. Good as an optional fallback mode, bad as the primary design.

### Option C — your own BLE GATT service

Don't. The watch's Bluetooth is already owned by the companion pairing, and Data Layer gives you
that same channel with buffering and reconnection handled. All cost, no benefit.

---

## 3. Layer 3 — the watch face (⚠️ the real constraint)

### What Google changed

| Date | Change |
|---|---|
| 2025-01-27 | No new AndroidX/WSL watch faces publishable on Google Play |
| 2026-01-14 | Legacy watch faces can no longer be **installed** from Play at all; no updates, no monetization |
| Wear OS 6 | Firmware **will not let the user select code-based watch faces**, even sideloaded |

Watch Face Format (WFF) is now the only format. It is **declarative XML with no executable code**,
which means a WFF face cannot itself draw a glucose trend graph from live data.

**This is exactly why xDrip+ watch faces do not work on the OnePlus Watch 3** — the xDrip
maintainers have stated they have no plan to update Wear support, because the old runtime is gone
on recent Pixel and OnePlus builds.

**Consequence for you:** your OnePlus Watch 3 is on Wear OS 5 today (where a code-based AndroidX
watch face would still work if sideloaded) but OnePlus has confirmed Wear OS 6 **during 2026**.
Anything code-based you build now breaks on your own device within months. Build WFF from day one.

### How to get custom data onto a WFF face

**3a. Complications** ✅ start here

Write a `ComplicationDataSourceService` in your Wear app. It supplies `SHORT_TEXT`
(e.g. `112↗`), `RANGED_VALUE` (glucose within your target range as an arc), `SMALL_IMAGE`
(trend arrow), etc. A WFF face declares `<ComplicationSlot>` with `<DefaultProviderPolicy>`
pointing at your provider.

Big win: this works with **any** third-party WFF watch face, including OnePlus's own. You get a
usable result before writing a single line of watch face XML.

**3b. Full-screen bitmap complication** — for the custom graph face

The pattern AndroidAPS shipped in PR #5123 to survive Wear OS 6: your Wear app renders the entire
watch face (graph, arrows, colours) into a **bitmap**, publishes it as a full-screen image
complication, and a trivial WFF document does nothing but display that complication full-screen.
Two slots are used — one with seconds, one without — to handle the ambient/dimming transition.

This gives you complete design freedom inside the WFF world.

Documented limitations from the AAPS implementation:
- Ambient (always-on) display only refreshes when new data arrives (~5 min), not continuously
- Higher battery draw than a native code face
- Occasional lag on second-hand animation
- Framework-drawn complication slots on top of the bitmap are not clickable

**3c. Keeping it fresh — the real production problem** ⚠️

Discovered by looking at how **Gluroo** (closed-source commercial app, actively maintained) handles
this. They have a dedicated support page titled *"My Wear OS Watch Face is showing an old CGM
Reading of my Blood Sugar?"*, which tells you this is not a hypothetical.

Their explanation: with WFF watch faces, the app **gives up control of when a complication may
update** — the platform decides, for battery reasons. Their suggested mitigations are weak
(tap the complication to signal interest; prefer text complications over graphical ones, which
refresh more often).

The platform rules underneath that:

- `UPDATE_PERIOD_SECONDS` minimum is **5 minutes**. You cannot pull faster.
- `ComplicationDataSourceUpdateRequester.requestUpdate()` should not be called more than **once
  every 5 minutes on average** or you get throttled.

**The design that actually works** — and this is the important takeaway from Gluroo's trouble:

1. **Use the push model, not the pull model.** Set `UPDATE_PERIOD_SECONDS = 0` and drive updates
   from your `WearableListenerService`: when a reading arrives from the phone over the Data Layer,
   call `requestUpdate()`. A CGM's natural cadence (1–5 min) lines up almost exactly with the
   5-minute average budget, so you are not fighting the platform — you are matching it.

2. **Never render an age as static text.** Push the reading's *timestamp* and let the watch face
   render the age with `TimeDifferenceComplicationText` / WFF dynamic expressions. These are
   evaluated by the platform continuously **with no data source request at all**. The result: even
   when the value is throttled and stale, the "4 min ago" next to it is always truthful and live.
   This converts the platform's worst behaviour from a silent safety hazard into a visible one.

3. **Consider a Tile as a second surface.** Tiles use `setFreshnessIntervalMillis()` and you can
   force a refresh yourself — you keep meaningfully more control than with a complication. Gluroo
   added Tiles in their rewrite. A Tile is the natural home for the trend graph; the complication
   stays the always-visible number.

**3d. Distribution:** sideloading via `adb install` is fine for personal use and unaffected by the
Play Store policy changes. `Watch Face Push` (Wear OS 6) only matters if you want an installer app.

### OnePlus Watch 3 specifics

Dual-chip design: Snapdragon W5 (Wear OS) + BES2800 MCU running an RTOS. **Only OnePlus's own
watch faces run on the RTOS in always-on / raise-to-wake.** Any third-party face keeps the W5
awake and costs noticeably more battery.

Practical implication: a **complication on a OnePlus stock face** may well give you better battery
life than a beautiful custom face. Another reason to ship §3a before §3b.

---

## 4. Existing open-source projects

| Project | What it is | Licence | Relevance |
|---|---|---|---|
| **GlucoDataHandler** (pachi81) | Phone + Wear app. LibreLinkUp / Dexcom / Nightscout / xDrip / Juggluco sources → Wear complications, alarms, Android Auto, Garmin | MIT | **Closest thing to your goal that already exists.** Try it before building. Not standalone on the watch; complications have reported bugs |
| **Gluroo** | Commercial closed-source app + Wear app + separate WFF watchface APK. LibreLinkUp follower + Dexcom Share. 8 complication slots incl. chart, COB, IOB, TIR; Tiles | Closed | **Best production case study.** Not forkable, but their public support docs document every failure mode you will hit (§3c). Was forced off code-based watch faces in May 2025 — direct evidence for the WFF-only rule |
| **glucose-sync** (nimbleflux) | Kotlin 2.1 + Compose, phone + Wear + shared module, LibreLinkUp/Medtrum/Dexcom/Nightscout, Data Layer, complications, EncryptedSharedPreferences | MIT | **Cleanest modern codebase to fork.** Architecture matches the recommendation almost exactly |
| **Juggluco** (j-kaltes) | Direct BLE to Libre 2/3, own Wear OS app with direct sensor→watch mode | GPL | The offline/low-latency path |
| **xDrip+** | Mature all-in-one, LibreLinkUp follower mode, broadcasts to other apps | GPL | Useful as a data source; its Wear faces are dead on your watch |
| **nightscout-librelink-up** (timoschlueter) | TS script, LibreLinkUp → Nightscout, 13 regions, configurable interval | AGPL | If you go the Nightscout route |
| **nightscout-connect** | Newer unified Nightscout bridge for many cloud sources | AGPL | Successor to the above |
| **AndroidAPS PR #5123** (Philoul) | WFF + full-screen bitmap complication watch face | AGPL | **Reference implementation for §3b** |
| **NightWatch** (StephenBlackWasAlreadyTaken) | 2015 Nightscout Android client | — | Unmaintained, explicitly superseded by xDrip. Ignore |
| **Nightwear** (rahim) | Standalone Wear 2.0 Nightscout face | — | Legacy format, dead on Wear OS 5+ |
| **pylibrelinkup** | Python LibreLinkUp client | MIT | Best tool for the stage-1 spike |
| **libreview-unofficial** (Stoplight) | OpenAPI spec for the API | — | Generate a Kotlin client from it |

---

## 5. Gap worth noting

There is **no mature standalone Kotlin/Java LibreLinkUp client library**. Every Android project
rolls its own with Retrofit/OkHttp — it is roughly 200 lines. `glucose-sync`'s provider module is
the best existing Kotlin implementation to read or lift.

---

## 6. Non-negotiable caveat

This is a personal, unofficial, reverse-engineered pipeline. It is not a medical device, the
LibreLinkUp API is not supported by Abbott and can break without notice, and the cloud path adds
1–5 minutes of latency. The official app stays the source of truth for dosing decisions. Build in
a visible "data is stale" state — silently showing an old number is the dangerous failure mode.
