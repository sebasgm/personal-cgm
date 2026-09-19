#!/usr/bin/env bash
#
# Creates labels + issues for the glucose monitoring app.
#
# Requirements: GitHub CLI (https://cli.github.com), authenticated via `gh auth login`
#
# Usage:
#   REPO=youruser/yourrepo ./create-issues.sh
#   ...or run it from inside a cloned repo and leave REPO unset.
#
set -euo pipefail

REPO="${REPO:-}"
if [[ -n "$REPO" ]]; then
  GH=(gh issue create --repo "$REPO")
  GHL=(gh label create --repo "$REPO")
else
  GH=(gh issue create)
  GHL=(gh label create)
fi

echo "==> Creating labels (existing ones are skipped)"
create_label() {
  "${GHL[@]}" "$1" --color "$2" --description "$3" 2>/dev/null \
    || echo "    label '$1' already exists, skipping"
}

create_label "enhancement"     "a2eeef" "New feature or request"
create_label "ui"              "d4c5f9" "Visual / interaction layer"
create_label "safety"          "b60205" "Patient-safety relevant"
create_label "legal"           "5319e7" "Licensing, disclaimers, compliance"
create_label "feature"         "0e8a16" "Larger feature work"
create_label "research"        "fbca04" "Investigation, no code yet"
create_label "discussion"      "c2e0c6" "Needs a decision before implementation"
create_label "needs-decision"  "e99695" "Blocked on a product/architecture choice"
create_label "security"        "b60205" "Security and data protection"
create_label "chore"           "ededed" "Maintenance"
create_label "connectivity"    "1d76db" "Transport, pairing, offline behavior"
create_label "integration"     "0052cc" "Third-party sensors and apps"

echo "==> Creating issues"

# --- Platform & connectivity -------------------------------------------------

"${GH[@]}" --title "Support smartwatches beyond Wear OS" --label "enhancement" --body \
"Add watch compatibility that isn't limited to Wear OS devices. Should cover other watch platforms and companion protocols so users on non-Wear OS watches can receive readings and alerts.

**Open question:** which platforms to target first (Garmin Connect IQ, Amazfit/Zepp, Fitbit, generic BLE notification relay)."

"${GH[@]}" --title "Android Auto support" --label "enhancement" --body \
"Add Android Auto integration so glucose readings and alerts are visible and audible while driving.

Note: Android Auto has strict driver-distraction rules. Confirm which template category this app fits before designing the UI."

"${GH[@]}" --title "Direct Wi-Fi / Bluetooth connection (no internet required)" --label "enhancement" --label "connectivity" --body \
"Support local connectivity over Wi-Fi or Bluetooth so the app works without an internet connection or cloud relay.

This is a core architectural constraint — it should be settled before any feature that assumes a backend (see the login/accounts issue)."

"${GH[@]}" --title "Integration with the FreeStyle Libre app plus a generic sensor option" --label "integration" --body \
"Support reading data from the official Libre app, and also provide a generic / vendor-neutral integration path for other sensors or data sources.

**Needs clarification:** the second source wasn't specified. Candidates: xDrip+, Juggluco, Nightscout, LibreLinkUp. Pick one to scope this issue properly."

"${GH[@]}" --title "Configurable low-glucose alert with single-sound rising-trend behavior" --label "enhancement" --label "safety" --body \
"Make the low-glucose alert configurable, including an option to sound only once when the trend arrow is rising, instead of repeating. Reduces alert fatigue on recovering lows."

# --- Chart & main view -------------------------------------------------------

"${GH[@]}" --title "Use the full main-screen area for the chart and increase Y-axis resolution" --label "enhancement" --label "ui" --body \
"The chart currently wastes screen space. Expand it to the full available area of the main screen and rescale the Y axis so glucose variation is more readable at a glance."

"${GH[@]}" --title "Rework the color scheme to match LibreLink conventions" --label "enhancement" --label "ui" --body \
"Adopt a color scheme closer to the LibreLink app, with the in-range band rendered as a light green stripe. Goal is familiarity for users coming from the official app."

"${GH[@]}" --title "Make the trace thicker and pick a high-contrast color over the green band" --label "enhancement" --label "ui" --body \
"The plotted line is too thin to read. Increase stroke weight and choose a color with the best contrast against the light green in-range band — black is the likely candidate, but test against both in-range and out-of-range backgrounds, and in dark mode."

"${GH[@]}" --title "Render a dot for every real measurement point" --label "enhancement" --label "ui" --body \
"Today the chart draws a continuous line only. Plot an explicit marker at each actual sensor reading so users can distinguish real data points from interpolation."

"${GH[@]}" --title "Add chart zoom (pinch gesture and/or button control)" --label "enhancement" --label "ui" --body \
"Let users zoom the chart in and out, via pinch gestures, dedicated buttons, or both."

"${GH[@]}" --title "Browse history from the main chart view" --label "enhancement" --label "ui" --body \
"Add day-by-day navigation (swipe or arrows) plus a date picker, so the user can move through historical data without leaving the main chart view."

