# Features

A personal continuous glucose monitor client for Android, with a companion browser view.
It reads from a CGM vendor's cloud follower service and turns it into a display, an alarm
clock, a logbook and a set of trend tools — all running on your own device.

Where a design choice is unusual for tools of this kind, it is marked **Unusual**. That is a
statement about the choice, not about anyone else's product.

---

## 1. The current reading

- **A value that owns the screen.** Large, coloured by which zone it falls in, with the trend
  arrow and the change since the last reading beside it.
- **A live age, always.** "27 seconds ago" counts up every second whether or not new data
  arrives.
- **Four distinct states** — fresh, later than expected, no signal, and no data at all — each
  visually unmistakable rather than a subtle shade apart.
- **Staleness removes the colour.** Past a threshold the value turns grey and says it is not
  current.

  **Unusual:** most displays keep colouring a value by its zone regardless of age. A green
  number that is forty minutes old reads as "fine" when the truth is "unknown", and grey is
  what "unknown" looks like.

- **The value in the status bar**, drawn into the ongoing notification's icon, sized to be
  readable at a glance without opening anything.
- **A stale value is withheld from the status bar** and replaced with a question mark. There
  is no room beside a status bar icon to say "17 minutes ago", so a number there would be
  read as current.

## 2. The chart

- **Full-area trace** with a pale green in-range band as the reference to read against.
- **A dot per real measurement**, so a measured point is distinguishable from the line drawn
  between two of them.
- **The line breaks across gaps.** Where the sensor stopped reporting, the trace stops too.

  **Unusual:** joining across a gap draws readings that were never taken, on a chart someone
  may make decisions from.

- **Pinch to zoom** from fifteen minutes to seven days, and **drag to browse** back through
  history, with day arrows, a date picker and a return-to-now control.
- **Alarm thresholds drawn as dashed lines**, showing the levels that will actually wake you
  rather than the display's zone boundaries.
- **An explicit no-signal state** on the chart after five minutes without data.
- **Browsing history is not "stale".** A window from last Tuesday is history, not a doubtful
  current reading, so it keeps its normal colouring.

## 3. Alarms

- **Five independently switchable alarms**: urgent low, low, high, very high, and signal loss.
- **Each with its own threshold**, repeat interval and Do Not Disturb setting.
- **Alarm thresholds are independent of the display range.**

  **Unusual:** where a chart stops calling a value "in range" and where you want to be woken
  are different questions. An alarm set at the edge of target fires constantly, and an alarm
  that fires constantly gets ignored.

- **Quiet while recovering.** A low that is already coming back up sounds once and then stays
  visible but silent, resuming if the trend stalls. Direction-aware, and an unknown trend is
  never treated as recovery.
- **Urgent low opts out of that**, because "it is coming back up" is not a reason to stop
  reporting a severe low.
- **One condition, one alarm.** A reading that satisfies two thresholds sounds only the more
  severe.
- **Stale data drives only the signal-loss alarm.**

  **Unusual:** alarming on a reading from half an hour ago wakes you for a low that already
  ended — and worse, a stale reading that happens to look fine implies everything is well.
  When the data is old, the honest alarm is "I don't know".

- **Signal loss is evaluated even when polling fails**, which is precisely when it matters.
- **Snooze keeps the alarm visible** while silencing it, because dismissing it would imply the
  condition had passed.
- **Alarm state survives restarts**, so a restart cannot re-announce something already heard.
- **Do Not Disturb is handled honestly.** The override switch stays disabled until the
  operating system grant it needs actually exists, with a direct link to the setting. Alarms
  use alarm-category audio, so they are audible on a silenced ringer without needing that
  grant at all.

## 4. Trends and patterns

- **Time in range** across five zones, as a stacked bar and as individual bars, over
  selectable periods.
- **GMI and estimated A1C**, with the formulas available in a tooltip and stated plainly to be
  estimates from sensor data rather than laboratory values.
- **Daily pattern**: the day in eight three-hour slices, each drawn as a band — the middle 50%
  of readings as a box, the median inside it, and whiskers to the 10th and 90th percentile.

  **Unusual:** a slice averaging 140 with a spread of 60–260 and one averaging 140 with a
  spread of 125–155 are completely different days, and a chart of averages draws them
  identically. The spread is where the information is.

- **The least predictable part of the day is named**, since that is usually the slice worth
  looking at.
- **Every statistic states its coverage.** Below a reliability threshold, figures are shown
  muted with an explanation rather than hidden or presented confidently.

  **Unusual:** a time-in-range figure computed from six of ninety days is not wrong so much as
  meaningless, and the only honest fix is to say how much is behind it.

