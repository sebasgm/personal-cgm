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
- **A clock along the bottom**, on round wall-clock times, with a faint line at each so a point
  can be read across to a time without touching the screen. The tick that crosses midnight
  carries its date instead of "00:00".

  **Unusual:** the date rather than the hour at the day boundary. Browsing back three days
  otherwise looks exactly like this morning, which is the one thing this chart must never be
  ambiguous about.
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

- **GMI and estimated A1C** first on the screen, formulas in a tooltip, stated plainly as
  estimates from sensor data rather than laboratory values.
- **Time in range** across five zones, stacked and individually, over selectable periods.
- **The day drawn twice**, from the same percentiles over the same window:

  - a **ribbon** continuous across the 24 hours at hourly resolution — median line, middle-50%
    band, paler 10th-to-90th band — which answers *when*: where a rise starts, how long a
    plateau lasts, whether the night drifts;
  - eight **three-hour boxes** — middle 50% as a box, median inside, whiskers to the 10th and
    90th — which answer *how much*: exact figures for a named part of the day, readable without
    interpolating anything.

  **Unusual:** two charts rather than one, because they are not substitutes. A question whose
  answer is a time needs a chart continuous in time; a question whose answer is a number is
  easier to read off discrete boxes.

  **Unusual:** bands rather than averages, in both. An hour averaging 140 with a spread of
  60–260 and one averaging 140 with a spread of 125–155 are different days that a chart of
  averages draws identically.

- Both share one value scale, so neither can look calmer than the other through a scale of its
  own, and the ribbon breaks across hours with nothing recorded.
- **Tap either chart** for a slice's median, middle 50%, 10–90 spread — and the number of days
  behind it.

  **Unusual:** the day count in the callout. A band's width says how variable that part of the
  day was and nothing about how much was behind it, and "07–08 is usually 90" means something
  different over three days than over thirty.

- The least predictable three-hour slice is named, since that is usually the one worth looking
  at.
- **Every statistic states its coverage**, and below a reliability threshold is shown muted
  with an explanation rather than presented confidently.

## 5. Logbook and diary

- **The logbook** is every raw reading, grouped by day, each day's header carrying its average
  and time in range. Zone as a coloured dot rather than coloured text, which keeps a long list
  readable.
- **The diary** is the other half: insulin and food, as *you* record them.

  **Unusual:** two separate screens with deliberately different names. One is what the sensor
  measured and cannot be edited; the other is what a person did and must be correctable. Calling
  either of them "the log" would blur the only distinction that matters about them.

- **Insulin logging** for basal and bolus doses, entered on a numeric keypad.
- **Carbohydrate logging** in grams, with chips for the amounts people actually estimate in.

  **Unusual:** grams only — no food database, no portion picker. Grams are the part of a meal
  this app can line up against a rise on the chart, and a form that asks for more is a form that
  stops being filled in.

- A time that defaults to now but can be nudged or set exactly: meals and doses get logged after
  the fact, and an entry filed under the moment you remembered would sit an hour after the rise
  it explains.
- Insulin and carbohydrates are shown **interleaved in one history**, since a bolus and the meal
  it covered only make sense beside each other — and that is how you spot the meal you forgot to
  dose for.
- They are never added together. Units and grams are different quantities, and they are stored in
  separate tables so that no query can total them.

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
- The chosen typeface reaches the **status bar number** too, where it is otherwise drawn by
  hand. Picking a legibility or dyslexia face trades a little size for familiar letterforms,
  which is the right trade for someone who went and asked for one; leaving it on the system
  default keeps the largest number available.
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

- **A report over any window, cut into ranges you choose** — by day, week, month, or into a
  set number of equal parts — with the distribution, mean, median, time in range and coverage
  for each, exportable as CSV, JSON or HTML and printable to PDF.

  **Unusual:** mean and median are shown together rather than one standing in for the summary.
  Glucose has far more room above target than below it, so the two separate whenever a stretch
  runs high, and the distance between them says something neither number says alone.

  **Unusual:** coverage travels with the figures into the exported file, along with the
  thresholds they were computed against and the disclaimer. A file gets mailed, printed and
  read months later by someone who was not there, so nothing that is implicit on screen is
  left implicit in the export.

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

- **Everything runs on your device by default.** No account, no analytics, nothing uploaded
  unless you switch on the relay below.
- Credentials encrypted with a key held in the hardware keystore; the password only ever goes
  to the CGM vendor.
- **History is yours and local** — the app accumulates its own minute-resolution record, which
  is what makes the long-range views possible.
- An **optional relay**, off until configured, pushing the current reading to a server you
  host so it can be read from a browser anywhere.

  **Unusual:** the server holds one value in memory and no history, cannot reach the sensor
  vendor at all, and forgets everything on restart. The worst a compromise yields is one
  number; the worst an outage costs is the seconds until the next push.

  While it is on, your latest reading exists somewhere other than your phone. The screen that
  enables it says so.

---

## What it deliberately does not do

Each is a decision, not an omission.

- **Not a medical device.** It describes what happened; it never suggests what to do.
- **Predictions never drive or suppress alarms.** A model's opinion cannot silence a measured low.
- **No accounts.** Sign-in means the vendor's credentials; a lock on the app is a lock on this
  device. The optional relay is a pipe, not an identity: one shared key, nothing to
  administer, no record of who you are.
- **No caregiver sharing.** The upstream service already does it, and a device-based alert
  would fail exactly when it is needed — a phone that is dead, offline or asleep.
- **No promise that alarms arrive.** The operating system can delay or suppress notifications,
  and claiming otherwise would be the most dangerous statement in the app.
