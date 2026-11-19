# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Every build gets a numbered heading here, released or not.** This file is what the What's New
screen shows, so a heading that said only "Unreleased" left no way to tell which build a phone
was actually running — which matters most for the builds that are sideloaded one at a time onto
the developer's own phone. A version that has been tagged carries its date; one that has only
been built carries `unreleased` in place of a date, and the number is still there.

## [3.5.0] - unreleased

### Added

- **"How these are calculated" now draws the two estimates instead of only naming them.**
  GMI and the A1C estimate, plotted against mean glucose, with the window's own mean
  marked on both lines.

  The box already said the two figures "legitimately disagree", which is true and is not
  something a sentence can demonstrate. Seeing 7.2 and 7.6 on the card, the question is
  whether one of them is wrong — and the answer is that both are straight lines in mean
  glucose with different slopes, meeting at exactly one point and diverging in a fixed
  direction either side of it. They agree at a mean of about 154 mg/dL, which both call
  7.0%. Below that the ADAG estimate reads lower than GMI; above it, higher, by more the
  further out you go. That is a picture, not a paragraph.

  The lines are sampled through the formulas rather than drawn as segments between their
  endpoints, because straightness is the thing being shown: if either published
  relationship were ever replaced by a curve this would bend with it rather than quietly
  flattening it. A test asserts that equal steps in glucose give equal steps in both
  figures, so the claim the picture makes is checked rather than assumed.

  One y axis, since both series are in the same unit — two scales would make their slopes
  a drawing decision rather than a fact. Each line is labelled at its own end in ordinary
  ink rather than in its own colour, so identity never rests on colour; the two tones are
  one hue separated by lightness, which survives colour deficiency and greyscale. The hue
  is the forecast blue-violet, which in this app already means *not a measurement* — which
  is exactly what both of these are, and the misreading the box exists to prevent.

  The room reserved for those end labels is measured rather than guessed. A reserve picked
  by eye already clipped "Est. A1C" in English, and the labels are translated.

### Changed

- **The GMI and A1C formulas are callable for any mean glucose**, not only for a window's.
  The explanation chart draws them over a range, and a second copy of a published constant
  in UI code would be the copy nobody checks. The card's figures now go through the same
  two functions, and the point where they agree is solved from the coefficients rather
  than written down as 154.

- **The field-testing checklist installs from the release APKs.** It still named the debug
  paths, which are the same file name on every build — which is how two field sessions end
  up unable to say what they tested. It also carries the watch's Bluetooth-relay route for
  when Wi-Fi debugging cannot reach it, which is the normal case on a network that isolates
  its clients, and the warning never to run `uninstall` against the phone.

## [3.4.0] - 2026-11-16

### Added

- **A finger prick now moves the number on Home.** For the few minutes a strip and a
  sensor reading describe the same moment, the figure is the two of them together —
  the strip at 60% and the sensor at 40% — and a line underneath says so, with both
  measurements printed beside it.

  Never silently. The whole reason the owner wanted a meter in this app is to know when
  the two instruments disagree, and a number that quietly moved would hide exactly that.
  So the caption carries the strip, the sensor and the split, and the split is read from
  the code rather than written into the sentence, so the day 60/40 is replaced by
  something measured the caption says the new one.

  **The blend is never what an alarm judges.** Take a strip of 55 against a sensor of 75:
  the blend is 63 — *higher than the reference measurement*, in the one direction where
  being wrong is dangerous, and higher precisely because the less trusted instrument
  pulled it there. A weighting that softens a hypo is not a weighting, it is a bug with a
  rationale. So the alarm engine is handed the worse of the two real measurements and
  never the average. The symmetric case holds too: a comfortable strip cannot silence a
  sensor reading 50.

  An alarm raised by a strip shows the strip's number, in the notification and in the car
  alert both. Announcing an urgent low and then printing the sensor's comfortable 75
  would be unreadable.

  **A pair is two measurements of one state**, which means one sensor cadence — five
  minutes, the same window the accuracy card on Trends uses, and deliberately not the
  fifteen the diary uses to attach a glucose to a dose. Glucose can move 60 mg/dL in
  fifteen minutes; two values that far apart are not two readings of one moment.

  **The blend only happens on a flat trace.** A strip reads capillary blood and a sensor
  reads interstitial fluid, which lags blood by five to fifteen minutes and lags further
  the faster glucose moves. Flat, the two agree about what "now" means. Moving, they are
  honestly describing different moments, and averaging them produces a number that is
  wrong in a direction that was predictable — so above one mg/dL per minute, the flat-arrow
  convention every CGM display already draws, the strip stands alone and the caption says
  why. No usable delta is treated as moving, not as flat: unknown is not evidence.

  **Nothing is overwritten and nothing is stored.** The reading table still holds what the
  sensor said and the blood test table what the strip said; the fused figure is derived at
  read time by a pure function in `:core`. That is the doctrine the diary already follows —
  a corrected reading would be a falsified record — and it has a practical edge: when the
  weighting stops being a guess, every past moment re-derives.

  **Statistics stay sensor-only.** Time in range, GMI, the daily pattern and the coverage
  figure all claim to describe a continuous sensor record, and fused points would make them
  describe something else while keeping their names. The blend earns its way into the
  aggregates when the error grid says it is better, not before. The chart is unchanged too:
  strips stay diamonds beside the trace rather than being folded into it.

  This is fusion, not calibration. The scope is one instant — the matched pair. What is
  deliberately not built is a correction carried forward, which needs the bias measured
  rather than asserted, and measuring it is what "the sensor against the strip" on Trends
  has been collecting pairs for since 3.1.7.


