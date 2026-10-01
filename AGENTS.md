# AGENTS.md — Developer & Agent Instructions

Technical guide, architecture decisions, algorithmic details, and development gotchas for agents and contributors working on **Parking Blues**.

---

## 1. Domain & Constraints

* **No Real-Time Occupancy Data:** The City of Zurich does not provide live sensor occupancy for on-street parking (Blue or White zones). Live occupancy feeds (PLS) exist only for commercial parking garages (*Parkhäuser*).
* **Core Paradigm:**
  1. Guide driver toward high-density clusters of eligible parking spots.
  2. Treat driving past a spot without stopping as implicit rejection ("occupied").
  3. Automatically advance to the next candidate in the cluster or expand search radius.
* **Zurich Zone Rules:**
  * **Blue Zones (*Blaue Zone*):** Free, time-limited with parking disc (*Parkscheibe*). Statutory limit is Monday–Saturday (08:00–11:30 and 13:30–18:00). Disc is set to the *next* half-hour mark, granting 60–90 minutes. Lunch hour (11:30–13:30) and nights (18:00–08:00) are unrestricted. Modeled in `backend/blue_zone_rules.py`.
  * **White Zones (*Weisse Zone*):** Metered street parking. City open data does not include real-time tariff rates; estimated at a standard flat rate (~2.00 CHF/h) in `backend/fee_estimate.py`.
* **Data Sources & Freshness:**
  * Bulk download from Zurich Open Government Data (OGD) was frozen end-of-2021.
  * Live Stadtplan WMS (`https://www.ogc.stadt-zuerich.ch/wms_cache/StadtplanApp`) serves active point (`Parkplaetze_GeoServer`) and line (`Parkplaetze_L_GeoServer`) features in EPSG:2056.
  * `scripts/ingest_zurich_parking.py` queries the official WFS/OGD endpoint and outputs `backend/data/zurich_parking.json` (~45k parking spots).

---

## 2. Architecture Overview

### Backend as Single Source of Truth
The algorithm (candidate generation, DBSCAN clustering, scoring, retargeting hysteresis, and rejection state machine) resides exclusively in `backend/session.py`.
* **Do not duplicate the algorithm in mobile/frontend clients.** Clients submit GPS coordinates and render whatever the server returns.
* **`SessionStore`:** Currently an in-memory dictionary. Cloud Run deployment must be pinned to `--max-instances=1` to prevent split-brain sessions across container instances.

### Algorithmic Decisions
* **Clustering (`backend/clustering.py`):**
  * `DEFAULT_CLUSTER_EPS_M = 12.0`: Tight threshold to group continuous curb runs without chaining across street corners or intersections.
  * `cluster_score = log1p(capacity) / (1 + distance/50.0) * (1.0 + free_zone_bonus)`: Logarithmic capacity gives diminishing returns, prioritizing close proximity over distant huge parking fields.
* **Heading & Directional Distance:**
  * Heading computed when movement >= 5m (`MIN_HEADING_UPDATE_DISTANCE_M`).
  * Straight-ahead candidates get up to 25% distance bonus (`DIRECTION_WEIGHT = 0.25`); candidates behind are penalized to prevent recommending awkward U-turns.
* **Retargeting Hysteresis:**
  * `LOCAL_PULL_IN_MARGIN_M = 15.0`: A newly discovered nearby spot must beat the currently targeted spot by at least 15m to trigger retargeting, preventing erratic target flickering while driving.

---

## 3. Android & Car App Library (CAL)

### Module Layout
* `android/shared/`: Kotlin Multiplatform (KMP) data models (`@Serializable`) and Ktor HTTP client (`ParkingApiClient`).
* `android/car/`: Shared Car App Library screens (`MapSearchScreen`, `ZoneSelectScreen`), location helpers, and osmdroid map renderer.
* `android/app/`: Phone module (Jetpack Compose) + Android Auto entry point.
* `android/automotive/`: Android Automotive OS (AAOS) entry point (`CarAppActivity`). Shares the same `applicationId` (`com.parkingblues.app`) as `:app`.

