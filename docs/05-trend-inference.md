# Long-term trend inference

How to get real insight out of accumulated history — and how not to manufacture insight
that isn't there.

---

## 1. Four different questions

"Trends" collapses four problems that need different machinery. Separating them is most
of the work.

| | Question | Unit of analysis | Needs |
|---|---|---|---|
| **A. Level** | Is my control better or worse than it was? | day | ~28 days |
| **B. Shape** | What does a typical day look like? | time-of-day bucket | ~14 days |
| **C. Change** | *What is different lately?* | (day × bucket) | ~11 weeks |
| **D. Cause** | Why? | — | not answerable |

**C is the interesting one** — "new trends inference" in your phrasing — and it is the
only one that needs real statistics rather than arithmetic.

**D is out of scope and must stay out.** Without meals, exercise and sleep the app can
observe *what* changed but never *why*. Everything here stays descriptive. "Your overnight
average rose 22 mg/dL" is a fact; "your basal is too low" is medical advice this app has
no business giving.

---

## 2. Why the raw table cannot answer any of them

At one reading a minute, two years is ~1M rows. Every question above wants to sweep months
of that, and some want to do it repeatedly across 8 buckets × 2 windows.

Worse, **zone counts depend on user-configurable thresholds**. If TIR is precomputed
against 70–180 and the user later changes the band, every historical figure is wrong and
the only fix is rescanning a million rows.