- **"The sensor against the strip" now splits by what glucose was doing.** Three rows —
  falling, flat, rising — each with its own count, median difference and mean difference,
  under the pooled figures that were there before.

  The pooled figures cannot answer the question anyone actually has about a sensor that
  reads high. A sensor that leans high on a flat trace is reading high; a sensor that
  agrees when flat and reads high only while glucose falls is *accurate and behind*,
  because interstitial fluid lags blood. Those two produce the same pooled median, call
  for opposite responses, and were previously indistinguishable on this card — which was
  recorded as a known gap in the code since the card shipped, and is what this closes.

  The slope is recovered from the sensor's own series rather than taken from a trend
  arrow. The arrow exists only on the live reading and is the vendor's opinion; a slope
  computed from the surrounding readings can be recovered for every pair already stored,
  so the breakdown works retroactively over the whole history rather than starting from
  today. It is the same arithmetic the live display uses for its delta, and the bucket
  boundary is the same one the blend is gated on, so "this pair is in the flat row" and
  "the app would have blended this pair" are one statement and not two that drift apart.

  The median is signed and the note above the rows says what the sign means, which pooled
  bias says in a sentence instead. Three rows of prose would be unreadable; a bare "+18"
  with no statement of which instrument is which is the kind of number this card exists
  to avoid printing. The note also states the flat boundary and warns that three groups
  is a thin split of an already small sample.

  Pairs with no reading far enough before them to measure a slope are counted and named
  rather than dropped into "flat". Missing data is not evidence of a flat trace, and a
  breakdown whose parts do not add up to the whole is one the reader is entitled to be
  told about. The breakdown hides itself entirely below two groups: one row would restate
  the headline under a second heading and imply a contrast the data has not got.

  Nothing about the pooled figures changed. The breakdown shows what they are made of; it
  does not correct them, and the correction — shifting the sensor series by a measured lag
  before comparing — needs this measurement first.

### Changed

- **The comparison loads more sensor history per strip**, 25 minutes before each rather
  than 5, because a slope is measured against what came before. Still a query per strip
  rather than the whole window: ninety days of readings is six figures of rows.

- **The watch carries the phone's version number**, because it carried nothing useful
  before. `:wear` set its own, and it was then left at 3.0.0 across every change the watch
  received — the complication itself among them, and the fix for a watch that froze on a
  stale payload and never recovered. A wrist showing "3.0.0" therefore meant nothing at
  all, which is precisely the failure the numbered headings in this file exist to prevent
  on the phone.

  The number now lives in the root build and both modules read it, and the `versionCode`
  is derived from it rather than written beside it: `major * 1000 + minor * 100 + patch`,
  which is what every version in this repository's history already used, so nothing
  downgrades across the change. Two numbers that must agree are two numbers that
  eventually will not, and a `versionCode` left behind is an APK Android silently refuses
  to install over the one already there.

### Fixed

- **The watch had a release build that could never be installed.** `:wear` set no signing
  config, so its release variant came out unsigned — and an unsigned APK cannot be
  installed on anything. It is signed with the debug key now, for the reason the phone is:
  these are two APKs of one application id, sideloaded one at a time, and a build signed
  with a different key could not be installed over the one already there.

  The watch APK is also copied to `build/apks` under a name carrying the version, as the
  phone's has been since 3.1.6. The name shouts about not installing it on the phone
  because the two share an application id — the watch build will install cleanly over the
  phone app and replace it, and the file name is the only warning present at the moment
  that mistake would be made.

## [3.2.1] - unreleased

### Added

- **New results arrive on their own.** Choosing a meter in Settings is now the whole
  setup: from then on the app takes each new result off it without being asked, and the
  value is in the diary and on the chart before the phone is picked up.

  The meter is the one source here that cannot be polled — it is asleep almost always and
  wakes for a few seconds after a strip, and nothing tells the phone that it has. What
  makes this work without scanning, and so without the location permission a scan would
  need, is that Android's Bluetooth controller will hold a *pending* connection to a
  bonded device and complete it the moment that device next advertises. The wait costs
  nothing while it waits.

  It lets go of the connection afterwards rather than holding it open. The meter is also
  talking to whatever else the owner has paired with it, and a meter generally entertains
  one connection at a time — holding ours open would make this app the reason the other
  one stopped working.

  Choosing a meter is the opt-in, and there is no second switch for automatic reading: a
  chosen meter that was never read would be a setting that does nothing.

### Changed

- **Reading the meter moved to the Diary, beside typing a result in.** It was in Settings,
  which is the wrong place to go looking for a measurement — the person who wants it is
  standing there with a meter in one hand, on the screen where the alternative is typing
  the number by hand. Settings keeps what belongs to it: pairing instructions, which meter
  to use, a connection test, and what the last automatic read did.

- **A manual read can ask for something other than "what is new".** Five scopes: new, the
  last one, seven days, thirty days, everything. The automatic read asks only for records
  above the highest already stored, which is right until something is missing — and then
  the question becomes whether the meter has it at all, which that request cannot answer.

  A period asks the meter for everything and keeps what falls inside it. The standard does
  have a time-range filter and it is optional, so meters commonly refuse it; the fallback
  would be exactly this, and one path that always works beats two where the second is only
  reached after the first has failed. Records that fall outside the period are counted and
  reported rather than silently dropped.

  The period is measured on the corrected time rather than the meter's own, so a meter
  whose clock is two hours out does not lose two hours of the window that was asked for.

## [3.2.0] - unreleased

### Added

