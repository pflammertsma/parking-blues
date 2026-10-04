# Parking Blues

A smart on-street parking assistant that guides drivers to legal parking spaces (**Blaue Zone** and **Weisse Zone**) in Zurich. Available as a web MVP, on Android phones, and in-car via Android Auto and Android Automotive OS (AAOS).

---

## The Problem & How It Works

Zurich does not publish real-time occupancy data for on-street parking spaces. Rather than guessing randomly or pointlessly navigating to a single spot that might be full:

1. **Select Zone:** Choose **Blue zones** (free with Swiss parking disc), **White zones** (metered), or **Both**.
2. **Smart Cluster Search:** The app locates high-density clusters of eligible parking spots close to your destination, factoring in arrival times, statutory duration rules, and street capacity.
3. **Automatic Pass-By Detection:** When you drive past a targeted spot without stopping, the app infers the spot is occupied and seamlessly reroutes to the next best spot in the cluster.
4. **Autonomous Radius Expansion:** If nearby spots are exhausted, the search radius automatically widens to surrounding streets.
5. **Plan Ahead:** On the phone, pan the map anywhere and tap **Search here** to browse parking away from your current position; **Recenter** returns to live results.
6. **Remember Your Car:** Tap **Parked here** in the car to save the spot. The phone then shows where you parked and sends an expiry reminder for blue-zone limits.

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
# Phone / Android Auto
.\gradlew.bat :app:installDebug
adb shell am start -n dev.lammertsma.parkingblues.debug/dev.lammertsma.parkingblues.MainActivity

# Android Automotive OS (AAOS emulator or head unit)
.\gradlew.bat :automotive:installDebug
adb shell am start -n dev.lammertsma.parkingblues.debug/androidx.car.app.activity.CarAppActivity

# Unit tests across all modules
.\gradlew.bat testDebugUnitTest
```

#### Testing Android Auto with the Desktop Head Unit (DHU)

A debug build installed with `adb` **will not appear on a real car's Android Auto screen**: the "Unknown sources" developer option does not apply to Car App Library apps. Test on the DHU instead (or distribute through a Google Play internal test track).

1. Install the phone app: `.\gradlew.bat :app:installDebug`.
2. On the phone, open **Android Auto** settings, tap **Version** about 10 times to enable developer mode, then in the ⋮ menu choose **Start head unit server**. Keep the phone **unlocked** and connected to the PC by USB.
3. Forward the port: `adb forward tcp:5277 tcp:5277`
4. Start the DHU once: `<android-sdk>\extras\google\auto\desktop-head-unit.exe` (install it via SDK Manager → SDK Tools → *Android Auto Desktop Head Unit Emulator*). Accept any terms prompt on the phone.
5. Open **Parking Blues** from the DHU launcher.

If the DHU sits on "Waiting for your phone", or Android Auto crashes with "Already connected", see [`android/README.md`](android/README.md#troubleshooting-the-dhu).

#### Release builds

```powershell
.\gradlew.bat :app:bundleRelease :automotive:bundleRelease
```

Produces signed bundles for the Play Console (`app/build/outputs/bundle/release/app-release.aab` and `automotive/build/outputs/bundle/release/automotive-release.aab`). Signing details, the keystore and Play track notes are in [`android/README.md`](android/README.md#release-builds).

---

## Architecture & Technical Deep-Dive

For detailed documentation on the clustering algorithm, Zurich parking rules, Car App Library constraints, and deployment pipelines, see [**`AGENTS.md`**](AGENTS.md).
