# 12 — The finger-prick meter

## Why this document exists

This app has exactly one measurement and no way to check it. Every number on every
screen comes from one sensor, read through one cloud follower service, and when that
number is wrong there is nothing in the app that can notice. The disclaimer already
tells the owner what to do about it — for any decision that matters, the official app
and a fingerstick — and then the app hears nothing back. The one measurement in the
owner's life that is a *reference* rather than an estimate is the one measurement the
app does not hold.

A Bluetooth meter closes that loop. It is not a second opinion about the current
value; it is the standard the current value can finally be scored against.

**This is not issue #2.** That issue is reading *the sensor* directly over BLE, which
means competing with the vendor's own app for the sensor's single connection and
owning a reverse-engineered protocol. This is a different radio conversation with a
different device, over a published standard, and the two neither block nor unblock
each other.

## The decision

Read the meter through the **Bluetooth SIG Glucose Service (`0x1808`)** — the standard
profile — and not through anything Roche-specific.

The target is the **Accu-Chek Guide**, because that is the meter the owner has. But
the reason to write it against the SIG profile rather than against that meter is that
the profile is what makes the work transferable: the same collector reads the
Accu-Chek Instant, the Aviva and Performa Connect, the Contour Next One, the CareSens
and the TaiDoc meters, with per-manufacturer quirks measured in single-digit lines.
Two independent open-source Android implementations prove this — xDrip+'s
`BluetoothGlucoseMeter`, whose own comment says it "should support any bluetooth
standard compliant meter offering the Glucose Service", and Juggluco's
`GlucoseMeterGatt` — and both are GPL and readable.

No account, no credentials, no network, nothing leaving the phone. It is the first
feature since the xDrip+ receiver that fits the app's stance without an argument.

### What is actually confirmed about the Guide, and what is not

| Claim | Status |
|---|---|
| The Guide is a Bluetooth Low Energy device | **Confirmed** — Roche's own 2016 launch release |
| It implements Glucose Profile 1.0 / Glucose Service 1.0 (Continua, model 925) | **Secondary sources only.** The PCHAlliance listing now redirects to himss.org and has no archive snapshot; the claim survives as a search snippet and a reseller page |
| Some Roche meters speak the standard profile | **Confirmed** — xDrip+ carries a quirk branch written specifically for the Roche Aviva Connect, which means a Roche meter was on the other end of that code |
| Pairing: six-digit PIN, one phone at a time | Consistent across Roche models and Glooko's own instructions; the Guide shows its code under **Settings → Wireless** |
| Memory depth, broadcast window, whether the phone sets the meter's clock | **Unverified.** Roche's manual is not fetchable and these are not published |
| A published protocol or SDK | None. Roche names only mySugr |

The honest position is that the certification is likely and unproven, and that
arguing about it on the internet is the slow way to find out. **A GATT dump of the
owner's own meter settles it in ten minutes**, which is why that is step one of the
build order below and not step four. If the Guide turns out not to expose `0x1808`,
the fallback is the Contour Next One — xDrip+'s reference device, unambiguously
compliant, and about the price of a month of strips.

Two facts from the survey are worth carrying regardless:

- **The meter is sold in mg/dL and mmol/L variants** and the unit is a property of the
  device. This is a trap with a known shape: the Bluetooth record carries its own unit
  flag, and the only correct reading of a measurement is the one the flag dictates.
  Nothing in this app may infer a meter's unit from the user's display preference.
- **The meter pairs with one phone at a time**, and a new pairing overwrites the old
  one. On that phone the bond belongs to Android rather than to any app, so this app
  and mySugr can both hold it — but the meter is not shared between two phones, and
  whichever app is connected when the meter wakes takes the reading first.

## The protocol

Small, published, and fully specified. What follows was read out of xDrip+ and
Juggluco, which agree with each other about everything that matters.

**A licensing note first, because it decides how they may be used.** Both are GPL-3.0
and this project is PolyForm Noncommercial; the two do not mix, and copying so much as
a parse routine would make that someone else's problem to litigate. They are reference
*documentation* here — read them to learn what real meters do, then write this app's
collector from the SIG specification, which is public and owes nobody anything. The
protocol is a standard; only their code is licensed.

