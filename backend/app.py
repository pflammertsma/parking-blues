"""Extremely basic Flask app: a JSON API in front of the session state
machine, plus the static web/ MVP UI. See README section 8, milestone 3.
"""

import os

from flask import Flask, jsonify, request

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

    def segment_json(segment: ParkingSegment, origin_lat: float, origin_lon: float) -> dict:
        return {
            "id": segment.id,
            "lat": segment.lat,
            "lon": segment.lon,
            "zone_type": segment.zone_type.value,
            "address_label": segment.address_label,
            "estimated_capacity": segment.estimated_capacity,
            "max_duration_minutes": segment.max_duration_minutes,
            "distance_m": round(
                haversine_m(origin_lat, origin_lon, segment.lat, segment.lon), 1
            ),
        }

    def session_json(session: ParkingSession, event: str | None = None) -> dict:
        body = {
            "session_id": session.id,
            "state": session.state.value,
            "radius_m": session.radius_m,
            "origin": {"lat": session.origin_lat, "lon": session.origin_lon},
            "current": (
                segment_json(session.current, session.origin_lat, session.origin_lon)
                if session.current
                else None
            ),
            "upcoming": [
                segment_json(s, session.origin_lat, session.origin_lon)
                for s in session.upcoming
            ],
            "rejected": [
                segment_json(s, session.origin_lat, session.origin_lon)
                for s in session.rejected
            ],
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

        session = store.create(lat, lon, ZONE_FILTERS[zone])
        return jsonify(session_json(session)), 201

    @app.get("/api/session/<session_id>")
    def get_session(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        return jsonify(session_json(session))

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
        event = store.update_position(session, lat, lon)
        return jsonify(session_json(session, event=event))

    @app.post("/api/session/<session_id>/reject")
    def reject(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        event = store.reject_current(session)
        return jsonify(session_json(session, event=event))

    @app.post("/api/session/<session_id>/confirm")
    def confirm(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        event = store.confirm_current(session)
        return jsonify(session_json(session, event=event))

    @app.post("/api/session/<session_id>/expand")
    def expand(session_id: str):
        session = store.get(session_id)
        if session is None:
            return jsonify(error="session not found"), 404
        store.expand_radius(session)
        return jsonify(session_json(session, event="expanded"))

    return app


if __name__ == "__main__":
    create_app().run(debug=True, port=5000)
