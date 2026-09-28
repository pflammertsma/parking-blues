# Parking Blues

A mobile app that helps drivers find legal on-street parking (**Blaue Zone** / **Weisse Zone**) near their destination in Zurich, on Android and iOS, with Android Auto and CarPlay support.

## 1. Product concept

The core loop, as specified:

1. **Start search.** The user opens the app when they want to park. They pick a mode: **Blue zones** (free, time-limited), **White zones** (paid, metered), or **Both**.
2. **First guess.** The app finds the best-matching legal parking spot very close to the user's current position and guides them to it.
3. **Rejection detection.** If the user reaches — or drives past — the exact spot without stopping, the app infers "no space there" and moves on to the next candidate, without the user having to say anything.
4. **Clustering, not single points.** Rather than fixating on one spot, the app computes a *cluster* of plausible spots (a block, a street, a loop) within a minimum radius of the user's starting position, and works through that cluster intelligently as candidates get rejected.

This design is a direct consequence of a hard constraint discovered during research (see §2): **Zurich does not publish real-time occupancy for on-street blue/white zone parking.** Only the legal *geometry and rules* (where parking is allowed, of what type, for how long) are known in advance. Whether a specific spot is actually free right now is unknowable until a car is physically there — so "pass-by = rejection" is the only real signal we get, and clustering is how we make that efficient instead of a dumb point-by-point crawl.

## 2. How the official map gets its data

