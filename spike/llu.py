"""Minimal LibreLinkUp client. Standard library only — no pip install required.

Reverse-engineered, unofficial, unsupported by Abbott. See docs/00-research.md.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import ssl
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, asdict
from datetime import datetime, timezone

# Abbott returns a redirect to the right one at login if we guess wrong.
DEFAULT_REGION = "eu"
REGIONS = ("ae", "ap", "au", "ca", "de", "eu", "eu2", "fr", "jp", "la", "ru", "us", "cn")

# The `version` header is checked loosely by the server but an ancient value gets
# rejected. Bump this if login starts failing with status 920/"UpdateRequired".
HEADERS_BASE = {
    "product": "llu.android",
    "version": "4.16.0",
    "content-type": "application/json",
    "accept-encoding": "gzip",
    "user-agent": "okhttp/4.12.0",
}

TREND_ARROWS = {
    1: ("falling quickly", "↓"),
    2: ("falling", "↘"),
    3: ("steady", "→"),
    4: ("rising", "↗"),
    5: ("rising quickly", "↑"),
}


class LibreLinkUpError(RuntimeError):
    pass


class RateLimited(LibreLinkUpError):
    def __init__(self, retry_after: float):
        super().__init__(f"rate limited, retry after {retry_after:.0f}s")
        self.retry_after = retry_after


class AuthExpired(LibreLinkUpError):
    pass


@dataclass
class Reading:
    """One glucose measurement, normalised."""

    value_mgdl: float
    value_display: float
    unit: str  # "mg/dL" or "mmol/L"
    timestamp_utc: str  # ISO 8601, from FactoryTimestamp
    trend: int | None  # 1..5, None in historical graph data
    is_high: bool
    is_low: bool

    @property
    def dt(self) -> datetime:
        return datetime.fromisoformat(self.timestamp_utc)

    def age_seconds(self, now: datetime | None = None) -> float:
        return ((now or _utcnow()) - self.dt).total_seconds()

    def trend_label(self) -> str:
        name, arrow = TREND_ARROWS.get(self.trend or 0, ("unknown", "?"))
        return f"{arrow} {name}"

    def to_json(self) -> dict:
        return asdict(self)


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _parse_factory_timestamp(raw: str) -> datetime:
    """FactoryTimestamp is UTC in US format, e.g. '9/18/2026 9:23:45 PM'.

    Note the sibling `Timestamp` field is the same instant in the *sensor's* local
    time. Always use FactoryTimestamp; local time silently breaks across DST and
    when travelling.
    """
    return datetime.strptime(raw, "%m/%d/%Y %I:%M:%S %p").replace(tzinfo=timezone.utc)


def _reading_from_measurement(m: dict) -> Reading:
    is_mgdl = bool(m.get("GlucoseUnits", 1))
    return Reading(
        value_mgdl=float(m["ValueInMgPerDl"]),
        value_display=float(m.get("Value") or m["ValueInMgPerDl"]),
        unit="mg/dL" if is_mgdl else "mmol/L",
        timestamp_utc=_parse_factory_timestamp(m["FactoryTimestamp"]).isoformat(),
        trend=m.get("TrendArrow"),
        is_high=bool(m.get("isHigh")),
        is_low=bool(m.get("isLow")),
    )


class LibreLinkUp:
    def __init__(self, email: str, password: str, region: str = DEFAULT_REGION):
        self.email = email
        self.password = password
        self.region = region
        self.token: str | None = None
        self.account_id_hash: str | None = None
        self.token_expires: int | None = None
        self._ssl = ssl.create_default_context()

    # -- transport ---------------------------------------------------------

    @property
    def base_url(self) -> str:
        return f"https://api-{self.region}.libreview.io"

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        headers = dict(HEADERS_BASE)
        if self.token:
            headers["authorization"] = f"Bearer {self.token}"
        if self.account_id_hash:
            headers["account-id"] = self.account_id_hash

        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method
        )

        try:
            with urllib.request.urlopen(req, timeout=30, context=self._ssl) as resp:
                raw = resp.read()
                if resp.headers.get("content-encoding") == "gzip":
                    raw = gzip.decompress(raw)
                return json.loads(raw)
        except urllib.error.HTTPError as e:
            if e.code in (429, 430):
                raise RateLimited(float(e.headers.get("retry-after") or 60)) from e
            if e.code == 401:
                raise AuthExpired("token rejected (401)") from e
            detail = e.read()[:400].decode(errors="replace")
            raise LibreLinkUpError(f"HTTP {e.code} on {path}: {detail}") from e

    # -- auth --------------------------------------------------------------

    def login(self) -> dict:
        """Authenticate, following the region redirect if we guessed wrong."""
        for _ in range(len(REGIONS)):
            payload = self._request(
                "POST", "/llu/auth/login", {"email": self.email, "password": self.password}
            )
            data = payload.get("data") or {}

            # Wrong region: Abbott tells us the right one instead of authenticating.
            if data.get("redirect") and data.get("region"):
                self.region = data["region"]
                continue

            status = payload.get("status")
            if status == 2:
                raise LibreLinkUpError("bad email or password")
            if status == 4 or "step" in data:
                step = (data.get("step") or {}).get("type", "unknown")
                raise LibreLinkUpError(
                    f"account needs action in the LibreLinkUp app first (step: {step}). "
                    "Open the app, accept any pending terms, then retry."
                )
            if status not in (0, None):
                raise LibreLinkUpError(f"login failed, status={status}: {payload}")

            ticket = data.get("authTicket") or {}
            if not ticket.get("token"):
                raise LibreLinkUpError(f"no token in login response: {payload}")

            self.token = ticket["token"]
            self.token_expires = ticket.get("expires")
            # Every authenticated call needs the SHA-256 of the *account id*.
            # Abbott added this in 2024; without it you get a 401.
            self.account_id_hash = hashlib.sha256(
                data["user"]["id"].encode()
            ).hexdigest()
            return data

        raise LibreLinkUpError("region redirect loop did not settle")

    def ensure_auth(self) -> None:
        if not self.token:
            self.login()

    # -- data --------------------------------------------------------------

    def connections(self) -> list[dict]:
        self.ensure_auth()
        try:
            return self._request("GET", "/llu/connections").get("data") or []
        except AuthExpired:
            self.login()
            return self._request("GET", "/llu/connections").get("data") or []

    def graph(self, patient_id: str) -> tuple[Reading | None, list[Reading]]:
        """Return (current reading, historical readings)."""
        self.ensure_auth()
        try:
            payload = self._request("GET", f"/llu/connections/{patient_id}/graph")
        except AuthExpired:
            self.login()
            payload = self._request("GET", f"/llu/connections/{patient_id}/graph")

        data = payload.get("data") or {}
        current_raw = (data.get("connection") or {}).get("glucoseMeasurement")
        current = _reading_from_measurement(current_raw) if current_raw else None
        history = [_reading_from_measurement(m) for m in (data.get("graphData") or [])]
        return current, history


# -- credential/token cache -----------------------------------------------

CACHE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".llu_cache.json")


def load_client() -> LibreLinkUp:
    """Build a client from env vars, reusing a cached token when present."""
    email = os.environ.get("LLU_EMAIL")
    password = os.environ.get("LLU_PASSWORD")
    if not email or not password:
        raise SystemExit(
            "Set LLU_EMAIL and LLU_PASSWORD (the LibreLinkUp *follower* account,\n"
            "not the LibreView account that wears the sensor).\n"
            "  export LLU_EMAIL=you@example.com\n"
            "  read -rs LLU_PASSWORD && export LLU_PASSWORD"
        )

    client = LibreLinkUp(email, password, os.environ.get("LLU_REGION", DEFAULT_REGION))

    if os.path.exists(CACHE_PATH):
        try:
            with open(CACHE_PATH) as f:
                cache = json.load(f)
            if cache.get("email") == email and cache.get("expires", 0) > time.time() + 3600:
                client.region = cache["region"]
                client.token = cache["token"]
                client.account_id_hash = cache["account_id_hash"]
                client.token_expires = cache["expires"]
        except (OSError, ValueError, KeyError):
            pass  # a broken cache is not worth failing over

    return client


def save_cache(client: LibreLinkUp) -> None:
    if not client.token:
        return
    tmp = CACHE_PATH + ".tmp"
    with open(tmp, "w") as f:
        json.dump(
            {
                "email": client.email,
                "region": client.region,
                "token": client.token,
                "account_id_hash": client.account_id_hash,
                "expires": client.token_expires or 0,
            },
            f,
        )
    os.chmod(tmp, 0o600)
    os.replace(tmp, CACHE_PATH)