| UUID | What it is |
|---|---|
| `0x1808` | Glucose Service — the only service that must be there |
| `0x2A18` | Glucose Measurement — one notification per record |
| `0x2A34` | Glucose Measurement Context — optional, carries meal/health annotations |
| `0x2A52` | Record Access Control Point (RACP) — the history request channel |
| `0x2A2B` / `0x2A08` | Current Time / Date Time — the meter's own clock |
| `0x2A29` | Manufacturer Name String, in Device Information (`0x180A`) |
| `0x2902` | Client Characteristic Configuration, on each of the above |

### The conversation, in order

1. **Connect by address** to a device Android has already bonded. Discover services;
   bail if `0x1808` is absent — that single check is the whole compatibility test.
2. **Read the manufacturer name** (`0x2A29`) *before anything else*, because it selects
   the quirks. Both implementations branch on `startsWith`, case-sensitively.
3. **Read the meter's clock.** Standard meters answer on Current Time (`0x2A2B`);
   **Roche answers on Date Time (`0x2A08`), inside the glucose service** — that is the
   Accu-Chek branch and it is two lines.
4. **Subscribe**: notify on `0x2A18`, notify on `0x2A34` if present, and **indicate**
   — not notify — on `0x2A52`. Indications are acknowledged, which is what makes the
   RACP a request/response channel rather than a firehose.
5. **Write the request** to `0x2A52`. Records then arrive as a burst of `0x2A18`
   notifications, and the RACP indicates when the burst is over.

### The RACP bytes

| Request | Bytes |
|---|---|
| All stored records | `01 01` |
| Records with sequence ≥ N | `01 03 01 <N lo> <N hi>` (uint16, little-endian) |
| First record | `01 05` |
| Last record | `01 06` |

"Newer than N" is spelled as "≥ N+1". First use asks for everything; afterwards, ask
for everything above the highest sequence already stored, which is what makes a meter
unseen for a fortnight backfill in one connection.

### Parsing a measurement

Little-endian, at least 14 bytes. Flags are byte 0:

- `0x01` time offset present, `0x02` type and location present, `0x08` sensor status
  present, `0x10` a context record follows.
- **`0x04` is the unit flag, and it is the one to get right**: clear means kg/L
  (× 100 000 → mg/dL), set means mol/L (× 1000 × 18.01559 → mg/dL). The meter states
  its unit per record; the app's display preference has no vote.

Then a uint16 sequence number, a base timestamp as year/month/day/hour/minute/second,
and — if flagged — a signed offset in minutes. The concentration itself is an IEEE
SFLOAT: a 12-bit signed mantissa and a 4-bit signed exponent, value = mantissa × 10^exp.

If type and location are present, the low nibble is the sample type, and **type 10 is a
control solution test**. It is dropped, always, before it reaches anything that counts:
a control solution is a test of the strips, deliberately off-scale, and one of them in
the record would corrupt a comparison card built to judge a sensor.

### The clock is the subtle part

A meter keeps its own wall clock and timestamps records with it. The rule both
implementations follow: read the meter's clock once per connection, take the difference
against the phone's, and shift every record by it. **If the clock cannot be read, discard
the records rather than guess** — a record filed at the wrong hour is worse than one
never filed, because the comparison card would then be scoring the sensor against a
value from somebody else's breakfast.

Note what this does *not* fix: drift and daylight saving between syncs. The shift is
measured at the moment of connection and applied uniformly, and a meter two hours out
since a timezone change is corrected only from the next sync on.

### Traps worth knowing before writing a line

Both references carry scars, and they are the cheapest education available here:

- **One GATT operation in flight at a time.** Android's stack silently drops a second
  write issued before the first completes. Both drivers implement a command queue
  stepped by the callbacks; that queue is the architecture, not a detail.
- **Neither blocks the callback thread, and both do anyway** — xDrip+ sleeps inside
  Binder callbacks in at least six places, with the comments to prove it hurt. A
  coroutine with a `CompletableDeferred` per command is the version of this that does
  not deadlock.
- **Bond first.** Reads fail with `GATT_INSUFFICIENT_AUTHENTICATION` on an unbonded
  device; Juggluco retries the RACP write eight times, two seconds apart, for exactly
  this. Bonding in Android's settings beforehand sidesteps the whole dance.
- **The sequence number is a uint16** and xDrip+ holds it in a signed `short`, which
  goes negative past 32767. Whatever this app stores it in, it is not an `Int` by
  accident.
