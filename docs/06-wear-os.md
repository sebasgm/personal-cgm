# Wear OS integration — OnePlus Watch 3

Replaces stages 3–5 of `docs/01-plan.md` with a plan specific to the watch you have.

---

## 1. The device, and what it decides for us

| | |
|---|---|
| OS | **Wear OS 5** today. Wear OS 6 promised "during 2026"; as of 2026-09-20 it has not shipped, and OnePlus has a history of missing these dates |
| Chips | Snapdragon W5 **plus a BES2800 MCU running an RTOS** |
| Radios | Bluetooth + Wi-Fi. No LTE |

### The dual-chip problem

The Watch 3's battery life comes from the RTOS chip handling the always-on display while the
W5 sleeps. **Only OnePlus's own watch faces run on the RTOS.** Any third-party face keeps the
Snapdragon awake for AOD, and the battery figures the watch is sold on stop applying.

The consequence is not obvious and shapes the whole plan:

> **A complication on a OnePlus stock face will almost certainly beat a beautiful custom face
> on battery.** So the first thing built is complications, not a watch face — and that is the
> version worth living with for a while before deciding a custom face is worth its cost.

### Why the format question is already settled

Three kinds of watch face exist, and only one has a future here:

- **Legacy (WSL)** — dead. Removed from Play in January 2026, and the runtime is already gone
  from recent Pixel and OnePlus builds. This is why xDrip+'s faces do not work on this watch.
- **Code-based AndroidX faces** — would work on this watch *today*, because it is still on
  Wear OS 5. But Wear OS 6 firmware does not let the user select them, and this watch is due
  Wear OS 6. Anything code-based we build now breaks on your own device, on OnePlus's
  schedule rather than ours.
- **Watch Face Format (WFF)** — declarative XML, no executable code. The only option.

Gluroo is the worked example: its code-based face was banned from Play in May 2025 and it had
to ship a WFF replacement. We get to skip that migration by starting where it ended up.

**WFF has no code, so it cannot draw a glucose trace from live data.** Everything custom
reaches the face through *complications*. That single fact drives sections 3 and 5.

---

## 2. Transport: the Data Layer

`DataClient` for state, `MessageClient` for urgency. Google Play Services runs this over
Bluetooth and falls back to Wi-Fi/cloud relay when out of range, buffering while disconnected.
This *is* the Bluetooth link — writing our own GATT service would re-implement it worse.

The wire format already exists: `WatchPayload` in `:core`, with `PATH`, `downsample()` to 48
points, and absolute epoch-millis timestamps. Three properties of it matter on the watch:

- **Small.** It syncs over Bluetooth; the short `@SerialName` keys on `GlucoseReading` exist
  for this.
- **Absolute timestamps, never "minutes ago".** The watch needs the raw instant to hand to the
  platform's time-difference bindings. See §3.
- **Self-contained.** It will often be rendered while disconnected, so one payload must be
  enough to draw everything with no back-channel.

### Module layout

```
:core      ← already Android-free. :wear consumes it unchanged.
:data-llu  ← already Android-free. Needed on the watch for the W6 standalone fallback.
:app       phone
:wear      new — Data Layer listener, complication sources, tile, bitmap renderer
:watchface new — WFF XML only, no code by definition
```

`:core` and `:data-llu` were made plain Kotlin/JVM modules for exactly this. That decision
now pays: nothing needs restructuring to start.

One thing that does need doing: **the app is Spanish by default.** `:wear` needs its own
`values-es` from the first screen, not retrofitted.

---

## 3. The freshness problem, which is the hard part

On WFF the app **does not control when a complication refreshes** — the platform does, with a
five-minute floor on `UPDATE_PERIOD_SECONDS` and a convention of not calling
`ComplicationDataSourceUpdateRequester.requestUpdate()` more than once per five minutes on
average.