Investigated by instrumenting the [Stadtplan (zueriplan3)](https://www.maps.stadt-zuerich.ch/zueriplan3/Stadtplan.aspx) app with a headless browser and inspecting its network traffic and app config.

### 2.1 Rendering path (what the map itself does)
- The app is a Dojo/Esri-JS-API client (`js/esri/...`) that loads a JSON app config from a proprietary WCF service:
  `GET .../zueriplan3/ServiceContracts/ApplicationService.svc/GetApplicationConfiguration?applicationConfigurationId=0`
- Every layer (basemap, POIs, parking, etc.) is drawn as **WMS raster tiles**, not vector data, from:
  `https://www.ogc.stadt-zuerich.ch/wms_cache/StadtplanApp` (cached) and `.../wms/StadtplanApp` (uncached)
- The backend is a **QGIS Server** WMS (confirmed via `WMS_Capabilities` XML referencing `qgis.org/wms` and `GetSchemaExtension`), despite layer names carrying a legacy `_GeoServer` suffix.
- Clicking a feature in the UI does **not** use standard WMS `GetFeatureInfo` — the app calls its own service:
  `POST .../ServiceContracts/DataService.svc/IdentifyMapObjects`
  However, the underlying WMS layers are independently queryable with plain OGC `GetFeatureInfo`, which is how the schema below was extracted directly.

### 2.2 The parking layer specifically
From the app config (`DynamicServices[0].Layers`), the "Parkplatz" layer (`Id: 60`) maps to WMS layers:
- `Parkplaetze_GeoServer` — **point** features, one per parking bay/segment, rich attributes
- `Parkplaetze_L_GeoServer` — **line** features, one per curb segment, geometry-oriented attributes

Related but separate layers exist for `Parkhaus` (garages), `Behindertenparkplatz` (disabled bays, not independently queryable), `Carparkplatz` (coach parking, not independently queryable).

**Point layer (`Parkplaetze_GeoServer`) attributes**, confirmed live via `GetFeatureInfo`:

| Field | Example | Meaning (inferred) |
|---|---|---|
| `art` | `Blaue Zone`, `Standard`, `Güterumschlag` | Human-readable category |
| `bezeichnung` | `Vulkanstrasse Nr. 130 - 200` | Street/address label |
| `kategorie` | `OPU`, `ZPU` | Internal category code |
| `orientierung` | `LPU` (parallel), `SCHPU` (angled) | Parking orientation |
| `gebpflicht` | `0` / `1` | **Fee obligation flag — the actual Blue/White discriminator** |
| `parkdauer` | `360` (minutes) | Max stay duration where explicitly regulated |
| `parkfeldnummer` | null or a bay number | Individually numbered bay (loading/disabled) vs. open segment |
| `eigentum`, `zugang` | `öffentlich` | Ownership / access |
| `inbetriebnahme` | date | Commissioning date |
| `stand` | **today's date** | Data snapshot date — see §2.4 on freshness |

**Line layer (`Parkplaetze_L_GeoServer`) attributes**: `typ` (`Blaue Zone`, `Parkscheibe`, presumably `Weisse Zone`/others), `abstellart` (e.g. `LPU`), `laenge` (segment length in meters — capacity proxy), `lokalisationnummer`, `parkierungsflaecheid` (groups segments into a parking area).

Coordinate system throughout: **EPSG:2056** (CH1903+ / LV95), the Swiss standard.

### 2.3 The open-data equivalent
The same dataset is published as Open Government Data:
- **Öffentlich zugängliche Strassenparkplätze OGD** — [opendata.swiss](https://opendata.swiss/en/dataset/offentlich-zugangliche-strassenparkplatze-ogd) / [data.stadt-zuerich.ch](https://data.stadt-zuerich.ch/dataset/geo_oeffentlich_zugaengliche_strassenparkplaetze_ogd) — downloadable as CSV, GeoJSON, SHP, GPKG, DXF, in both LV95 and WGS84.
- **Parkhäuser (garages)** — [opendata.swiss](https://opendata.swiss/de/dataset/offentliche-parkhauser) — static garage metadata.
- **Parkleitsystem (PLS)** — real-time **garage** occupancy only, as an XML/RSS feed: `http://www.pls-zh.ch/plsFeed/rss`. Not all garages participate. A third-party project, [ParkenDD](https://opendatazurich.github.io/parkendd-api/), already normalizes this feed (and equivalents for other cities) into a clean JSON API with history — worth using instead of parsing the raw feed ourselves.

### 2.4 ⚠️ Important freshness gap
Search results for the OGD dataset state the download **"reflects the status as of end of 2021 and is no longer being updated."** But the live WMS `GetFeatureInfo` responses carry `"stand": "2026-09-27"` (today, at time of writing) — i.e. **the system serving the live map is materially fresher than the public bulk download.** For a product whose entire value proposition is accuracy, we cannot build on the frozen 2021 snapshot. We need to either:
- find and use the actual live feed backing the map (a follow-up spike: check for a real WFS `GetFeature` endpoint, since `WFSUrl` in the app config currently points at a WMS-only endpoint with no working WFS `GetCapabilities`), or
- periodically re-derive our own snapshot by systematically walking the live WMS via `GetFeatureInfo` (works today, but is not an intended integration point and could break or get rate-limited), or
- contact the city's geodata team (`https://www.stadt-zuerich.ch/geodaten/`) to ask for direct access to a maintained feed/export.

This is the single biggest open risk in the data layer and should be resolved early, before investing in the mobile clients.

## 3. Data model (proposed, normalized)

Collapse the two source layers into one internal `ParkingSegment`:

```
ParkingSegment {
  id
  geometry: LineString | Point   // curb segment or single bay, stored as WGS84 for app use
  zone_type: enum { BLUE, WHITE, DISABLED, LOADING, OTHER }
  fee_obligated: bool            // from gebpflicht
  max_duration_minutes: int?     // from parkdauer; null => statutory default (90 min w/ disc for blue zone)
  orientation: enum { PARALLEL, ANGLED, PERPENDICULAR }
  length_m: float?               // capacity proxy
  estimated_capacity: int        // derived from length_m / orientation, or 1 if parkfeldnummer is set
  address_label: string?
  source_stand_date: date
}
```

Plus, separately, `GarageStatus` (garage id, free_spaces, total_spaces, updated_at) fed by ParkenDD/PLS for an optional "Parkhaus" mode.

## 4. Core algorithm (client- or server-side, TBD in §5)

1. **Candidate generation** — query `ParkingSegment`s matching the selected zone filter(s) within an initial radius `R0` (e.g. 300 m) of the origin point.
2. **Clustering** — group nearby segments (e.g. DBSCAN, `eps` ≈ 50–80 m) into walkable clusters; rank clusters by a score combining distance from origin, aggregate estimated capacity, and (once available, see §7) a crowdsourced fill-probability prior.
3. **Targeting** — guide the driver toward the best-ranked cluster, then the best segment within it.
4. **Pass-by / rejection detection** — place a geofence around the targeted segment; project the user's live position onto the segment's bearing. If the user crosses past the far end without a stop event (speed ≈ 0 for > N seconds inside the geofence), mark it rejected for this session and re-target the next segment in the cluster (or the next cluster once exhausted).
5. **Expansion** — if a whole cluster is exhausted with no stop detected, widen the radius and repeat from step 1.
6. **Completion** — a stop event inside a targeted geofence is treated as "parked here"; end the session (and optionally ask the user to confirm, which becomes a crowdsourced training signal).

This entire flow needs to run continuously in the background while driving, which is the main reason native platform integration (Android Auto / CarPlay) matters — this is meant to be glanced at, not typed into.

## 5. Architecture

### 5.1 Backend
A thin service that is the source of truth for `ParkingSegment`/`GarageStatus`, so mobile clients never talk to Zurich's infrastructure directly (control over caching, rate limits, and resilience to upstream changes):
- Ingests and normalizes the city geodata (see §2.4 — exact ingestion mechanism is an open item) into **PostGIS** for spatial queries.
- Ingests garage real-time data via ParkenDD/PLS.
- Exposes a small API: "candidate segments of type X within radius R of (lat, lon)" and/or does clustering server-side and returns ranked clusters directly — keeping scoring/ranking logic server-side means we can tune it without app-store releases.
- Later: ingests anonymized crowdsourced "was this spot free/full" signals from clients (§7).

Suggested stack: Kotlin/Ktor or Python/FastAPI, PostGIS, a small scheduled job for data refresh.

### 5.2 Mobile clients
Android Auto and CarPlay **require native platform SDKs** for the in-car surface (Android's `androidx.car.app` Car App Library; Apple's `CarPlay` framework with `CPMapTemplate`/`CPListTemplate`) — neither Flutter nor React Native can drive these car-screen surfaces, so cross-platform UI frameworks are not viable for this app. Plan:

- **Shared core** (Kotlin Multiplatform, compiled to JVM for Android and via Kotlin/Native for iOS): candidate generation, clustering, pass-by/rejection state machine, API client, data models — the parts covered in §4 that must behave identically on both platforms.
- **Android**: Kotlin + Jetpack Compose (phone UI) + Car App Library (Android Auto UI: `NavigationTemplate`/`PlaceListMapTemplate`).
- **iOS**: Swift + SwiftUI (phone UI) + CarPlay framework. Note: Apple gates CarPlay navigation-app entitlements behind a request/approval process — this should be applied for early, as it can take time.
- **Location**: Android `FusedLocationProviderClient` + Geofencing API, run from a foreground service while a search is active; iOS `CLLocationManager` region monitoring, requiring "Always" authorization with justification (this is a hard App Store review point for a driving app that tracks location in the background — budget time for review pushback).

### 5.3 Why not real-time occupancy for street parking
Repeating because it shapes everything: there is no sensor network for on-street blue/white zone spaces in Zurich (unlike garages, which do have PLS). The product's honest value proposition is *"we know exactly where you're allowed to park and for how long, and we get smarter about where it's actually free over time"* — not *"we know a spot is free right now."* This should be reflected in onboarding/UX copy so expectations are set correctly.

## 6. Open risks / questions to resolve early

1. **Data freshness** (§2.4) — find the real live feed or negotiate direct access before building on a 2021 snapshot.
2. **License/attribution terms** of the OGD dataset — Swiss OGD is generally open but often requires source attribution ("Quelle: Stadt Zürich" or similar); confirm exact terms before shipping.
3. **Rejection-detection false positives** — a driver dropping off a passenger, or slowing for traffic, could be misread as "checked and rejected." Needs tuning (dwell-time thresholds, speed profile) and probably a manual override ("actually, this one's free").
4. **Background location on iOS** — CarPlay + background location is a stricter App Review path than a typical app; plan for it.
5. **CarPlay navigation entitlement** — apply to Apple early; lead time is unpredictable.
6. **Battery drain** from continuous geofencing/location — needs real-device testing before committing to an update frequency.

## 7. Later / stretch: crowdsourced learning loop

Once step 4/6 of the algorithm (confirmed parked / confirmed rejected) is happening across many users, those signals become a time-of-day/day-of-week fill-probability model per segment — this is the natural, data-driven answer to "we don't have real occupancy," and the main long-term differentiator over the official static map. Out of scope for MVP, but the data model and event logging in §5.1 should be designed so this is additive later rather than a rearchitecture.

## 8. Suggested milestones

1. **Data spike**: resolve §6.1, stand up PostGIS with a real, fresh snapshot of blue/white zone segments for Zurich.
2. **Backend MVP**: candidate + cluster API, no real-time garage data yet.
3. **Phone app MVP** (Android or iOS first, whichever ships app-review-critical items faster): zone toggle, candidate targeting, manual "I parked"/"it was full" input — validates the algorithm with a human in the loop before automating rejection detection.
4. **Automated rejection detection**: geofencing + pass-by inference, replacing manual confirmation as the primary signal.
5. **Second platform** parity.
6. **Android Auto / CarPlay** integration.
7. **Garage mode** (Parkhaus + real-time occupancy via ParkenDD) as an additive mode.
8. **Crowdsourced fill-probability model** (§7).

## 9. Web MVP (milestone 3, in progress)

An extremely basic reference implementation of the algorithm in §4, as a
web app instead of a native mobile client, to validate the logic before
investing in Android/iOS/Auto/CarPlay:

- `backend/` — Flask JSON API + the algorithm itself (`geo.py`,
  `clustering.py`, `session.py`), backed by real Zurich data
  (`parking_data.py`, loading the snapshot in `data/zurich_parking.json`).
- `backend/blue_zone_rules.py` — the actual Blue Zone time rule (see
  below), not the flat "60 min" the ingested data alone would suggest.
- `scripts/ingest_zurich_parking.py` — fetches the City of Zurich's
  official "Öffentlich zugängliche Strassenparkplätze OGD" dataset (CC0,
  ~45k blue/white-zone points; see §2.3) via its WFS endpoint and
  writes the snapshot the app loads. Re-run it to refresh; per the
  dataset's own metadata this won't surface new data until the city
  updates its source (see §2.4/§6.1 — the freshness question is still
  open, this is the best available official source in the meantime).
- `web/` — a single static page (vanilla HTML/CSS/JS, no build step) that
  drives the API: pick a zone, start a search, and either use real
  geolocation or a manual lat/lon field to simulate "driving" past
  candidate spots and watch the auto-rejection logic kick in.
- `tests/` — pytest coverage for the geo helpers, clustering/ranking, the
  session state machine (including simulated drive-by sequences), the
  HTTP API, and a sanity check on the ingested data itself.

Run it:

```
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m backend.app        # serves http://127.0.0.1:5000
pytest                        # run the test suite
```

Known MVP limitations (intentional, not oversights): single in-memory
session store (no persistence, no auth), the ingested data is frozen at
the source's own end-of-2021 snapshot (§2.4), parking spots have no
street name/address (the source dataset doesn't include one -- just an
internal ID) and are modeled as points rather than curb-segment geometry,
and there's no garage/Parkleitsystem integration yet. Real-data density
also means clustering (O(n^2)) is capped to the nearest
`MAX_CANDIDATES_PER_QUERY` segments per query rather than run over
everything in radius -- see `backend/session.py`.

### 9.1 Blue Zone: what "60 minutes" actually means

The ingested data's `max_duration_minutes` for blue-zone spots is a flat
60 for 99.8% of them, but the real rule (confirmed against the city's own
page, stadt-zuerich.ch/.../parkscheibe.html) is time-of-day and
day-of-week dependent, not a flat 60-minute-from-arrival window:

- Restricted only Monday–Saturday, 08:00–11:30 and 13:30–18:00.
- The parking disc's dial must be set to the half-hour mark *following*
  arrival (arriving exactly on a mark still advances to the next one),
  then 60 minutes from there -- so the real usable time is
  60–90 minutes depending on arrival minute, not a flat 60.
- Free lunch hour: arriving 11:30–13:30 is unrestricted, with a grace
  deadline of 14:30 regardless of exact arrival time in that window.
- Overnight (18:00–08:00) is unrestricted, with a grace deadline of 09:00
  the next restricted-window morning.
- Sundays are unrestricted unless additional signage says otherwise (not
  modeled -- no per-street signage data).

`backend/blue_zone_rules.py` encodes this as `blue_zone_deadline(arrival)`,
and `backend/app.py` computes it as of "now" (in `Europe/Zurich`, not
server-local time -- this container runs on UTC, and naively using
`datetime.now()` without a timezone silently misjudges which rule window
applies) for every blue-zone segment returned by the API, exposed as
`legal_until`. Not modeled: Swiss/Zurich public holidays (same
unrestricted treatment as Sundays, but there's no holiday calendar wired
in) and multi-day continuous parking (a car that's been there since
Saturday night, still parked when Monday's restricted hours resume) --
this answers "what's the deadline for a single arrival right now", not
"is this car currently legal given how long it's actually been there".

### 9.2 Preferred parking duration

The web UI lets the user say how long they need to park (a `duration_minutes`
field on `POST /api/session`, stored on the session as
`preferred_duration_minutes` and echoed back so the UI can show what's
active), and candidate matching then excludes any spot that can't
accommodate that long *right now* -- this is why it has to factor in the
current time and day, not just compare against a spot's flat duration
number: a blue-zone spot capped at "60 minutes" might genuinely fit a
2-hour request if checked during the free lunch hour, overnight, or (not
modeled per-street, but the general rule) on a Sunday, when it's
unrestricted rather than actually limited to 60 minutes.

`backend/duration_filter.py`'s `segment_supports_duration(segment,
requested_minutes, now)` is the single predicate this runs through: blue
zone delegates to `blue_zone_deadline` and compares the remaining time
against what's requested (unrestricted = always satisfies); white zone
just compares against the flat per-spot cap, since no time-of-day rule is
modeled for it (a missing cap is treated as "no recorded limit", i.e. it
satisfies anything). `SessionStore._candidates_for` applies this
alongside the existing zone/radius filters, and re-applies it on every
radius expansion with a fresh `now` -- a spot that doesn't fit can start
fitting (or stop fitting) as real time passes during a session, e.g. a
blue-zone spot crossing into the lunch-hour or overnight window while the
driver is still searching.

### 9.3 White zone: an estimated hourly rate, explicitly not a real one

Neither of our data sources (the live map's backend, §2.2, or the OGD WFS
dataset, §2.3) includes a fee amount for white-zone spots -- just the
fee-liable yes/no flag already used to set `zone_type`. Actual Zurich
meter rates depend on a tariff zone (roughly 3-4x apart between the
high-tariff inner city and everywhere else) that isn't in our data at
all, and on time of day; public reporting on the current rate was also
inconsistent, landing in the middle of what looked like a contested 2026
fee increase.

Given all of that, `backend/fee_estimate.py` is one flat, explicitly
unverified number (`ESTIMATED_WHITE_ZONE_RATE_CHF_PER_HOUR`, currently
2.0) in the middle of the general daytime range that turned up in
research -- not tied to any spot's real tariff zone, which we have no way
to look up. The API field is named `estimated_fee_chf_per_hour` (not
`fee_chf_per_hour`) and the UI always appends "(rough estimate)" so
nobody mistakes it for real pricing.

### 9.4 Clustering/ranking tuned against real density, not fixture data

`backend/clustering.py`'s defaults were chosen against the original
12-point fixture dataset and never revisited after real Zurich data (§9)
replaced it -- which produced genuinely bad picks. Two separate problems,
found by reproducing a real "why is this so far away" report:

- **`DEFAULT_CLUSTER_EPS_M` was 80m.** Real segments sit only a few
  meters apart along a curb, so any eps loose enough to bridge a street
  corner chains an entire connected neighborhood into one "cluster" --
  the top-ranked cluster near a real test point spanned 235 segments over
  300+ meters, nowhere near walkable. Now 12m: tight enough to not bridge
  across intersections, loose enough to still merge one continuous curb
  run.
- **`cluster_score` was linear capacity / (1 + distance/100).** Even
  after fixing the eps, a 40-segment cluster 264m away still outscored a
  9-segment one 69m away -- a single driver only needs one spot, and 9
  nearby tries is already plenty of hedge against some being taken, so
  capacity going from 20 to 40 shouldn't matter as much to the score as
  going from 5 to 9. Now `log1p(capacity) / (1 + distance/50)`: capacity
  has diminishing returns, and distance is weighted more steeply, so
  proximity dominates at realistic search radii instead of a much bigger
  cluster winning over a much closer one.

Also added a soft preference for free (blue-zone) clusters when searching
both zones together (`FREE_ZONE_SCORE_BONUS`, up to +30% score scaled by
what fraction of the cluster is free) -- a nudge, not a hard rule, since
a genuinely much closer paid cluster should still win over a distant free
one.

Verified against the real default search point: "both zones" now picks a
white-zone spot 55.9m away instead of jumping to a blue cluster 230.6m
away for more aggregate capacity.

### 9.5 Retargeting was flickering constantly while driving

Separate bug, reported as "the target keeps jumping while the vehicle
moves": `SessionStore._pull_in_local_cluster` runs on every position
update (not just on exhaustion or real drift -- see the "checked every
tick" reasoning in `update_position`), searching a radius around the
driver's *current* position for anything not yet in the session. As the
car moves, that search window moves with it, so it surfaces some
not-yet-seen segment almost every tick -- purely because the window
shifted, not because that segment is any better than what's already
targeted. The old code accepted any non-empty find unconditionally, so
the target reshuffled to whatever was merely *new* on nearly every tick.

Fixed by only accepting a local find when it beats the current target by
more than `LOCAL_PULL_IN_MARGIN_M` (15m) -- still checked every tick (so
a genuinely better spot is still picked up immediately), but no longer
treats "new" as synonymous with "better". Verified with a 60-tick
simulated drive (~2m/tick, ~120m total) along real data: 5 retargets, each
moving sequentially to the next real spot the driver was actually passing
closer to -- not the near-every-tick churn the bug report described.

### 9.6 Driving-direction awareness

Follow-up to §9.5: distance alone doesn't capture "the driver is heading
straight for a better spot" versus "there's a closer one, but it's back
the way they came" -- the latter would mean backtracking, and shouldn't
win just for being nominally closer. `ParkingSession` now tracks a
`heading_deg` (a compass bearing, recomputed from consecutive positions
whenever they're at least `MIN_HEADING_UPDATE_DISTANCE_M` (5m) apart --
below that, GPS/drag jitter gives too noisy a bearing to trust, so the
previous heading is kept rather than reset).

`_directional_distance` (backend/session.py) adjusts a candidate's
distance by up to `DIRECTION_WEIGHT` (25%) based on how well it lines up
with that heading -- straight ahead counts as closer than it actually is,
straight behind as farther, tapering to no adjustment for something to
the side. `_pull_in_local_cluster` compares *this* adjusted distance
against `LOCAL_PULL_IN_MARGIN_M`, not the raw one, so a candidate the
driver is heading toward can win a reconsideration sooner than pure
distance would justify -- while the margin still does its §9.5 job of
requiring a genuine improvement, not just proximity to the search window,
so this doesn't reopen the flickering that section fixed.

## 10. Deploying the web MVP to GCP

`deploy/gcloud.sh <project-id> <billing-account-id> [region]` provisions a
new GCP project and deploys to Cloud Run in one go (region defaults to
`europe-west1` -- not `europe-west6`/Zurich, which would've been fitting,
but Cloud Run domain mappings aren't available there; see section 11). It
assumes `gcloud auth login` is already done and you have a billing account
to link (`gcloud billing accounts list`).
Requires `gcloud` locally -- it cannot be run from a Claude Code on the
web session, which has no access to your machine's credentials.

Under the hood this is a plain `gcloud run deploy --source .`: Cloud
Run's buildpack detects `requirements.txt` and `Procfile` and runs
`gunicorn -b :$PORT main:app` (`main.py` is the production WSGI
entrypoint -- Flask's own dev server, used by `python -m backend.app`
locally, explicitly isn't meant for this).

The deploy pins `--max-instances=1`. `SessionStore` (backend/session.py)
keeps sessions in an in-memory dict scoped to one process; Cloud Run
scaling out to multiple instances would silently drop sessions created on
a different one. That's fine for a low-traffic MVP demo, not for real
concurrent load -- see the "known MVP limitations" note in section 9.

## 11. Serving the frontend from lammertsma.dev + API on a subdomain

The MVP is also reachable at `lammertsma.dev/projects/parking-blues`, with
the API on its own subdomain, `api.parking-blues.lammertsma.dev`. Two
separate origins, not one proxied through the other -- `web/`'s frontend
assets (`index.html`, `app.js`, `style.css`) are static files, so there's
no need for Firebase Hosting to proxy anything through Cloud Run (which
would additionally have required the Cloud Run service and the Firebase
Hosting site to live in the same GCP project, which they don't --
`parking-blues-mvp` per section 10, vs. `lammertsma-dev`).

**Frontend** -- plain static hosting, in a *different* repo
(`pflammertsma/lammertsma-dev`, the personal site), copied under
`public/projects/parking-blues/` following the same pattern as that repo's
other projects (e.g. `switch-plates/`). Not a build step or a git
submodule -- `scripts/publish_to_lammertsma.py` copies `web/`'s three files
verbatim and applies the small portfolio-specific tweaks the app itself
doesn't carry (`noindex,nofollow`, favicon, a title byline), always from
the pristine source so it's safe to re-run repeatedly.
`.github/workflows/publish-frontend.yml` runs that script automatically on
every push to `main` that touches `web/`, commits the result to
`lammertsma-dev` (so that repo's git history stays the real record of
what's live, not just whatever CI last deployed), and runs `firebase
deploy --only hosting` there. Needs two repo secrets on this side,
neither of which this session can generate (both require an interactive
browser login):
- `LAMMERTSMA_DEV_PUSH_TOKEN`: a GitHub PAT with write access to
  `pflammertsma/lammertsma-dev`, so this repo's CI can push there.
- `FIREBASE_TOKEN`: from `firebase login:ci` (run once, locally, against
  the `lammertsma-dev` Firebase project).

**API** -- `web/app.js`'s `API_BASE` constant detects when it's being
served from a `lammertsma.dev` hostname and points `fetch()` calls at
`https://api.parking-blues.lammertsma.dev` instead of a same-origin path;
everywhere else (this app's own Cloud Run URL, `python -m backend.app`
locally) it stays same-origin as before. Since the two are now different
origins, `backend/app.py` sends CORS headers (`Access-Control-Allow-Origin`
etc., via a small `after_request` hook -- no new dependency) for an
allow-list of origins (`ALLOWED_ORIGINS`) rather than `*`, since a public
`*` would let any third-party site create/drive sessions against this API
using a visitor's own IP/browser as the requester.

Cloud Run **domain mappings** (`gcloud run domain-mappings create`, needed
for the API subdomain below) only work in a fixed legacy list of regions,
which does not include `europe-west6` (Zurich) -- attempting it there fails
with `UNIMPLEMENTED`. That's why the service now deploys to `europe-west1`
(Belgium) instead; see section 10.

Three one-time steps outside this repo, requiring credentials this session
doesn't have, so they need to be run by hand:

1. **Redeploy the service to `europe-west1`** if it's currently only in
   `europe-west6`: `gcloud run deploy parking-blues --source . --region
   europe-west1 --allow-unauthenticated --max-instances=1` (from the repo
   root, in the `parking-blues-mvp` project). Once it's confirmed working,
   the old `europe-west6` revision can be deleted (`gcloud run services
   delete parking-blues --region europe-west6`) to avoid paying for/
   maintaining two.
2. **Cloud Run domain mapping**: `gcloud beta run domain-mappings create
   --service parking-blues --domain api.parking-blues.lammertsma.dev
   --region europe-west1` (the mapping command lives under `gcloud beta`,
   not the main track). If `lammertsma.dev` hasn't already been verified as
   owned in *that* GCP project, `gcloud` will point you to Search Console's
   domain verification first -- domain mappings are gated per-project, so
   verifying it once for `lammertsma-dev` (Firebase's project) doesn't
   carry over.
3. **DNS** (Cloudflare, where `lammertsma.dev`'s DNS is managed): add the
   CNAME record the domain-mappings command prints (typically
   `ghs.googlehosted.com`) for `api.parking-blues.lammertsma.dev`, set to
   **DNS only** (grey cloud, not proxied) -- Cloudflare's proxy interferes
   with Google's automatic managed-certificate issuance/renewal for the
   mapping.