- **The app reads a finger-prick meter over Bluetooth.** Phase 1 of
  `docs/12-finger-prick-meter.md`: Settings → Medidor de tiras lists the meters Android
  has already bonded, and reading one pulls every result it holds that is not already
  stored. No account, no network, nothing leaving the phone — the first new radio this app
  drives itself, after three versions in which the only Bluetooth was the watch's.

  **Through the Bluetooth SIG's Glucose Service, not through anything Roche-specific.**
  That is what makes the work transferable: the same code reads an Accu-Chek Guide, an
  Instant, an Aviva or Performa Connect, a Contour Next One, a CareSens. A meter that does
  not speak it announces itself by not having the service, and that case is handled by
  printing what it *does* expose — the GATT dump the plan called for, taken on the owner's
  own meter, which is the difference between "this meter cannot work" and "this meter
  needs a quirk writing".

  **No scanning, and therefore no location permission.** Pairing happens in Android's own
  Bluetooth settings — the meter into pairing mode, the six-digit PIN from the back of it
  — and the app connects to an already-bonded device by address. Scanning is the half that
  needs `ACCESS_FINE_LOCATION` on this `minSdk`, and a glucose monitor asking for location
  in order to read a meter is a permission nobody should grant. The bill is
  `BLUETOOTH_CONNECT` on Android 12 and up, asked for once, and the legacy pair capped at
  API 30.

  **The protocol lives in `:core`, with twenty tests and no device.** Flags, a 12-bit
  signed mantissa with a 4-bit signed exponent, kg/L and mol/L, the sequence number, base
  time plus a signed offset, the sample-type nibble. Every vector is written out by hand
  from the specification, because a record misread by a factor of ten is not a visible
  failure: it is a plausible-looking glucose, stored as the reference that every other
  figure in the app is now judged against. The five reserved float encodings — NaN, NRes,
  the infinities — come back as "no reading" rather than as 2047.

  **One operation in flight at a time, each with a deadline.** Android's GATT stack
  silently drops a second write issued before the first completes, which is the single
  most common way this kind of code fails: it works in testing and then fails on a busier
  phone. Every step suspends on its own callback rather than blocking the binder thread it
  arrives on, and a meter that stops answering halfway produces a reported failure instead
  of a button that never comes back.

  **It asks for what it does not have.** The first read takes the meter's whole history;
  every read after it asks for records above the highest already stored, so a meter
  unsynced for a fortnight fills in at once and one synced ten minutes ago answers
  "nothing newer" and costs a second. The highest is derived from the stored rows rather
  than remembered in a setting — delete a row by hand and the next read fetches it again,
  instead of skipping it forever. Which record number to ask from can only be decided once
  the meter has named itself, since sequence 41 means nothing without knowing whose 41 it
  is, so that decision happens mid-conversation.

  **Control solution tests are dropped and counted.** They are deliberately off-scale, so
  one stored among real results would be the worst point in every comparison the sensor is
  scored by. Counted rather than silently discarded, because a read that found four
  records and stored none should say which four.

  **The meter's clock is measured, corrected and reported.** A meter keeps its own wall
  clock; the difference against the phone's is measured once per connection and applied to
  every record, and the screen says which way and by how much. A meter two hours out would
  otherwise file every result two hours early with nothing on screen to explain it. Drift
  and daylight saving between reads are not corrected — the measurement is of one moment,
  and nothing here can know about the rest.

  **Nothing has talked to a real meter yet.** The protocol is tested to the byte and the
  session logic is not: no device has been on the other end of it. The screen is built to
  be the test — it reports what was found, what was stored, what was already there and
  what the clock said, rather than reporting success.

## [3.1.9] - unreleased

### Fixed

- **A finger prick did not reach the comparison card until the next sensor reading.** The
  card on Trends recomputed when the window changed or a reading landed, and a reading
  lands every few minutes — so a strip typed in just now sat invisible for up to five of
  them. Which looks exactly like the entry not having been saved, and was reported as
  precisely that. It now recomputes when the tests change as well, because both halves of
  a comparison move it.

### Changed

- **"How these are calculated" now says what is not in the calculation.** The tooltip said
  GMI and estimated A1C come from sensor readings. That was true before this app held any
  other kind of glucose; now that it holds finger pricks, "from sensor readings" stops
  being a complete answer and starts being one the reader has to infer the rest of.

  They are not in either figure, and the reason is worth the sentence it takes: a handful
  of spot values a day is not the continuous record those formulas were derived for.
  Adding them would change what the number describes without changing its name — which is
  the failure mode this whole feature was built to avoid.

## [3.1.8] - unreleased

### Fixed

- **Storing a meter's record twice would have reported success and stored nothing.** The
  `blood_tests` table has a unique index over the meter's name and its own record number,
  so a second copy of one record cannot be written. What was not right was what happened
  when something tried: Room's upsert resolves that conflict by updating the row whose
  primary key matches, and a result arriving from a meter has no id yet — so the update
  matched nothing, the write quietly did nothing, and the caller was told it had been
  stored.

  Nothing could hit this yet, because nothing reads a meter. It is fixed now rather than
  in phase 1 because the collector will be built on top of this answer: a meter hands out
  its whole numbered history on every connection, so a record already held arrives again
  as a matter of routine, and a collector that cannot tell "already have it" from "stored
  it" cannot decide what to ask for next.

  `saveBloodTest` now reports one of three outcomes rather than a boolean. The row that is
  already there is kept rather than overwritten: both copies carry the same reading, and
  the stored one may have had a note added to it by hand since. The constraint is caught as
  well as checked, since the check and the write are two statements.

### Changed

- **`GlucoseUnit.MMOL_PER_MGDL` is now `MGDL_PER_MMOLL`.** The value is 18.0182 — the
  milligrams per decilitre in one millimole per litre — and the old name claimed to be its
  reciprocal. Every conversion using it was correct, which is what made it worth renaming:
  the arithmetic read as though it were multiplying by the wrong quantity, and the next
  person to write a conversion would have followed the name rather than the code.

