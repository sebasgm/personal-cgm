# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Nothing has been released yet. Everything built so far sits under Unreleased, and the first
tagged version will be cut when the app is worth installing on a phone that is not the
developer's — which, given this reads medical data, means after the disclaimer, the license
decision and a pass over the security plan.

## [Unreleased]

### Added

- Hourly rollups summarising history: additive sums so any window is assembled from stored
  rows rather than recomputed, and a per-hour distribution so time in range can be recalculated
  for any thresholds after the fact. Local date and hour are resolved when a row is written,
  because "my 3am" is a wall-clock idea and epoch arithmetic yields UTC.
- Daily pattern on Trends: the day in eight three-hour slices, each a band rather than a bar.
  A slice averaging 140 with a spread of 60–260 and one averaging 140 with a spread of 125–155
  are different days that a chart of averages draws identically.
- Time in range, GMI and estimated A1C over selectable periods, every figure stating the
  coverage behind it and rendering muted below the point where it would be reliable.
- Tap a point on the chart for its value and date.
- Wear OS app and the transport that feeds it, sharing the reading model, thresholds and
  freshness rules with the phone so the two cannot disagree about whether a value is current.
- Silent notification on the watch carrying the latest reading, opening onto the last three
  hours of insulin — the question usually being asked when a number is higher than expected.
- Daily insulin reminders, on the phone and the watch. Each firing schedules the next in the
  current zone rather than repeating on a fixed interval, so daylight saving and travel move
  them correctly.
- Android Auto screen, declared as an IOT app so it draws its own card instead of taking over
  the media tab. Stale readings show no number at all: a driver has under a second to look and
  no way to tell a current value from a frozen one.
- Accessibility settings: three typefaces, continuous text size, letter and line spacing, and
  three zone palettes including a colour-blind-safe one that separates zones along blue–yellow
  and stays legible in greyscale. Size and spacing sit above the font choice because they help
  more, and help everyone.
- Release notes in the app, generated from this file.
- Continuity report showing how much of the last day was actually recorded — a gap here cannot
  be backfilled, so it is worth being able to see rather than infer.
- Forecast benchmark scoring prediction models against your own history, including a clinical
  error grid so a dangerous error is not averaged in with a harmless one.
- Optional relay: the phone pushes the current reading to a server you host so it can be read
  from a browser. One value in memory, no history, no ability to reach the vendor, forgotten on
  restart. Off until configured, and the only feature that sends data off the device.
- Return to Home when the app is opened after five minutes away.

- LibreLinkUp as a data source: login, session persistence across restarts, and polling on a
  schedule that backs off on failure and gives up loudly rather than silently on a rejected
  credential.
- A foreground polling service, because a value that must be at most a minute or two old
  cannot be left to WorkManager's fifteen-minute floor.
- Five glucose zones with four configurable boundaries. Low and high default to the
  LibreLinkUp account's own targets so the app agrees with the official one.
- Editable ranges in Settings, stored per boundary so that untouched boundaries keep
  following the account.
- Choosable units (mg/dL or mmol/L), defaulting to following the account. Values are always
  stored in mg/dL; the unit only changes how they are read.
- Alarms for urgent low, low, high, very high and signal loss, each independently switchable
  with its own threshold, repeat interval and Do Not Disturb setting. The low alarm can sound
  once and then stay silent while the trend recovers, and urgent low opts out of that by
  default.
- The glucose value in the status bar, rendered into the ongoing notification's icon.
- Home screen: the current value with four freshness states, the trace, window presets and a
  three-cell stat strip.
- Chart: full-area layout, LibreLink-style pale green in-range band, a dot per real
  measurement, breaks across gaps where the sensor stopped reporting, labelled axis, pinch
  zoom, and day-by-day history browsing with a date picker.
- An explicit red NO SIGNAL state after five minutes without data.
- Time in range coloured against the 70% clinical target, and a stacked all-zone bar in
  Trends. Low coverage dims the colours rather than replacing them with grey, so the zones
  stay distinguishable.
