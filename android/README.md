# Parking Blues — Android

First version of the Android client (milestone 6, README §11), built with
Kotlin Multiplatform so the phone UI, the Android Auto / Android
Automotive OS (AAOS) car UI, and a later iOS client can share the same
data models and API client without duplicating logic.

## Module layout

```
android/
  shared/       Kotlin Multiplatform core (commonMain/androidMain/iosMain)
  car/          Car screens + location helpers, shared by app/ and automotive/
  app/          Phone module (Jetpack Compose) + Android Auto entry point
  automotive/   Android Automotive OS (AAOS) entry point
```

### `shared/` — thin client, not a reimplementation

The backend (`backend/session.py`) is the single source of truth for the
algorithm: clustering, per-segment approach/depart rejection tracking,
cluster-scoped retargeting, continuous local-cluster pull-in, radius
expansion. `shared/` does **not** reimplement any of that in Kotlin —
doing so would mean two algorithms to keep in sync, and would silently
regress every fix made against the web MVP. See README §5.2 for the
architecture decision this follows.

What's actually here:
- `model/` — `@Serializable` DTOs (`ParkingSegment`, `SessionSnapshot`,
  `ZoneType`, `ZoneFilter`) mirroring `segment_json()` / `session_json()`
  in `backend/app.py` field-for-field, including which fields are
  zone-specific (`legalUntil` blue-only, `estimatedFeeChfPerHour`
  white-only).
- `api/ParkingApiClient.kt` — Ktor client wrapping the same five JSON
  endpoints `web/app.js` calls: create session, get session, update
  position, reject, confirm, expand radius.
- `ParkingSessionRepository.kt` — a `StateFlow<SessionSnapshot?>` +
  `StateFlow<String?>` (error) holder shared by both the phone and car
  screens, so they always observe the same state. Every action
  (`startSearch`, `updatePosition`, `rejectCurrent`, `confirmCurrent`,
  `expandRadius`) just POSTs and replaces the snapshot with whatever the
  server returns — the on-device equivalent of what dragging the "you"
  marker does in the web app.

iOS targets (`iosArm64`/`iosSimulatorArm64`/`iosX64`) are declared but
not built out yet — structurally ready for a later CarPlay client, no
iOS app exists in this repo yet.

### `car/` — the actual car screens, shared by both platforms

Android Auto (phone-projected, via `app/`) and Android Automotive OS
(native, via `automotive/`) run the *exact same* `CarAppService`/
`Session`/`Screen` code — the Car App Library docs are explicit that one
codebase serves both. Only the manifest wiring differs per platform (see
below), so that part stays in `app/`/`automotive/`; this module is just
the code, plus the location helpers both platforms need:

- `car/ParkingCarAppService.kt` / `car/ParkingCarSession.kt` — entry
  point; requests location permission via `carContext.requestPermissions`
  (the car-host way, since a normal runtime-permission dialog can't be
  shown here) and feeds continuous GPS into the shared repository.
- `car/ZoneSelectScreen.kt` — `ListTemplate`, one row per `ZoneFilter`.
- `car/MapSearchScreen.kt` — the active search screen: a **self-drawn**
  map (Maps SDK for Android, via `AppManager.setSurfaceCallback` + a
  `VirtualDisplay`/`Presentation` hosting a `MapView`) rendered through
  `MapWithContentTemplate`, with a full-width `ListTemplate` of ranked
  candidates alongside it. Draws distinct markers `PlaceListMapTemplate`
  can't: a "you" pin, the destination pin, dimmed rejected spots, and the
  current target highlighted — matching `web/app.js`'s map exactly (see
  `SessionSnapshot.you`/`.origin`/`.rejected`). **Confirmed working
  end-to-end on a real phone via Android Auto/DHU**; does *not* currently
  work on the AAOS emulator (see AAOS notes below) — that's an emulator
  environment limitation, not a code issue.