## [3.1.7] - unreleased

### Added

- **Finger-prick results are recorded, and the sensor is scored against them.** This app
  has had one measurement and no way to check it: everything on every screen came from one
  sensor read through one cloud service, and when that number was wrong nothing here could
  notice. The disclaimer already sent the owner to a fingerstick for any decision that
  mattered, and the app never heard the answer. Now it does, and the answer is the only
  reference measurement it has ever held.

  **A strip is its own table, not a row in the reading series.** Three capillary values a
  day filed among the sensor's five-minute estimates would not give the record a reference,
  it would quietly wreck it — time in range, GMI, the daily pattern and the coverage figure
  all mean "of the sensor series", and none of them would have meant that afterwards. So
  `blood_tests` sits beside the diary's doses and meals (Room 11), and the comparison
  between the two instruments is computed rather than baked in.

  **Entered on the Diary, as a third kind of entry.** In whichever unit the screen is
  showing and stored in mg/dL like every other glucose here, with both decimal separators
  accepted — "5,8" is five point eight millimoles and must never read as fifty-eight.
  Nothing is rounded: 5.8 mmol/L is 104.5 mg/dL and stays there, because rounding it to 104
  for tidiness would be this app editing someone else's measurement.

  **A diamond on the chart.** Every other mark there is round — the trace's dots, the
  current value, the inspected point — and shape was the only channel left that did not
  already mean something: colour is the zone, dashes are the forecast, size is emphasis.
  Filled in its own zone colour because it is a measurement, outlined in the trace's colour
  so it stays legible sitting exactly on the line, which is where an accurate sensor puts
  it. It is never joined to the trace and never part of it.

  **"The sensor against the strip" on Trends** reports how many comparisons there are, the
  mean difference between the two instruments (MARD, the figure a CGM's accuracy is actually
  quoted in), and how many comparisons fall in the two Clarke zones that would not have led
  anyone astray. Underneath, the zones in words rather than letters — "D" means nothing to
  the person holding the phone and "would miss what needed detecting" means everything —
  with the hazardous three in the error colour, which in this app has always meant "what you
  are looking at may not be true". Here it means that about the sensor.

  Which way the sensor leans is a sentence rather than a tile, because a signed median in a
  tile is a minus sign with no statement of what it is a minus of. A lean smaller than the
  display's own resolution is reported as no lean at all, rather than as "0 above the
  strip", which is a sentence that says a thing and unsays it.

  **A comparison needs the two measurements within five minutes of each other**, nearest in
  either direction, and a test with nothing that close is dropped rather than paired with
  whatever was nearest. Five rather than the fifteen minutes the diary uses to attach a
  glucose to a dose: that window asks what the context was when something was injected,
  where a value from twelve minutes earlier is still context. This one asks whether two
  instruments measured the same blood, and glucose can move sixty mg/dL in fifteen minutes.

  Below ten comparisons the card says so in the error colour. Ten is not a defensible
  sample for an accuracy claim — the literature uses hundreds — and it is not meant to be;
  it is the point at which a figure stops being one bad strip and starts being a pattern,
  which is the only claim the card ever makes. The caveat that matters more is on the card
  permanently: a sensor reads interstitial fluid, which lags blood and lags further the
  faster glucose moves, so every figure here slightly overstates how wrong the sensor is.

  Blood tests are deliberately never pruned, where readings are dropped after a year. There
  are a handful a day, they are the only reference measurements in the database, and the
  comparison they feed is worth more the longer it runs. The table also carries a unique
  index over the meter's name and its own record number, so that when something does read a
  meter, re-reading it cannot store the same result twice.

  This is phase 0 of `docs/12-finger-prick-meter.md`, and nothing in it talks to a meter
  yet: results are typed in by hand, which is deliberate — the radio is an input method
  rather than the feature, and building the model first means a failed Bluetooth spike costs
  a weekend instead of a feature. Reading an Accu-Chek Guide over the standard Bluetooth
  Glucose Service is phase 1; weighting a strip against a sensor for the same instant is
  phase 2. The diary's exports do not yet include finger pricks.

## [3.1.6] - unreleased

### Added

- **Insulin per day on Trends, as stacked bars.** The diary could say what today came to and
  list the last fortnight as figures. Neither answers whether a fortnight is drifting upward,
  whether weekends dose differently, or whether a change made three weeks ago moved anything at
  all. Those are questions about a series, and a series has to be drawn.

  Basal sits at the bottom of each bar with bolus stacked on it, so a bar's height is the day's
  total and the join in it is the split. Two bars per day side by side would make each kind easy
  to follow on its own and make the total — the figure a day is actually reasoned about in —
  something to add up by eye. Stacking keeps both: the whole is the height, the parts are the
  segments.

  The axis starts at zero and always will. A trace is read as a shape, so its axis can start
  wherever the readings happen to be; a bar is read as a height, and an axis starting at thirty
  units draws a 34-unit day at three times the height of a 32-unit one. On a quantity someone
  injects, exaggerating a two-unit difference into a tripling is not a cosmetic choice.

  A day nobody logged is a gap rather than a bar of no height. That is the diary's existing rule
  and it matters more here: in a row of bars, a zero is a loud claim that no insulin was taken,
  where a gap says only that nothing was written down. The caption says how many of the window's
  days carry anything at all, because four logged days out of thirty should not be read as a
  month of dosing.

  Today is drawn faintly and left out of the averages until it is over. At nine in the morning
  today's bar holds the morning basal and nothing else; averaged as though it were a finished day
  it reports a fall in dosing that is only the time of day. It comes back into the average when
  it is the only logged day there is — on the first day of use the alternative is a chart with a
  bar on it and a dash where the average should be, which reads as a bug rather than as a caveat.

  Sliding across the chart reports one day's total, its split, and how many injections it came
  from: eight units in one dose and eight in four are the same bar and not the same day. A tap
  opens it full screen, where ninety bars have the room to be touched individually, and turning
  the phone sideways gives them considerably more.

  The two kinds are one violet hue in two tones rather than two colours, because they are two
  parts of one quantity. Every other meaning in this app is already spoken for — red is "this may
  not be true", amber and green are zones, the forecast's blue-violet is "not a measurement", the
  teal is the interface — and separating the tones by lightness rather than by hue means the
  stack survives colour-blind vision and greyscale without a second palette.

  The card sits outside the "nothing recorded in this window" branch of the screen, because a
  dose is not a reading. The diary keeps working through a sensor change, a warm-up, or a week
  the phone was not polling, and a window with no readings in it can still have every injection
  of that week written down.

