# personal-cgm

Personal pipeline for getting FreeStyle Libre glucose readings from LibreLinkUp onto an
Android phone, and from there onto a Wear OS watch.

**Not a medical device.** The LibreLinkUp API is unofficial, reverse-engineered and unsupported
by Abbott, and the cloud path adds minutes of latency. The official app stays the source of truth
for any dosing decision. See [docs/00-research.md](docs/00-research.md) §6.

Research: [docs/00-research.md](docs/00-research.md) · Staged plan: [docs/01-plan.md](docs/01-plan.md)
Issue roadmap: [docs/02-roadmap.md](docs/02-roadmap.md) · UI design: [docs/03-ui-design.md](docs/03-ui-design.md)
Alarm behaviour: [docs/04-alarms.md](docs/04-alarms.md) · Wear OS plan: [docs/06-wear-os.md](docs/06-wear-os.md) · Trend inference: [docs/05-trend-inference.md](docs/05-trend-inference.md)

## Status

| Stage | What | State |
|---|---|---|
| 0 | Validate with existing apps (GlucoDataHandler, Gluroo) | not done — still worth doing |
| 1 | LibreLinkUp API probe (Python) | built |
| 2 | Android phone app | built and running on device |
| 3 | Wear Data Layer + complication | not started |
| 4 | Watch Face Format watch face | not started |
| 5 | Full-screen bitmap face with graph | not started |
| 6 | Alarms, standalone fallback, Nightscout export | not started |

## Layout

```
core/         Pure Kotlin/JVM. Reading model, freshness policy, poll scheduler,
              watch wire format. No Android types — the Wear module shares this verbatim.
data-llu/     Pure Kotlin/JVM. LibreLinkUp client behind core's GlucoseSource interface.
app/          Android phone app: polling service, secure storage, Room history, Compose UI.
web/          Local Ktor server + browser client for Home and Trends.
spike/        Python probe for measuring the live API.
docs/         Research and plan.
```

`core` and `data-llu` are deliberately not Android libraries. Stage 3 reuses them on the watch,
and stage 6 needs the LibreLinkUp client to run there directly.

## Stage 1 — probe the API

Use the **follower** account — the one invited to follow the sensor, not the LibreView account
wearing it.

```bash
export LLU_EMAIL=you@example.com
read -rs LLU_PASSWORD && export LLU_PASSWORD

./spike/llu_probe.py login    # region, patient id, sensor, display unit
./spike/llu_probe.py probe    # one reading + how far behind the sensor it is
./spike/llu_probe.py record   # poll and record; leave running a few hours
./spike/llu_probe.py stats    # real cadence and cloud latency
```

Standard library only — no `pip install`. The recorded `.jsonl` is the fixture to develop the app
against, so the live API is not hammered during UI work. Recordings are gitignored: they are
personal health data.

The `stats` output matters for stage 2 — measured cloud latency is what the freshness thresholds
in `core/…/Freshness.kt` should be tuned against.

## Stage 2 — build and install the phone app

```bash
./gradlew test           # 34 tests, no device needed
./gradlew :app:assembleDebug
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, `compileSdk = 37`, `minSdk = 29`. Gradle
fetches the JDK 17 toolchain itself. AGP 8.x will not work — it relies on a Gradle internal API
removed in 9.6.

## Decisions worth knowing

- **Credentials** use a hand-rolled Android Keystore AES-GCM wrapper over DataStore.
  `EncryptedSharedPreferences` was deprecated in April 2025.
- **Foreground service type is `specialUse`**, not `dataSync`: since Android 15, `dataSync` is
  capped at ~6 hours a day, which is fatal for a continuous monitor.
- **Freshness is computed from the clock**, never from whether the last fetch succeeded. A stale
  reading behind a successful poll is still stale. The UI degrades visibly.
- **Timestamps are absolute epoch millis end to end.** The watch needs the raw instant to render
  reading age through the platform's time-difference bindings — the one display that stays true
  when complication updates get throttled. See research §3c.
