# Web client

Home and Trends in a browser, signed in with LibreLinkUp credentials.

```bash
./gradlew :web:run          # http://127.0.0.1:8080
```

## Why a server exists at all

A page cannot call Abbott directly. Measured against the live endpoint:

```
OPTIONS https://api-la.libreview.io/llu/auth/login
→ 204, vary: Origin, and no Access-Control-Allow-Origin
```

Without that header every browser blocks the request before it is sent, so a purely
static page is impossible no matter how it is written. Something server-side has to
make the call; this is the smallest thing that can.

## What it deliberately is not

Not a backend. It has no database, no accounts of its own, and no storage of any
kind. Sessions live in a map in memory and are gone when the process stops. The
password is used once to obtain an Abbott token and then dropped.

The project decided against building an identity service
(`docs/02-roadmap.md` §4), and this does not quietly become one.

The browser holds only an opaque random id, in an `HttpOnly` cookie it cannot read
— so a script on the page cannot walk off with a token Abbott would honour for six
months.

**It binds to `127.0.0.1`.** There is no authentication here beyond the Abbott
login and no TLS, so reaching it from elsewhere has to be a decision someone makes
on purpose. `CGM_WEB_HOST` overrides it and prints a warning saying as much.

## What it can and cannot show

`:core` and `:data-llu` are reused unchanged — zone classification, freshness,
statistics and continuity all come from the server, where they are already tested.
Re-deriving any of that in JavaScript would create a second set of thresholds that
could disagree with the phone's, on a page showing whether someone is low.

The limitation worth knowing: **there is no history here.** LibreLinkUp serves
about twelve hours of graph data at roughly fifteen-minute spacing and nothing
older, and this server stores nothing between restarts. So:

- **Now** is complete — current value, trend, age, and a twelve-hour chart.
- **Trends** describes that same twelve hours, not a week. Coverage is stated on
  the page for exactly this reason, and usually reads as unreliable, which is
  honest rather than broken.
- GMI and estimated A1C are shown but need about fourteen days to mean anything.
  Over half a day they are arithmetic, not a measurement.

Longer windows live in the phone app, which records its own history as it polls.
Giving the web client real history would mean letting this server accumulate and
store data — which is the backend the project decided against, and a separate
decision rather than a missing feature.