### Critical Gotchas
1. **CAL Host Rate Limiting:**
   * Non-navigation templates (`PaneTemplate`, `ListTemplate`, and content overlays in `MapWithContentTemplate`) are throttled by the car host system (typically 3–5 seconds between `Screen.invalidate()` calls).
   * In `MapSearchScreen.kt`, only call `invalidate()` when displayed text or state actually changes.
2. **Map Rendering via osmdroid:**
   * Google Maps SDK cannot be used on AAOS emulators because Google Play's Dynamite module delivery does not serve `maps_dynamite` to this device class.
   * `MapSearchScreen` renders using osmdroid over `SurfaceCallback` via a `VirtualDisplay`/`Presentation`.
3. **osmdroid VectorDrawable Sizing Bug:**
   * `osmdroid`'s `Marker.draw` resets icon bounds to `mIcon.getIntrinsicWidth()` / `getIntrinsicHeight()` on every frame.
   * To resize vector markers (e.g. `CAR_ICON_DP`, `DESTINATION_ICON_DP`), vector drawables must be rasterized to a `BitmapDrawable` with explicit pixel dimensions on a `Canvas`.
4. **AAOS Driving State Restrictions:**
   * AAOS blocks launching non-allowlisted apps unless the gear is set to **Park (P)**. In the emulator, set Extended Controls → Car sensor data → Gear = P.
5. **Shared App Icon:**
   * Launcher icons reside in `:car` (`res/mipmap-anydpi-v26/`) and merge into both `:app` and `:automotive`.
   * Launcher icons must remain in `mipmap*`, not `drawable*`.
6. **POI App Permissions:**
   * Declared as a POI app (`androidx.car.app.category.POI`).
   * Requires `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />` and `<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />`.
   * Must never declare `androidx.car.app.NAVIGATION_TEMPLATES` (mutually exclusive with `MAP_TEMPLATES`; rejected during review).
7. **Android Auto Manifest Meta-Data:**
   * AAOS uses `<meta-data android:name="com.android.automotive" android:resource="@xml/automotive_app_desc" />`.
   * Android Auto in `:app` requires `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />`. Missing this silently drops the app from the Android Auto launcher.

---

## 4. Development & Build Commands

### Backend & Web
```bash
# Setup virtual environment
python3 -m venv .venv
source .venv/bin/activate  # On Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt

# Run local development server
python -m backend.app      # Serves http://127.0.0.1:5000

# Run backend test suite
pytest
```

### Android (from `android/` directory)
```powershell
# Build all modules
.\gradlew.bat assembleDebug

# Run unit tests across all modules
.\gradlew.bat testDebugUnitTest

# Install & launch Android Automotive OS (AAOS)
.\gradlew.bat :automotive:installDebug
adb shell am start -n com.parkingblues.app/androidx.car.app.activity.CarAppActivity

# Install & launch Phone App / Android Auto entry
.\gradlew.bat :app:installDebug
adb shell am start -n com.parkingblues.app/.MainActivity
```

---

## 5. Deployment & CI/CD

* **Cloud Run Backend:**
  * Service: `parking-blues`
  * Project: `parking-blues-mvp`
  * Region: `europe-west1` (Belgium — chosen because Cloud Run custom domain mappings are unavailable in `europe-west6` Zurich).
  * Manual deploy script: `deploy/gcloud.sh <project-id> <billing-account-id>`
  * CI Workflow: `.github/workflows/deploy-backend.yml` automatically deploys on pushes touching `backend/`.
* **Frontend Hosting:**
  * Hosted at `https://lammertsma.dev/projects/parking-blues` via Firebase Hosting in `pflammertsma/lammertsma-dev`.
  * Published via `.github/workflows/publish-frontend.yml`.
