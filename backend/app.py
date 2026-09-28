"""Extremely basic Flask app: a JSON API in front of the session state
machine, plus the static web/ MVP UI. See README section 8, milestone 3.
"""

import os
from datetime import datetime

from flask import Flask, jsonify, request

from .blue_zone_rules import ZURICH_TZ, blue_zone_deadline
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


def create_app(store: SessionStore | None = None) -> Flask:
    app = Flask(__name__, static_folder=WEB_DIR, static_url_path="")
    app.json.sort_keys = False
    store = store or SessionStore(ALL_SEGMENTS)

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
    def create_session():
        data = request.get_json(silent=True) or {}
        try:
            lat = float(data["lat"])
            lon = float(data["lon"])
        except (KeyError, TypeError, ValueError):
            return jsonify(error="lat and lon are required numbers"), 400

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
    def get_session(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        return jsonify(session_json(session, datetime.now(ZURICH_TZ)))

    @app.post("/api/session/<session_id>/position")
    def update_position(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        data = request.get_json(silent=True) or {}
        try:
            lat = float(data["lat"])
            lon = float(data["lon"])
        except (KeyError, TypeError, ValueError):
            return jsonify(error="lat and lon are required numbers"), 400
        now = datetime.now(ZURICH_TZ)
        event = store.update_position(session, lat, lon, now=now)
        return jsonify(session_json(session, now, event=event))

    @app.post("/api/session/<session_id>/reject")
    def reject(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        now = datetime.now(ZURICH_TZ)
        event = store.reject_current(session, now=now)
        return jsonify(session_json(session, now, event=event))

    @app.post("/api/session/<session_id>/confirm")
    def confirm(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        event = store.confirm_current(session)
        return jsonify(session_json(session, datetime.now(ZURICH_TZ), event=event))

    @app.post("/api/session/<session_id>/expand")
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
