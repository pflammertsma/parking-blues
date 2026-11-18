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

### Abuse protection (anonymous API)
* **Rate limits** live in `backend/app.py` (`RateLimits`, Flask-Limiter with in-memory counters) and are tested in `tests/test_rate_limits.py`. Per-IP limits are generous because mobile carriers share IPs; the tight limit on position updates is keyed by **session id**.
* **Client IP** comes from `ProxyFix(x_for=1)`: exactly one trusted hop (the entry Cloud Run appends), so a client-supplied `X-Forwarded-For` cannot dodge limits.
* **Single-process assumption:** counters and sessions are in memory, so they are only correct while one process serves the API (Cloud Run `--max-instances=1`, one gunicorn worker). Use a shared store before scaling out.
* `SessionStore` expires idle sessions (2 h) and caps the total (5000). Clients treat the resulting 404 as "start a new session"; a `429` makes the Android repository pause live updates until `Retry-After` has passed.
* **Gotcha:** `SessionStore` defines `__len__`, so an *empty* store is falsy. Never write `store or default`; compare with `is None`. The Flask-Limiter decorators hold only a weak reference to the limiter, so `create_app` keeps a strong one in `app.extensions`.

### The web page: answer first, adjust second
* `web/` is the page for people without the app. It must be **immediately useful**: on open it shows parking around where the visitor is (browser geolocation; central Zurich while that is pending, denied or outside the data), both zones, no stay filter, no questions. Zone chips, the stay select, address search, tapping the map and dragging the pin only *correct* that answer.
* It reads one stateless endpoint, `GET /api/nearby?lat&lon&zone&stay`: ranked **areas** (same-zone runs of adjacent spots) with rules, disc mark and fee, widening the radius up to 1 km. No session is created or stored, so a page view costs nothing in the `SessionStore`. Ranking and rules stay on the server; the page only draws them. The old session/position endpoints still serve the Android clients.
* The destination is in the URL **fragment** (`#lat=..&lng=..&zone=..&stay=..`), one history entry per pick, so Back works and links carry the search without reaching any server. The location the page opens with is never written there.
* Address search goes browser -> swisstopo's `SearchServer` (open CORS, no key, WGS84 `lat`/`lon` in the results, bounded to Zurich with a LV95 `bbox`). The privacy policy says so; keep it in step if the provider changes.
* **Same look as the car map.** The page's icons are the Android vector drawables' paths copied into `web/app.js` (`ZONE_ICON_SVG` from `ic_zone_blue/white.xml`, `YOU_SVG` from `ic_location_triangle.xml`, `DESTINATION_SVG` from `ic_destination.xml`), and `orientedBoxCorners` is a port of `MapClustering.orientedBoxCorners`. The visitor is the purple arrow, rotated to the browser's heading, shown only once a position is shared and kept moving with `watchPosition`. If the artwork changes in the app, change it here too.
* The page stays **unindexed** on purpose (`noindex` is added by `scripts/publish_to_lammertsma.py`; it is for friends and family). English only.

