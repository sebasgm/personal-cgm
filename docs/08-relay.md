# The relay

A hosted server the phone pushes to, and a browser reads from. No database.

---

## 1. What changes, and what it costs

The local web client could only ask Abbott directly, which meant twelve hours of coarse
history and a laptop running a server on the same machine. A relay fixes both: the phone
already has the data, and pushing it outward means a browser anywhere can read it.

It also reverses something decided earlier, and that is worth stating plainly rather than
letting it happen quietly.

`docs/02-roadmap.md` §4 rejected a backend, and rejected caregiver sharing on the grounds that
an alert sent from the phone fails exactly when it is needed. Both still hold for *alerting*.
But this is not alerting, it is viewing, and:

- **Health data will exist outside your phone.** Briefly and in memory, but it will transit a
  machine on the internet and sit in its RAM. That is a real change from "everything runs on
  your device", and `FEATURES.md` has to stop saying otherwise.
- **It reopens caregiver sharing.** Once readings reach a server, fanning them out to someone
  else is a small step. That was deferred pending exactly this, so the door is now open — but
  it is a separate decision, not a consequence.

## 2. Shape

```
phone  ──HTTPS POST──▶  relay  ◀──HTTPS GET──  browser
                     (memory only)
```

- **No database.** One payload held in memory, replaced on each push, with a TTL. A restart
  loses it and the next push restores it, which is the correct behaviour for something whose
  only job is to carry the current value.
- **The payload is `WatchPayload`**, the same object already sent to the watch. One wire
  format, already tested, already sized for a small link.
- **The relay never talks to Abbott.** It cannot poll, cannot log in, and holds no
  credentials. If the phone stops pushing, the relay has nothing and says so.

## 3. Staleness is the whole design

A relay adds a second way for a number to be wrong: the phone can stop pushing while the
relay keeps happily serving what it last heard.

So the payload's own timestamp governs everything. The browser renders age from it, exactly as
the phone and the watch do, and past the same thresholds the value loses its colour and then
disappears entirely. A relay that serves a frozen number as though it were current would be
worse than having no relay.

The in-memory entry also expires. An hours-old reading is not stale data, it is no data.

## 4. Access

No accounts — that decision stands. A single secret, generated on the phone and shown in
Settings:

- the **phone** sends it as a bearer token when pushing;
- the **browser** is asked for it once and keeps a session cookie.

One secret, two uses, nothing to administer. Rotating it is regenerating it on the phone.

What that buys and does not buy: anyone holding the secret can read your glucose, so it is a
password and behaves like one. It is long, random, and never in a URL, because URLs end up in
logs, history and referrers.

**TLS is not optional.** Without it the secret and the readings cross the network in clear.
Any host that terminates HTTPS for you is fine; one that does not is not a candidate.

## 5. Pushing

Off by default, and enabled explicitly — this is the one feature that sends health data off
the device, and it should never be something that turned itself on.

Pushed on each successful reading, from the same repository flow that feeds the watch. It
fails quietly: a relay that is down, unreachable or misconfigured must never disturb polling,
and the phone is the source of truth regardless.

## 6. Hosting

Not decided here, because it is yours to pick. What it needs is modest — a JVM, a public
name, and TLS:

- a small VPS,
- a container host with a free tier,
- or a machine at home behind a tunnel, which keeps the data on hardware you own and is the
  option most in keeping with the rest of this project.

The relay is stateless, so it can be restarted, moved or rebuilt at any time without losing
anything that is not about to be pushed again.