This is not theoretical. Gluroo has a support page titled *"My Wear OS Watch Face is showing
an old CGM Reading of my Blood Sugar?"*, and their own advice amounts to tapping the
complication to look interested.

Three things make it survivable:

1. **Push, not pull.** `UPDATE_PERIOD_SECONDS = 0`, and fire `requestUpdate()` from the
   `WearableListenerService` when a reading lands. A CGM's cadence and the platform's budget
   are both about five minutes, so we stop fighting it and start matching it.

2. **Never send the age as text.** Send the timestamp; let the face render age with
   `TimeDifferenceComplicationText`. The platform evaluates that continuously with **no data
   source request at all**. The value may be throttled and stale; the "4 min ago" beside it
   stays true. This converts the platform's worst behaviour from a silent hazard into a
   visible one, and it is the same principle the phone already follows.

3. **A Tile for anything that must be current on demand.** Tiles use
   `setFreshnessIntervalMillis()` and can be refreshed by us. A swipe gets real data even when
   the face's complication is being throttled.

The phone's rule carries over unchanged: **stale data shows no value.** `StatusIconLabel`
already encodes this for the status bar and is in `:core` precisely so the watch reuses the
decision rather than reinventing it.

---

## 4. Alarms on the wrist

Phone notifications bridge to a paired watch automatically, so the first version needs no
work — and needs care that it does not *duplicate*. A watch-side notification plus the
bridged phone one is two buzzes for one low.

Order of attack:
1. Let the phone's alarms bridge. Confirm they arrive and how they feel.
2. Only if bridging is unreliable, add watch-local notification with the bridged one
   suppressed via `setLocalOnly` on the phone side.
3. `MessageClient` for the urgent-low path, since it is lower-latency than a synced DataItem.

The honest limit, already in the disclaimer: **the watch cannot be more reliable than the
phone's feed.** If the phone missed the reading, the wrist cannot invent it.

---

## 5. Surfaces, in the order they should be built

### W0 — Validate the device first (no code)

**Status: in progress.**

The point is not to try an app. It is to find out whether this watch can do the thing the
next five stages assume, before any of them is built. **Gluroo's release notes mention
compatibility problems with OnePlus watches without saying what they are**, and if
complications misbehave here, W1 through W4 are built on sand.

#### Set up wireless debugging first

Needed for W1 regardless, and it is the fiddly part, so do it while nothing depends on it.

