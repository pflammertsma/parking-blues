"""Extremely basic Flask app: a JSON API in front of the session state
machine, plus the static web/ MVP UI. See README section 8, milestone 3.
"""

import math
import os
from dataclasses import dataclass
from datetime import datetime

from flask import Flask, jsonify, request
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from werkzeug.middleware.proxy_fix import ProxyFix

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


def create_app(store: SessionStore | None = None, rate_limits: RateLimits | None = None) -> Flask:
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

    # In-memory counters are correct only while a single process serves the
    # API (Cloud Run is pinned to --max-instances=1 with one gunicorn
    # worker); switch to a shared store such as Redis before scaling out.
    limiter = Limiter(
        key_func=get_remote_address,
        app=app,
        default_limits=[limits.default],
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
        return jsonify(error="rate limit exceeded", detail=str(error.description)), 429

    @app.after_request
    def add_cors_headers(response):
        # The frontend is also served statically from lammertsma.dev/projects/
        # parking-blues/, a different origin from this API's own subdomain --
        # see README section 11. No credentials/cookies are involved (session
        # id travels in the URL path), so a simple allow-list is enough.
        origin = request.headers.get("Origin")
        if origin in ALLOWED_ORIGINS:
            response.headers["Access-Control-Allow-Origin"] = origin
            response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            response.headers["Access-Control-Allow-Headers"] = "Content-Type"
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
    @limiter.limit(limits.create_session)
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
    @limiter.limit(limits.session_calls)
    def get_session(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        return jsonify(session_json(session, datetime.now(ZURICH_TZ)))

    @app.post("/api/session/<session_id>/position")
    @limiter.limit(limits.position_per_ip)
    @limiter.limit(limits.position_per_session, key_func=session_key)
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
    @limiter.limit(limits.session_calls)
    def reject(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        now = datetime.now(ZURICH_TZ)
        event = store.reject_current(session, now=now)
        return jsonify(session_json(session, now, event=event))

    @app.post("/api/session/<session_id>/confirm")
    @limiter.limit(limits.session_calls)
    def confirm(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        event = store.confirm_current(session)
        return jsonify(session_json(session, datetime.now(ZURICH_TZ), event=event))

    @app.post("/api/session/<session_id>/expand")
    @limiter.limit(limits.session_calls)
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
