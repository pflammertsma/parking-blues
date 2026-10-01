# Parking Blues — Android

Client applications for Android phones, Android Auto, and Android Automotive OS (AAOS), sharing core logic through Kotlin Multiplatform.

---

## Architecture & Modules

```
android/
├── shared/       # Kotlin Multiplatform core (data models, Ktor API client, repository)
├── car/          # Shared Car App Library screens (MapSearchScreen) & osmdroid map renderer
├── app/          # Phone module (Jetpack Compose) + Android Auto entry point
└── automotive/   # Android Automotive OS (AAOS) standalone app entry
```

* **Backend as Single Source of Truth:** The `shared/` module is a thin client that does not replicate search, ranking, or clustering algorithms. It communicates with the backend via Ktor and exposes a unified `StateFlow<SessionSnapshot?>`.
* **Shared Car UI:** `:app` (Android Auto via DHU) and `:automotive` (AAOS) share the exact same `CarAppService` and screen definitions located in `:car`.
* **Map Rendering:** Rendered via **osmdroid** over `SurfaceCallback` using CartoDB Positron raster tiles.

---

## Build & Run

### Android Automotive OS (AAOS)
```powershell
# Build and install on AAOS emulator (AVD must be in Park / Gear = P)
.\gradlew.bat :automotive:installDebug
adb shell am start -n com.parkingblues.app/androidx.car.app.activity.CarAppActivity
```

### Phone & Android Auto
```powershell
# Build and install phone app
.\gradlew.bat :app:installDebug
adb shell am start -n com.parkingblues.app/.MainActivity
```

To test Android Auto projected onto a car screen using the Desktop Head Unit (DHU):
1. Enable Developer Mode in Android Auto settings on the phone and select **Start head unit server**.
2. Forward the communication port: `adb forward tcp:5277 tcp:5277`
3. Launch Desktop Head Unit: `<android-sdk>/extras/google/auto/desktop-head-unit.exe`

---

## Configuration

* **Backend Endpoint:** Configured in `build.gradle.kts` via `BASE_URL`. Defaults to the Cloud Run deployment. Set to `10.0.2.2:5000` (emulator) or `localhost:5000` (with `adb reverse tcp:5000 tcp:5000`) for local backend development.
* **Default Zurich Location:** When active GPS is unavailable or outside Zurich, location provider falls back to fixed Zurich coordinates (`47.379198, 8.531307`) defined in `TestLocation.kt`.
