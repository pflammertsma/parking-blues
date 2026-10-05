"""Extremely basic Flask app: a JSON API in front of the session state
machine, plus the static web/ MVP UI. See README section 8, milestone 3.
"""

import math
import os
from dataclasses import dataclass
from datetime import datetime

from flask import Flask, g, jsonify, request
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from werkzeug.middleware.proxy_fix import ProxyFix

from .auth import (
    ROLE_OWNER,
    AbuseGuard,
    Account,
    AuthError,
    AuthService,
    Session,
    auth_from_env,
)
from .blue_zone_rules import ZURICH_TZ, blue_zone_deadline
from .fee_estimate import ESTIMATED_WHITE_ZONE_RATE_CHF_PER_HOUR
from .parking_data import ALL_SEGMENTS
from .geo import haversine_m
from .models import ParkingSegment, ZoneType
from .session import ParkingSession, SessionStore

ZONE_FILTERS: dict[str, set[ZoneType]] = {
    "blue": {ZoneType.BLUE},
    "white": {ZoneType.WHITE},
    "both": {ZoneType.BLUE, ZoneType.WHITE},
}

WEB_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")

ALLOWED_ORIGINS = {
    "https://lammertsma.dev",
    # Local `firebase serve`/`firebase emulators:start` for the portfolio
    # site, and this app's own dev server serving web/ directly.
    "http://localhost:5000",
    "http://127.0.0.1:5000",
    "http://localhost:5001",
    "http://127.0.0.1:5001",
}


@dataclass(frozen=True)
class RateLimits:
    """Limits for anonymous callers (there are no accounts yet). Each field is
    a Flask-Limiter string; ';' combines several windows. Starting values to
    tune from real traffic -- see TODO.md.

    Per-IP limits are deliberately generous because mobile carriers put many
    phones behind one address; the tight, per-user limit is the one keyed by
    session id (a drive sends ~1 position update per second).
    """

    create_session: str = "20 per minute;300 per day"      # per IP
    position_per_session: str = "90 per minute"             # per session id
    position_per_ip: str = "1200 per minute"                # per IP ceiling
    session_calls: str = "60 per minute"                    # get/reject/confirm/expand, per IP
    default: str = "600 per minute"                         # everything else (static files), per IP
    auth: str = "20 per minute;200 per day"                 # sign-in/refresh, per IP
    # Signed-in callers are counted per account instead of per IP, and get
    # more headroom: abuse can be traced to (and blocked on) an account.
    create_session_account: str = "60 per minute;2000 per day"
    position_per_account: str = "600 per minute"
    session_calls_account: str = "240 per minute"
    default_account: str = "1200 per minute"
    enabled: bool = True


# Anything beyond this is not a legitimate request body for this API.
MAX_REQUEST_BYTES = 8 * 1024


