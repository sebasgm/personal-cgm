# Can machine learning establish a better-grounded trend?

An honest evaluation, before committing to a model.

---

## 1. The short answer

**A bigger model is not the opportunity here. An honest one is.**

The published numbers are unambiguous about two things: deep learning does beat simple
extrapolation at 30–120 minutes, and it needs population data to do it. We have one person
and a history that starts at install. Training an LSTM on that will produce something worse
than the damped line already shipped, not better.

But there is a genuine gap, and it is not a modelling gap. **Every CGM app shows a
prediction. None shows you whether it works on your data.** Accu-Chek draws a 50% band.
Dexcom fires Urgent Low Soon. Neither tells you their hit rate on *you*, this month, against
the trivial alternative of assuming the current value persists.

That is the thing nobody is doing, it is buildable, and it is the precondition for any model
work that follows. You cannot claim a well-grounded trend without a way to show it is better
than doing nothing.

---

## 2. What the literature actually reports

RMSE in mg/dL, the standard benchmark datasets (OhioT1DM, UVA/Padova simulator):

| Horizon | Deep learning | Notes |
|---|---|---|
| 30 min | **16–20** | Transformer-LSTM 19.3; LSTM-GRU 17.2; RNN 16.1 |
| 60 min | **29–34** | Transformer-LSTM 31.8; LSTM-GRU 29.4 |
| 60 min | **15.9** | CGM-LSM, a foundation model pretrained on 15.96M recordings |
| 90 min | ~40 | |

And the finding that matters most for us, from the GLYFE benchmark, which exists specifically
to stop people comparing against nothing:

> For horizons up to **15 minutes, linear extrapolation is comparable to and sometimes
> better than** more complex models. Its advantage declines at longer horizons.

GLYFE's recommended baselines are zero-order hold, linear extrapolation and a third-order
autoregressive model. Any claim of improvement is measured against those.

### What this means concretely

- At **15 minutes**, sophistication buys close to nothing.
- At **30 minutes**, deep learning buys perhaps 2–4 mg/dL over a good linear model. Real, but
  small next to a sensor's own ~9% MARD.
- At **60+ minutes**, the gap becomes substantial — and that is exactly where a per-person
  model has the least data to learn from.
- The best 60-minute number in the table came from a model pretrained on sixteen million
  recordings. That is not a technique we can apply; it is an asset we do not have.

---

## 3. Why n=1 is the binding constraint

The transfer-learning literature says it plainly: individual data alone gives reduced
performance, and the accepted fix is to pretrain on population data and fine-tune per person.
Meta-learning work exists precisely because per-person data is too thin.

Our position:

- **No population data.** One user, no backend, and a deliberate decision not to build one.
- **History starts at install** and cannot be backfilled (research §0).
- A year of polling is roughly 500k readings, which sounds like a lot and is one person's
  narrow slice of glucose dynamics.

So the ladder that is actually available, cheapest first:

| Model | Data needed | Runs on device | Worth trying |
|---|---|---|---|
| Zero-order hold | none | trivially | **as a baseline, not a product** |
| Linear extrapolation | none | trivially | **baseline** |
| Damped Theil–Sen (shipped) | none | yes | current default |
| **AR(p), fitted online** | days | yes | **yes — genuinely learns personal dynamics, cheap** |
| **Gradient boosting on features** | weeks | yes | **yes — takes time-of-day and sensor-day** |
| SVR / kernel methods | weeks | yes | GLYFE's standout; worth a look |
| LSTM / transformer | months, ideally population | marginal | not until the harness says the simpler ones are exhausted |
| Foundation model | someone else's 16M recordings | if weights ever published | watch, do not build |

The two bolded rows are where the value is. Both can use covariates no one else has here —
see §5.

---

## 4. The contribution: measure, then model

Build the evaluation before the model. Concretely:

### Walk-forward benchmarking on your own history

For every candidate model, replay history: stand at time *t*, predict, compare against what
actually happened. Never evaluate on data the model was fitted to.

### Report three things, not one

1. **RMSE per horizon**, against ZOH and linear extrapolation on the same windows. A model
   that cannot beat "assume it stays the same" is not a model.
2. **Calibration** — does the 50% band contain the truth 50% of the time? Already built for
   the shipped forecast; it generalises to every candidate.
3. **Clarke error grid zones**, and specifically the **unsafe fraction** (C+D+E). RMSE treats
   a 40 mg/dL error at 250 the same as one at 60. Clinically they are nothing alike, and an
   error grid is the standard way of saying so. A model with better RMSE and a worse unsafe
   fraction is worse.

### Then show it in the app

A Trends card reading *"Over the last 30 days, the damped-linear model beat carrying the last
value forward by 3.2 mg/dL at 30 minutes, and its 50% band held 48% of the time."* — that is
the sentence no other app can produce about itself.

---

## 5. Where something genuinely novel is available

