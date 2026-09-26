# 09 — More than one sensor

## Why this document exists

`docs/00-research.md` §1C looked at Nightscout and said *skip it*. That verdict was
right and is now being partly reversed, so it is worth being precise about which
part, because the reasoning has not changed — only the question being asked.

**What was rejected:** Nightscout *as the spine*. Self-host Node and MongoDB, bridge
LibreLinkUp into it, read it from phone and watch. Against it: a server to run and
pay for (Heroku and Azure free tiers are gone; realistically about $5/month), one
more hop of latency, and far more surface area than a single-user project can
justify. The decisive argument was that it buys history, alarms and an ecosystem —
none of which was the problem being solved, which was getting a number onto a watch.

**What was never rejected:** xDrip+ as a *source*. The tool survey listed it as
"useful as a data source". Two adjacent things were ruled out and should not be
confused with it: its watch faces, which use a format Wear OS 5 no longer runs, and
direct BLE to the sensor, deferred because only one app can hold a Libre's
connection and a Libre 3 generally has to be activated by whichever app will own it.

**Why the old verdict does not block this.** Every objection was to *running* a
Nightscout server. Receiving a broadcast from an app already on the phone costs no
hosting, no money and no extra hop. "Don't make Nightscout the spine" was never
"don't read from anything."

## The decision

Support a second source by **receiving xDrip+'s local broadcast**, not by writing
another vendor client.

The arithmetic is what settles it. A Dexcom Share client is a week of work, an
ongoing terms-of-service exposure, and a protocol that breaks when Dexcom changes
it. Medtronic is the same again, and community bridges for it are broken regularly
on purpose. Roche's SmartGuide has no public API and no community groundwork to
build on at all. Against that, an intent receiver is about a hundred lines and
inherits every sensor xDrip+ already reads — Dexcom, Medtronic, Eversense, the
Chinese sensors it supports — without this app knowing any of their protocols.

It also fits the app's stance better than anything already in it: no account, no
credentials, no network, nothing leaving the phone. The relay is still the only
feature that sends data anywhere.

## How it works

xDrip+ broadcasts `com.eveningoutpost.dexdrip.BgEstimate` with the reading, its
timestamp and a slope name, and `…BgEstimateNoData` when it has nothing. The user
has to switch this on: **xDrip+ → Settings → Inter-app settings → "Broadcast
locally"**, with **"Compatible Broadcast"** enabled so the extras are populated.

Three pieces:

- **`PushedReading`** (`:core`) judges what arrived — the plausible range, a missing
  timestamp, a timestamp in the future. It is outside the Android layer so it can be
  tested without a device, and it is named generically because a Nightscout source
  would need exactly the same three judgements.
- **`XdripBroadcast`** (`:app`) pulls the extras out of the intent. Nothing else.
- **`XdripReceiver`** (`:app`) hands the result to `GlucoseRepository.ingest`.

### Two decisions worth keeping

**The receiver is registered at runtime, not declared in the manifest.** Since
Android 8 a manifest-declared receiver cannot be given an implicit broadcast, and
xDrip+'s is implicit unless the user has named a target package in its settings. A
receiver registered by a running process is exempt, and this app already keeps a
foreground service alive for the whole session — so the one component guaranteed to
be running is where this belongs. It is registered from a collector on the source
setting, so switching source takes effect without restarting the service and a
listener for an app the user has stopped using does not stay registered.

**`pollOnce` and `ingest` share one pipeline.** The success half of `pollOnce` became
`accept(result)`, and both routes go through it. Persistence, the rollup refresh,
delta refinement, threshold overrides and the emit to downstream sinks all have to
happen identically, or the watch and the status bar would disagree with the chart
depending on where a reading came from.

A pushed source still runs the loop, because the tray icon, the alarms and the
freshness clock all depend on it. Its "poll" reports how stale the last push is, and
deliberately reports success while readings are arriving — calling it a failure would
start an exponential backoff against a source that has no failure mode, since nothing
was asked of anyone.

### What the broadcast does not carry

No thresholds, no unit, no history, no sensor serial and no activation date. The app
falls back to its own defaults and applies the user's overrides on top, exactly as it
does to LibreLinkUp's account band. History is the app's own database. The sensor
description xDrip+ sends — "G6 Native", "Libre2" — is shown in Settings, because it is
the only way this app can name the sensor behind a pushed reading.

**The app cannot tell whether the broadcast is switched on.** An xDrip+ with it off
looks exactly like one that is not running. That is why the Settings screen states the
setting to change and shows whether anything has arrived, rather than reporting a
status it cannot observe.

## Still not done

- **Nightscout as a source.** The client is small and the same `GlucoseSource`
  interface takes it. Worth adding when there is a site to read; it also happens to
  be how loop state would arrive (see `docs/10-insulin-model.md`).
- **Dexcom's official API.** The only vendor path with an actual licence to use it.
  Delayed by about an hour, so useless for a live display — but it can **backfill**,
  which LibreLinkUp categorically cannot, and every long window in this app is
  currently empty for its first months because of that.
- **One history, two sensors.** The source is a radio choice, so only one is live at
  a time. Readings from different sensors are not distinguished in the database, so
  switching source mid-session interleaves two devices' readings into one trace.
  Acceptable while switching is rare and deliberate; it would need a source column on
  the reading table to be anything more.
