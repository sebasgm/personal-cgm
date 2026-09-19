# Issue roadmap

Triage of issues #1–#11 against what the API actually provides (measured, not assumed)
and what is already built.

---

## 0. The constraint that shapes everything

**LibreLinkUp cannot give us history. At all.**

Measured against the real account on 2026-09-19:

| Endpoint | What it returns |
|---|---|
| `/llu/connections/{id}/graph` | **12.3 hours**, aggregated to ~15-minute spacing |
| `/llu/connections/{id}/logbook` | **3 entries over 3.5 days** — sparse scan/alarm events, not a trace |

The logbook is documented as "about 14 days" but it holds *events*, not continuous
readings. It is useless for reconstructing a glucose curve.

### What follows from this

Issues **#6, #7, #8, #9** all want windows of 7 days to 1 year. None of that data exists
anywhere we can reach. It can only be **accumulated by our own polling, starting the day
the service first runs.**

```
install day  ──────────────────────────────────────────>
    │            │              │                 │
    0d          14d            30d               90d
 nothing     GMI/A1C        hourly bars      full 90d TIR
             meaningful     meaningful        meaningful
```

Three consequences, and they are not negotiable:

1. **Get the polling service running now**, ahead of any UI work. Every day it is not
   running is a permanent hole in the analytics. This is the single highest-value action
   in this document and it needs nothing new built.
2. **Raise retention.** Currently 90 days (`HISTORY_RETENTION_MILLIS` in
   `GlucoseRepository`). #6 wants 1-year plots, so retention must exceed the longest
   window. At ~1 reading/minute a year is ~525k rows — perfectly fine for SQLite, but the
   schema needs an index and the queries need to aggregate rather than load rows.
3. **Every analytic must state its coverage.** "GMI 7.1%" computed from 4 days of data is
   a lie of omission. Each figure carries *"based on 6 of 90 days"* or it does not ship.

### The one escape hatch

The **LibreView web portal** (not the API) offers a CSV export of glucose history. A
one-time importer would backfill months in a single step and make the analytics useful
immediately rather than in three months. Worth building early — see Phase 3.

---

## 1. Triage

| # | Title | Depends on | Phase |
|---|---|---|---|
| 9 | Time in range barchart | thresholds model, history | 1 (model) / 3 (chart) |
| 11 | Status bar value | nothing — service exists | **1** |
| 1 | Configurable low alert | alert engine, thresholds | **1** |
| 10 | Logbook of raw values | nothing — Room has the data | **1** |
| 5 | Basal/bolus insulin dosing | new entity, reminders | **2** |
| 6 | Historical plot | retention, insulin markers | **2** |
| 7 | GMI and A1C | ≥14 days of history | **3** |
| 8 | Hourly average barchart | ≥7 days of history | **3** |
| 2 | Direct Wi-Fi / Bluetooth | different acquisition path | 5 |
| 3 | Android Auto | separate surface | 5 |
| 4 | Non-Wear OS watches | scope undecided | 5 |

---

## 2. Foundation work the issues force

These are not features. They are model changes that several issues need, and doing them
late means rewriting the features built on top.

### 2a. Configurable thresholds (blocks #9, #1, and all colouring)

Current `GlucoseRange` has a two-boundary band (70/180) plus a hardcoded urgent-low at 55.
Issue #9 wants four configurable zones. #1 wants a low alert. Reconciled as **five** zones:

| Zone | Default | Source |
|---|---|---|
| Urgent low | < 55 | needed by #1; not in #9 but required for a useful alert |
| Low | < 70 | account (**decided**) |
| In range | 70 – 180 | account (**decided**) |
| High | 180 – 240 | #9 |
| Very high | > 240 | #9 ("too high") |

**Resolved:** #9 specified 80, the account reports 70. Decision is to take
`targetLow`/`targetHigh` **from the LibreLinkUp account** as the defaults, so the app
agrees with what LibreLink itself shows. All five boundaries stay user-overridable in
Settings; the account only supplies the defaults.

This replaces `GlucoseRange`/`Zone` in `:core`. Doing it now is cheap; doing it after four
charts are built is not.

**Built.** Editing lives in Settings → Ranges, backed by `ThresholdOverrides` in `:core`.
Two details worth knowing, because both were deliberate:

*Overrides are per boundary, not a whole `GlucoseThresholds`.* Only the boundaries the user
actually edited are stored; the rest keep following the account. Storing the whole object
would mean editing the urgent low silently freezes the in-range band, and the app would
stop agreeing with LibreLink the next time the account's target moved — which was the
entire reason for reading targets from the account in the first place.

*The account's values are kept alongside the effective ones.* `CgmState.accountThresholds`
holds what the account said, so the screen can show "account says 70" beside a boundary the
user has taken over and offer to hand it back. Without it, "follow the account again" would
have nothing to restore.