### Changed

- **The chart callout and the axis gutter are written once now.** The panel of figures that
  appears when a chart is slid across, and the width of the strip the value labels sit in, were a
  copy per chart file. Both are promises to the reader — that sliding any chart here gives
  figures the same way, and that two charts stacked on one screen have axes that line up — and a
  promise kept in three copies is one that eventually is not. The formatter behind "6 U" rather
  than "6.0 U" moved the same way, from a copy on each screen that prints an amount to one for
  all of them.

## [3.1.5] - 2026-11-02

### Added

- **The sensor figure warns for its last three days, and says so out loud on the final one.** The
  day count on Home has always carried a deadline and never looked like it: day 12 of 14 was drawn
  exactly like day 3, in the same colour, in a cell shared with two statistics that change every
  few minutes. It now turns red for the last three days.

  Three days rather than a few hours, because what a wearer can actually do about a sensor running
  out is have the next one to hand, and that decision has a day or two of lead time in it. A
  warning that first appears on the final evening arrives after the point where anything can be
  done with it.

  On the last day the app also says it once, in a dialog, when it is opened — over whichever
  screen it opens to, since this is about opening the app rather than about looking at the chart.
  It states how long is left and that readings stop when the sensor does; it does not tell anyone
  to fit a new one, and the official app is still where a session actually begins. Once per run,
  not persisted and not remembered per sensor: a replacement being needed today stays true until
  the sensor is replaced, which makes it worth repeating the next time the app opens and not worth
  repeating twice in a sitting.

  The colour stops at expiry instead of continuing past it. By then the cell reads "expired", and
  a word is stronger than a colour.

  Both the colour and the notice are computed against the lifetime the wearer picked, like every
  other sensor figure here — Abbott ships 14-day and 15-day sensors through the same account, and
  a shared constant would have warned a day early on half of them.

  The red is the theme's error colour rather than a zone colour. A sensor running out says nothing
  about glucose, and borrowing the low-reading red would make it look like it did.

## [3.1.4] - unreleased

### Added

- **A dose now records the glucose you were at when you gave it.** The diary knew that four units
  were taken at 13:05 and nothing about why they were four. The glucose at the moment of the
  injection is the reason for the size of the dose, and without it the insulin history is a column
  of numbers with their justification removed.

  The form says what it is about to store, before saving and while the entry can still be moved to
  another time — the person typing is the only one who can tell that the figure is wrong. The value
  comes from the reading nearest the time the dose was *given*, so a forgotten injection entered an
  hour later takes the glucose from an hour ago rather than from now.

  Nothing within a quarter of an hour means no glucose is stored, rather than the closest reading
  however far away. A dose can be entered while a sensor is warming up, inside a gap in the data,
  or backdated to a day the phone was not polling — and a bolus filed with a value from six hours
  earlier reads as a fact while being a fiction. Doses recorded before this build keep no glucose
  for the same reason: backfilling them would write a number the user was never shown.

  It is copied onto the dose rather than looked up when the diary is read. Readings are pruned
  after a year and a sensor's history cannot be re-fetched, so the pair only survives as a copy.

  The diary's three exports carry it: a `glucose_mgdl` column in the CSV, `glucoseMgdl` in the
  JSON, and a column beside the amount in the PDF. Always in mg/dL, whatever the phone is
  displaying, because a file is read by other software months later and a column whose unit
  depended on a setting at the time of export could not be trusted.

## [3.1.3] - unreleased

### Added

- **The diary export takes a date range, and comes in CSV, JSON or PDF.** The diary's natural unit
  is a visit — "the last fortnight", "since the dose changed" — and an export fixed to everything
  stored is the wrong document for every one of those. Two date buttons, fourteen days by default,
  and the end day is included in full. Dragging the start past the end reorders the pair rather
  than refusing it: that is an ordinary slip and an error message for it is friction the screen can
  absorb.

  All three formats carry the same things, as the report's exports do: the span, the zone it was
  grouped in, day totals with basal and bolus apart, how many meals went uncounted, and the
  disclaimer. The PDF goes through the system print pipeline by way of a visible page, the same
  route the report uses — it paginates and embeds fonts properly, and it leaves one document to
  keep correct instead of two that have to agree.

  Days come out oldest first here, unlike the screen's own list: a diary read as a document is read
  forwards, while newest-first exists so the phone shows today without scrolling.

### Changed

- **The print viewer is now shared rather than owned by the report.** It took a `Report` and
  rendered that report's HTML, so the diary could not reach the print pipeline at all without a
  second copy of the WebView, the print-framework lookup and its failure reporting. It now takes a
  file.

