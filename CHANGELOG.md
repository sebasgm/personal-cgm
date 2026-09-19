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
- Logbook of raw readings grouped by day.
- Trends: time in range, GMI and estimated A1C, each gated behind a coverage threshold and
  labelled with the coverage it was computed from.
- A medical disclaimer, shown before first use and reachable from Settings thereafter.
- Freshness thresholds tuned against recorded live data rather than guessed, with the
  measurements and their limits recorded in the code.

### Changed

- Statistics are aggregated in SQL rather than by loading rows, and retention was raised to
  two years so a one-year window cannot silently truncate.
- The chart's window became a continuous span with a movable end, replacing four fixed spans
  that always ended at now.

### Fixed

- The trace was drawn with pixel rather than dp stroke widths, which made it close to
  invisible on a high-density display.
- A stale reading no longer keeps its zone colour anywhere — not in the big value, not in the
  status bar icon, not on the chart. An old green number reads as "fine", which is the one
  thing it must not do.
- The delta now carries the interval it spans, so a fifteen-minute change cannot be read as a
  five-minute one.

### Security

- Credentials are encrypted with the Android Keystore; DataStore only ever holds ciphertext.
- Credentials are read from a gitignored `.env` for the development probe, and identifiers are
  masked in its output.
