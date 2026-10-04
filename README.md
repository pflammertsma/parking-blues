# Parking Blues

A smart on-street parking assistant that guides drivers to legal parking spaces (**Blaue Zone** and **Weisse Zone**) in Zurich. Available as a web MVP, on Android phones, and in-car via Android Auto and Android Automotive OS (AAOS).

---

## The Problem & How It Works

Zurich does not publish real-time occupancy data for on-street parking spaces. Rather than guessing randomly or pointlessly navigating to a single spot that might be full:

1. **Select Zone:** Choose **Blue zones** (free with Swiss parking disc), **White zones** (metered), or **Both**.
2. **Smart Cluster Search:** The app locates high-density clusters of eligible parking spots close to your destination, factoring in arrival times, statutory duration rules, and street capacity.
3. **Automatic Pass-By Detection:** When you drive past a targeted spot without stopping, the app infers the spot is occupied and seamlessly reroutes to the next best spot in the cluster.
4. **Autonomous Radius Expansion:** If nearby spots are exhausted, the search radius automatically widens to surrounding streets.

---

## Live Links

* **Web App:** [lammertsma.dev/projects/parking-blues](https://lammertsma.dev/projects/parking-blues)
* **Backend API:** [api.parking-blues.lammertsma.dev](https://api.parking-blues.lammertsma.dev)

---

## Project Structure

```
parking-blues/
├── backend/       # Python/Flask API, DBSCAN clustering, zone duration rules & session state
├── web/           # Lightweight, zero-build web MVP (vanilla HTML/CSS/JS)
├── android/       # Kotlin Multiplatform client
│   ├── shared/    # Multiplatform data models & Ktor API client
│   ├── car/       # Car App Library screens (Android Auto & AAOS) + osmdroid map renderer
│   ├── app/       # Phone Compose UI + Android Auto host service
│   └── automotive/# Android Automotive OS (AAOS) standalone app entry
├── scripts/       # Data ingestion scripts for Zurich open geodata
└── tests/         # Pytest suite for backend algorithm, geo math, and session tracking
```

---

## Quickstart

### 1. Backend & Web App

```bash
# Setup virtual environment and dependencies
python3 -m venv .venv
source .venv/bin/activate  # On Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt

# Start backend server (serves http://localhost:5000)
python -m backend.app

# Run backend unit tests
pytest
```

To run the web frontend locally, navigate to `http://localhost:5000` in your browser.

---

### 2. Android (from `android/` directory)

```powershell
# Build and install on Android Automotive OS (AAOS emulator or head unit)
.\gradlew.bat :automotive:installDebug
adb shell am start -n dev.lammertsma.parkingblues/androidx.car.app.activity.CarAppActivity

# Build and install on Phone / Android Auto
.\gradlew.bat :app:installDebug
adb shell am start -n dev.lammertsma.parkingblues/.MainActivity

# Run Android unit tests across all modules
.\gradlew.bat testDebugUnitTest
```

---

## Architecture & Technical Deep-Dive

For detailed documentation on the clustering algorithm, Zurich parking rules, Car App Library constraints, and deployment pipelines, see [**`AGENTS.md`**](file:///c:/Users/pflam/StudioProjects/parking-blues/AGENTS.md).