On the watch: *Settings → System → Developer options* (tap the build number seven times in
*About* if it is not there) → **Wireless debugging** → *Pair new device*.

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb pair <watch-ip>:<pair-port>     # the six-digit code is on the watch
adb connect <watch-ip>:<debug-port> # different port from the pairing one
adb devices                         # should list the watch
```

#### Install the reference app

**GlucoDataHandler** (`github.com/pachi81/GlucoDataHandler`, MIT) already does
LibreLinkUp → Wear complications, which is exactly the W1 path. Phone APK and Wear APK come
from its releases page.

```bash
adb -s <phone>  install -r GlucoDataHandler-<ver>.apk
adb -s <watch>  install -r GlucoDataHandler-wear-<ver>.apk
```

Configure it on the phone with the same LibreLinkUp follower account, then add its glucose
complication to a **OnePlus stock watch face** — stock specifically, so the RTOS question in
§1 stays out of the measurement.

#### Record the baseline before starting

Twenty-four hours on your normal face with no third-party complication, so the battery figure
below has something to be compared against. Without this the battery number means nothing.

#### What to record

| # | Question | Why it matters | Result |
|---|---|---|---|
| 1 | Do third-party complications appear in the stock face's picker at all? | If not, W1–W4 are dead on this watch and the answer is a Tile-only design | |
| 2 | Does a value actually appear, and how far behind the phone is it? | Baseline for the §3 freshness problem | |
| 3 | Over 24h, how often does the displayed value actually change? | Decides whether W3 needs a Tile first. Under ~12 refreshes a day means the platform is throttling hard | |
| 4 | Watch battery over 24h, with vs without the complication | The baseline every later decision is judged against | |
| 5 | Does it survive a watch reboot, and Bluetooth dropping out and returning? | Data Layer buffering is supposed to handle this; confirm it does here | |
| 6 | Anything OnePlus-specific: blanking, vanishing after AOD, refusing to re-add | This is the Gluroo risk, stated concretely | |

#### What each outcome means

- **Complications work and refresh reasonably** → W1 as written.
- **Complications work but refresh badly** → W2 (Tile) moves ahead of W3, and the age counter
  from §3 becomes load-bearing rather than a nicety.
- **Complications are broken on this watch** → the plan changes shape entirely: Tile plus
  notifications, no watch face, and #4 (non-Wear watches) gets more interesting.

Record the answers here in the table rather than in a chat log, since they justify the
decisions the rest of this document makes.

### W1 — Data Layer + first complication

- `:wear` module, Compose for Wear, `values-es`.
- Phone: publish `WatchPayload` on each successful poll. The repository already exposes a
  `results` `SharedFlow` for exactly this sink — it was put there in stage 2 and has had no
  consumer until now.
- Watch: `WearableListenerService` caching the latest payload.
- `ComplicationDataSourceService` exposing:
  - `SHORT_TEXT` — value + trend glyph
  - `RANGED_VALUE` — position in the target band, via `GlucoseThresholds.fraction()`
  - `SHORT_TEXT` — **reading age**, as `TimeDifferenceComplicationText` over the timestamp
- Push model as in §3.
- Sideload with `adb install` over Wi-Fi debugging.

**Exit:** your own complications on a OnePlus stock face, with a live age counter. Leave it a
day and record how often the value actually refreshes — that number decides whether W3 needs
a Tile before a face.

This is the milestone where the watch becomes genuinely useful. Everything after is polish.

### W2 — Tile

Graph plus current value, refreshed on our terms. Reuses the chart geometry from
`ui/GlucoseChart.kt` and `ChartScale`/`ValueAxis` in `:core` — the phone chart has already
solved axis snapping, minimum span, and breaking the line across gaps, and none of that should
be written twice.

### W3 — WFF watch face

XML only: time, date, and complication slots with `<DefaultProviderPolicy>` pointing at W1's
providers. Colour the value by zone using WFF expressions.

**Measure battery against the W0 baseline before going further.** On this watch that
comparison is the whole decision.

### W4 — Full-screen bitmap face (only if W3's battery cost is acceptable)

The AndroidAPS pattern (PR #5123): the `:wear` app renders the entire face — trace, value,
arrow, delta, staleness — into a bitmap, publishes it as a full-screen image complication, and
a trivial WFF document displays it. Two slots, with and without seconds, for the ambient
transition.

Known costs, from AAPS's own notes: ambient refreshes only when data arrives, higher battery
than a native face, and framework-drawn complication slots on top are not clickable.

### W5 — Standalone fallback

Watch polls LibreLinkUp itself over Wi-Fi when the phone has been silent for N minutes.
`:data-llu` runs there unchanged. Opt-in, off by default: it doubles cloud polling against a
rate-limited API and puts credentials on a second device.

---

## 6. Risks

- **OnePlus-specific complication problems.** Unquantified; W0 exists to find them.
- **AOD battery** with any non-OnePlus face. Structural to the watch, not fixable by us.
- **Wear OS 6 arriving mid-build.** Mostly good — it is what WFF targets — but it will change
  face selection behaviour and is worth re-testing W3 against on the day it lands.
- **Complication throttling** worse than the five-minute budget suggests. §3's age counter is
  the mitigation; the Tile is the escape hatch.

---

## 7. What this does not change

The phone stays the source of truth and the only thing that talks to Abbott by default. The
watch is a display. Every rule already established — stale shows no value, age is rendered by
the platform, alarms never fire on old data — carries over unchanged, because they live in
`:core` and `:core` is what the watch imports.
