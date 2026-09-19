# Phone app UI design

Brief: **as clean as LibreLink, as informative as Gluroo.**

---

## 1. The governing idea

Those two goals pull against each other, so the resolution has to be structural rather
than a compromise on every screen.

- **LibreLink is calm** because it answers exactly one question — *what is my glucose right
  now* — and gives that answer the whole screen.
- **Gluroo is useful** because it surrounds the number with context: delta, insulin on
  board, time in range, trend.

Putting Gluroo's density onto LibreLink's home screen would just produce a cluttered
LibreLink. So:

> **Calm surface, dense second layer.** Home answers one question and stays quiet. Every
> dense view is exactly one tap away, and each home element is the door to its own detail
> screen.

Three rules that follow:

1. **One thing dominates.** On Home, the current value owns the upper half. Nothing
   competes with it.
2. **Density is earned by tapping.** The stat strip shows three numbers; tapping any of
   them opens the full chart behind it.
3. **Staleness outranks everything.** A stale in-range value must never look calm. This is
   the one rule that overrides visual restraint.

---

## 2. Navigation

Bottom bar, four destinations. LibreLink uses the same shape, and it keeps Home free of
chrome.

```
   Now        Trends      Logbook     Settings
```

Insulin logging is a **bottom sheet** reachable from Home and Logbook, not a fifth tab —
it is an action, not a place.

---

## 3. Now (home)

```
┌──────────────────────────────────────┐
│                                 ⚙    │
│                                      │
│              311                     │   ~96sp, zone colour
│           mg/dL  →  +2               │   unit · trend · delta
│                                      │
│          27 seconds ago              │   live, always truthful
│                                      │
│   ┌──────────────────────────────┐   │
│   │ ─────────────────── 240      │   │   threshold lines
│   │      ╱╲                      │   │
│   │ ░░░░╱░░╲░░░░░░░░░░░░ 180     │   │   target band shaded
│   │ ────────────────────  80     │   │
│   └──────────────────────────────┘   │
│      3h    6h    12h    24h          │   range chips
│                                      │
│   ┌────────┬─────────┬───────────┐   │
│   │  TIR   │   Avg   │  Sensor   │   │   the Gluroo strip
│   │  42%   │   198   │  day 1/14 │   │   each tappable
│   └────────┴─────────┴───────────┘   │
│                                      │
│         [ + Log insulin ]            │
│                                      │
├──────────────────────────────────────┤
│  Now     Trends    Logbook    ⚙      │
└──────────────────────────────────────┘
```

**The number.** Colour comes from the zone. Weight is heavy, digits tabular so the layout
does not jitter as values change. mmol/L accounts get one decimal and the same box.

**The age line.** Never "just now" when it isn't. It ticks every second. This is the single
most important text on the screen, because it is what makes the big number safe to trust.