Each slider is bounded by its neighbours, so disorder cannot be entered.
`GlucoseThresholds.sanitised()` still runs on load, as a net for settings written by an
older build — but it is the net, not the mechanism. Repairing after the fact would be worse
than preventing: raising the urgent low past the low would drag the low, high and very high
up with it, and the user would watch three numbers they never touched change themselves.

### 2b. Statistics engine (blocks #7, #8, #9)

A pure, testable module in `:core` over a list of readings:

- time in range per zone, as duration and percentage
- mean, SD, coefficient of variation
- GMI: `3.31 + 0.02392 × mean_mgdl` (standardised)
- estimated A1C: `(mean_mgdl + 46.7) / 28.7`
- per-bucket averages for arbitrary bucketing (#8's 3-hour buckets)
- **coverage**: readings present vs readings expected for the window

Coverage is a first-class output, not a footnote. Clinical guidance treats CGM metrics as
unreliable below ~70% coverage, and our coverage will be poor early on by construction.

These are computed with SQL aggregation, not by loading a year of rows into memory.

### 2c. Treatment entity (blocks #5, and #6's dose markers)

New Room table: type (basal/bolus), units, timestamp, optional note. Separate from
readings — different source, different lifecycle, user-editable where readings are not.

---

## 3. Phases

### Phase 1 — trustworthy and useful on day one

Nothing here waits for accumulated data.

1. **Thresholds model** (§2a) — foundation
2. **Home screen** — see `docs/03-ui-design.md`
3. **#11 status bar value** — **built.** The number is rendered into a bitmap and set as
   the ongoing notification's small icon, as xDrip does, because Android gives a
   notification one icon and no way to put text in the status bar. `StatusIconLabel` in
   `:core` decides *what* it says and `StatusBarIcon` in `:app` draws it.

   Three glyphs is the budget, which forces two decisions. Double-digit mmol/L drops its
   decimal, since "10.0" does not fit — rounded, not truncated, so 9.99 reads as 10 rather
   than 9. And a **stale** reading shows `?` instead of its last value: the status bar is
   glanced at, not read, so there is no room beside it for "17 min ago" and a number there
   would be taken as current. Aging readings still show their number; they are late, not
   wrong, and the shade says so. Above the sensor's range it reads `HI`, below it `LO`.
4. **#1 low alert** — configurable threshold, and the single-sound-on-rising behaviour.
   That behaviour needs alert *state*: alerted-at, last-trend, acknowledged. Re-alert when
   the trend stops rising or the value falls further. This is the subtlest logic in
   Phase 1 and deserves its own tests.
5. **#10 logbook** — the data is already in Room; this is a screen.

### Phase 2 — log what you do

6. **#5 insulin logging** — entry sheet, preset doses, daily reminders.
7. **#6 historical plot** — zoom, period presets, threshold lines, dose markers.
   Periods beyond ~12h show only what we have accumulated; the plot must show where our
   data begins rather than implying a flat line.

### Phase 3 — analytics

8. **LibreView CSV import** — do this *first* in the phase. It converts every following
   chart from "meaningful in three months" to "meaningful now".
9. **#9 TIR barchart** — four bars, configurable bounds.
10. **#7 GMI / A1C** — with the tooltip explaining the formula, and an explicit
    "estimated, not a lab value" caveat plus coverage.
11. **#8 hourly averages** — 8 bars × 3h, over 7/14/30/90 days.

### Phase 4 — the watch

Stages 3–5 of `docs/01-plan.md`, unchanged. The decisions already made (shared `:core`,
absolute timestamps, `WatchPayload`) keep this unblocked.

### Phase 5 — bigger tracks

- **#2 direct Wi-Fi/BT.** Covered in research §1B. Real trade-off: only one app can hold
  the sensor's BLE connection, and for Libre 3 the sensor is generally bound to the app
  that activated it. Taking it means giving up the official LibreLink app. It slots behind
  the existing `GlucoseSource` interface, so it is additive, not a rewrite.
- **#3 Android Auto.** Separate surface; GlucoDataHandler ships this via a companion app.
- **#4 non-Wear OS watches.** Scope genuinely open, as you noted. Garmin, Amazfit/Mi Band
  (WatchDrip+) and Fitbit each need their own bridge. Recommend deciding this only after
  the Wear OS path works end to end.

---

## 4. Decisions

1. **In-range bounds come from the account** (70–180 here), not #9's literal 80.
   Overridable in Settings.
2. **Five zones**, with a configurable urgent low defaulting to 55, so #1's alert has
   something meaningful to fire on.
3. **No LibreView CSV import for now** — portal access unconfirmed. Analytics accumulate
   from polling instead, which makes running the service early even more important. The
   importer stays in the backlog: if portal access turns up later it is worth building,
   because it would retroactively fill the gap.
4. **Build order after the foundation: Home screen first.**

### Still open

- **#11 "tray bar"** — read as the Android **status bar** (the persistent number beside the
  clock, as xDrip does), not a home-screen widget, and **built on that reading**. If a
  home-screen widget was meant instead, the label logic in `StatusIconLabel` carries over
  unchanged; only the surface would be new work.