- **Off-by-one in "newer than".** xDrip+'s Contour path asks for records ≥ 1 when it has
  none stored, and therefore never fetches record 0. The i-SENS path uses ≥ highest and
  re-downloads the newest record every time — which is why a stable dedupe key matters
  more than a clever request.
- **`first`/`last` semantics are not reliably implemented by meters.** xDrip+'s own
  comment says it "needs verifying on device", and one manufacturer numbers records
  backwards. Prefer sequence ranges over ordinal requests.
- **Status 133 appears nowhere in either driver**, which does not mean it will not
  happen — it is Android's generic GATT failure and it is usually a reconnect.

## What it costs on Android

The app currently declares **no Bluetooth permission at all** and contains no BLE
code — the only Bluetooth in the tree is the Wear Data Layer, which Google Play
Services owns. So this is the first radio this app drives itself.

The permission bill is smaller than it looks, because of one design choice:

> **Pair in Android's own Bluetooth settings; then pick from bonded devices.**

A meter the user has already bonded can be connected **by address, without scanning**,
and scanning is the expensive half of the permission story: on API 29–30 a BLE scan
requires `ACCESS_FINE_LOCATION`, a runtime prompt that this app has never asked for and
would struggle to justify to someone reading the permission list of a glucose monitor.
Connecting to a known device needs none of it.

That leaves:

- `BLUETOOTH` + `BLUETOOTH_ADMIN`, `android:maxSdkVersion="30"` — the legacy pair.
- `BLUETOOTH_CONNECT` for API 31+, a runtime permission, asked for once on the screen
  that adds a meter.
- No location permission, and no `BLUETOOTH_SCAN`, for as long as pairing stays in
  Android's hands. If an in-app scanner is ever added, `BLUETOOTH_SCAN` with
  `android:usesPermissionFlags="neverForLocation"` is the form to use.

The connection itself belongs in the existing `PollingService`. It is already a
foreground service that runs for the whole session, already the component that owns the
runtime-registered xDrip+ receiver, and a meter that wakes up twice a day has no claim
on a service of its own.

## Where this belongs in the app

**Not as a `GlucoseSource`, and not as a `SourceKind`.** The source enum is a radio
choice — one source is live at a time, and its readings go into the `readings` table and
become the trace, the statistics, the alarms and the watch face. A fingerstick is not a
member of that set. Three spot values a day would corrupt every aggregate built on the
assumption of a continuous five-minute series: time in range, GMI, the daily pattern and
the coverage figure all mean "of the sensor record", and a chart that silently mixed in
capillary values would be answering a question nobody asked.

It belongs beside the diary, as its own fact:

- **`BloodTest` in `:core`** — mg/dL, the instant it was taken, the meter's record
  sequence number, the sample type, and how it arrived (meter or typed). Plausibility
  bounds exactly as `PushedReading` has them, for the same reason.
- **A `blood_tests` table**, Room 10 → 11, following `MIGRATION_9_10`'s shape.
- **DAO → repository → view model → screen**, for which the `InsulinDose` chain is a
  file-for-file template, down to `saveDose` returning false rather than throwing.

Two consequences worth stating plainly.

**Manual entry comes first, and the radio is an input method.** A fingerstick typed in
by hand is the same datum as one that arrived over Bluetooth; the diary already proves
the form. Building the table, the entry form, the chart marker and the comparison card
*before* any BLE code means the feature is useful on day one, the hard part is isolated,
and a failed GATT spike costs a weekend rather than a feature.

**The payoff is already in `:core` and currently unused.** `ClarkeErrorGrid.zoneOf()`
takes a reference value and a predicted one and reports the clinical zone — it exists,
it is tested, and its only caller today is the forecast benchmark. Paired points from a
meter are precisely the reference it was written for, and they turn an abstract
"sensors can be wrong" into a card that says how wrong *this* sensor has been, in the
one unit a clinician reads.

## Phase 2 — one value from two measurements

The owner's decision, to be built after the meter is reading: where a sensor value and a
strip value describe the same instant, **weight the strip at 60% and the sensor at 40%**.

That is a statement about which instrument is believed, and it is the right direction —
the strip is the reference and the sensor is the estimate. What follows is the set of
rules that keep a blended number honest, because a blended number is the one kind of
value in this app that no instrument ever produced.

