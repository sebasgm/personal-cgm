# 11 — Testing the two things only hardware can answer

Both were written without a device attached. Neither can be proven by the build.

## Build

```sh
export JAVA_HOME=$HOME/.jdks/jbr-21.0.11
./gradlew :app:assembleDebug :wear:assembleDebug
```

| | Path |
|---|---|
| Phone | `app/build/outputs/apk/debug/app-debug.apk` |
| Watch | `wear/build/outputs/apk/debug/wear-debug.apk` |

Both carry the application id `dev.cgm.app` — deliberately, because the Wear Data
Layer delivers only to the same id on the other device — and both are signed with
the same debug key, which the Data Layer also requires. Installing the phone APK
over an existing build keeps its database and settings.

## 1. The complication

### Getting it onto the watch

The watch needs its own APK; the phone's install does not carry it.

```sh
adb devices                                   # phone over USB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Watch: Settings → Developer options → ADB debugging + Debug over Wi-Fi,
# which shows an address.
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r wear/build/outputs/apk/debug/wear-debug.apk
```

### The open question

**Whether a stock OnePlus face's complication picker lists third-party sources at
all.** This is the W0 question from `docs/06-wear-os.md` and nothing in the code
can answer it. Long-press the face → edit → pick a slot → look for **Glucose**.

If it is absent, the complication is fine and the face is the problem: try a
third-party face (Pujie, Facer, or any Watch Face Format face that accepts
external complications) before concluding anything about this app.

### What to check once it is placed

- A number appears, with a trend arrow beside it.
- **The age counts up on its own.** This is the one that matters. Leave the phone
  on the other side of the room for ten minutes: the age must keep climbing
  without the value changing. If it freezes, the time-difference text is not
  working and the complication is lying about staleness — which is the only way
  this feature can be actively harmful.
- A fresh reading on the phone reaches the face within about five minutes. The
  platform averages update requests, so it is not instant and is not meant to be.
- A ranged-value slot draws an arc that is low near the bottom of your target band
  and high near the top — it spans the in-range band, not 40–400, so an
  out-of-range value pins at an end on purpose.
- With the phone off, the value stays and the age keeps climbing.
- TalkBack reads the value and the trend as words.

## 2. The poller staying alive

The symptom being fixed: the status bar number freezes while the app still looks
like it is running.

### The visible check

**Settings → Service and safety → "Last check"**, a wall-clock time to the second.
It should never be more than a few minutes old while the service runs. That line
exists precisely so this failure is visible without a cable.

### Confirming the watchdog is armed

```sh
adb shell dumpsys alarm | grep -i -A2 watchdog_check
```

An entry should be listed roughly fifteen minutes out. If nothing appears, the
exact-alarm grant is missing — check **Settings → Alerts and ranges → Insulin
reminders**, which asks for it.

### Forcing the failure

```sh
adb shell am force-stop dev.cgm.app
```

The notification disappears. Within fifteen minutes it should come back by itself,
with "Last check" moving again. Before this change it stayed dead until the app
was opened or the phone rebooted.

```sh
adb logcat -s PollingService:* ServiceWatchdog:* WatchdogReceiver:*
```

- `poller is not running; starting it` — the process had been killed.
- `poller has stalled; restarting it` — the service was alive but its loop wedged.
- `poll loop died; restarting` — an exception inside the loop, and **the stack
  trace beside it is the actual bug**. Worth reporting rather than living with.

### Also worth doing

- Swipe the app out of recents. Polling should survive, or return within fifteen
  minutes.
- Reboot. It should come back without opening the app — including on xDrip+, which
  used to be missed because the boot check only asked about LibreLinkUp
  credentials.
- Press **Stop** in Settings. It must stay stopped: the watchdog is cancelled
  there, and a watchdog that overrides a deliberate stop would be a worse bug than
  the one it fixes.
- Leave it overnight and check "Last check" in the morning. That is the real test;
  everything above is only the parts that can be forced.