## 5. Logbook and treatments

- **Every raw reading**, grouped by day, with each day's average and time in range in its
  header — so scrolling gives a sense of the week without opening anything else.
- **Zone shown as a coloured dot** rather than coloured text, which keeps a long list readable.
- **Insulin logging** for basal and bolus doses, with units entered on a numeric keypad and a
  time that defaults to now but can be moved, because doses get logged after the fact.

## 6. Projection

- **An optional forecast** of where glucose may go, drawn as a dashed line inside a confidence
  band, visually separated from measured data by a divider at the boundary between the two.
- **The band is measured, not assumed.** It comes from testing the model against your own
  recorded history and taking the spread of its actual errors at each horizon.
- **It reports its own hit rate**, measured on history the band was *not* fitted to.

  **Unusual:** a model that states its own confidence without ever checking it is guessing
  twice. This one can tell you that its 50% band held 48% of the time — or that it did not.

- **It must be acknowledged every time the app opens**, and until it is, no projection is
  drawn at all.
- **It never feeds an alarm and never counts as a reading.** Alarms fire on measurement only.
- **Off by default.** A guess about the future has to be asked for.

## 7. Knowing whether the record is any good

- **A continuity report**: how much of the last 24 hours was actually recorded, how many gaps,
  and the longest one.

  **Unusual:** a gap in this kind of record is permanent — the upstream service serves only
  about half a day of coarse history — so whether the app kept up overnight is worth being
  able to check rather than infer from a chart that looks slightly sparse.

- **Freshness thresholds derived from measurement.** The points at which a reading is called
  late or stale were set from recorded live timing, not chosen by feel.
- **A prompt to exempt the app from battery optimisation**, which is the usual cause of
  readings missing overnight.
- **A forecast benchmark harness** that scores prediction models against your own history,
  including a clinical error grid so that a dangerous error is not averaged in with a harmless
  one.

## 8. Accessibility

- **Three typefaces**: the system font, a high-legibility face drawn so characters cannot be
  mistaken for one another, and a dyslexia-specific face. The options are labelled with what
  the evidence for each actually shows rather than ranked by reputation.
- **Text size, letter spacing and line spacing** as continuous controls, placed *above* the
  font choice because they make more difference and help every reader.
- **Live preview**, including the large number, since no description of a typeface is worth as
  much as seeing a reading rendered in it.
- **Three zone palettes**: the conventional one, a colour-blind-safe palette that separates
  zones along the blue–yellow axis and stays legible in greyscale, and a high-contrast variant.
- **Colour is never the only cue.** Zones also carry position, labels and shape.
- **Dark mode throughout**, since this is an app people open at three in the morning.

## 9. Units, language and notes

- **mg/dL or mmol/L**, defaulting to following the account so the setting does not have to be
  remembered in two places. Values are always stored in one unit; the choice only changes how
  they are read, so switching can never silently move an alarm threshold.
- **Spanish and English**, with a language setting independent of the system.
- **Release notes in the app**, generated from the project's own changelog so the two cannot
  drift apart.
- **A medical disclaimer** before first use and permanently available afterwards, plus a short
  periodic reminder that can be switched off.

## 10. Privacy and ownership

- **Everything runs on your device.** No account, no server, no analytics, nothing uploaded.
- **Credentials are encrypted with a key held in the device's hardware keystore**, and the
  password is only ever sent to the CGM vendor.
- **History is yours and local.** The app accumulates its own minute-resolution record, which
  is what makes the long-range views possible at all.
- **A browser view** for the current reading and trends, served by a small local process that
  stores nothing and forgets everything when it stops. It listens only on the local machine
  unless deliberately told otherwise.

---

## What it deliberately does not do

Stated because each is a decision rather than an omission.

- **It is not a medical device** and does not advise. It describes what happened; it never
  suggests what to do about it.
- **It does not use predictions to drive alarms**, or to suppress them. A model's opinion
  cannot silence a measured low.
- **It has no backend and no accounts of its own.** Sign-in means the CGM vendor's own
  credentials, and a lock on the app is a lock on this device.
- **It does not implement caregiver sharing.** The upstream service already does that, and a
  device-based alert would fail in exactly the situation it exists for — a phone that is dead,
  offline or asleep.
- **It does not claim alarms are guaranteed.** The operating system can delay or suppress
  notifications, and promising otherwise would be the most dangerous statement in the app.
