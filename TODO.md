# TODO

Open work, grouped by area. Tick items off (or delete them) as they land.

## Phone UI: make it feel finished

### Top of the screen
- [ ] **Replace the solid blue app bar with floating controls over an edge-to-edge map.** Transparent status bar; a rounded, softly shadowed pill at the top holding the app mark, the active zone chip and the overflow menu. Frees ~12% of the screen and stops it looking like the Material template.
- [ ] **Make the bar informative.** Show live state ("246 spaces within 300 m") instead of the static title "Parking Blues".
- [ ] **Show the active zone** (small "Both zones" chip) so users can tell what is hidden. The zone picker itself stays in the overflow submenu.

### Map controls
- [ ] **Restyle or remove the zoom buttons.** osmdroid's default square white −/+ buttons don't match Material and appear after any touch. Either hide them (pinch-to-zoom only) or replace them with Material-styled buttons grouped with Recenter.
- [ ] **Make "Search here" easy to see.** The pale lavender pill disappears against the light map. Use a filled brand-blue button with white text and a stronger elevation (aim for at least 3:1 contrast against the map tiles in light and dark).
- [ ] Make Recenter match: same elevation and colour treatment as Search here.

### Content
- [ ] **Bottom sheet** with the live summary (spaces nearby, nearest cluster and distance, Blue time limit or White fare estimate) and a prominent **Parked here** button. The phone cannot save a spot today; only the car can.
- [ ] **Tappable clusters.** Tap a marker for a card: zone type, number of spaces, distance, legal-until or estimated CHF/h, and a Navigate button that opens Google Maps. (Marker info windows are disabled today, so taps do nothing.)
- [ ] **Richer parked-car screen:** mini-map with the walking route, countdown ring for the time limit, big "Navigate to my car".

### Map markers
- [ ] **Distinguish Blue and White zones.** Solid blue tile for blue zones, white tile with blue outline for white zones, plus a small legend.
- [ ] **Count badges instead of stacked tiles.** Overlapping "P" tiles read as clutter; show one badge per cluster with the count.
- [ ] **Standard "you" marker:** blue dot with a heading cone instead of the purple triangle.
- [ ] **Fix the destination pin.** The black arrow stub shows on top of the "you" marker when nothing has been searched elsewhere. Hide it until the user searches away from their position, and use a proper pin.
- [ ] **Dark map style** when the system is in dark mode (the tile source is light-only).

### States, onboarding, trust
- [ ] **Loading, empty and error states.** Progress while searching; a friendly "No parking found here. Our data covers Zurich." card; clear offline and error messaging.
- [ ] **Permission rationale screen** before the system dialogs (location, notifications), and a clear screen when permission is refused.
- [ ] **About / Privacy / Data sources / Feedback** entry in the overflow menu (Play also requires a privacy-policy link).
- [ ] **One visual language:** align the app bar and shapes with the icon and feature graphic (same blues, 16 dp rounded shapes, logo mark).
- [ ] Accessibility pass: content descriptions for markers and map controls, touch targets, TalkBack, large text.

## Release and Play Store
- [ ] **Hide "Use test location" in release builds.** It currently ships in the phone overflow menu.
- [ ] **Verify the minified release build end to end** on a device (map, search, parked-spot reminder, notifications, Android Auto). Only startup and the screenshot flows have been checked.
- [ ] **Automotive OS screenshots** for the Automotive form factor, plus finishing its form-factor tasks (review policy).
- [ ] Privacy policy URL, Data safety form, content rating, and the remaining store-listing sections in Play Console.
- [ ] Test in a real car through the Play internal test track (sideloaded debug builds never appear in Android Auto).
- [ ] **Baseline profiles:** add a `:baselineprofile` module (plugin and benchmark 1.5.0 work with AGP 9.4.1) and generate a profile on a device.
- [ ] Add the dedicated-track Play Console steps and the version-code scheme to the README so they are not lost.
- [ ] Commit `assets/play-store-icon-512.png`, `assets/play-store-feature-graphic-1024x500.png` and `assets/play-screenshots/`.

## Car (Android Auto / Automotive OS)
- [ ] Confirm on the DHU that the icon-only Recenter button and the debug-only ⋮ button fit the action strip, and that Developer options and the test-location toggle work.
- [ ] Verify the heading and position smoothing on a real drive (arrow follows travel direction, no hopping).
- [ ] Capture and review the car screen in light and dark.

## Tests and tooling
- [ ] Unit tests for `ParkedSpotStore`, reminder scheduling, and the phone follow/browse logic. There are no UI or instrumented tests.
- [ ] Add `pytest-cov` to a dev requirements file (backend coverage is 97% today).
- [ ] Remove the `android.newDsl=false` and `android.builtInKotlin=false` opt-outs: needs the `kotlin-android` plugin dropped and `:shared` moved to AGP's Android-KMP library plugin. Required before AGP 10.
- [ ] Fix the remaining deprecation warnings (osmdroid `Polygon` colour setters, `setBuiltInZoomControls`).

## Kotlin Multiplatform / iOS and CarPlay (not now)
- [ ] Move `MapClustering` into `commonMain` with plain lat/lon types (no osmdroid, no `java.lang.Math`).
- [ ] Put parked-spot storage, reminder timing, last zone and location behind interfaces in `:shared`; keep SharedPreferences and WorkManager as the Android implementation.
- [ ] Extract the shared "where do I search from" orchestration (duplicated in `MainActivity` and `MapSearchScreen`) into a controller in `:shared`.
- [ ] Add SKIE or callback helpers so Swift can observe `StateFlow`.
- [ ] Static iOS framework / XCFramework packaging, and a macOS CI job that compiles `iosMain` and runs the iOS simulator tests.
- [ ] Rename `:car` (it now holds general Android code, not only car code).
