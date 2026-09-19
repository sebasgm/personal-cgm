# Staged plan

Each stage ends in something you can actually look at. Nothing is built until the stage before it
is proven. Stage 0 exists specifically so you can discover the project is unnecessary before
writing code.

---

## Stage 0 — Validate with what already exists (one evening, zero code) — *still worth doing*

**Goal:** prove the whole pipeline is possible on *your* account and *your* watch before investing.

1. Confirm the LibreLinkUp follower relationship: primary account invites a second email, accept it.
2. Install **GlucoDataHandler** on the phone + its Wear app on the OnePlus Watch 3.
3. Configure the LibreLinkUp source, add its complication to a OnePlus stock watch face.
4. Observe for a day: does data arrive, how stale is it, what does it cost in battery?

**Exit criteria:** a real glucose number on the watch.
**Decision point:** if GDH covers your needs, the rest of this plan is optional and becomes
"build the watch face I actually want" rather than "build everything."

---

## Stage 1 — LibreLinkUp API spike (desktop, Python) — **built**

**Goal:** understand the API's real behaviour with your data, with no Android in the way.

- Script with `pylibrelinkup` (or raw `httpx` against the documented endpoints).
- Determine: your region endpoint, your `patientId`, actual update cadence, real latency vs. the
  phone app, rate-limit behaviour, token lifetime.
- Log 24h of readings to a JSON file. This becomes the **fixture for offline Android development**
  so you are not hammering Abbott's API while debugging UI.

**Deliverable:** `spike/llu_probe.py` + a day of recorded readings.
**Exit criteria:** you can state the real end-to-end latency in minutes.

---

## Stage 2 — Android phone app: data layer — **built**

**Goal:** a phone app that reliably holds a current glucose value. No watch yet.

- Kotlin, single Activity, Compose.
- Retrofit + OkHttp LibreLinkUp client (own module, port from `glucose-sync` — MIT, compatible).
- Keystore-backed AES-GCM + DataStore for credentials and JWT. **Not**
  `EncryptedSharedPreferences`: `androidx.security:security-crypto` was deprecated
  in April 2025 and the migration Google points at is exactly this.
- Foreground service, 60s poll, exponential backoff on 429, re-login on 401.
- Room table of readings (needed for the graph in stage 5).
- Debug screen: current value, trend, age of reading, last error.
- Explicit `Stale` state when the newest reading is older than a threshold.

**Exit criteria:** app survives overnight with no phone charging, network drops, and airplane-mode
toggles, and shows a correct value or an honest staleness indicator every time.
*Code is written and builds; this criterion is unverified until it runs on a real device with real
credentials.*

**Toolchain note:** AGP 8.x is incompatible with Gradle 9.6+, so the project is on AGP 9.4.1 /
Gradle 9.7.1. AGP 9 has built-in Kotlin support, so there is no `kotlin-android` plugin. Compose
BOM 2026.09 forces `compileSdk = 37`.

---

## Stage 3 — Watch transport + first pixel on the watch

**Goal:** first real end-to-end.

- Wear OS module in the same project (shared `:core` module for the reading model).
- Phone: push each reading via `DataClient` to a single DataItem path.
- Watch: `WearableListenerService`, cache the latest reading locally.
- Watch: `ComplicationDataSourceService` exposing `SHORT_TEXT` (value + trend arrow) and
  `RANGED_VALUE` (position in target range).
- **Push model, not pull:** `UPDATE_PERIOD_SECONDS = 0`, and call
  `ComplicationDataSourceUpdateRequester.requestUpdate()` from the `WearableListenerService` when
  a reading lands. Rate-limit yourself to one update per 5 min on average or the platform throttles
  you (see research §3c).
- **Ship a second complication carrying the reading's timestamp**, rendered with
  `TimeDifferenceComplicationText` so the age counts up continuously without any data source
  request. This is the safety net for platform throttling — the number may be stale, the age never
  lies.
- Add the complication to a **OnePlus stock watch face** — no custom face yet.
- Sideload with `adb install` over Wi-Fi debugging.

**Exit criteria:** your own complication showing live glucose on the stock face, with a live age
counter beside it. Leave it running a full day and record how often the value actually refreshes —
that number decides whether stage 5 needs a Tile. This is the milestone where the project is
genuinely useful.

---

## Stage 4 — Watch Face Format face, simple

**Goal:** your own watch face, built the way that survives Wear OS 6.

- WFF XML watch face APK: time, date, your glucose complication slot with `<DefaultProviderPolicy>`
  pointing at your provider from stage 3.
- Colour the value by range using WFF expressions on the complication data.
- Verify ambient mode behaviour and measure battery cost versus the stock face.

**Exit criteria:** custom face on the watch, and a measured battery number you are willing to live
with.

---

## Stage 5 — Full-screen bitmap face with trend graph

**Goal:** the face you actually want — a real graph.

- Follow the AndroidAPS PR #5123 pattern: Wear app renders the whole face (last 3h graph, value,
  arrow, delta, staleness) to a `Bitmap`.
- Publish as a full-screen image complication; two slots (with/without seconds) for the ambient
  transition.
- Minimal WFF document that displays it full-screen.
- Layer caching so redraws stay under ~100ms.
- **Also ship the graph as a Tile.** Tiles use `setFreshnessIntervalMillis()` and can be refreshed
  on demand, so a swipe always gets you current data even when the watch face complication is
  being throttled. Cheap to add once the bitmap renderer exists, and it is the escape hatch if
  stage 3's measurements showed bad refresh behaviour.

**Exit criteria:** trend graph on the wrist, ambient mode acceptable.

---

## Stage 6 — Make it dependable

- Alarms: high/low/urgent-low/stale, with vibration on the watch via `MessageClient`.
- Watch-side standalone fallback: if no phone contact for N minutes, the watch polls LibreLinkUp
  over Wi-Fi itself (opt-in, off by default).
- Battery tuning: poll interval adapting to trend stability.
- Optional: Nightscout as a second sink, so history lives somewhere you control.
- Optional: direct-BLE source behind the same interface (Juggluco-style), if latency bothers you.

---

## Architecture summary

```
Libre sensor ──BLE──> LibreLink app ──> Abbott cloud ──HTTPS──> [Phone app]
                                                                    │
                                                    Wear Data Layer (Bluetooth)
                                                                    │
                                                              [Wear app]
                                                                    │
                                              ComplicationDataSourceService
                                                                    │
                                                    Watch Face Format face
```

Repository layout to grow into:

```
:core          shared Kotlin model (GlucoseReading, TrendArrow, ranges, staleness)
:data-llu      LibreLinkUp Retrofit client
:app           phone app (polling service, settings, debug UI)
:wear          Wear OS app (data layer listener, complication providers, bitmap renderer)
:watchface     WFF XML watch face APK
spike/         Python probes and recorded fixtures
```

## Rules of engagement

- Every stage works offline against the stage-1 fixture file. Never debug UI against the live API.
- The staleness state is a first-class feature, not an error case. Render age via platform dynamic
  expressions, never as text you push — that way it stays true even when your data does not.
- Keep the source behind an interface from stage 2 so Nightscout / direct-BLE can slot in later
  without a rewrite.
- Nothing code-based for the watch face. Ever. WFF only.