So the raw table stays for the detail plot (#6) and the logbook (#10), and everything
analytic reads from rollups.

---

## 3. The rollup, and the two tricks that make it work

```
hourly_rollup
  hourStartMillis  INTEGER PRIMARY KEY   -- UTC hour
  localDate        TEXT                  -- resolved at write time
  localHour        INTEGER               -- 0..23, at write time
  count            INTEGER
  sum              REAL
  sumSq            REAL
  min, max         REAL
  buckets          INTEGER               -- distinct 5-min buckets with data
  histogram        BLOB                  -- 72 × uint16
  sensorSerial     TEXT
  sensorDay        INTEGER
```

### Trick 1 — store `sum` and `sumSq`, not `mean` and `sd`

Means and variances built from sums are **additive**. A 90-day mean and SD come from
summing 2160 hourly rows; no window size needs its own table, and no aggregate is ever
recomputed from raw.

### Trick 2 — store a histogram, not zone counts

72 bins of 5 mg/dL from 40–400, as `uint16`. 144 bytes an hour, ~1.2 MB a year. Nothing.

What it buys is disproportionate:

- **TIR for any thresholds, retroactively.** Change the target band and every historical
  figure recomputes instantly. Without this, configurable ranges (#9) and historical
  analytics are in permanent conflict.
- **Percentiles.** Median, IQR, 10th/90th — which is exactly what an Ambulatory Glucose
  Profile is made of, and what a mean-and-SD rollup cannot give you.

### Why local date and hour are stored, not computed

`(timestampMillis / 3600000) % 24` — which the current `averageForHourRange` query uses —
is **wrong**. It yields UTC hours, and it breaks across DST and travel. "My 3am" is a
local-wall-clock concept. Resolve it once at write time, in the zone the reading was taken.

*(This is a real bug in the DAO as it stands; it only looks right because the current
account is close enough to UTC−3 for the shape to seem plausible.)*

### Maintenance

- Updated incrementally as readings arrive; only the current hour is ever dirty.
- Fully **rebuildable from raw**, because a rollup that can drift from its source and
  can't be regenerated is a liability. Rebuild is also what a CSV import would trigger.

---

## 4. What becomes answerable

### Tier 1 — Level over time (question A)

Rolling 14-day TIR and GMI plotted across months. The trend line should be **Theil–Sen**
rather than least squares: a single bad week (illness, a failed sensor) shouldn't rotate
the line, and Theil–Sen is indifferent to that.

Report the slope with an interval, and in units that mean something: *"TIR improving by
about 2 percentage points a month"*.

### Tier 2 — Shape of a typical day (question B)

The **AGP**: median line with 25–75 and 10–90 bands, by time of day, over 14 days. This is
the standard clinical view and the histogram gives it directly.

It is issue #8 done properly. Eight 3-hour bars show *where* the average sits; the AGP
shows **spread**, which is where the information actually is. A bucket averaging 140 with
an IQR of 60–260 is a completely different situation from one averaging 140 with an IQR of
125–155, and #8's bar chart draws them identically.

Worth also splitting weekday vs weekend — for most people those are two different diseases.

### Tier 3 — What changed (question C)

The detector:

1. **Unit of observation: the (day, bucket) mean.** Never raw readings — see §5.
2. **Windows:** recent = last 14 days; baseline = the 7 weeks before that.
3. **Per-day quality gate:** a day contributes to a bucket only if its coverage there is
   ≥60%. Otherwise a day where the sensor dropped out through lunch quietly reports a
   better lunch.
4. **Test:** Mann–Whitney on daily means (n ≈ 14 vs 49). Non-parametric, since daily means
   are skewed and small-n.
5. **Effect-size gate:** surface nothing below ~15 mg/dL, however significant. With enough
   days, statistically detectable and worth knowing diverge sharply.
6. **Multiple comparisons:** 8 buckets tested weekly finds something by chance roughly
   every other week. Benjamini–Hochberg at q=0.10, *and* the effect gate.
7. **Sensor-change guard:** exclude comparisons where the two windows barely overlap in
   sensor sessions, since sensor-to-sensor bias is real and would present as a trend.

Output is a card, in plain language, with its own evidence:

> **Overnight is running higher**
> 00:00–03:00 averaged **178 mg/dL** over the last 14 days, against **142** in the 7 weeks
> before. Based on 13 of 14 days and 44 of 49.

**When to say nothing.** The detector's default answer must be silence. A feature that
finds something every week trains you to ignore it — the same failure mode as an alarm
that fires at the edge of target.

### Tier 4 — Variability

- **CV** = SD/mean. Directly from `sum`/`sumSq`. Commonly cited stability threshold ≤36%.
- **MODD** — mean of differences between the same time on consecutive days. Falls out of
  hourly rollups and measures day-to-day *reproducibility*, which CV misses entirely.
- **MAGE** needs raw excursion detection, so it runs over the raw table on a bounded window
  rather than from rollups.

### Tier 5 — Sensor-session effects (the one nobody else does)

We store `sensorSerial` and `sensorDay`. Align every reading by day-of-session and average
across sessions.

Libre sensors are widely observed to read low in the first ~24h and drift near expiry. With
4+ sessions that becomes a *personal, measured* bias curve rather than folklore — "your
sensors read about 12 mg/dL low on day 1" is genuinely actionable and needs no new data
collection at all.

It also feeds back into Tier 3 as a confounder to exclude.

### Short-horizon prediction — **built**, with conditions

Originally scoped out here. Asked for and now built, because the argument against it was
about *how* a forecast is presented rather than whether one can exist, and the presentation
is solvable.

- **Model.** Theil–Sen slope over the last 20 minutes, projected with exponential damping
  (`tau` = 45 min). Glucose mean-reverts, so an undamped line from a steep rise reaches
  implausible numbers inside the hour: +2 mg/dL/min projects to about +90 over two hours
  rather than +240. Robust slope, so one bad reading at the end of the window — the most
  influential and least corroborated point — cannot swing it.
- **The band is measured, not assumed.** `ForecastCalibrator` backtests the model over the
  user's own history and takes residual quantiles per horizon. A model that states its own
  confidence without ever checking is guessing twice.
- **Coverage is measured on held-out history.** Bands are fitted on everything older than the
  last week and scored on that week. Fitting and scoring on the same data always returns the
  nominal figure and tells the user nothing. This is the number that answers "does it work",
  and it is surfaced in Settings and Trends rather than buried.
- **Never feeds an alarm**, never counts as a reading, never persists as data.
- **Drawn in a language that cannot be mistaken for measurement**: dashed where the trace is
  solid, translucent where the trace is opaque, no measurement dots, its own hue, and a
  visible divider at the boundary between what was measured and what is guessed.
- **Off by default.** A guess about the future has to be asked for.

Two horizons are deliberately not built: a "you will go low in 30 minutes" alert, because
that is an alarm and alarms fire on measurement here; and anything using the forecast to
suggest an action.

### Not doing: machine learning

A personal history is ~90–700 *daily* observations. Robust statistics beat a learned model
at that size, and every output here has to be explainable to be trustworthy. If a claim
can't be traced to "these days versus those days", it shouldn't be on screen.

---

## 5. The traps

These are what separate inference from a noise generator.

### Autocorrelation — the big one

Consecutive 1-minute readings are almost the same number. Glucose has a correlation length
of roughly 30–60 minutes, so 20,000 readings in a fortnight carry maybe a few hundred
independent observations.

Run a t-test on raw readings and **everything is significant** — p-values of 1e-40 on
differences of 3 mg/dL. The fix is structural, not a correction factor: aggregate to one
number per day per bucket *first*, then test across days.

This single mistake would make the whole feature confidently wrong, and it is the default
thing to do.

### Missingness is not random

Gaps happen in showers, in bed with the phone across the room, when a sensor fails. They
are correlated with time of day and with behaviour, so a window with gaps over lunch
reports better lunches. Hence the per-day coverage gate, not just a window-level one.

### Regression to the mean

The fortnight after a bad fortnight is usually better regardless of what anyone did.
Any "improving" claim drawn from a window that started on an extreme is suspect —
which is another reason to prefer Theil–Sen over a two-point comparison.

### Sensor-to-sensor bias

A new sensor reading 10 mg/dL higher looks exactly like a real trend. Tier 5 both measures
this and neutralises it.

### Threshold changes rewrite history

If the user edits the target band, every TIR figure changes. The histogram makes that
consistent rather than incoherent — but any *stored* claim ("TIR improved 8 points") has to
record the thresholds it was computed under, or it becomes a lie after an edit.

---

## 6. Honesty rules

1. Describe, never prescribe.
2. Every figure carries its window, its n, and its coverage.
3. Silence is the default output. No finding is better than a weak one.
4. Minimum data gates are hard gates, not warnings — below them the feature says "not yet"
   and shows nothing.
5. Any stored claim records the thresholds it was computed under.

---

## 7. When each feature can first work

There is no backfill (research §0), so this is a calendar, not a sprint plan:

```
day 0    ───────────────────────────────────────────────────>
  │         │              │                    │
day 0     day 14         day 28              day ~77
nothing   AGP,           week-over-week      change detection
          GMI, CV        comparison          (14d vs prior 7w)

                                    day ~60-90: sensor-session effects (4+ sensors)
```

**This timeline is the strongest argument for the LibreView CSV import.** You set it aside
earlier because portal access was unconfirmed — but it would collapse eleven weeks of
waiting into one import, and it is the only lever that exists. Worth checking whether the
account can reach the LibreView web export.

---

## 8. Phases

- **A — Rollup infrastructure. ✅ built.** Schema, incremental update, rebuild-from-raw, and fix the
  UTC-hour bug. No UI. Everything else depends on it, and it should land before more
  history accumulates under the wrong shape.
- **B — AGP.** Percentile bands by time of day. Replaces #8's bar chart with the view that
  carries the information, over the same rollups.
- **C — Level trends.** Rolling TIR/GMI over months with a Theil–Sen line.
- **D — Change detection.** Tier 3 in full, with gates.
- **E — Sensor-session effects.** Tier 5.
- **F — MODD / MAGE.** Variability beyond CV.

A is worth doing soon regardless of when B–F land: it changes how readings are written, so
every day it waits is a day of history in a shape that needs migrating.