- **Removed the combined raw export.** It wrote readings, insulin and meals into one table with a
  `section` column, which meant one header trying to fit two shapes — and the narrow and wide row
  builders being mixed under it had already shifted a column once. Each screen now has the document
  for its own subject, and nothing is reachable by two code paths that could drift.

## [3.1.2] - unreleased

### Added

- **Readings now record when they arrived, not only when they were measured.** The logbook export
  carries both, plus the lag in minutes. This exists because the readings table could not
  distinguish the two explanations for a gap: a poller that was asleep, and a poller that was
  awake and being handed nothing new. Both write no rows — a poll that re-reads an unchanged
  current value stores nothing — so after the fact they looked identical. A lag of one minute means
  the value was polled live; a lag of forty means it was swept up long afterwards, which is what a
  gap being backfilled looks like. The column is excluded from the payload sent to the watch: it is
  a fact about this phone's fetching, not about the glucose.

### Fixed

- **Backfill was overwriting the trend arrows of readings already captured live.** Readings were
  inserted with `REPLACE`, for idempotence over the overlapping windows polling fetches
  constantly. But a measurement polled live carries a trend arrow, and the same measurement
  re-served inside the vendor's 15-minute history carries none — graph points have no arrow — so
  the later, poorer copy replaced the better one and a live reading was downgraded to `UNKNOWN` by
  its own backfill. Inserts now ignore a timestamp already present, which is just as idempotent: a
  reading is immutable, so there was never anything for a second write to update.

  This also corrected a diagnosis. `UNKNOWN` had looked like a reliable marker of a value that
  arrived late, and it is not — some of those rows were captured live and then clobbered. The
  arrival time above is the marker that actually holds.

## [3.1.1] - unreleased

### Changed

- **The logbook's export now lives on the Logbook, and insulin has its own on the Diary.** Both
  were briefly one combined file behind a button on the report screen, which put the logbook's
  export two screens away from the logbook. Each screen now exports its own subject: the Logbook
  writes every reading, the Diary writes insulin and meals. Separate files rather than one with a
  filter, because the narrower file is the one worth sending when the question is about a gap.

- **The logbook export calls out values that arrived late.** The trend column reads `UNKNOWN` for a
  reading that came from the vendor's 15-minute history rather than a live poll, because the graph
  points carry no arrow. It is the only thing in the file that distinguishes a value fetched after
  the fact from one polled at the time, and it turns out to be exactly four readings an hour.

### Fixed

- **The version heading in What's New showed a stray bracket.** Keep a Changelog brackets the
  version because in the source it is a link target, and the parser trimmed brackets from the
  *ends* of the line — which took the opening one and left the closing one stranded in the middle.
  Headings now read "3.1.1 · unreleased".

## [3.1.0] - 2026-10-22

The first release since the phone and the watch were put into daily use together, which is where
most of this came from: the freezes, the gaps and the wrong sensor arithmetic were all found by
wearing it rather than by reading it.

### Added

- **Meal photographs.** A picture can be attached to a meal, through the system photo picker,
  which needs no permission at all — it hands back the one image you chose and never gives the
  app the gallery. The image is downscaled to 1024px and stored as a file in the app's own
  storage, with only its name in the database: a BLOB column would put hundreds of megabytes of
  JPEG inside the same database that holds two years of readings, and every query and migration
  would carry them. Thumbnail on the diary row, full size on tap. **Photographs expire after 90
  days** against two years for the readings, because a reading from last year still feeds a
  pattern while a photo of last year's lunch is bytes nobody will open — and when one expires the
  meal itself is kept, with only the picture forgotten. Deleting a meal deletes its file, and a
  sweep on startup collects anything orphaned by a crash between the two writes. Nothing in the
  app reads these images, and nothing estimates carbohydrates from them.

- **A raw export, beside the report.** Every reading, dose and meal as stored, in one CSV in time
  order, timestamped in UTC with the epoch millis beside each stamp so a timezone argument cannot
  silently move a reading to another hour. This exists because the report cannot answer the
  question it most often raises: the report is assembled from the hourly rollups, so a gap in it
  is a statement about the summaries, and only the readings table knows whether the readings are
  there. The button is deliberately outside the report's own export row — the case it is for is
  the one where the report came back empty.

- **A per-day insulin summary in the diary.** The last fourteen days, most recent first, each with
  total units and basal and bolus broken out, and the day's carbohydrates underneath as context —
  beside, never added, because units and grams are not the same quantity. Days with nothing
  recorded are absent rather than shown as zero: "nothing logged" and "no insulin taken" are
  different claims and this is a diary.

- **A sensor detail view.** Tapping the sensor figure on the home screen opens the serial number,
  the activation time, the day of the session and the time left — hours and minutes inside the
  final day, whole days before it. Before the last day the hours would be false precision: the end
  is computed from a vendor timestamp and a nominal lifetime, so "3 days" is honest where
  "71 h 12 min" pretends to an accuracy the inputs do not carry. Abbott's own product code is
  shown when the account sends one, stored uninterpreted, so the 14-versus-15-day mapping can
  eventually be learned from sensors this phone has actually worn instead of guessed.

- **A glucose alarm can be announced on the car screen.** Android Auto has no surface an app can
  keep a number on, so a firing alarm is repeated as a message — the one shape the car does
  surface — and read aloud. Off by default, and that is not timidity: it speaks the reading to
  whoever is in the car, not only to the driver. **This has never been seen to fire on a real car
  screen.** It builds, installs and persists its setting; that is all that has been confirmed.


