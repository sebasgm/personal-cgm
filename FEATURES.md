# Features

A personal continuous glucose monitor client for Android, with a companion browser view.
It reads from a CGM vendor's cloud follower service and turns it into a display, an alarm
clock, a logbook and a set of trend tools — all running on your own device.

Choices that are unusual for tools of this kind are marked **Unusual**, with the reasoning.
That describes the choice, not anyone else's product.

---

## 1. The current reading

- A large value coloured by its zone, with trend arrow and change since the last reading.
- A **live age** that counts up every second whether or not new data arrives.
- Four unmistakable states: fresh, late, no signal, no data.
- **Staleness removes the colour.** Past a threshold the value turns grey.

  **Unusual:** a green number forty minutes old reads as "fine" when the truth is "unknown".
  Grey is what unknown looks like.

- The value in the **status bar**, drawn into the ongoing notification's icon.
- A stale value is **withheld** from the status bar and replaced with a question mark — there
  is no room beside an icon to say "17 minutes ago", so a number there would be read as now.

## 2. The chart

- Full-area trace over a pale green in-range band.
- A dot per real measurement, so a reading is distinguishable from the line between two.
- **The line breaks across gaps.**

  **Unusual:** joining across a gap draws readings that were never taken.

- Pinch to zoom from fifteen minutes to seven days; drag to browse history, with day arrows,
  a date picker and a return-to-now control.
- **Alarm thresholds drawn as dashed lines** — the levels that will actually wake you, rather
  than the display's zone boundaries.
- An explicit no-signal state after five minutes without data.
- Browsed history keeps normal colouring: last Tuesday is history, not a doubtful reading.

## 3. Alarms

- **Five independently switchable alarms**: urgent low, low, high, very high, signal loss —
  each with its own threshold, repeat interval and Do Not Disturb setting.
- **Alarm thresholds are independent of the display range.**

  **Unusual:** an alarm at the edge of target fires constantly, and one that fires constantly
  gets ignored.

- **Quiet while recovering.** A low already coming back up sounds once, stays visible and
  silent, and resumes if the trend stalls. An unknown trend is never treated as recovery.
- Urgent low opts out of that, because recovering is not a reason to stop reporting a severe low.
- One condition, one alarm: a reading meeting two thresholds sounds only the more severe.
- **Stale data drives only the signal-loss alarm.**

  **Unusual:** alarming on a half-hour-old reading wakes you for a low that already ended —
  and a stale reading that looks fine implies everything is well.

- Signal loss is evaluated even when polling fails, which is when it matters.
- Snooze silences while keeping the alarm visible; dismissing would imply it had passed.
- Alarm state survives restarts, so a restart cannot re-announce what was already heard.
- **Do Not Disturb handled honestly:** the override stays disabled until the operating system
  grant actually exists, with a link to it. Alarm-category audio means alarms are audible on a
  silenced ringer without that grant.

## 4. Trends and patterns

- **Time in range** across five zones, stacked and individually, over selectable periods.
- **GMI and estimated A1C**, formulas in a tooltip, stated plainly as estimates from sensor
  data rather than laboratory values.
- **Daily pattern**: the day in eight three-hour slices, each a band — middle 50% as a box,
  median inside, whiskers to the 10th and 90th percentile.

  **Unusual:** a slice averaging 140 with a spread of 60–260 and one averaging 140 with a
  spread of 125–155 are different days that a chart of averages draws identically.

- The least predictable slice is named, since that is usually the one worth looking at.
- **Every statistic states its coverage**, and below a reliability threshold is shown muted
  with an explanation rather than presented confidently.

## 5. Logbook and treatments

- Every raw reading, grouped by day, each day's header carrying its average and time in range.
- Zone as a coloured dot rather than coloured text, which keeps a long list readable.
- **Insulin logging** for basal and bolus doses, entered on a numeric keypad, with a time that
  defaults to now but can be moved — doses get logged after the fact.

## 6. Units, language and notes

- **mg/dL or mmol/L**, defaulting to following the account. Values are stored in one unit and
  the choice only changes how they are read, so switching can never move an alarm threshold.
- **Spanish and English**, with a language setting independent of the system.
- **Release notes in the app**, generated from the project's changelog so the two cannot drift.
- A **medical disclaimer** before first use and permanently available after, plus a short
  periodic reminder that can be switched off.

## 7. Accessibility

- **Three typefaces**: the system font, a high-legibility face whose characters cannot be
  mistaken for one another, and a dyslexia-specific face — each labelled with what the
  evidence for it actually shows rather than by reputation.
- **Text size, letter spacing and line spacing** as continuous controls, placed *above* the
  font choice because they matter more and help every reader.
- Live preview, including the large number.
- **Three zone palettes**: conventional, colour-blind-safe (separating zones along blue–yellow
  and legible in greyscale), and high contrast.
- **Colour is never the only cue** — zones also carry position, labels and shape.
- Dark mode throughout, since this is an app people open at three in the morning.

## 8. Knowing whether the record is any good

- **A continuity report**: how much of the last 24 hours was recorded, how many gaps, and the
  longest.

  **Unusual:** a gap here is permanent, since the upstream service serves only about half a
  day of coarse history — so whether the app kept up overnight is worth being able to check.

- **Freshness thresholds derived from measured timing**, not chosen by feel.
- A prompt to exempt the app from battery optimisation, the usual cause of readings missing
  overnight.
- **A forecast benchmark** scoring prediction models against your own history, including a
  clinical error grid so a dangerous error is not averaged in with a harmless one.

## 9. Projection

- An **optional forecast**, drawn as a dashed line inside a confidence band, separated from
  measured data by a visible divider.
- **The band is measured, not assumed** — it comes from testing the model against your own
  history and taking the spread of its actual errors at each horizon.
- **It reports its own hit rate**, measured on history the band was not fitted to.

  **Unusual:** a model stating its confidence without ever checking it is guessing twice. This
  one can tell you its 50% band held 48% of the time — or that it did not.

- Must be acknowledged **every time the app opens**, and until then nothing is drawn.
- **Never feeds an alarm and never counts as a reading.** Off by default.

## 10. Privacy and ownership

- **Everything runs on your device.** No account, no server, no analytics, nothing uploaded.
- Credentials encrypted with a key held in the hardware keystore; the password only ever goes
  to the CGM vendor.
- **History is yours and local** — the app accumulates its own minute-resolution record, which
  is what makes the long-range views possible.
- A **browser view** for the current reading and trends, served by a small local process that
  stores nothing and listens only on the local machine unless deliberately told otherwise.

---

## What it deliberately does not do

Each is a decision, not an omission.

- **Not a medical device.** It describes what happened; it never suggests what to do.
- **Predictions never drive or suppress alarms.** A model's opinion cannot silence a measured low.
- **No backend, no accounts of its own.** Sign-in means the vendor's credentials; a lock on the
  app is a lock on this device.
- **No caregiver sharing.** The upstream service already does it, and a device-based alert
  would fail exactly when it is needed — a phone that is dead, offline or asleep.
- **No promise that alarms arrive.** The operating system can delay or suppress notifications,
  and claiming otherwise would be the most dangerous statement in the app.
