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
| Low | < 80 | #9 |
| In range | 80 – 180 | #9 |
| High | 180 – 240 | #9 |
| Very high | > 240 | #9 ("too high") |

⚠️ **Discrepancy to resolve:** #9 specifies in-range starting at **80**, but your
LibreLinkUp account reports a target of **70**–180. Two different numbers for the same
concept. Options: take the account's values as defaults, use #9's literally, or ask once
at setup. Needs your call — see the questions at the end.

This replaces `GlucoseRange`/`Zone` in `:core`. Doing it now is cheap; doing it after four
charts are built is not.

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
3. **#11 status bar value** — render the number into the notification's small icon, as
   xDrip does, so it sits in the status bar at a readable size. The ongoing notification
   already exists; this is its icon and layout.
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

## 4. Open questions

1. **In-range lower bound: 70 or 80?** #9 says 80; your account says 70. Which wins as the
   default?
2. **Urgent low.** #9 has no "too low" band, but a low alert without one is blunt. Add a
   fifth zone at a configurable default of 55?
3. **LibreView CSV import** — do you have access to the LibreView web portal for this
   account? If so this moves up, because it unlocks Phase 3 immediately.
4. **#11 "tray bar"** — confirming this means the Android **status bar** (the persistent
   number next to the clock), not a home-screen widget.