- **A Wear OS complication.** The reading on any watch face with a slot for it — short text,
  ranged value or long text — rather than a watch face of our own, so it works on the faces
  already installed and leaves their layout and battery behaviour alone. The platform never polls
  us: an update is requested when a reading lands, which is the only moment there is anything new
  to say. The **age is rendered by the watch face**, counting up from the reading's own timestamp,
  which is what makes it trustworthy — a string we formatted once would freeze and then understate
  the age for as long as the feed was down, exactly when the number is most dangerous. The gauge
  spans the in-range band rather than the sensor's full scale, because a 40-to-400 arc puts every
  ordinary reading within a few degrees of the same position. See docs/06-wear-os.md.

- **The time-in-range bands now say where their edges are.** Each row carries its boundary beside
  its name — "high" is a word, "180–240" is the thing being counted — and the stacked bar, which
  previously did nothing when tapped, opens the bands in full: boundary, percentage, and how much
  of an average recorded day was spent in each. The figures follow the account's own targets or
  your override, so the card can never name a band the chart was not measured against. Time is per
  *recorded* day, and the caption says so: the fractions describe the readings that exist, and
  multiplying them by 24 hours would describe a day nobody fully measured.

- **A guided tour.** Thirteen steps through every screen, each with a "Show me" that opens the
  screen it describes and comes straight back on Back. Offered once after setup, and in Settings
  for good. It is as much about what the app will not do as what it will — the last step is the
  caveats, by which point there is enough context for "nothing backfills" to mean something.
  Pages of prose rather than spotlights drawn over the real screens: an overlay has to know where
  things are and breaks whenever they move, and a screen reader makes nothing at all of a hole cut
  in a dimmed layer, which matters more here than the animation would.

- **A second data source: xDrip+.** Readings arrive over xDrip+'s local broadcast, which means
  every sensor xDrip+ can read — Dexcom, Medtronic, Eversense, the Chinese sensors it supports —
  without this app learning any of their protocols. No account, no credentials, no network,
  nothing leaving the phone; the relay is still the only feature that sends data anywhere. It has
  to be switched on inside xDrip+ (Settings → Inter-app settings → "Broadcast locally", with
  "Compatible Broadcast"), and the app says so, because an xDrip+ with it off is indistinguishable
  from one that is not running. The source is chosen in Settings, and on the setup screen too,
  since choosing it otherwise sits behind a sign-in that a user of it does not have. See
  docs/09-sources.md.

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

- **A meal no longer needs a carbohydrate count.** The field is optional, because the common case
  is honest ignorance: you ate, you know roughly when, and you did not weigh it. A required field
  forces a number that is a guess and then lets every total downstream treat that guess as
  measured. An uncounted meal contributes **nothing rather than zero** to a day's grams, and the
  summary says how many of the day's meals carried no figure, so a total can never be read as
  covering meals it does not. Nonsense typed into the field is still refused — an omission and a
  typo are not the same thing.

- **The sensor lifetime is asked rather than assumed.** Abbott ships 14-day and 15-day sensors
  through the same account and the API does not reliably say which is which, so the lifetime is a
  choice in the sensor view, applied immediately. The default stays 14: of the two ways to be
  wrong, showing a good sensor as finished is recoverable by looking at it, and showing a dead
  sensor's readings as current is not.


- **The report leaves out ranges with no readings in them.** Nothing backfills, so asking for a
  year by day on a three-month-old install produced a page that was mostly dashes, with the part
  worth reading buried in it. They are dropped once in the builder rather than in each of the four
  things that render a report, and dropped rather than merged into their neighbours — a week with
  two days recorded is not the same measurement as a week with seven, and closing the gap would
  present it as though it were. The count of omitted ranges travels into all three formats and onto
  the screen, because without it the rows that remain would read as the whole period. The summary
  row still spans the period entire, which is what its coverage figure is for.

- **A poll and a pushed reading now share one pipeline.** The success half of the poll became
  `accept(result)`, used by both: persistence, the rollup refresh, delta refinement, threshold
  overrides and the emit to the watch all have to happen identically, or the status bar and the
  chart would disagree depending on where a reading came from. A pushed source still runs the
  loop, because the tray icon, the alarms and the freshness clock all depend on it, but its poll
  asks nothing of anyone and reports how stale the last push is.
- **Setup no longer gates on a LibreLinkUp password.** It gates on having a source at all — xDrip+
  has nothing to authenticate, and waiting for a credential it will never have left the setup
  screen up forever.
- **The report's controls moved to sit with its exports**, at the foot of the Trends tab, and are
  no longer in Settings. Choosing a period and choosing a file format are one task — deciding what
  to send someone — and splitting them across two tabs meant changing a period, walking back, and
  checking whether what you exported was what you meant.
- **Asking for a PDF opens the report on screen, with Print on it**, instead of handing an unseen
  page to the printer. The page has to be laid out in a real window before it can be printed —
  an unattached WebView has no window, is never laid out, and is held by nothing the framework
  keeps, which are three separate ways for a print job to come to nothing. Showing the page
  removes all three, and also says whether a failure is the document or the printer. The HTML
  file is written on the way in, so the printed page and the exported file are the same bytes.
  A page that fails to load says so and leaves Print disabled, rather than printing blank.
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

- **A 15-day sensor read "day 15/14" and expired a day early.** A single 14-day constant was
  applied to every sensor, and `isExpired` was derived from it — so a Libre 2 Plus was greyed out
  as finished while it was still working. The lifetime is now per sensor, and past the designed
  end the strip says "expired" rather than printing a day number above the lifetime, which stated
  a contradiction and buried the one fact that mattered.