### Parking route (EXPERIMENT)
* `POST /api/route` (`backend/route_planner.py`) plans a drive from the user's position to a chosen destination that passes the parking areas most likely to have a space, as numbered stops on a drawn route. The planner scores each area by its chance of a free space (every space assumed free with probability `P_FREE` = 10%, a guess: there is no occupancy data), then greedily adds stops that lower the *expected time until parked* (drive + walk, with a penalty if every stop is full) by at least `MIN_GAIN_S`. That is what favours dense areas and drops small clusters that are not worth a detour. All drive times come from a router, which is where the road rules come from (one-ways, banned turns, bus- and tram-only roads); the planner never models them itself.
* **Why it is an experiment, and the plan:** the road rules come from the **public OSRM demo server** (`router.project-osrm.org`; `routing.openstreetmap.de/routed-car` was 5-9 s for a 14-point table, so it is not the default). Its policy is light use, about 1 request a second, no uptime promise, so it must not carry real traffic: calls are spaced 1 s apart (`ROUTING_MIN_INTERVAL_S`), the endpoint is limited to 10/minute per IP (`RateLimits.route`) and the app calls it only on a button press. **If the experiment succeeds, migrate to a self-hosted OSRM** (an OSM extract of Zurich on its own Cloud Run service; only `ROUTING_BASE_URL` and `ROUTING_MIN_INTERVAL_S=0` change). **If the guidance proves inaccurate, switch to Google routing** by writing a Google Routes implementation of the small `Router` interface (`table`, `route`); the planner and endpoint do not change.
* **Points that are not on a road** (`route_planner.py` module docstring has the numbers): OSRM snaps every point to the nearest road with no limit (a point in the lake snaps 707 m away and still routes), so the planner reads the snap distance OSRM reports for each point. A start more than 400 m from a road is refused (`no_road_near_start`); between 40 m and 400 m the route starts on the road and the response carries `start_snap` so the app draws the gap dashed and says so; a destination may be off a road (ends at the nearest road, refused beyond 800 m); a parking area more than 40 m from a road is not offered as a stop; a start or destination on a cut-off piece of network gets the next-nearest roads tried (at most 2 each) before `not_connected`.
* **Errors are coded, not worded by the server:** `/api/route` answers `{"error", "code"}` with `routing_unavailable` (502), `routing_busy` (503), `no_road_near_start`, `no_road_near_destination`, `not_connected` (all 422) and `no_parking_nearby` (404); the message is fixed in `PUBLIC_MESSAGES`, the detail only goes to the log. The app (`ParkingSessionRepository.routeFailure`) turns the code into words and offers Retry only where trying again can help; a 404/405 with no code means the server does not have the endpoint yet (an app newer than the server), which is what a build gets until the backend is deployed.
* Known limits (say them when judging accuracy): OSM car routing cannot model lane-level bus lanes inside an otherwise open street or time-dependent restrictions, and a stop is the area's centre point snapped to the nearest road, which on a street with a parallel one-way can be the wrong side. The client sends the spots already driven past as `exclude` so they are not routed to again.
* **On the phone** the button (next to Recenter) appears once a destination is chosen (long press, Search here) and the device is inside `CoverageArea`; the route is drawn by `RouteOverlays` and summarised in a card (stops, minutes, chance). Draw order is part of the spec: the part **already driven** is dark and *beneath* the parking boxes, the part **still ahead** is bright purple and *above* them (with numbered stops), under only the destination pin and the driver's arrow. `renderParkingOverlays` places both at the right points of its sequence, and `RouteProgress` (pure, in `:car`) moves the cut as the device moves, forward only. A new destination, zone or Recenter drops the route; Recenter with a route only resumes following.
* **Testing it locally:** the debug build trusts `10.0.2.2` over plain HTTP, so run `python -m backend.app` and temporarily construct `ParkingApiClient(baseUrl = "http://10.0.2.2:5000", ...)` in `ParkingBluesApp` (never commit that). The deployed backend has no `/api/route` until it is released. Planning one route calls the public server twice, about a second apart.
* The route request sends the user's position and destination through our server to a third party; `web/privacy.html` says so. Keep it in step if the provider changes.