- `car/SearchScreen.kt` — the original, simpler search screen
  (`PlaceListMapTemplate`, the host's own rendered map) kept as a working
  fallback/reference; not wired into `ParkingCarSession`/`ZoneSelectScreen`
  right now, `MapSearchScreen` is.
- `location/LocationSource.kt` — `FusedLocationProviderClient` wrapper,
  continuous (`locationUpdates`) and one-shot (`lastKnownLocation`).
- `location/TestLocation.kt` — see "Test location" under Config below.

`MapWithContentTemplate`/`MapController` (`androidx.car.app.navigation.model`)
are `@ExperimentalCarApi` in Car App Library 1.4.0 — opted into
deliberately in `MapSearchScreen.kt` (`androidx.annotation.OptIn`, *not*
Kotlin's own `@OptIn`/`@file:OptIn`, which silently no-ops here since this
API uses AndroidX's older Java-based opt-in annotation, not Kotlin's).
Re-check this API's status before relying on it in anything shipped.

### `app/` — phone module + Android Auto

Plain Compose UI: zone radio buttons, a "Find parking" button, a status
line showing the current target/error, a "Use test location" switch (see
Config). Also the **Android Auto entry point**: a head unit/DHU binds to
the `CarAppService` declared in this module's manifest, discovered via
this normal, Play-Store-installable app. Unlike AAOS, Android Auto
doesn't need its own launcher `Activity` or `uses-feature` — `MainActivity`
already satisfies "the app has a normal launcher."

### `automotive/` — Android Automotive OS (AAOS) module

**Why a separate Gradle module, not a shared one with `app/`:** Google's
own recommended structure (developer.android.com/training/cars/apps/
library/set-up-project) is separate phone vs. automotive modules sharing
a common core, not product flavors. `automotive/build.gradle.kts`
deliberately sets the **same `applicationId`** as `app/`
(`com.parkingblues.app`) — Google Play publishes a phone build and an
AAOS build as two APKs/AABs under one listing, sharing a signing key, per
developer.android.com/training/cars/apps/automotive-os.

Depends on `androidx.car.app:app` (via `car/`, transitively) **and**
`androidx.car.app:app-automotive` — the latter is a genuinely separate
artifact from the former, and is where
`androidx.car.app.activity.CarAppActivity` (the required AAOS launcher
entry point) actually lives. This was confirmed by inspecting both AARs
directly (`app-1.4.0.aar` does *not* contain `CarAppActivity`;
`app-automotive-1.4.0.aar` does) after an earlier draft of this module
assumed it was in `app` and would have shipped a manifest that couldn't
launch.

### Declared as a POI app, not a navigation app

A deliberate correction made early on: the app ranks and shows candidate
spots, it doesn't turn-by-turn route to them. Concretely, in **both**
`app/src/main/AndroidManifest.xml` and
`automotive/src/main/AndroidManifest.xml`:
- `ParkingCarAppService`'s intent-filter category is
  `androidx.car.app.category.POI`, not `...category.NAVIGATION`.
- `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />` is
  **mandatory** for `PlaceListMapTemplate`/`MapWithContentTemplate` and is
  mutually exclusive with `androidx.car.app.NAVIGATION_TEMPLATES` —
  Google's review rejects apps declaring both. Omitting it crashes at
  runtime with `SecurityException: The car app does not have a required
  permission: ...MAP_TEMPLATES`, not a build-time or manifest-merge error,
  so this is easy to miss until you actually launch the app on-device.
- `<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />` is
  additionally required for `MapSearchScreen`'s self-drawn map (any
  `AppManager.setSurfaceCallback` usage), on top of `MAP_TEMPLATES`.