def parse_coordinates(data: dict) -> tuple[float, float] | None:
    """lat/lon as finite numbers within range, or None if invalid."""
    try:
        lat = float(data["lat"])
        lon = float(data["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    if not (math.isfinite(lat) and math.isfinite(lon)):
        return None
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
        return None
    return lat, lon


def create_app(
    store: SessionStore | None = None,
    rate_limits: RateLimits | None = None,
    auth: AuthService | None = None,
) -> Flask:
    app = Flask(__name__, static_folder=WEB_DIR, static_url_path="")
    app.json.sort_keys = False
    app.config["MAX_CONTENT_LENGTH"] = MAX_REQUEST_BYTES
    # Behind Cloud Run's front end, the real client address is the entry that
    # proxy appended to X-Forwarded-For; trusting exactly one hop (the last
    # entry) means a client can't dodge limits by sending its own header.
    app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1)
    # `is None`, not `or`: an empty SessionStore has len() == 0 and is falsy.
    if store is None:
        store = SessionStore(ALL_SEGMENTS)
    limits = rate_limits if rate_limits is not None else RateLimits()
    # Sign-in is optional and only active when configured (see auth_from_env);
    # without it every caller is anonymous, exactly as before.
    if auth is None:
        auth = auth_from_env()
    guard = AbuseGuard(auth) if auth is not None else None

    # Must be registered before the limiter below: Flask runs before_request
    # hooks in registration order, and the limiter's key depends on who is calling.
    @app.before_request
    def authenticate():
        g.sub = None
        g.role = None
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer ") or request.path.startswith("/api/auth/"):
            return None
        if auth is None:
            return None  # sign-in isn't configured; ignore any token
        try:
            claims = auth.tokens.verify_access(header[len("Bearer "):])
        except AuthError as exc:
            return jsonify(error=exc.message, code=exc.code), exc.status
        account = auth.account(claims["sub"])
        if account is None:
            return jsonify(error="unknown account", code="invalid_token"), 401
        if account.role != ROLE_OWNER and account.is_blocked(auth.clock()):
            return jsonify(error="account blocked", code="account_blocked"), 403
        g.sub = account.sub
        g.role = account.role
        return None

    def client_key() -> str:
        return f"acct:{g.sub}" if g.get("sub") else get_remote_address()

    def is_owner() -> bool:
        return g.get("role") == ROLE_OWNER

    def tiered(anonymous: str, account: str):
        return lambda: account if g.get("sub") else anonymous

    # In-memory counters are correct only while a single process serves the
    # API (Cloud Run is pinned to --max-instances=1 with one gunicorn
    # worker); switch to a shared store such as Redis before scaling out.
    limiter = Limiter(
        key_func=client_key,
        app=app,
        default_limits=[tiered(limits.default, limits.default_account)],
        default_limits_exempt_when=is_owner,
        storage_uri="memory://",
        headers_enabled=True,
        enabled=limits.enabled,
    )

    # Flask-Limiter's route decorators hold only a weak reference to the
    # limiter; without this, it is garbage-collected once create_app returns
    # and every limited route starts raising ReferenceError.
    app.extensions["parking_blues_limiter"] = limiter

    def session_key() -> str:
        return f"session:{request.view_args.get('session_id', '')}"

    @app.errorhandler(429)
    def too_many_requests(error):
        # Repeated breaches by a signed-in account escalate to a block.
        sub = g.get("sub")
        if guard is not None and sub:
            blocked = guard.record_violation(sub)
            if blocked is not None:
                return jsonify(error="account blocked", code="account_blocked"), 403
        return jsonify(error="rate limit exceeded", detail=str(error.description)), 429

    def auth_json(session: Session) -> dict:
        return {
            "access_token": session.access_token,
            "expires_in": session.expires_in,
            "refresh_token": session.refresh_token,
            "account": account_json(session.account),
        }

    def account_json(account: Account) -> dict:
        return {"sub": account.sub, "email": account.email, "name": account.name, "role": account.role}

    def auth_unavailable():
        return jsonify(error="sign-in is not available", code="auth_unavailable"), 503

    def string_field(name: str) -> str | None:
        value = (request.get_json(silent=True) or {}).get(name)
        return value if isinstance(value, str) and 0 < len(value) <= 4096 else None

    @app.post("/api/auth/google")
    @limiter.limit(limits.auth)
    def auth_google():
        if auth is None:
            return auth_unavailable()
        id_token = string_field("id_token")
        if id_token is None:
            return jsonify(error="id_token is required", code="bad_request"), 400
        try:
            return jsonify(auth_json(auth.sign_in_with_google(id_token)))
        except AuthError as exc:
            return jsonify(error=exc.message, code=exc.code), exc.status

    @app.post("/api/auth/refresh")
    @limiter.limit(limits.auth)
    def auth_refresh():
        if auth is None:
            return auth_unavailable()
        refresh_token = string_field("refresh_token")
        if refresh_token is None:
            return jsonify(error="refresh_token is required", code="bad_request"), 400
        try:
            return jsonify(auth_json(auth.refresh(refresh_token)))
        except AuthError as exc:
            return jsonify(error=exc.message, code=exc.code), exc.status

    @app.post("/api/auth/logout")
    @limiter.limit(limits.auth)
    def auth_logout():
        if auth is None:
            return auth_unavailable()
        refresh_token = string_field("refresh_token")
        if refresh_token is not None:
            auth.sign_out(refresh_token)
        return "", 204

    @app.get("/api/me")
    def me():
        if auth is None or not g.get("sub"):
            return jsonify(error="sign in required", code="unauthenticated"), 401
        return jsonify(account_json(auth.account(g.sub)))

    @app.delete("/api/me")
    def delete_me():
        if auth is None or not g.get("sub"):
            return jsonify(error="sign in required", code="unauthenticated"), 401
        auth.delete_account(g.sub)
        return "", 204

    @app.after_request
    def add_cors_headers(response):
        # The frontend is also served statically from lammertsma.dev/projects/
        # parking-blues/, a different origin from this API's own subdomain --
        # see README section 11. No credentials/cookies are involved (session
        # id travels in the URL path), so a simple allow-list is enough.
        origin = request.headers.get("Origin")
        if origin in ALLOWED_ORIGINS:
            response.headers["Access-Control-Allow-Origin"] = origin
            response.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
            response.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization"
            response.headers["Access-Control-Expose-Headers"] = "Retry-After"
        return response

    def segment_json(
        segment: ParkingSegment,
        origin_lat: float,
        origin_lon: float,
        you_lat: float,
        you_lon: float,
        now: datetime,
    ) -> dict:
        body = {
            "id": segment.id,
            "lat": segment.lat,
            "lon": segment.lon,
            "zone_type": segment.zone_type.value,
            "address_label": segment.address_label,
            "estimated_capacity": segment.estimated_capacity,
            "max_duration_minutes": segment.max_duration_minutes,
            # Distance from the destination -- fixed for the life of the
            # session, useful for judging "is this actually near where I'm
            # headed". distance_from_you_m below is the one that matters
            # moment-to-moment while driving.
            "distance_m": round(
                haversine_m(origin_lat, origin_lon, segment.lat, segment.lon), 1
            ),
            "distance_from_you_m": round(
                haversine_m(you_lat, you_lon, segment.lat, segment.lon), 1
            ),
        }
        if segment.zone_type == ZoneType.BLUE:
            # `now` must be Zurich-local -- see backend/blue_zone_rules.py
            # for the actual (time-of-day-dependent) rule; a flat "60 min"
            # is not it.
            deadline = blue_zone_deadline(now)
            body["legal_until"] = deadline.isoformat() if deadline else None
        else:
            # See backend/fee_estimate.py -- a single flat guess, not real
            # per-spot pricing (which we have no data for at all).
            body["estimated_fee_chf_per_hour"] = ESTIMATED_WHITE_ZONE_RATE_CHF_PER_HOUR
        return body

    def session_json(session: ParkingSession, now: datetime, event: str | None = None) -> dict:
        args = (session.origin_lat, session.origin_lon, session.last_lat, session.last_lon, now)
        body = {
            "session_id": session.id,
            "state": session.state.value,
            "radius_m": session.radius_m,
            "origin": {"lat": session.origin_lat, "lon": session.origin_lon},
            "you": {"lat": session.last_lat, "lon": session.last_lon},
            "preferred_duration_minutes": session.preferred_duration_minutes,
            "current": (
                segment_json(session.current, *args) if session.current else None
            ),
            "upcoming": [segment_json(s, *args) for s in session.upcoming],
            "rejected": [segment_json(s, *args) for s in session.rejected],
            "rejected_count": len(session.rejected_ids),
        }
        if event is not None:
            body["event"] = event
        return body

    @app.get("/")
    def index():
        return app.send_static_file("index.html")

    @app.post("/api/session")
    @limiter.limit(tiered(limits.create_session, limits.create_session_account), exempt_when=is_owner)
    def create_session():
        data = request.get_json(silent=True) or {}
        coordinates = parse_coordinates(data)
        if coordinates is None:
            return jsonify(error="lat and lon are required numbers within range"), 400
        lat, lon = coordinates

        zone = data.get("zone", "both")
        if zone not in ZONE_FILTERS:
            return jsonify(error=f"zone must be one of {list(ZONE_FILTERS)}"), 400

        duration_minutes = data.get("duration_minutes")
        if duration_minutes is not None:
            try:
                duration_minutes = int(duration_minutes)
                if duration_minutes <= 0:
                    raise ValueError
            except (TypeError, ValueError):
                return jsonify(error="duration_minutes must be a positive integer"), 400

        now = datetime.now(ZURICH_TZ)
        session = store.create(
            lat, lon, ZONE_FILTERS[zone], preferred_duration_minutes=duration_minutes, now=now
        )
        return jsonify(session_json(session, now)), 201

    @app.get("/api/session/<session_id>")
    @limiter.limit(tiered(limits.session_calls, limits.session_calls_account), exempt_when=is_owner)
    def get_session(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        return jsonify(session_json(session, datetime.now(ZURICH_TZ)))

    @app.post("/api/session/<session_id>/position")
    @limiter.limit(tiered(limits.position_per_ip, limits.position_per_account), exempt_when=is_owner)
    @limiter.limit(limits.position_per_session, key_func=session_key, exempt_when=is_owner)
    def update_position(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        data = request.get_json(silent=True) or {}
        coordinates = parse_coordinates(data)
        if coordinates is None:
            return jsonify(error="lat and lon are required numbers within range"), 400
        lat, lon = coordinates
        now = datetime.now(ZURICH_TZ)
        event = store.update_position(session, lat, lon, now=now)
        return jsonify(session_json(session, now, event=event))

    @app.post("/api/session/<session_id>/reject")
    @limiter.limit(tiered(limits.session_calls, limits.session_calls_account), exempt_when=is_owner)
    def reject(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        now = datetime.now(ZURICH_TZ)
        event = store.reject_current(session, now=now)
        return jsonify(session_json(session, now, event=event))

    @app.post("/api/session/<session_id>/confirm")
    @limiter.limit(tiered(limits.session_calls, limits.session_calls_account), exempt_when=is_owner)
    def confirm(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        event = store.confirm_current(session)
        return jsonify(session_json(session, datetime.now(ZURICH_TZ), event=event))

    @app.post("/api/session/<session_id>/expand")
    @limiter.limit(tiered(limits.session_calls, limits.session_calls_account), exempt_when=is_owner)
    def expand(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        now = datetime.now(ZURICH_TZ)
        store.expand_radius(session, now=now)
        return jsonify(session_json(session, now, event="expanded"))

    return app


if __name__ == "__main__":
    create_app().run(debug=True, port=5000)
