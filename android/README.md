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

Debug builds install as **`dev.lammertsma.parkingblues.debug`** and are labelled *Parking Blues Dev*, so they sit next to a release build instead of clashing with its signature. Release builds use `dev.lammertsma.parkingblues`.

### Android Automotive OS (AAOS)
```powershell
# Launch default AAOS emulator (AVD must be in Park / Gear = P)
emulator -avd Automotive_Portrait_API_34-ext9

# Build and install on AAOS emulator
.\gradlew.bat :automotive:installDebug
adb shell am start -n dev.lammertsma.parkingblues.debug/androidx.car.app.activity.CarAppActivity
```

### Phone & Android Auto
```powershell
# Build and install phone app
.\gradlew.bat :app:installDebug
adb shell am start -n dev.lammertsma.parkingblues.debug/dev.lammertsma.parkingblues.MainActivity
```

### Testing Android Auto with the Desktop Head Unit (DHU)

> **A sideloaded debug build never shows up on a real car.** Google's testing docs state that the Android Auto "Unknown sources" developer option "doesn't apply to apps built using the Android for Cars App Library". To try the app in an actual vehicle it must be installed from Google Play (an internal test track or Internal App Sharing; no review needed). Use the DHU for everything else.

**One-time setup**

1. Install the DHU: Android Studio → SDK Manager → SDK Tools → *Android Auto Desktop Head Unit Emulator* (installs to `<android-sdk>\extras\google\auto\`).
2. On the phone, open **Android Auto** settings and tap **Version** about 10 times to enable developer mode.
3. In Android Auto settings → **Previously connected cars**, make sure **Add new cars to Android Auto** is on.

**Each session**

1. Install the phone app: `.\gradlew.bat :app:installDebug`.
2. Connect the phone to the PC by USB and **unlock it; keep the screen on** (the phone screen locking after 30 s will drop the session).
3. In Android Auto, open the ⋮ menu → **Start head unit server**. A notification confirms it is running.
4. Forward the port: `adb forward tcp:5277 tcp:5277`
5. Start the DHU **once**. From PowerShell:
   ```powershell
   $dir = "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto"
   Start-Process "$dir\desktop-head-unit.exe" -WorkingDirectory $dir
   ```
   On first connection, accept the terms prompt on the phone.
6. Open **Parking Blues** from the DHU launcher. Use **Test drive** (debug builds only) to move the simulated position into Zurich if you are elsewhere.

#### Troubleshooting the DHU

* **"Waiting for your phone":** the phone's head unit server is not answering. Stop and restart **Start head unit server**, confirm the phone is unlocked, and check the forward with `adb forward --list`.
* **Android Auto crashes with `IllegalStateException: Already connected`:** stale connections piled up from launching the DHU repeatedly. Run `adb shell am force-stop com.google.android.projection.gearhead`, reopen Android Auto, **Start head unit server** again, then start the DHU exactly once.
* **App crashes the moment it opens on the DHU:** read the stack trace with `adb logcat -d | findstr FATAL`. Car App Library rejects invalid templates at runtime (for example, click listeners on rows inside a `PaneTemplate`).
* **Is Android Auto accepting the app?** During a DHU session, `adb logcat | findstr CAR.VALIDATOR` shows which packages are allowed or denied. The messages only appear while a session is running. Also check that the service resolves: `adb shell cmd package query-services --brief -a androidx.car.app.CarAppService`.
* The phone's default log buffer rotates in minutes; enlarge it with `adb logcat -G 16M` before reproducing a problem (resets on reboot).

---

## Release Builds

```powershell
.\gradlew.bat :app:bundleRelease :automotive:bundleRelease
```

| Artifact | Output | Play Console |
|---|---|---|
| Phone + Android Auto | `app/build/outputs/bundle/release/app-release.aab` | Normal tracks |
| Android Automotive OS | `automotive/build/outputs/bundle/release/automotive-release.aab` | Dedicated *Automotive OS* track |

Both share one `applicationId` (`dev.lammertsma.parkingblues`) and therefore one store listing, but a single artifact cannot serve both: the AAOS build needs the `android.hardware.type.automotive` feature, a `CarAppActivity` launcher and `app-automotive`. See Google's [Automotive OS guide](https://developer.android.com/training/cars/apps/automotive-os).

* **Signing:** `android/keystore/keystore.properties` and `android/keystore/parking-blues-release.jks` (both git-ignored). Back them up together; they are the upload key. Signing is wired through the `parkingblues.release-signing` convention plugin in `build-logic/`; without the properties file, release builds are left unsigned instead of failing. Enroll in **Play App Signing** when creating the listing so a lost upload key can be reset.
* **Minification:** R8 and resource shrinking are on for release (`proguard-rules.pro` in each app module). Verify a release build on a device before shipping; shrinking can break reflection-based code without any build error.
* **Version codes:** Play requires a unique code per uploaded artifact, so the phone and Automotive bundles cannot share one. Bump `parkingblues.release` in `gradle.properties` for every upload. Each form factor has its own block of a million codes so they can never collide: phone = `1,000,000 + release`, Automotive = `2,000,000 + release` (release 1 gives 1,000,001 and 2,000,001).
* Debug and release builds have different application IDs (`.debug` suffix), so both can be installed at once. In Android Auto they appear as two apps (*Parking Blues* and *Parking Blues Dev*).
* **Android Studio and the command line:** running Gradle from both at once causes file-lock failures (for example in `lint-cache`).

---

## Configuration

* **Backend Endpoint:** Defined as `DEFAULT_BASE_URL` in `ParkingApiClient.kt` (`shared/`), pointing to `https://api.parking-blues.lammertsma.dev`. Can be overridden directly via `ParkingApiClient(baseUrl = "http://localhost:5000")` for local development.
* **Default Zurich Location:** When active GPS is unavailable or outside Zurich, location provider falls back to fixed Zurich coordinates (`47.379198, 8.531307`) defined in `TestLocation.kt`.