- Logbook of raw readings grouped by day.
- Insulin logging for basal and bolus doses: units typed on a numeric keypad, relative
  shortcuts or an exact date and time, an optional note, today's running totals and deletion.
  Either decimal separator is accepted, since which one the keyboard offers depends on the
  language.
- Trends: time in range, GMI and estimated A1C, each gated behind a coverage threshold and
  labelled with the coverage it was computed from.
- A medical disclaimer, shown before first use and reachable from Settings thereafter.
- Spanish, as the default language, with English available and a language setting. Every
  user-facing string is a resource: 162 of them, in `values/` (Spanish) and `values-en/`.
  The chosen language also reaches the ongoing notification, the alarms and the error
  messages, not just the screens.
- Freshness thresholds tuned against recorded live data rather than guessed, with the
  measurements and their limits recorded in the code.

### Changed

- Freshness thresholds retuned against recorded live timing rather than estimated: on this
  connection the worst age of a healthy reading is under three minutes, so late is five and
  stale is ten.
- Deltas always state the interval they span. The change is measured over five minutes while
  readings arrive every minute, so two consecutive values never account for it — 150 to 160
  reading as −3 only means the value five minutes ago was 163.
- Alarms share a notification group with the ongoing reading, so an alert no longer claims a
  second status bar slot and pushes the value out of view.
- Status bar digits are larger: the icon is sized to the label instead of padded into a square,
  and narrower glyphs are drawn bigger because width is what hits the slot's limit first.
- Retention raised to two years, since the longest chart window has to fit inside it.
- The projection is switched off behind a build flag, pending a decision to bring it back.
- Statistics are aggregated in SQL rather than by loading rows, and retention was raised to
  two years so a one-year window cannot silently truncate.
- The chart's window became a continuous span with a movable end, replacing four fixed spans
  that always ended at now.

### Fixed

- The wake lock, the watch bridge and the time zone receiver were declared but never started.
  The phone had therefore never published a reading to the watch, and the overnight polling fix
  had never been in effect.
- The poll loop slept through the night. A foreground service keeps the process alive but not
  the CPU, so its timer did not fire in deep sleep and every minute missed was a reading lost.
- Rebuilding a rollup after a time zone change silently re-expressed history, moving readings
  between slices depending on when maintenance ran. Rows now record the zone they were
  resolved in.
- Reading age could go negative when the clock moved, rendering as "-3 minutes ago" while
  reading as maximally fresh.
- Screens that group by day cached the time zone once and kept drawing day boundaries from the
  zone you left.
- Pinch and pan became unresponsive when inspection was wired into the gesture path.
- The watch never asked for notification permission, so everything it posted was dropped.
- Deltas were computed from graph data spaced about fifteen minutes apart and presented as if
  they spanned five.
- The trace was drawn with pixel rather than dp stroke widths, which made it close to
  invisible on a high-density display.
- A stale reading no longer keeps its zone colour anywhere — not in the big value, not in the
  status bar icon, not on the chart. An old green number reads as "fine", which is the one
  thing it must not do.
- Alarm notifications no longer replace the glucose number in the status bar with a generic
  warning icon. Android gives the status bar one icon per notification and a high-importance
  alarm wins the space, so the value disappeared exactly when it mattered most; every
  notification now carries the value as its icon.
- A restart no longer shows "--" in the status bar while polling is still failing. The newest
  stored reading goes on screen before the first fetch, still aged honestly.
- The trend arrow on Home was a quarter the height of the value beside it, which made the
  second thing anyone looks for the hardest thing to see. It is now half.
- The delta now carries the interval it spans, so a fifteen-minute change cannot be read as a
  five-minute one.
- The poller no longer stays dead after the app is reinstalled. It previously started only on
  sign-in and on boot, so replacing the build killed the service and nothing brought it back
  until someone pressed Start by hand — with no reading, no value in the status bar, and no
  indication why. It now starts whenever a configured app opens, and Settings says plainly
  whether it is polling or stopped.


### Security

- Credentials are encrypted with the Android Keystore; DataStore only ever holds ciphertext.
- Credentials are read from a gitignored `.env` for the development probe, and identifiers are
  masked in its output.
