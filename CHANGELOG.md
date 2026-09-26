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

- **A configurable number of figures per day inside each range.** Six gives a value every four
  hours, so a week's row reads as six numbers rather than one mean — which is what says *when* in
  the day it comes apart. Only divisors of 24 are offered: five bands would be 4.8 hours each,
  putting a boundary at 04:48 and making two adjacent figures incomparable. Bands are grouped on
  the rollups' own local hour, so 08–12 stays the reader's morning across a time zone change or a
  DST shift. Empty bands are kept rather than dropped, because a gap in a printed grid is a fact
  and removing the row would line the rest up under the wrong heading. The HTML export gets a
  ranges-by-bands table of medians, CSV a row per range per band, and JSON a `bands` array.

- **Sliding across either daily-pattern chart reads its slices**, with the figures following the
  finger instead of arriving one tap at a time, and **tapping opens that chart full screen**,
  where turning the phone sideways gives a 24-hour axis the width it wants. The card stacks both
  charts, which leaves each about a fifth of a phone — enough to see the shape, not enough to put
  a finger on one hour of it.

- **A "Now" button on the main chart**, shown while browsing history, returning the window to
  the live edge in one tap.

- **A report over a chosen period, cut into ranges**, at the foot of the Trends tab. The period
  — a week to a year — and the split — by day, week, month, or into a chosen number of equal
  parts — are set once in Settings and stored, because the report is configured in one place
  and read in another. Each range gets its
  distribution, its mean and its median side by side, its time in range and its coverage, with
  a line for the whole period above them, on its own period rather than the chips at the top of
  the tab. Mean and median are both shown rather than one being
  chosen: glucose is right-skewed, so they part company whenever a stretch runs high, and that
  parting is the finding. Built from the hourly rollups, so a year costs a few thousand rows
  rather than hundreds of thousands of readings.
- **Exporting that report as CSV, JSON or HTML, and printing it — which is also how it becomes
  a PDF.** The HTML is self-contained, with the histograms as inline SVG, no scripts and no
  network; the system print pipeline turns it into a PDF rather than a second renderer that
  would have to be kept in agreement with the first. Every format carries the thresholds the
  zones were computed against, the coverage behind each range, and the disclaimer — a file
  outlives the screen it came from. Files are written to the cache and the previous export is
  cleared each time, so glucose history is not left lying around in app storage.
- A clock along the bottom of the main chart, on round wall-clock times with a faint line at
  each, so the moment a point belongs to can be read without tapping it. The tick that crosses
  local midnight carries its date rather than "00:00": a window browsed back three days would
  otherwise be indistinguishable from this morning's. Boundaries are resolved through a time
  zone rather than by dividing epoch millis, which puts them in the wrong place wherever an
  offset is not a whole hour.
- Carbohydrate logging, in grams, beside insulin on the same screen — a meal and the bolus for
  it are one event to the person having them. Chips fill the field with the amounts people
  actually estimate in; there is no food database, because grams are the only part of a meal
  this app can line up against a rise on the chart. Carbohydrates live in their own table
  behind a real migration, and are never totalled with insulin: units and grams are different
  quantities.
- The daily pattern at one-hour resolution as well as three, which is what the ribbon is drawn
  from.
- Tap either daily-pattern chart for the numbers behind a slice: median, middle 50%, 10–90
  spread, and how many days and readings are behind it. The day count is the part that was not
  available anywhere before — a band's width says how variable an hour was, not how much was
  behind it, and three days and thirty days draw the same picture.

### Changed

- **The report's controls moved to sit with its exports**, at the foot of the Trends tab, and are
  no longer in Settings. Choosing a period and choosing a file format are one task — deciding what
  to send someone — and splitting them across two tabs meant changing a period, walking back, and
  checking whether what you exported was what you meant.
- **Asking for a PDF now writes the HTML file too**, and prints that file rather than a string held
  in memory. The printed page and the exported file are the same bytes, so what came off the
  printer can be checked against something that exists.
- **Reopening the app always returns the chart to the present.** The window state outlives the
  screen, so a chart left on last Tuesday used to still be there on reopening — and a stale
  window is the one thing this app must never present as the current value. The "Now" button
  moved out of the browse bar and onto the chart, where the hand already is after dragging.

- **Settings is grouped by whose setting it is.** What you adjust about your own care comes
  first — alarms, ranges, and the insulin reminders now beside them, since all three are the app
  deciding to interrupt you — then the report, then display and reading, then sharing, then the
  account. What keeps the app running and what it is obliged to tell you sit at the bottom:
  important, read once, and not what anyone opens the screen for. Areas are separated by a rule
  above their heading rather than by a gap, and headings are small, spaced and in the accent
  colour rather than the size and weight of a row title — at this length the screen had become
  one long list with words occasionally in bold.

- The Doses tab is now the **Diary**, and holds insulin and food. "Log" was not available: the
  Logbook is already the record of what the sensor measured, and this is the record of what a
  person did — the distinction between an immutable measurement and a correctable entry is the
  only thing worth knowing about the two screens, so the names keep it.
- The daily pattern card now carries **both** charts: a continuous ribbon across the 24 hours —
  median line, middle-50% band, paler 10th-to-90th band — above the eight three-hour box plots
  that were already there. They are the same percentiles at two widths and neither replaces the
  other: a box spanning 18:00 to 21:00 cannot say whether a rise starts before dinner or after
  it, which is what the ribbon is for, while exact figures for a named part of the day are
  easier to read off a box than off a curve. Both share one value scale, and the ribbon breaks
  across hours with nothing recorded, exactly as the live trace breaks across gaps.
- GMI and estimated A1C are the first card on Trends, under the coverage notice. They were below
  two charts and a scroll, which made the figure anyone opened the screen for read as a footnote
  to the charts rather than as the summary they support.

### Fixed

- **Exports were unparseable on any phone that writes decimals with a comma.** `String.format`
  follows the device locale, so coverage came out as `0,95`: not a number in JSON, and a second
  column in CSV. The same bug put `x="12,3"` into the histogram SVG, which is not a length — so
  every bar of every chart silently failed to draw, and took the printed report with it. Figures
  written for a machine now always use a dot; how they are written on screen still follows the
  phone. Tested under a Spanish locale, because a laptop set to English cannot see any of this.
- **A range with no readings produced a JSON file nothing could open**, writing `"meanMgdl": ,`
  where there was no mean. An absent figure is `null` now — and a range with nothing in it is
  one of the cases most worth exporting.
- **Printing could abort before the dialog appeared.** The WebView that renders the report is
  never attached to the view tree, so nothing held a reference to it and it could be collected
  between loading the page and the print service asking for it. It is now kept alive for the
  life of the job and released when the job ends. Printing also needs an Activity, which is
  looked up rather than assumed, and a failure now says so instead of appearing to do nothing.

## [3.0.0] - 2026-09-24

Licensed under the PolyForm Noncommercial License 1.0.0: free to use, modify and share for
any noncommercial purpose, and not to be sold. That is a deliberate restriction and it means
the project is source available rather than open source.

The phone app is the released part, and it is stable: reading, charting, alarms, logbook,
insulin, trends and accessibility are all in daily use.

Two things ship alongside it and are **not** finished, deliberately kept in rather than held
back, because each is useful now and neither is load-bearing:

- **The Wear OS app** receives readings and shows them, but has no complication, tile or watch
  face yet, which is how a watch is actually read. It has not been verified on hardware.
- **The relay and its browser client** work end to end, but have only been exercised against a
  locally run server, and nothing has run on a hosted one.

Neither affects the phone. Both can be ignored entirely.


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