**The graph.** Target band shaded, the configurable thresholds (#9) drawn as lines so the
zones are legible without a legend. Tapping opens the full plot (#6).

**The stat strip.** Three cells, no more — this is where Gluroo's density enters without
crowding. TIR → Trends. Avg → Trends. Sensor → sensor detail. When insulin logging lands
(#5), IOB replaces Sensor here and Sensor moves into the graph header.

### Degraded states

The home screen has four states and they must be visually unmistakable.

```
FRESH               AGING                  STALE                   NO DATA
                                                                
    311                 311                    311                    ——
  27s ago            7 min ago             23 min ago            no reading
                  ⚠ later than usual    ⚠ NOT CURRENT          Sensor warming up?
 zone colour       zone colour, muted    grey, desaturated       grey
```

In **stale**, the zone colour is removed entirely. A grey 311 with a warning reads as
"unknown", which is the truth. A green 95 that is forty minutes old is the dangerous case
this rule exists to prevent.

Errors that need the user (bad password, revoked access) get a banner above the graph with
an action, not a toast.

---

## 4. Trends

Dense by design — this is where the second layer lives.

```
┌──────────────────────────────────────┐
│  Trends                              │
│   7d    14d    30d    90d            │
│                                      │
│   ⓘ based on 6 of 90 days            │   coverage, always
│                                      │
│  ┌────────────────────────────────┐  │
│  │ Time in range                  │  │   #9
│  │  Very high  ████ 18%           │  │
│  │  High       ██████████ 31%     │  │
│  │  In range   ████████████ 42%   │  │
│  │  Low        ██ 9%              │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌──────────────┬─────────────────┐  │
│  │ GMI      ⓘ   │  Est. A1C   ⓘ   │  │   #7
│  │ 7.4%         │  7.6%           │  │
│  └──────────────┴─────────────────┘  │
│                                      │
│  ┌────────────────────────────────┐  │
│  │ Average by time of day         │  │   #8
│  │   ▁▃▅█▆▄▃▂                     │  │   8 bars × 3h
│  │  00 03 06 09 12 15 18 21       │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌──────┬──────┬──────────────────┐  │
│  │ Avg  │  SD  │  CV              │  │
│  │ 198  │  52  │  26%             │  │
│  └──────┴──────┴──────────────────┘  │
└──────────────────────────────────────┘
```

**Coverage first.** The banner is above the charts, not below. With no backfill, early
windows are mostly empty and a confident-looking 90-day TIR built on six days would be
misleading. Below ~70% coverage the cards render muted with the figure still shown.

**The ⓘ tooltips** (#7) give the formula and state plainly that these are estimates derived
from CGM data, not laboratory values.

---

## 5. Logbook (#10)

Raw values, which no other app exposes — that is the point of the issue.

```
┌──────────────────────────────────────┐
│  Logbook                    ⬆ export │
│  [All] [Lows] [Highs] [Insulin]      │
│                                      │
│ ── Today · avg 198 · TIR 42% ──────  │   sticky day header
│   04:31   ● 311   →                  │
│   04:30   ● 309   →                  │
│   04:12   ◐  88   ↘                  │
│   03:45   ▣ Bolus 4u                 │   #5 interleaved
│   03:44   ● 142   ↗                  │
│                                      │
│ ── Yesterday · avg 176 · TIR 61% ──  │
│   23:58   ● 168   →                  │
└──────────────────────────────────────┘
```

Day headers carry that day's summary, so scrolling gives a sense of the week without
opening Trends. Zone shown as a coloured dot rather than colouring the text, which keeps a
long list readable. Export to CSV; import from LibreView CSV lives in Settings.

---

## 6. Insulin sheet (#5)

A bottom sheet, not a screen.

```
┌──────────────────────────────────────┐
│  Log insulin                    ✕    │
│                                      │
│   ( Bolus )   ( Basal )              │
│                                      │
│   [ 2u ] [ 4u ] [ 6u ] [ 8u ]        │   presets
│                                      │
│        ──  4.0 u  ++                 │
│                                      │
│   Time   now  ·  edit                │
│                                      │
│        [ Save ]                      │
└──────────────────────────────────────┘
```

Presets are configured in Settings. Time defaults to now and is editable, because doses get
logged after the fact. Reminders are scheduled notifications configured per dose type.

---

## 7. Full plot (#6)

Opened from the Home graph. Landscape-capable.

- Period presets: 1d · 1w · 15d · 1m · 90d · 6m · 1y
- Pinch to zoom, drag to pan
- Threshold lines from the configured zones, always visible
- Basal/bolus markers on the time axis
- **Where our data starts is drawn explicitly** — a marker and shaded "no data" region, so
  an empty year does not read as a flat line

At long periods the line becomes a min/max band per bucket rather than a polyline; a year
of per-minute points is neither drawable nor readable.

---

## 8. Settings

Grouped, plain:

- **Account** — LibreLinkUp, region, sign out
- **Ranges** — the five zone boundaries (#9), urgent low
- **Alerts** — low alert threshold, rising-trend single-sound (#1), urgent low, stale-data alert
- **Display** — units, status bar value and size (#11)
- **Insulin** — preset doses, reminders (#5)
- **Data** — retention, export CSV, import LibreView CSV
- **Source** — LibreLinkUp today; direct BLE later (#2)

---

## 9. Visual language

| | |
|---|---|
| Type | One family. Tabular figures for all numbers so digits do not jitter. |
| Colour | Carries zone meaning only. Never decorative — if something is coloured, the colour means something. |
| Zones | urgent low & low → red · in range → green · high → amber · very high → deep orange |
| Stale | Removes all zone colour. Grey plus a warning glyph. |
| Motion | Only the age counter and value transitions. No decorative animation on a screen people check at 3am. |
| Dark mode | First-class. This app gets opened in the dark more than most. |
| Accessibility | Zone is never colour alone — always paired with position, label or glyph, for colour-blind readability. |

---

## 10. Build order

Matches Phase 1 of `docs/02-roadmap.md`:

1. Home with the four freshness states and the graph — replaces the current debug screen
2. Status bar value (#11)
3. Logbook (#10)
4. Settings: ranges and alerts (#9 model, #1)
5. Trends, once there is enough history to render honestly
