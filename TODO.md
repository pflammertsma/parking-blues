# TODO

Open work, grouped by area. Tick items off (or delete them) as they land.

## Phone UI: make it feel finished

- [x] **Theme colours:** every Material 3 role is now defined (menus, buttons and containers no longer fall back to the baseline purple); contrast-checked light and dark palettes in `Theme.kt`.

### Top of the screen
- [ ] **Replace the solid blue app bar with floating controls over an edge-to-edge map.** Transparent status bar; a rounded, softly shadowed pill at the top holding the app mark, the active zone chip and the overflow menu. Frees ~12% of the screen and stops it looking like the Material template.
- [ ] **Make the bar informative.** Show live state ("246 spaces within 300 m") instead of the static title "Parking Blues".
- [ ] **Show the active zone** (small "Both zones" chip) so users can tell what is hidden. The zone picker itself stays in the overflow submenu.

### Map controls
- [x] **Restyle or remove the zoom buttons.** osmdroid's default square white −/+ buttons don't match Material and appear after any touch. Either hide them (pinch-to-zoom only) or replace them with Material-styled buttons grouped with Recenter.
- [x] **Make "Search here" easy to see.** The pale lavender pill disappears against the light map. Use a filled brand-blue button with white text and a stronger elevation (aim for at least 3:1 contrast against the map tiles in light and dark).
- [x] Make Recenter match: same elevation and colour treatment as Search here.

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
- [x] **Hide "Use test location" in release builds.** It currently ships in the phone overflow menu.
- [ ] **Verify the minified release build end to end** on a device (map, search, parked-spot reminder, notifications, Android Auto). Only startup and the screenshot flows have been checked.
- [ ] **Automotive OS screenshots** for the Automotive form factor, plus finishing its form-factor tasks (review policy).
- [ ] Privacy policy URL, Data safety form, content rating, and the remaining store-listing sections in Play Console.
- [ ] Test in a real car through the Play internal test track (sideloaded debug builds never appear in Android Auto).
- [ ] **Baseline profiles:** add a `:baselineprofile` module (plugin and benchmark 1.5.0 work with AGP 9.4.1) and generate a profile on a device.
- [ ] Add the dedicated-track Play Console steps and the version-code scheme to the README so they are not lost.
- [ ] Commit `assets/play-store-icon-512.png`, `assets/play-store-feature-graphic-1024x500.png` and `assets/play-screenshots/`.

## Website, legal and abuse protection

Not legal advice: have the policy and terms reviewed before relying on them.

### Policy pages on lammertsma.dev
- [x] `web/privacy.html` and `web/terms.html` written (acceptable-use clause, blocking rights, Swiss law), linked from the project page footer and each other. `scripts/publish_to_lammertsma.py` now publishes them with the other web files; they appear at `/projects/parking-blues/privacy` and `/projects/parking-blues/terms` (the site uses `cleanUrls`).
- [ ] **Publish:** merge to `main` so `publish-frontend.yml` syncs them, then open both URLs on lammertsma.dev.
- [ ] Put the privacy URL in the Play Console (App content, privacy policy) and link both pages from the app's overflow menu (About).
- [ ] **Verify what the policy claims** before relying on it: Cloud Run request logs really are kept ~30 days (Cloud Logging default bucket), and no log line contains coordinates.
- [ ] Have a lawyer review both pages; add a postal address if you want one in the policy (Play also shows the developer's address in the listing).
- [ ] Update the Play **Data safety** form to match (precise location collected and processed on a server, not stored; not shared for ads).
- [ ] Re-read both pages whenever behaviour changes (accounts, analytics, new third parties).

### Rate limiting for anonymous API calls
Done: per-IP limits on session creation, per-session limits on position updates with a generous per-IP ceiling, per-IP limits on the other calls and static files, `429` JSON with `Retry-After`, idle expiry (2 h) and a cap (5000) on in-memory sessions, 8 KB request bodies, validated lat/lon, and the Android client backs off on `429` (see `backend/app.py` `RateLimits` and `tests/test_rate_limits.py`).
- [ ] **Tune the numbers from real traffic** (starting values: 20 sessions/min and 300/day per IP, 90 position updates/min per session, 60 other calls/min per IP). Watch for legitimate users behind shared mobile-carrier IPs being limited.
- [ ] Check what Cloud Run actually sends in `X-Forwarded-For` for the `api.parking-blues.lammertsma.dev` domain mapping so the limiter sees the real client IP (the code trusts exactly one hop).
- [ ] Confirm gunicorn runs a single worker on Cloud Run: the in-memory counters are per process. Move counters (and sessions) to Redis/Firestore before scaling past one instance or worker.
- [ ] Tighten CORS if more origins are not needed; add a Cloud Billing budget alert and a Cloud Run request-rate alert.
- [ ] The web MVP shows the raw `rate limit exceeded` message; make it say how long to wait (read `Retry-After`).

### Accounts and automatic abuse blocking
Built (optional Google sign-in, see `backend/auth.py`, `AuthRepository.kt` and `deploy/auth-setup.md`): anonymous use stays the default and sign-in is in the menu; backend verifies the Google ID token and issues a 15-minute access token plus a rotating 30-day refresh token (hashes stored in Firestore, Zurich); tiered limits per account instead of per IP; escalating automatic blocks (1 h, 1 day, 1 week, permanent) after repeated rate-limit breaches; owner accounts exempt via `OWNER_SUBS`; delete-account endpoint and menu item; tokens encrypted on the device with the Keystore and excluded from backups.
- [x] **Google Cloud and GitHub setup** from `deploy/auth-setup.md` is done (consent screen, web client plus debug/release/Play Android clients, Firestore in Zurich, signing secret, `GOOGLE_WEB_CLIENT_ID` variable); sign-in is live on the backend.
- [x] Real sign-in verified on the emulator (debug build): the account and a hashed refresh token appear in Firestore.
- [ ] **Set the `OWNER_SUBS` GitHub secret** to your own account id and redeploy, then confirm the role shows `owner` in Firestore.
- [ ] Try a sign-in from a release build and from a Play internal-track install (each has its own Android client).
- [ ] **Standalone Automotive OS app:** it has no phone to share a login with, so it stays anonymous for now. Add sign-in with Car App Library's `SignInTemplate` (QR code or on-car Google account) using the same `AuthRepository`. Android Auto needs nothing: it runs inside the phone app and uses its login.
- [ ] Web MVP stays anonymous; add Google Identity Services sign-in there if wanted (needs its own web client origin).
- [ ] **Abuse detection beyond rate limits:** flag scraping patterns (systematic coverage of the whole city, very high search volume without movement) and impossible movement (teleporting positions); decay strikes after a long good period; an admin way to list, unblock and permanently block accounts without editing Firestore by hand.
- [ ] Delete expired refresh tokens (they are stored with a numeric expiry, so Firestore's TTL feature cannot do it): a small scheduled cleanup, or store `expires_at` as a timestamp and add a TTL policy.
- [ ] Play Integrity API so only genuine builds of the app can call the API anonymously.
- [ ] Play Console: update the Data safety form (email, name, identifiers) and use the privacy page's `#delete-account` anchor as the account-deletion URL.
- [ ] Sign-in UX polish: show the account in the About screen; offer sign-in again after a "Please sign in again" notice.

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