**"The same instant" needs a definition, and a tighter one than the diary's.**
`InsulinDose.GLUCOSE_MATCH_WINDOW_MILLIS` is fifteen minutes and takes the nearest
reading in either direction; that rule is right for "what was the glucose when this dose
was given" and wrong here. Glucose can move 60 mg/dL in fifteen minutes, so two values
that far apart are not two measurements of one state. Fusion gets its own constant —
one sensor cadence, about five minutes — and the same nearest-in-either-direction rule.
Outside that window there is no pair, and nothing is blended.

**The two instruments are not measuring the same fluid.** A strip reads capillary blood;
a sensor reads interstitial fluid, which lags blood by five to fifteen minutes and lags
*further* the faster glucose is moving. When the trace is flat the two agree about what
"now" means. When it is moving at 3 mg/dL/min they are honestly describing different
moments, and averaging them produces a number that is wrong in a direction that was
predictable. The delta is already computed and already on the snapshot, so the rule is
cheap: **blend when flat, prefer the strip when not.**

**Nothing is ever overwritten.** The reading table keeps what the sensor said and the
blood test table keeps what the strip said; the blended figure is derived at read time
by a pure function in `:core`. This is the same doctrine that already governs the
diary — "a corrected reading would be a falsified record" — and it has a practical edge
beyond principle: when the weighting changes from 40/60 to something measured, every
past point re-derives, because nothing was baked in.

**In an alarm zone the strip wins outright.** Take a strip of 55 against a sensor of 75:
the blend is 63. The blend is *higher than the reference measurement*, in the one
direction where being wrong is dangerous, and it is higher precisely because the less
trusted instrument pulled it there. So the blend is for display and for comparison; the
alarm engine takes the strip at face value whenever the strip is the more severe of the
two. A weighting that softens a hypo is not a weighting, it is a bug with a rationale.

**Statistics stay sensor-only, at first.** Time in range, GMI and the daily pattern claim
to describe a continuous record with a stated coverage. Fused points would make them
describe something else, and the coverage figure would no longer mean what it says. The
blend earns its way into the aggregates when the error grid says it is better, not
before.

**This is fusion, not calibration.** The scope is one instant: the matched pair. What is
deliberately *not* being built is a correction carried forward — an offset that decays
over the following hours, which is what a calibrating CGM actually does. That needs the
bias to be measured rather than asserted, which needs paired points, which is what
phase 0 starts collecting on the day it ships.

**What eventually replaces 40/60.** The split is a prior, not a measurement: nobody has
yet checked how far this sensor sits from this meter. After a few dozen paired points
the app can report the real bias and spread, and the weights can be derived from the
instrument that has been more consistent rather than from a decision made in advance.
Until then 40/60 is a reasonable, stated guess, and the doc that says so is what stops
it from quietly becoming a fact.

## Build order

0. **No radio. ✅ built** in 3.1.7 — `BloodTest`, the `blood_tests` table (Room 11),
   manual entry on the Diary, a diamond on the chart, and "the sensor against the strip"
   on Trends. Useful alone, and it is the whole data model. The paired points it starts
   collecting are what phase 2's weighting will eventually be derived from.
1. **The spike. ✅ built into the app** in 3.2.0, rather than run as a throwaway: the
   meter screen connects to a bonded device and, when the glucose service is absent,
   prints what the device does expose. The dump is now a button, which also means it
   stays useful for the next meter.
2. **The collector. ✅ built** in 3.2.0 — bond-aware connect by address, incremental RACP
   pull by sequence, the parse in `:core` with twenty tests of hand-written vectors,
   control-solution filtering, meter-clock correction. It lives in its own session class
   rather than in `PollingService`: a meter is awake for seconds after a test and asleep
   the rest of the day, so there is nothing to hold a connection open for.

   **Unconfirmed on hardware.** No device has been on the other end of it.
3. **Phase 2.** The weighted value, under the rules above.

## Open questions

- **Does the Guide expose `0x1808`?** Settled by step 1, not by more reading.
- **Which unit is this particular meter in**, and does its record flag agree with its
  back label?
- **Is the meter's clock trustworthy** — does Roche set it from the phone at pairing, or
  does it drift on its own?
- **Do fused values ever enter the aggregate statistics**, and what does the coverage
  notice say on the day they do?
- **Does a strip reading belong on the watch?** It is a fact about the last few minutes
  and the watch's whole job is the current value, so probably not — but the payload is
  versioned and the question should be answered deliberately rather than by omission.