- **Re-scanning a sensor made it look like a new one.** LibreLinkUp moves the activation timestamp
  to the moment of a scan, and the day count was computed from that timestamp alone, so a sensor
  halfway through its life restarted at day one. The **serial number** is now the identity, kept in
  its own table so it survives a restart, and the earliest activation ever seen for a serial wins.
  A new sensor means a new serial and nothing else — and a first sighting is never reported as a
  change, so reinstalling mid-sensor no longer claims one was just fitted.

- **A high or low alarm froze the tray icon on the value it fired with.** The ongoing notification
  carried `setGroupSummary`, and the alarm joined the same group as its only child. A group with a
  summary and exactly one child is a suppressed group: the platform hid the summary — the only
  notification a new reading updates — and showed the lone alarm, whose icon is rendered once at
  fire time and never again. So the tray sat on the firing value for the life of the alert, and on
  signal loss showed "?" and appeared to stop working altogether. Both comments in the code
  asserted the opposite platform behaviour. Neither notification is grouped now. An alert costs a
  second status-bar slot, which is the right trade: an extra icon is noise, a stale number is a
  lie.

- **A restarted phone could freeze the watch and the relay indefinitely.** Payload sequence numbers
  restarted from zero after a process restart while the watch still held the old high-water mark,
  so every new reading was rejected as stale — permanently, not until the next poll.

- **The poller did not come back after the app was replaced.** Only a reboot restarted it, so every
  sideloaded install left the service dead until the app was opened by hand. It now also starts on
  `MY_PACKAGE_REPLACED`, which is confirmed on the device: back up and in the foreground about a
  second after the install killed it.

- **The status-bar bitmap was unbounded.** A pathological label could ask for an absurdly wide
  icon, so the width is now clamped. This is **not** claimed as the cause of any tray trouble — the
  clamped path has never been reached on a real device — and the complication now logs whether the
  platform asked for an update, which is the part that was unobservable.


- **The watchdog was killing the service it was meant to protect.** Its stall threshold was
  twelve minutes while the loop's longest legitimate sleep — the wait after a condition needing
  the user — is fifteen, so a healthy loop was declared stalled and "recovered" from every
  quarter of an hour. Recovery was stop-then-start, and the start came from a background
  process, where Android 12 and later refuse it: the net effect was a deliberately killed
  service that could not come back. Hence the poller hanging *more* often than before the
  watchdog existed, not less.
  
  Three changes. The loop now publishes the instant it intends to poll next, and a stall is
  measured against that deadline plus five minutes' grace — a deadline cannot drift out of step
  with the scheduler that sets it, which a hand-picked constant did. A wedged loop is rebuilt in
  place by delivering an action to the service, which is already in the foreground and so subject
  to none of the background-start restrictions; the service is never stopped to fix it. And the
  watchdog decides synchronously inside `onReceive`, from a one-key preference file, because the
  permission to start a foreground service from an alarm lasts about as long as the broadcast
  does and the first version spent it on a settings read.
- **The poll loop ran on the main thread.** `lifecycleScope` is confined to it, so readings
  shared a thread with every frame the UI drew. Nothing in the loop needs to be there.
- **GMI, estimated A1C, time in range and the daily pattern only recomputed when the window
  changed.** They were calculated once when the screen was first built, so the only way to see
  current figures was to switch the period and switch back. They are derived from the window, the
  thresholds and the newest reading's timestamp now — the timestamp rather than the state itself,
  because the state is replaced on every poll even when the poll returned a reading already held,
  and an aggregate over ninety days of readings should run once per new reading rather than once a
  minute. They also stop computing while the Trends tab is off screen.

- **The poller could stop and leave a working-looking app behind.** One unhandled exception
  anywhere in the loop — a notification the system refused, a failed preference read, an OEM
  throwing from Do Not Disturb — ended polling for good, while the service stayed in the
  foreground with a frozen number in the status bar. That is the worst shape a failure can take,
  because nothing about it looks wrong. The loop is now supervised and restarted, each iteration's
  notification and alarm work is guarded separately so neither can take the fetch down, and a
  condition that needs the user (a rejected password) retries every quarter of an hour instead of
  stopping until someone noticed and pressed Start.
- **Nothing used to bring the poller back once its process was gone.** `START_STICKY` is a
  request, and several manufacturers' battery managers ignore it. A quarter-hourly exact alarm now
  checks: it starts the service if it is not running, and stops and restarts it if it is running
  but has not polled in twelve minutes — a plain restart would miss that case, since starting an
  already-running service does nothing to the loop that is wedged inside it. Exact because that is
  one of the few things permitting a background foreground-service start, and the app already holds
  the grant for reminders. Swiping the app out of recents re-arms it rather than ending it.
- **Settings shows the time of the last check**, which is the only way to see this class of failure
  without attaching a cable: "running" is what the service says about itself, a timestamp from
  forty minutes ago is evidence.
- **A reboot left xDrip+ users with no poller.** The boot receiver asked only whether LibreLinkUp
  credentials existed, and a pushed source has none.

- **Printing could never work while a language override was set**, which is the whole reason the
  PDF button failed. The Activity applied the chosen language by replacing its base context with
  `createConfigurationContext`, and a configuration context is a new context whose *outer context
  is itself* — so every system service fetched from the Activity stopped believing it came from
  one, and `PrintManager.print` refuses outright in that case ("Can print only from an activity").
  The configuration is applied with `applyOverrideConfiguration` now, which keeps the real
  Activity context underneath while resources still resolve in the chosen language. Any other
  system service that cares who asked was equally affected; printing is simply where it showed.
- **A print failure now says what went wrong**, in a dialog carrying the actual reason rather than
  a message that only restated the symptom. There is also an **Open in browser** action beside
  Print, which reaches a PDF through the browser's own print and touches none of this.

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
