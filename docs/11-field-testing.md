# 11 — Testing the two things only hardware can answer

Both were written without a device attached. Neither can be proven by the build.

## Build

```sh
export JAVA_HOME=$HOME/.jdks/jbr-21.0.11
./gradlew :app:versionedApkRelease :wear:versionedApkRelease
```

| | Path |
|---|---|
| Phone | `build/apks/cgm-<version>.apk` |
| Watch | `build/apks/cgm-WATCH-do-not-install-on-phone-<version>.apk` |

Release builds rather than debug ones, since 3.4.0: both are signed with the debug
key on purpose and both are named after `versionName`, so a file still says which
build it is once it is off the machine that built it. `assembleDebug` still works
and still lands in `*/build/outputs/apk/debug/`, but a debug APK is named the same
thing every time, which is how two field sessions end up unable to say what they
tested.

The two modules share one version number, from the root `build.gradle.kts`. The
watch set its own until 3.4.0 and it was left at 3.0.0 through four changes, so a
wrist could not be asked what it was running — if a watch reports 3.0.0, that is
what it means, not that it is up to date.

Both carry the application id `dev.cgm.app` — deliberately, because the Wear Data
Layer delivers only to the same id on the other device — and both are signed with
the same debug key, which the Data Layer also requires. Installing the phone APK
over an existing build keeps its database and settings.

**That shared id is why the watch APK's name shouts.** The watch build will install
cleanly over the phone app and replace it, and the file name is the only warning
present at the moment that mistake would be made. Always install it with `-s` and a
transport that is the watch.

## 1. The complication

### Getting it onto the watch

The watch needs its own APK; the phone's install does not carry it.

```sh
adb devices                                   # phone over USB
adb install -r build/apks/cgm-<version>.apk

# Watch: Settings → Developer options → ADB debugging + Debug over Wi-Fi,
# which shows an address.
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r \
  build/apks/cgm-WATCH-do-not-install-on-phone-<version>.apk
```

`-s` is not optional. Without it `adb` picks the only device, or refuses when there
are two — and the failure that matters is the one where it picks the phone.

**If Wi-Fi debugging cannot reach the watch**, which is the normal case on a network
that isolates its clients, the phone will relay instead: ADB debugging and *Debug
over Bluetooth* on the watch, the same switch in the Wear OS companion app, the
phone on USB, and then

```sh
adb forward tcp:4444 localabstract:/adb-hub
adb connect 127.0.0.1:4444
```

after which the watch is `127.0.0.1:4444` and takes the same `-s` install. Checked
on a `/17` network where the laptop's ARP table held nothing but the gateway and
mDNS discovery found no device at all: there is no watch IP to find there, and the
address the watch prints is not reachable.

**A first install of a release build over an existing debug one** should be an
in-place replace, since both carry the same debug key. If it is refused with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, `adb -s <watch> uninstall dev.cgm.app` is safe
on the watch and only on the watch: the watch keeps one cached payload and no
history. Never run that against the phone, which holds the readings.

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

### What the watchdog may and may not do

It has exactly two moves, and the distinction matters because getting it wrong
made things worse once already:

- **Service not running** → start it.
- **Service running but past its own polling deadline by five minutes** → ask it
  to rebuild its loop in place, by delivering an action to the service that is
  already in the foreground.

It never stops a running service. The first version did — stop, then start — and
the start came from a background process, where Android 12 and later refuse it.
A legitimate fifteen-minute wait was being read as a stall, so the watchdog was
killing a healthy service every quarter of an hour and failing to bring it back.

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