- Each row in a `PlaceListMapTemplate`/`MapWithContentTemplate` list that
  carries a `Place` must have a `DistanceSpan` (a `SpannableString` span
  over a placeholder character, on the title or one of the row's texts)
  or the template throws `IllegalArgumentException: All non-browsable
  rows must have a distance span attached...` when built. `SearchScreen.kt`
  does this; `MapSearchScreen.kt`'s list doesn't need it since its rows
  aren't `Place`-backed (the map markers are drawn separately).

### Android Auto's *own*, separate template declaration

The one that cost the most time to find: Android Auto (`app/`) needs its
**own** manifest declaration, distinct from AAOS's, even though both
ultimately point at the same `automotive_app_desc.xml` content
(`<uses name="template" />`):

- AAOS (`automotive/AndroidManifest.xml`): `<meta-data
  android:name="com.android.automotive" android:resource="@xml/automotive_app_desc" />`
- Android Auto (`app/AndroidManifest.xml`): `<meta-data
  android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />`
  — **a different key**, needing its **own** copy of the XML file at
  `app/src/main/res/xml/automotive_app_desc.xml` (a library module like
  `car/` can't hold an Android resource file two application modules both
  reference by `@xml/...`, so it's duplicated, not shared).

Missing the Android Auto one is **silent**: no crash, no error toast —
the app simply never appears in Android Auto's launcher, not even in its
"Customize launcher" list of installed-but-hidden apps. The only trace is
in the *phone's* logcat (not the app's own, and not visible from the DHU
window): `CAR.VALIDATOR: Package DENIED; Uses for TEMPLATE not defined
[com.parkingblues.app]`. `adb logcat | grep CAR.VALIDATOR` is the fastest
way to confirm this specific failure mode again if it recurs.

## Config

- `minSdk 28` (`app`) / `29` (`automotive`, matching AAOS's own baseline),
  `compileSdk`/`targetSdk 35`.
- `BASE_URL` (`BuildConfig`, per module): both debug and release point at
  the deployed Cloud Run backend
  (`https://parking-blues-794638973209.europe-west1.run.app` — note
  `europe-west1`, not the `europe-west6` the top-level README's deploy
  section originally assumed; see `deploy/gcloud.sh`'s comment). Real
  Android Auto/DHU testing is on a USB-connected phone, and that USB link
  resets often enough (observed repeatedly in one session) to make `adb
  reverse`-to-a-local-backend unreliable, so pointing at the real deployed
  backend is simpler and just as representative (same live Zurich data).
  To debug against a local `python -m backend.app` instead, temporarily
  edit the `debug` block in the relevant module's `build.gradle.kts` back
  to `10.0.2.2:5000` (AAOS emulator only) or `localhost:5000` + `adb
  reverse tcp:5000 tcp:5000` (real device/phone emulator) — a debug-only
  `network_security_config.xml` already permits cleartext to both hosts
  for when you do this.
- **Test location**: `car/location/TestLocation.kt` defaults every module
  to a fixed Zurich point (`47.379198, 8.531307` — the same
  `DEFAULT_ORIGIN` `web/app.js` uses) instead of real GPS, since a real
  phone's actual GPS is essentially never in Zurich during development.
  `app/`'s phone UI has a "Use test location" switch to turn this off in
  favor of real GPS (also affects Android Auto, since it runs in the same
  process); `automotive/` has no UI for it yet and stays on the default.
  Per-install (`SharedPreferences`), so `app/` and `automotive/` each
  remember their own choice independently.
- **Maps SDK for Android API key**: `local.properties` (gitignored) holds
  `mapsApiKey=...`, injected into both `app/` and `automotive/` manifests
  via `manifestPlaceholders`. Restricted (GCP Console → APIs & Services →
  Credentials, or `gcloud services api-keys create`) to the Maps SDK for
  Android API only, plus `com.parkingblues.app` + the debug keystore's
  SHA-1 fingerprint (`keytool -list -v -keystore ~/.android/debug.keystore
  -alias androiddebugkey -storepass android -keypass android`). A release
  build needs its own key restricted to the release signing fingerprint
  instead of this one. The `maps-android-backend.googleapis.com` API must
  be enabled on the GCP project (`gcloud services enable
  maps-android-backend.googleapis.com`).

## Build & run

```bash
cd android
./gradlew :shared:build :car:build :app:assembleDebug :automotive:assembleDebug
```

### Android Automotive OS (AAOS emulator)

```bash
./gradlew :automotive:installDebug
adb shell am force-stop com.parkingblues.app   # if reinstalling over a running instance
```

On an AAOS AVD (e.g. `Automotive_Portrait_API_34-ext9` or
`Automotive_1024p_landscape_API_32`). See "AAOS testing notes" below —
`MapSearchScreen`'s self-drawn map does not currently work here.

### Android Auto (real phone + Desktop Head Unit)

No AAOS AVD needed — Car App Library code is identical for Android Auto
and AAOS, so a real (or emulated) phone plus the Desktop Head Unit (DHU)
tool exercises the same `car/` code.

1. Install DHU (one-time): `sdkmanager "extras;google;auto"` — installs
   to `<sdk>/extras/google/auto/desktop-head-unit.exe`.
2. On the phone: open the **Android Auto** app → tap the version number
   repeatedly until "Developer mode enabled" → ⋮ menu → **Developer
   settings** → enable **"Unknown sources"** (lets our unpublished app
   show up) → tap **"Start head unit server."**
3. `adb forward tcp:5277 tcp:5277` (DHU's fixed port).
4. `./gradlew :app:installDebug`, then install onto the phone:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
5. Run DHU **in your own interactive terminal, not through any
   non-interactive tool/script** — it's a console app that reads an
   interactive stdin prompt after connecting, and exits immediately if
   stdin isn't a real terminal (this bit repeatedly when scripted):
   ```
   cd <sdk>/extras/google/auto
   ./desktop-head-unit.exe
   ```
6. If the DHU window shows "Waiting for phone...", open the Android Auto
   app on the phone once to trigger the handoff.

**The phone's USB connection resets itself often enough in practice** to
silently drop both `adb forward tcp:5277 tcp:5277` (DHU) and any `adb
reverse` you've set up — if DHU stops responding or the app can't reach
its backend, check `adb forward --list` / `adb reverse --list` and
re-add whatever's missing, no need to restart the phone itself.

## Testing notes / gotchas

### AAOS (emulator)

- **The vehicle must be in Park.** `CarPackageManagerService` blocks any
  third-party app that isn't on a hardcoded system allowlist (Google
  Maps, System UI, car settings, ...) from launching while the driving
  state is `IDLING` or `MOVING`, regardless of a correctly-declared
  `distractionOptimized` manifest meta-data — this is intended platform
  safety behavior, not an app defect. In the emulator: Extended Controls
  → Car sensor data → Gear = **P (Park)**. `adb shell dumpsys car_service
  inject-vhal-event 0x11400400 4` (VHAL `GEAR_SELECTION`, value `4` =
  `PARK`) was tried as a scriptable alternative but did not reliably
  change `Current Driving State` on the AVD tested here — the Extended
  Controls GUI is the reliable path.
- The AVD system image matters: `adb shell cmd car_service help` throwing
  `SecurityException: ... requires non-user build` confirms a locked-down
  "user" (production-signed) image with no shell-level
  distraction-optimization bypass — expected, not a setup bug.
- **`MapSearchScreen`'s self-drawn map does not currently work on the AAOS
  emulator tested here**, even fully signed into a Google account:
  `MapsInitializer` fails with `DynamiteModule$LoadingException: No
  acceptable module com.google.android.gms.maps_dynamite found. Local
  version is 0 and remote version is 0` / `ProviderHelper: Unknown
  dynamite feature maps_dynamite` — Play Store on this AVD doesn't carry
  the Maps rendering module in its catalog at all, not a download/timing
  issue (confirmed by retrying after a full Play-services app update).
  Everything upstream of that (the `Surface`/`VirtualDisplay`/
  `Presentation` plumbing, the API key, `MapWithContentTemplate` itself)
  is confirmed working, since the exact same code renders a real map on
  Android Auto via a physical phone. Use `SearchScreen`
  (`PlaceListMapTemplate`, no custom rendering) for AAOS-emulator testing
  instead, or test on real AAOS hardware if/when available.
- The recurring build failure this module hit repeatedly: `--` inside an
  XML comment (`<!-- ... -- ... -->`) is invalid per the XML spec and
  fails AAPT2 with `The string "--" is not permitted within comments`.
  Grep for it across `**/src/main/**/*.xml` if a build fails on
  `processDebugMainManifest` or a resource-compile step with no other
  obvious cause.

### Android Auto (real device)

- See "Android Auto's *own*, separate template declaration" above — by
  far the easiest way to lose an hour here is the app installing fine,
  running fine standalone, and simply never showing up in Android Auto's
  launcher with zero on-screen explanation.
- `PlaceListMapTemplate` requires every non-browsable row to carry a
  `DistanceSpan`; `MapWithContentTemplate` requires the
  `androidx.annotation.OptIn` opt-in described above; both were found by
  reading the actual crash logcat (`adb logcat | grep AndroidRuntime`),
  not by guessing from documentation alone.
