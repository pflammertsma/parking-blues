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
# Launch default AAOS emulator (AVD must be in Park / Gear = P)
emulator -avd Automotive_Portrait_API_34-ext9

# Build and install on AAOS emulator
.\gradlew.bat :automotive:installDebug
adb shell am start -n dev.lammertsma.parkingblues/androidx.car.app.activity.CarAppActivity
```

### Phone & Android Auto
```powershell
# Build and install phone app
.\gradlew.bat :app:installDebug
adb shell am start -n dev.lammertsma.parkingblues/.MainActivity
```

To test Android Auto projected onto a car screen using the Desktop Head Unit (DHU):
1. Enable Developer Mode in Android Auto settings on the phone and select **Start head unit server**.
2. Forward the communication port: `adb forward tcp:5277 tcp:5277`
3. Launch Desktop Head Unit: `<android-sdk>/extras/google/auto/desktop-head-unit.exe`

---

## Configuration

* **Backend Endpoint:** Defined as `DEFAULT_BASE_URL` in `ParkingApiClient.kt` (`shared/`), pointing to `https://api.parking-blues.lammertsma.dev`. Can be overridden directly via `ParkingApiClient(baseUrl = "http://localhost:5000")` for local development.
* **Default Zurich Location:** When active GPS is unavailable or outside Zurich, location provider falls back to fixed Zurich coordinates (`47.379198, 8.531307`) defined in `TestLocation.kt`.
