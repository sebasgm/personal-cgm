# 10 — Insulin on board, borrowed from oref0

**Status: planned, not built.** This document is the plan, written while the reasoning
is fresh. Nothing in `:core` implements it yet.

## What is being borrowed, and what is not

OpenAPS is not an API. `openaps.org` is a reference design and a community; the
algorithms live on inside AndroidAPS, Loop and Trio, and the shared data layer is
Nightscout. So "integrate with OpenAPS" resolves into three quite different things,
and they deserve different answers.

### Borrow the mathematics — yes

`oref0`'s insulin model is two parameters and a closed-form curve. Given a dose, a
peak time and a duration of action it yields both **IOB** (units still to act) and
**activity** (units working right now). It is arithmetic over the `InsulinDose` rows
the Diary already holds: a pure function in `:core`, fully testable, no new
dependency, no network.

Defaults to ship with:

| Insulin | Peak | Duration |
|---|---|---|
| Rapid-acting (Novorapid, Humalog, Apidra) | 75 min | 300 min |
| Ultra-rapid (Fiasp, Lyumjev) | 55 min | 300 min |

Both configurable, because the curve is a population average and the point of this
app is measuring one person.

oref0 is MIT-licensed. The model is re-implemented from the published form rather
than transliterated, and `NOTICE` gets an entry either way.

### Read loop state — yes, when there is a Nightscout to read

Nightscout's `devicestatus` collection carries IOB, COB, predicted glucose, temp
basal, and whether a loop enacted or merely suggested. Displaying that is still
*describing*, which is what this app does. It needs the Nightscout client from
`docs/09-sources.md` and nothing else.

### Feed a loop — no

Not a scoping decision, a category one. This app reads a consumer sensor through an
unofficial interface, with measured latency and permanent gaps that nothing can
backfill, and its own projection is switched off by deliberate choice. Supplying
glucose to something that doses insulin would move it from describing what happened
to participating in treatment. That contradicts the disclaimer at the top of the
README and is the single change most likely to create real exposure.

## The correction that matters

**oref0's "basal" is not this app's `InsulinKind.BASAL`.**

In oref0 basal means rapid-acting insulin dripped continuously by a pump — the same
drug as a bolus, delivered differently, so the same curve applies. `InsulinKind.BASAL`
here means *long-acting background insulin, once or twice a day*: Tresiba, Lantus,
Levemir, Toujeo. A different drug, with a duration measured in days for some of them
and a profile that is nothing like a 75-minute peak.

Running a Tresiba dose through the rapid-acting curve produces a number that is
confidently wrong, which is the worst kind of wrong this app can produce.

**Therefore: IOB is computed from boluses only.** Long-acting doses are excluded from
the figure and labelled as excluded. Modelling them later means a separate
flat-plateau profile and its own honest caveats; it is not a matter of passing
different parameters to the same function.

## What does not transfer

Dynamic carb absorption, `autosens` and deviation analysis all need three numbers
this app has never asked for: insulin sensitivity factor, carb ratio, and a basal
profile. Without them, oref0's COB degrades to a guess with a citation attached, and
inventing plausible-looking defaults for them would be the least honest thing in the
codebase.

Holding them is also a commitment. They are dosing parameters. An app that stores
your ISF is one screen away from suggesting a correction, and that screen is the one
this project has decided not to build.

## Build order

1. **Insulin curve in `:core`.** `InsulinModel(peakMinutes, durationMinutes)` →
   `iobAt(dose, atMillis)` and `activityAt(dose, atMillis)`; `InsulinOnBoard.at(doses,
   millis)` summing the boluses. Pure, with tests for the shape of the curve: zero at
   t=0 and t=duration, maximum activity at the peak, total area equal to the dose.
2. **Dose arithmetic, which needs no model at all.** Total daily dose, basal/bolus
   split, doses per day, time since the last bolus. The Diary already holds all of it
   and the report could carry it per range beside the glucose figures. This is the
   cheapest real value here and should probably land first.
3. **IOB and activity on screen.** A figure on Home and a second trace under the
   chart. Two rules, both borrowed from how the projection is already handled: the
   assumptions (peak, duration) are stated where the number is shown, and it never
   appears beside a suggested dose.
4. **COB, only with a declared absorption time.** The user sets it; the model is named
   on screen; it is not oref0's dynamic estimate and must not be described as one.
5. **Loop state from Nightscout**, if and when there is a site.
6. **ISF, carb ratio, deviations, autosens** — held. Revisit only on a deliberate
   decision to change what this app is.

## One thing to stay honest about

IOB is *the* number people look at when deciding whether to correct. Putting it on
the Home screen is a step closer to decision support than anything currently in the
app. The mitigations are the ones the projection already lives under — state the
assumptions, never pair it with a suggestion, keep it out of the alarms — but they are
mitigations, not a reason the concern does not apply.