Four ideas, in order of how confident I am that they are under-served.

### 5a. Abstention — a model that declines to predict

Every app predicts always. A model that knows when it is out of its depth and says *"not
predictable right now"* instead of drawing a confident wrong line would be genuinely new in
this space, and it fits the honesty rules this app already follows everywhere else.

Concretely: if measured error in the current context exceeds a threshold, draw nothing. The
machinery is already there — the calibration table is per-horizon; make it per-context.

### 5b. Sensor-session day as a covariate

We store `sensorSerial` and `sensorDay` on every rollup row. Libre sensors are widely
observed to read low on day 1 and drift near expiry. Nobody uses that as a *model input*.
After four or five sessions it is a personal, measured effect rather than folklore — both a
better feature and a correction in its own right.

### 5c. Context-conditional model selection

The best model overnight is probably not the best model after a meal. Fitting a
regime-specific choice — quiet overnight, post-meal excursion, recovering low — and picking
per context is a small idea nobody seems to ship.

### 5d. Compression-low detection

A false low from lying on the sensor is a real, common, night-time problem. Its signature is
recognisable: a fast drop, a flat floor, a fast recovery, no matching insulin or carbs.
Classifying it is feasible.

**With a hard constraint: annotate, never suppress.** Silencing a low alarm on a model's say-so
is the single most dangerous thing this app could do. Marking one *"this pattern looks like
sensor compression"* while still alarming is useful; deciding not to wake someone is not.

---

## 6. First run, on real data

The harness is built (`core/…/Benchmark.kt`) and has been run over the 12.8-hour probe
recording. The window is short and the readings are mostly 15-minute-spaced backfill, so
n is 14–16 per horizon — these are findings, not conclusions.

```
model       horizon       n    RMSE   zone A   unsafe
hold           15m      16    35.2      75%     0.0%
ar3            15m      16    29.9      81%     0.0%
hold           30m      15    48.8      67%     0.0%
ar3            30m      15    43.4      73%     0.0%
hold           60m      14    71.0      50%    14.3%
ar3            60m      14    68.9      50%    14.3%
hold          120m      14    91.2      50%    21.4%
ar3           120m      14   140.5      29%    28.6%
```

Three things fell out of it immediately.

### The shipped model produced nothing at all

`linear` and `damped` scored **zero predictions**. Both derive their slope from
`ForecastModel.slopeMgdlPerMinute`, which needs four readings inside a twenty-minute
window — and this data is fifteen minutes apart, so the window never holds more than two.
The model declined, correctly, every single time.

That is not a bug so much as an unstated assumption: **the shipped forecast silently
requires minute-resolution data.** On the phone it has that. Two places it does not:

- the **web client**, which only ever sees graph data, so it could never draw a forecast;
- the phone **after any polling gap**, where the backfill is fifteen-minute spaced.

Worth deciding deliberately rather than discovering later: either widen the slope window
when readings are sparse, or state that the forecast is a minute-resolution feature and
show its absence rather than nothing.

### AR(3) beat the baseline at short horizons and lost badly at long ones

+5.3 mg/dL at 15 minutes, +5.4 at 30, +2.1 at 60, and **−49.4 at 120**. The shape matches
the literature exactly: fitted linear dynamics help nearby and mislead far out. It also
justifies the unsafe column — at two hours AR(3) was not merely less accurate, it put 29%
of predictions in dangerous zones against the baseline's 21%.

A model that wins on RMSE at one horizon and is clinically worse at another is precisely
what a single averaged number would have hidden.

### The absolute errors are much worse than published benchmarks

35 mg/dL at 15 minutes against the literature's 16–20 at 30. Two honest reasons: this
window is fifteen-minute-spaced rather than five, and the glucose in it ranged from 99 to
390. Published benchmarks are five-minute Dexcom data fromresearch cohorts. It is a reminder
that borrowed numbers do not transfer, which is the argument for measuring on your own data
in the first place.

---

## 7. What I recommend

1. **Build the benchmark harness.** Baselines, walk-forward evaluation, RMSE per horizon,
   calibration, Clarke zones. It is modest work and it converts every later question from
   argument into measurement.
2. **Run it on the history you have.** It will answer whether the shipped damped model is
   even beating linear extrapolation on your data. That answer is currently unknown, which is
   the real problem.
3. **Add AR(p) and gradient boosting as candidates**, with time-of-day and sensor-day
   features. Both are on-device-feasible and both are learning something real.
4. **Add abstention** once the per-context error table exists, because it falls out of it.
5. **Revisit deep learning when the harness says the simple models have stopped improving** —
   and not before, because until then it would be a bigger model fitted to less evidence.

The honest framing for anything built here: this is a personal instrument, evaluated on one
person, and every claim it makes about itself is a claim about *this* history. That is a
weaker statement than a clinical trial and a much stronger one than any app currently makes.