### Crash reporting (Firebase Crashlytics)
* **Contract in `:shared`, SDK in the platform.** `CrashReporter` (`isAvailable`, `isEnabled`) is in `:shared` commonMain and lists what an implementation may report. Android's `AndroidCrashReporter` (in `:car`) is the only implementation today; an iOS app provides its own on the Firebase iOS SDK (a `GoogleService-Info.plist` from a registered iOS app) and passes it to the same shared code. Do not let platform SDK calls leak into shared code.
* **Only a stack trace, the device model and OS, and the installation id.** Never attach location, searches, account data or where the car is parked (no custom keys, no log lines with them). The privacy policy (`web/privacy.html`, "Crash reports") and the Play Data safety form say exactly that.
* **Off in debug builds, on by default in release builds, user can switch it off** (phone About screen; the car's "More" screen). **No-op without `google-services.json`**: that file is gitignored (`android/**/google-services.json`, one copy each in `android/app/` and `android/automotive/`), the Firebase Gradle plugins are applied only when it exists, so forks and contributors build as before. Firebase project: `parking-blues`, the same project as the backend; both package names (`dev.lammertsma.parkingblues`, `.debug`) are registered in it.
* **Verify after a release change:** About, long-press the version, Developer options, "Send a test crash"; reopen the app; the report appears in the Firebase console (Crashlytics) within a few minutes. Release builds upload the R8 mapping automatically so traces are readable.

### Map tiles (CARTO key stays server-side)
* CARTO requires an API key on every tile request. A key in the web JavaScript or the APK is public, so both clients load tiles from `GET /api/tiles/light_all/{z}/{x}/{y}[@2x].png` and `backend/tiles.py` adds the key. It comes from the `CARTO_API_KEY` env var, mounted on Cloud Run from Secret Manager (`parking-blues-carto-key`) by `deploy-backend.yml`. **Never put the key (or any key) into `web/`, the Android sources or the repo.** Without the variable (a local checkout) tiles are requested unkeyed and come back watermarked.
* The proxy is deliberately narrow: style allow-list (`STYLES`), a bounding box around Zurich (`REGION`), a 1500-tile in-memory LRU cache, a 1-day `Cache-Control`, and its own per-IP limit (`RateLimits.tiles`; tile requests carry no auth header). Upstream failures become a 502 and are not cached. Tile traffic counts toward Cloud Run requests, so watch usage if the map gets busy.
* Git history still contains the old key (it was committed before the proxy). It was intentionally not rotated; if abuse of it shows up in the CARTO dashboard, rotate it, update the secret (`gcloud secrets versions add parking-blues-carto-key --data-file=-`) and redeploy.

### Optional sign-in (accounts)
* Anonymous is the default. `backend/auth.py` (Google ID token verification, our own HS256 access JWTs, rotating hashed refresh tokens, `AccountStore` with in-memory and Firestore implementations, `AbuseGuard`) is wired into `backend/app.py`. It is **off unless `GOOGLE_WEB_CLIENT_ID` and `AUTH_JWT_SECRET` are set**, so a missing configuration means fully anonymous rather than broken. Setup: `deploy/auth-setup.md`.
* The `before_request` authentication hook must be registered **before** the Flask-Limiter instance, because the limiter's key (account vs IP) depends on it.
* The Android side: `AuthRepository` (shared) refreshes tokens without UI, so the car screens keep a valid token with the phone in a pocket; `SecureTokenStore` keeps it Keystore-encrypted and out of backups. Android Auto shares the phone app's login; the standalone Automotive OS app is anonymous until it gets a `SignInTemplate` flow.
* The web page (`web/app.js`) has the same optional sign-in via Google Identity Services, using the same web client ID. The Google script is loaded lazily, only when the visitor opens the sign-in panel. The refresh token is kept in `localStorage`, the access token in memory; `api()` renews it on a 401 and falls back to anonymous if the refresh token is rejected. `https://lammertsma.dev` must be an *Authorized JavaScript origin* of that client.
* Developer options (test location) live behind a long-press on the version in **About**, in every build; they are never in the normal menu.

### Algorithmic Decisions
* **Clustering (`backend/clustering.py`):**
  * `DEFAULT_CLUSTER_EPS_M = 12.0`: Tight threshold to group continuous curb runs without chaining across street corners or intersections.
  * `cluster_score = log1p(capacity) / (1 + distance/50.0) * (1.0 + FREE_ZONE_SCORE_BONUS * free_fraction)`: Logarithmic capacity gives diminishing returns, prioritizing close proximity over distant huge parking fields. `capacity` and `free_fraction` come from `_neighborhood_capacity`, not just the cluster's own segments -- below.
  * `_neighborhood_capacity`: a cluster's score also counts same-zone-type capacity from *other* clusters within `NEIGHBORHOOD_RADIUS_M` (60m) of its centroid, at `NEIGHBORHOOD_CAPACITY_WEIGHT` (0.8). Without this, a tight real curb run that real-world gaps (driveways, a bus stop) fragment into several tiny same-zone clusters has each one scored alone, individually too small to compete with one bigger cluster nearby -- confirmed live: 8 blue clusters (1-7 spaces each, totaling 22) each lost to a single 100-space white one only ~100m closer, despite the blue stretch clearly being the better bet. `DEFAULT_CLUSTER_EPS_M` stays tight (loosening it risks the intersection-chaining regression it was tuned to fix); this is a separate, looser radius used for scoring only, not for what counts as "one cluster."
  * `FREE_ZONE_SCORE_BONUS = 0.8`: still a soft nudge, not a hard override -- a free cluster needs a comparable, not just any, distance to win (see `test_free_zone_bonus_does_not_override_a_large_proximity_difference`).
* **Heading & Directional Distance:**
  * Heading computed when movement >= 5m (`MIN_HEADING_UPDATE_DISTANCE_M`).
  * Straight-ahead candidates get up to 25% distance bonus (`DIRECTION_WEIGHT = 0.25`); candidates behind are penalized to prevent recommending awkward U-turns.
* **Retargeting Hysteresis:**
  * `LOCAL_PULL_IN_MARGIN_M = 15.0`: A newly discovered nearby spot must beat the currently targeted spot by at least 15m to trigger retargeting, preventing erratic target flickering while driving.

---

## 3. Android & Car App Library (CAL)

### Module Layout
* `android/shared/`: Kotlin Multiplatform (KMP) data models (`@Serializable`) and Ktor HTTP client (`ParkingApiClient`).
* `android/car/`: Shared Car App Library screens (`MapSearchScreen`), location helpers, clustering/icon helpers shared with the phone map, and osmdroid map renderer.
* `android/app/`: Phone module (Jetpack Compose map UI) + Android Auto entry point.
* `android/automotive/`: Android Automotive OS (AAOS) entry point (`CarAppActivity`). Shares the same `applicationId` (`dev.lammertsma.parkingblues`) as `:app`.

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
   * The launcher icon lives in `:car` and merges into both `:app` and `:automotive`. The two adaptive-icon wrappers (`ic_launcher.xml`, `ic_launcher_round.xml`) stay in `res/mipmap-anydpi-v26/` because the manifest names `@mipmap/ic_launcher`; the vector **layers** (`ic_launcher_background/foreground/monochrome.xml`) are in `res/drawable/`, which is where lint wants a `<vector>` (a vector in a mipmap folder is flagged).
   * **Sources: `assets/app icon.svg`** (Inkscape groups labelled `background` and `foreground`) **and `assets/app icon monochrome.svg`** (a group labelled `monochrome`, the layer Android 13+ tints for themed icons). After editing either run `python scripts/generate_app_icon.py`: that rewrites the adaptive layers `ic_launcher_background.xml` / `ic_launcher_foreground.xml` and copies the SVG to `web/icon.svg`. Re-render the 512 px Play icon with Inkscape (command in the script's docstring). Never hand-edit the generated XML.
6. **POI App Permissions:**
   * Declared as a POI app (`androidx.car.app.category.POI`).
   * Requires `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />` and `<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />`.
   * Must never declare `androidx.car.app.NAVIGATION_TEMPLATES` (mutually exclusive with `MAP_TEMPLATES`; rejected during review).
7. **Android Auto Manifest Meta-Data:**
   * AAOS uses `<meta-data android:name="com.android.automotive" android:resource="@xml/automotive_app_desc" />`.
   * Android Auto in `:app` requires `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />`. Missing this silently drops the app from the Android Auto launcher.
8. **Sideloaded Car App Library apps never appear in a real car:**
   * Android Auto's "Unknown sources" developer option does not apply to template apps. Real-vehicle testing needs a Google Play internal test track or Internal App Sharing; use the Desktop Head Unit otherwise (see `android/README.md`).
9. **Template validation happens at runtime:**
   * Car App Library throws in `onGetTemplate()` for invalid models, crashing the screen on every launch. Example: a `Row` inside a `PaneTemplate` cannot have a click listener (use a `ListTemplate`). Test every template change on the DHU.
10. **Phone and AAOS builds cannot be one artifact:**
    * Separate `:app` and `:automotive` bundles under one `applicationId`, uploaded to different Play tracks. `CarAppActivity` extends `FragmentActivity`, so `androidx.fragment` must stay in `:automotive` (it is excluded from `:app`).
11. **Debug builds use `applicationIdSuffix = ".debug"`:**
    * Never build class names from `context.packageName` (it no longer equals the Kotlin package); use `packageManager.getLaunchIntentForPackage` or explicit class references. adb commands for debug builds use `dev.lammertsma.parkingblues.debug/<fully.qualified.Class>`.
14. **The car app has a list of areas for Play review:**
    * Play's Automotive review rejected a map-only POI app for "fails to populate a list of spaces". Tapping the summary row opens `NearbyScreen`: the top 5 *areas* (clusters, not single spots) in the backend's own order, e.g. "Blue zone · 120 m NE" with the spaces and rules, then `AreaScreen` with **Navigate** (`CarContext.ACTION_NAVIGATE` with a `geo:` URI, a `MessageScreen` if no navigation app exists) and **Show on map**. The logic is pure and tested in `NearbyAreas.kt` / `NearbyAreasTest`. Spots have no street names (only database ids), so rows use distance, compass direction, space estimate and rules.
13. **Never touch location before the permission is answered:**
    * A fresh install has no location permission, and `LocationManager.getLastKnownLocation` then throws `SecurityException`. `MapSearchScreen` runs `lastKnownLocation()` from its constructor, i.e. before the permission dialog is answered, so an unguarded call crashes the app at launch. That is exactly what Play's Automotive review hit ("crashes at launch"). `lastKnownLocation()` now checks `hasLocationPermission()` and returns null, and callers fall back to central Zurich.
    * Your own devices and the DHU already have the permission, and `adb shell pm grant` hides it too. **Before every release, test a fresh install with nothing granted**: `adb uninstall <id>`, install the *release* APK, launch, and try both Allow and Don't allow, on a Play-less AAOS image (`Automotive_Portrait_API_34-ext9`) and a Play one (`Automotive_1024p_landscape_API_32`).
12. **Marker sizes differ per surface:**
    * `MapIcons` sizes are tuned for the projected car map; the phone passes `MapIcons.PHONE_ICON_SCALE` to render them smaller.
15. **The car map follows the vehicle, and gives up following only temporarily:**
    * `MapSearchScreen` follows the vehicle by default. A pan or zoom (`onScroll`/`onScale`) stops following and shows the **Recenter** action; following resumes on Recenter or after `MapFollowState.IDLE_RESUME_MS` (30 s) without a gesture, easing back over `RETURN_ANIMATION_MS`. The Recenter action exists only while exploring, so the action strip changes only on those two transitions (`invalidate()` is host-throttled, gotcha 1). State and timing rules are `MapFollowState` (unit-tested); the screen owns the `Handler` timer.
    * The "you" marker and the camera follow `ParkingSessionRepository.livePosition` (the latest fix, published *before* the server round trip), not the snapshot's `you` (the server's echo of an older fix). Following the echo froze the map whenever a response was slow or the server answered 429 (`position_per_session` is 90/min and the fused provider can deliver faster than 1 Hz when other apps ask for it). Do not move the marker or camera from `snapshot.you`.
    * **Test it** on the AAOS emulator with Developer options → "Use test location" (`adb emu geo fix` does not reach the fused provider, and the Play-less image has no Google Play services for it at all): drive, swipe the map (Recenter appears), wait 30 s (it glides back), press Recenter.

16. **The phone map has a bottom sheet that says what the app is for:**
    * `ParkingSheet.kt` (in `:app`) under the map: collapsed it is one line ("26 parking areas nearby · ~246 spaces", "Best bet: ..."), pulled up it lists the areas (`NearbyAreas`, shared with the car's list) with **Navigate**, and an "About these results" note (blue/white rules, fee is an estimate, the city publishes where parking is *allowed*, not what is free). Tapping a row flies the map there (`MapUiState.focus`).
    * **Outside Zurich the server does not answer empty:** a session can still hold Zurich's spots while the phone is hundreds of km away (a Berlin fix showed "668983 m SW"). So `sheetState` (pure, in `:car`, tested) counts only areas within `NEARBY_LIMIT_M` (2 km, the backend's `MAX_RADIUS_M`); with none it says "No street parking known here" and offers **Show Zurich**. Do not "fix" this by trusting an empty session.
    * **`CoverageArea` (in `:car`) is the one definition of "where the app has anything":** the parking data's bounding box plus ~2 km (47.30-47.46 N, 8.43-8.64 E; widen it if the data ever grows beyond the city). The phone map cannot be panned out of it (`setScrollableAreaLimitDouble`, min zoom 14 because at 13 a phone is wider than the box), and a device outside it gets **Zurich to browse** instead of a blank map: `MainActivity.startSearch` turns *any* search whose origin is outside into a browse of Zurich HB (so the slower launch-time search cannot overwrite it with an empty Berlin session -- that race happened), a sticky one-line banner says so, and the arrow, following and Recenter are off. Coming back inside resumes live results. The backend tile area (`REGION` in `backend/tiles.py`) is larger and unrelated: it only decides where tiles exist.
    * A "Loading map..." indicator shows until a tile exists at the map centre (polled via osmdroid's tile cache; there is no callback), with a flat placeholder colour instead of osmdroid's grid, and gives up after 8 s.
17. **Double-tap-and-drag zoom is phone-only (`DoubleTapDragZoom`):** an osmdroid overlay that claims `onDoubleTap` (osmdroid's own handler zooms in at once, leaving nothing to drag) and zooms from the second tap's drag (down = in, up = out, a quarter of the map height per level; plain double tap = one level in). The car host's `SurfaceCallback` has no such gesture. Test on an emulator with an `adb shell "input tap x y & sleep 0.2; input motionevent DOWN ..."` sequence; two separate `adb` calls are too slow to be a double tap.

    * **Long press sets the destination** (`LongPressDestination`): a search at the pressed point, the same as "Search here" but exact, with following turned off. It is `busy` while a drag-zoom holds the touch, because that touch outlasts the long-press timeout and must not also drop a pin.
    * **Mouse wheel zooms around the pointer** (`MouseWheelZoom`, wheel up = in, half a level a notch). osmdroid 6.1.20 has no wheel handling, so without it the wheel fell through to touch handling and scrolled the map north/south (seen on the emulator with a mouse). Compose delivers a wheel to its embedded view as a touch event but a plain view gets it as a generic motion event, so the handler is wired to both. Test with `adb shell input mouse scroll x y --axis VSCROLL,2`.

18. **The parked spot (phone map, Android Auto, Automotive OS):**
    * **One record, observable:** `ParkedSpotStore` (`:car`) holds it in SharedPreferences and exposes `parkedSpotFlow`; every writer (the car's "Parked here", the Android Auto watcher, the phone map, a notification) goes through `saveParkedSpot` / `clearParkedSpot`, which is why the phone screen follows them live. `ParkedSpot.assumed` and `.destination` are on-device only (never synced to the account).
    * **Android Auto disconnect assumes the spot** (`ParkedAutomation` + the pure `CarConnectionTracker`, both started from `ParkingBluesApp`, i.e. the `:app` process only): on projection connected -> not connected, if a fix was seen *during that connection* within 5 minutes, and no spot exists, the last position is saved as an **assumed** spot (zone and time read from the nearest known spot within 25 m via `ParkedSpotFactory`), with the usual notification and expiry reminder. Connecting again clears it everywhere (device, reminders, account copy). It needs the process alive at disconnect; it cannot be tested on an emulator without a real Android Auto host (the emulator's Android Auto is a stub), so it is covered by unit tests of the tracker only: **verify it on a real car or the DHU before relying on it.** The Automotive OS app has no phone to disconnect from, so opening it just clears the old spot (`ParkingCarSession`).
    * **Phone map:** the spot is a **black classic pin with a P** (`ic_parked_pin`), drawn even with no session (offline, no location yet). The destination pin is hidden when the destination *is* the car; tapping a destination pin offers "Set as parking spot". A card at the top (`ParkedCard`) offers **Walk to car** and, when the destination the driver chose is more than 50 m from the car, **Walk to destination** (Google Maps in walking mode via `google.navigation:...&mode=w`, else any maps app, else a message: never let `startActivity` throw, an emulator without a maps app crashed on it).
    * The destination is remembered by `ParkingSessionRepository.destination`, set only by searches the user chose (long press, Search here), not by the Zurich view shown to someone abroad.

19. **No toasts.** Messages to the user are in-app: a Material snackbar on the phone (`MainActivity.message(...)`, hosted by the sheet scaffold so it sits above the bottom sheet, and by the About screen; it can carry an action such as Retry) and a `MessageScreen` in the car (`:car`). Do not add `Toast` or `CarToast` calls.
20. **Overlays on the phone map stay small.** Cards over the map (`ParkedCard`, `RouteSummary`) are no wider than `CARD_MAX_WIDTH` (480 dp) however wide the screen, and the parked card is a one-row bar (pin, two short lines, walk-to-car icon, a menu for the rest), not a panel: it was a quarter of a phone screen and a bar across a tablet before. The outside-coverage notice is a centred pill. Keep new overlays to that scale.
22. **Framing the car uses per-side padding** (`ViewportFit` in `:car`, pure and tested): osmdroid's `zoomToBoundingBox` has one border for all sides, but the top of the map is covered by the parked bar and "Search here", the bottom by the sheet. A border big enough for the top squeezes the width to nothing (the pin ended up behind the button), so the parked fit uses `FIT_TOP_DP`/`FIT_BOTTOM_DP`/`FIT_SIDES_DP` and centres the points in the free area. The parked bar is a button: tapping it runs the same fit.
21. **First run with location denied** (found by smoke-testing the release build on a fresh install): the sheet says "Location is off" with a **Show Zurich** button (`SheetState.NoLocation`) instead of waiting for ever, the map starts on Zurich (`CoverageArea.CENTER_*`) instead of an arbitrary corner, a search at a place the user chose never needs the permission, and there is no "you" arrow without a real fix.

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
# Default AAOS emulator AVD: Automotive_Portrait_API_34-ext9
# Launch AAOS emulator:
emulator -avd Automotive_Portrait_API_34-ext9

# Build all modules
.\gradlew.bat assembleDebug

# Run unit tests across all modules
.\gradlew.bat testDebugUnitTest

# Install & launch Android Automotive OS (AAOS)
.\gradlew.bat :automotive:installDebug
adb shell am start -n dev.lammertsma.parkingblues.debug/androidx.car.app.activity.CarAppActivity

# Install & launch Phone App / Android Auto entry
.\gradlew.bat :app:installDebug
adb shell am start -n dev.lammertsma.parkingblues.debug/dev.lammertsma.parkingblues.MainActivity
```

---

## 5. Deployment & CI/CD

* **Cloud Run Backend:**
  * Service: `parking-blues`
  * Project: `parking-blues` (the one GCP/Firebase project for everything: Cloud Run, Firestore, Secret Manager, OAuth clients, Crashlytics)
  * Region: `europe-west1` (Belgium — chosen because Cloud Run custom domain mappings are unavailable in `europe-west6` Zurich).
  * Manual deploy script: `deploy/gcloud.sh <project-id> <billing-account-id>`
  * CI Workflow: `.github/workflows/deploy-backend.yml` automatically deploys on pushes touching `backend/`.
* **Android releases to Play:** `.github/workflows/release-android.yml`, started by a `release-<version>` tag (internal tracks only) or by hand. **Read the publication rules in `deploy/play-publish-setup.md` before releasing**: only from `main`; the tag is `release-<version>` and equals `parkingblues.version`; tags never reach production; production is a **promotion** of a build already tested on internal, done in Play Console (the workflow uploads a new bundle and a version code can only be uploaded once); fresh-install and privacy checks first.
* **Frontend Hosting:**
  * Hosted at `https://lammertsma.dev/projects/parking-blues` via Firebase Hosting in `pflammertsma/lammertsma-dev`.
  * Published via `.github/workflows/publish-frontend.yml`, which runs `scripts/publish_to_lammertsma.py`. That script copies an explicit list of files (page, `app.js`, `style.css`, `icon.svg`, `privacy.html`, `terms.html`): add any new page or asset to it or it will never be published.
  * The privacy policy and terms live in `web/` (`/projects/parking-blues/privacy`, `/terms`); keep them in step with what the app and server actually do.
* **Android Play Console Releases:**
  * `.github/workflows/release-android.yml` -- fires on a `release-<version>` tag push (internal tracks only) or `workflow_dispatch`, never on a plain branch push: the tag is the "we've decided to ship" gate. Builds `:app:bundleRelease` and `:automotive:bundleRelease` and uploads each to its own Play Console track (track names are run inputs, not hardcoded -- see the workflow's own doc comment for why: Google's December 2024 change to Automotive OS distribution means which track name is right depends on how a given listing was set up, not something to assume).
  * Needs a one-time setup of the Play API service account and CI-side signing secrets: `deploy/play-publish-setup.md`. Reuses the existing upload key in `android/keystore/` (see Release Builds in `android/README.md`), not a separate one.
  * The workflow does not bump the version itself -- that stays a deliberate commit before triggering a run. **`parkingblues.version` (`major.minor.patch`, each 0..99, in `android/gradle.properties`) is the whole version**: it is the visible name of both apps, and the codes are computed from it in `android/build.gradle.kts` as `formFactor * 1,000,000 + major * 10,000 + minor * 100 + patch` (phone 1, Automotive 2; `0.1.6` is 1000106 / 2000106, `0.2.0` is 1000200 / 2000200). Never set a name or code by hand, and never go to a lower version: Play never accepts a code twice or a lower one.
