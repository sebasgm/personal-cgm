# Alarm behaviour

Implemented in `core/…/Alarms.kt` (pure, 21 tests) and delivered by
`app/…/alarm/AlarmNotifier.kt`.

---

## 1. The five alarms

| Alarm | Default | Repeat | Quiet while recovering | DND override |
|---|---|---|---|---|
| Urgent low | below 55 | 5 min | **no** | on by default |
| Low | below 70 | 15 min | yes | off |
| High | above 220 | 15 min | yes | off |
| Very high | above 300 | 15 min | yes | off |
| Signal loss | after 20 min without data | 15 min | n/a | off |

Each is independently switchable, and every field above is per-alarm configurable.

## 2. Alarm thresholds are not display thresholds

`AlarmSetting.thresholdMgdl` is deliberately independent of `GlucoseThresholds`.

Where the graph stops calling a value "in range" and where you want to be woken up are
different questions. The defaults reflect that: the display band is 70–180 (from the
account), but the high alarm sits at 220. An alarm at the exact edge of target fires
constantly, and an alarm that fires constantly gets ignored — which is the real failure.

The two are edited in different places for the same reason: display bands under
Settings → Ranges, alarm levels inside each alarm. Editing your ranges recolours the graph
and moves time-in-range; it does not change when you get woken.

## 3. Silence while recovering (issue #1)

A low that is already coming back up does not need announcing every fifteen minutes.

- Condition becomes true → **sound once**, always.
- Still true, trend moving back toward range → **stay visible, stay silent**.
- Trend stops recovering → **sound resumes** on the next repeat interval.

"Recovering" is direction-aware: rising for a low, falling for a high. An **unknown**
trend is never treated as recovering — absence of evidence is not evidence of improvement.

**Urgent low opts out.** `silenceWhileRecovering = false` by default, because "it is
coming back up" is not a reason to stop telling someone they are at 45.

## 4. Severity suppression

At 45 mg/dL both *urgent low* and *low* are true. Only the more severe one fires;
likewise *very high* suppresses *high*. One condition, one alarm.

## 5. Stale data never drives glucose alarms

If the reading is STALE, only signal loss can fire.

Both directions matter. Alarming on a 40 that arrived half an hour ago wakes someone for
a low that already ended. Worse, a stale reading that happens to look fine would
otherwise suppress nothing while silently implying everything is well. When the data is
old, the honest alarm is "I don't know", and that is what signal loss says.

## 6. Signal loss is the alarm that must not be off

A dead feed and a flat line look identical on a graph. Every other alarm tells you
something about the body; this one tells you the app has stopped being able to.

It is evaluated **after every poll, including failed ones** — skipping it on failure
would disable it exactly when it matters. Its default (20 min) is deliberately looser
than the display's staleness threshold (10 min): the screen should stop claiming a value
is current long before it is worth waking someone.

## 7. Snooze

30 minutes, from the notification action. A snoozed alarm **keeps firing and stays
visible** — it just makes no sound. Dismissing the notification would imply the condition
had gone away.

## 8. State survives restarts

`AlarmRuntimeState` is persisted. Without it, every process restart would re-announce
every active alarm, which on a service designed to restart is a reliable way to train
someone to ignore alarms.

---

## 9. What Android will and will not allow

This shaped the implementation more than anything else.

### DND bypass needs a user grant

`setBypassDnd(true)` is **silently ignored** unless the app holds **Notification Policy
Access**, which only the user can give in system settings. The app cannot ask for it with
a runtime permission dialog.

So the override switch is disabled until the grant exists, with a card explaining why and
a button to the system screen. A switch that claims to override DND while Android ignores
it would be a lie about a safety feature.

### Channel settings are immutable after creation

Importance, sound and DND bypass belong to the user once a channel exists; later attempts
to change them do nothing.

Rather than the usual delete-and-recreate dance, each alarm has **two channels** — one
that bypasses DND and one that does not — and the toggle selects which one we post to.
The bypass channel is only created once policy access exists, so we never create a channel
whose bypass flag the system quietly dropped.

### Alarm audio escapes silent, but not DND

Channels use `USAGE_ALARM`, so alarms are audible with the ringer silenced — which covers
most of what people actually want overnight — without needing the DND grant at all.

### Shortcuts provided

- **Notification Policy Access** — grants DND bypass
- **Per-channel settings** — sound, vibration, importance for that one alarm
- **App notification settings** — everything at once

---

## 10. Not done yet

- Custom sound per alarm (Android owns this; the per-channel shortcut is the answer)
- Time-based profiles (quieter overnight)
- Rising/falling **rate** alarms, as distinct from level alarms
- Escalation when an urgent low goes unacknowledged