# --- Data & display ----------------------------------------------------------

"${GH[@]}" --title "Allow switching units between mg/dL and mmol/L" --label "enhancement" --body \
"Add a setting to toggle measurement units. Must apply consistently across the chart, the current-value display, alert thresholds, and any shared or exported data."

"${GH[@]}" --title "Show the trend in the expanded status bar notification" --label "enhancement" --label "ui" --body \
"The status bar carries the glucose value as the ongoing notification's small icon. When that notification is dropped down in the shade, the trend should be shown alongside it.

**Needs clarification:** whether the trend belongs *in the icon itself* — an arrow beside the number, which competes for space in what is effectively a 24dp square — or in the expanded notification's own content.

Current behaviour, for reference: the collapsed status bar icon shows the number only (three glyphs is its budget), while the expanded notification's title already carries value, unit, trend arrow and delta."

"${GH[@]}" --title "Show an explicit red signal-loss state after 5 minutes without data" --label "enhancement" --label "safety" --body \
"If no new reading is received for 5 minutes, the app must display a clear, explicit signal-loss indicator in red.

Silent staleness is a safety risk — the user should never mistake an old value for a current one. This should also propagate to the watch and Android Auto surfaces."

# --- Safety & legal ----------------------------------------------------------

"${GH[@]}" --title "Add a medical disclaimer" --label "safety" --label "legal" --body \
"Display a disclaimer stating that the data shown must not be treated as authoritative or as the sole source of truth, and that in case of doubt the user should consult the official sensor app.

**To decide:** placement — first-run acceptance screen, plus a persistent and easily reachable copy in settings or near the reading."

"${GH[@]}" --title "Disclaimer coverage for the sharing / remote alerting feature" --label "safety" --label "legal" --body \
"Separate from the general medical disclaimer: sharing and remote alerts can fail (no connectivity, OS notification throttling, carrier delays). Add a dedicated disclaimer covering delivery failure, shown when a user sets up sharing."

# --- Sharing, accounts & integrations ---------------------------------------

"${GH[@]}" --title "Share readings with caregivers by email and phone number" --label "feature" --body \
"Allow the user to register emails and phone numbers of people they want to share results with.

Delivery should work both for contacts who have the app installed and for those who don't (SMS / email fallback), with high and low alert thresholds configurable in settings.

Overlaps with the sharing disclaimer and the accounts issue — sequence them together."

"${GH[@]}" --title "Add login and user management" --label "feature" --label "needs-decision" --body \
"Add authentication and user account handling.

**Open question:** this conflicts with the local-first / no-internet goal. Decide whether accounts are local-only, optional, or required only for the sharing feature, before implementing."

"${GH[@]}" --title "Evaluate integrations with other sensors and test strips" --label "research" --label "integration" --body \
"Research and scope integrations with other CGM sensors on the market, and with fingerstick meters and strips such as Accu-Chek.

**Deliverable:** a compatibility matrix and a recommendation on which to support first."

# --- Project & governance ----------------------------------------------------

"${GH[@]}" --title "RFC: security and data-handling plan for a serious open-source project" --label "discussion" --label "security" --body \
"Define what this app needs to be robust enough to release publicly:

- local-first data processing wherever possible
- encryption at rest
- minimal or no telemetry
- a written threat model for health data
- secure handling of any sync or sharing backend
- dependency hygiene and release signing

**Deliverable:** an agreed written plan before implementation starts."

"${GH[@]}" --title "RFC: choose a license" --label "discussion" --label "legal" --label "needs-decision" --body \
"Goal as stated: allow modification and redistribution, prohibit commercial exploitation.

**Caveat to resolve:** a non-commercial restriction means the project is *not* OSI-approved open source, and many contributors, packagers, and F-Droid-style repos will not accept it.

Options to weigh:
- PolyForm Noncommercial — matches the stated intent, not OSI open source
- a source-available license — similar tradeoff
- AGPL-3.0 — true open source, permits commercial use, but forces anyone distributing a modified version to publish their source, which blocks most closed-source exploitation

Pick based on which outcome actually matters more."

"${GH[@]}" --title "Naming and branding" --label "discussion" --body \
"Requirements:
- the short word \"Jaz\" built into the name
- a reference to glucose or CGM, in the same spirit as \"Gluroo\"
- a rabbit as logo and app thumbnail

**Deliverable:** a shortlist of names, a trademark and Play Store name-availability check, and a logo direction."

"${GH[@]}" --title "Maintain a changelog" --label "chore" --body \
"Adopt a CHANGELOG.md following Keep a Changelog plus semantic versioning, updated on every release."

"${GH[@]}" --title "Research Play Store publication requirements" --label "research" --label "legal" --body \
"Investigate Google Play policies for health and medical apps:

- health-data declarations and the Data Safety form
- restrictions on medical-device-adjacent claims
- what wording in the store description stays compliant given the medical disclaimer
- whether regulatory classification as a medical device applies in the target markets"

echo "==> Done."
