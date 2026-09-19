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

---

## 5. Second issue wave

`create-issues.sh` carries a larger and newer set than the eleven above — 23 issues. Six of
them are the chart, which is the signal worth reading: the app has been in daily use and the
trace is what grates.

### Built

**Chart rewrite** (5 issues at once, because they all touched the same eighty lines and doing
them in sequence would have meant rewriting it five times). Now `ui/GlucoseChart.kt`, out of
`HomeScreen.kt`, because it is the screen's centre of gravity rather than a detail of it.

- *Full area and Y resolution.* The chart was 160dp inside a scroller; it now takes whatever
  the column has left. `ValueAxis` in `:core` replaces the old `min-15 … max+15`: bounds are
  snapped to a 10 mg/dL grid so they do not crawl as readings arrive, hold a minimum 60 mg/dL
  span so a flat hour does not get magnified into mountains, and always keep the in-range
  band in view because the band is the reference the trace is read against.
- *LibreLink colours.* Pale green in-range stripe, with a dark-mode variant.
- *Thicker, high-contrast trace.* 3dp, near-black in light and near-white in dark. Chosen for
  the worst case rather than the average: the line has to stay legible over the green band,
  over plain background, and inverted. The old stroke was `4f` — raw pixels, about 1dp on a
  4x display, which is the whole reason it looked like a hair.
- *A dot per real measurement.* Plus the corollary the issue implies: `ChartSeries.segments`
  breaks the line wherever the sensor stopped reporting, because one unbroken line through a
  forty-minute hole draws readings that were never taken, on a chart someone may dose from.
  Dots are dropped below 7dp spacing — at 24 hours a dot per reading is not a dot, it is a
  thicker line that costs more to draw.
- *Red signal loss at five minutes.* A compact red bar above the chart, in the one colour
  reserved for "what you are looking at may not be true". Nothing else may use red.

**Chart interaction** (2 issues). Pinch only, as decided — zoom buttons dropped, preset chips
kept as the coarse control.

Both issues needed the same change underneath, which is why they landed together: the window
stopped being one of four fixed spans ending at *now* and became a span plus an end. Zoom
scales the span, browsing moves the end.

- *Zoom* is clamped to 15 minutes — below which the trace is a zigzag between individual
  samples rather than a curve — and 7 days, beyond which the line is denser than the pixels
  and the question has become a Trends question. After a pinch no chip is selected and the
  real span is shown beside them, because a chip reading "3h" over a 1h 47m chart is the one
  thing that row must not do.
- *Browsing* is a horizontal drag on the same gesture detector, plus day arrows, a date
  picker and a "Now" button — the button because at a week's span dragging back to the
  present would take a while. The end is stored as null while live rather than as an
  instant: storing "now" would freeze the chart the moment it was set, and every new reading
  would appear to fall outside the window.
- Two things follow from browsing that are easy to get wrong. The newest reading on screen
  loses its emphasis while browsing, because it is the last reading of a window that has
  passed rather than the current value. And staleness stops colouring the trace, because
  stale is a fact about the live feed — a window from yesterday is not stale, it is history,
  and history is not in doubt.
- The stat strip follows the browsed window too, so its coverage figure never describes a
  window nobody is looking at.

**Unit switching** (mg/dL ↔ mmol/L). Three choices, not two: following the account is the
default, so switching units in the official LibreLink app does not have to be remembered here
as well. `CgmState.unit` carries the effective unit and exists before the first reading, so
Settings works on a fresh install; `accountUnit` is kept beside it so "follow my account" has
something to follow.

Values are stored in mg/dL whatever is displayed — a unit is a way of reading a number, not a
different number. That is why the alarm sliders still move in mg/dL steps while showing mmol/L:
the stored threshold is unchanged by how it is read, so switching units can never silently move
an alarm. The chosen unit reaches the chart axis, the big value, the stat strip, the status bar
icon, the notification, the range editor, the alarm list and the alarm editor. It also reaches
the watch, since `WatchPayload` carries the snapshot and the snapshot now carries the effective
unit.

**Medical disclaimer**, shown before first use and kept in Settings for ever after. Ahead of
sign-in deliberately: it governs how every number in the app should be read, so it is not
something to meet afterwards. Stored as an accepted *version* rather than a flag, so materially
rewording it can ask again.

It carries one caveat the issue did not ask for, because this app's own behaviour demands it:
alarms can fail to arrive. Android may delay or suppress notifications while the phone is
asleep, in Do Not Disturb or saving battery, and the app cannot override all of that. Promising
an alarm it cannot guarantee would be the most dangerous thing on the screen. The separate
*sharing* disclaimer stays open, since there is nothing to share yet.

**`CHANGELOG.md`**, Keep a Changelog plus semver. Everything sits under Unreleased, which is
honest: nothing has been tagged, and the first version should not be cut until the disclaimer,
the license decision and the security pass are done.

**On the five minutes:** the display now says NO SIGNAL at 5 minutes, drops the value's zone
colour at 10, and the *alarm* still waits until 20. That spread is deliberate and is the
principle already in `docs/04-alarms.md` — the screen should stop claiming a value is current
long before it is worth waking someone over.

### Next, in order

1. **Trend in the expanded notification** — the status bar icon's three glyphs go to the
   number, so the trend has nowhere to live there. The open question is whether it belongs in
   the icon at all (an arrow beside the number, competing for a 24dp square) or in the
   expanded notification's content, where the title already carries arrow and delta. Worth
   looking at the built version before deciding, since the expanded view may already say it.
*(Both done — see Built above.)*

### Needs a decision before any code

These are not blocked on effort, they are blocked on answers, and several gate each other —
accounts gates sharing, and sharing gates the sharing disclaimer.

- **License.** The issue states the tension itself: "allow modification and redistribution,
  prohibit commercial exploitation" is not OSI open source, and F-Droid-style repos will not
  carry it. AGPL-3.0 is the option that keeps it genuinely open while making closed-source
  exploitation unattractive. Worth deciding early — it shapes whether contributions are
  possible at all.
- **Login and accounts.** The issue notes the conflict with local-first itself. Until it is
  resolved as local-only / optional / sharing-only, caregiver sharing cannot be scoped.
- **Second data source.** "Generic sensor option" needs one name to be scopeable: xDrip+,
  Juggluco, Nightscout or LibreLinkUp.
- **Naming and branding**, **Play Store research**, the **security RFC**, and the **sensor
  compatibility matrix** are all written deliverables rather than code.
- **Android Auto**, **non-Wear watches** and **direct Wi-Fi/BLE** stay where the first
  roadmap put them: behind a working Wear OS path. Direct BLE in particular means giving up
  the official LibreLink app, since only one app can hold the sensor's connection.
