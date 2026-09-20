# Norda — Walk. Run. Explore.

**Minimalist Outdoor Navigation — MVP and Technical Design**

rev 3 · August 25, 2026

This document defines the product scope, screens, technical architecture, data
model, GPS/sensor processing approach, process rules and development roadmap
for a minimalist Android app that combines walking and running tracking with a
compass, offline maps, waypoints and "Return to Start" navigation.

The first draft (August 25, 2026) has been rewritten with the decisions made;
**this file is the project's single source of truth.**

| Area | Decision |
|---|---|
| Platform | Android; no iOS in the first phase (the core is kept pure, the door stays open) |
| Language | Kotlin |
| Package name | `com.aripd.norda` |
| UI | Android Views + XML + Canvas (no AppCompat/Material/Compose) |
| External dependencies | **0** at runtime — only Android SDK + Kotlin stdlib |
| Location | `android.location.LocationManager` / GNSS (no Play Services) |
| Compass | `SensorManager` (rotation vector; fallback: accelerometer + magnetometer) |
| Storage | SQLite (with the thin-layer rule, see section 8) |
| Tracking | Foreground service (`location` type) |
| Map | Custom offline raster-tile renderer + **our own tile packaging pipeline** (GitHub Actions) |
| Routing | Not in the MVP |
| Cloud / Account | None |
| SDK | `minSdk 26` (Android 8.0) · `targetSdk 35` (Android 15, edge-to-edge) |
| Versioning | SemVer; every change gets a tag + release (section 15) |
| Development | TDD — red → green → refactor (section 13) |
| License | Code **MIT** (`LICENSE`); map data © OpenStreetMap contributors (ODbL) |

## 1. Product definition and positioning

Norda is not a "running app + compass"; it is a **minimalist, offline-first
outdoor navigation product**. The core promise: the user walks/runs in an
environment without internet, sees their position and heading, records the
activity and, when needed, returns to the starting point.

> **Walk. Run. Explore. Don't lose your way.**

In classic running apps the activity metrics are the main product; in Norda
**wayfinding is a first-class feature**. Answering the question "how do I get
back to where I started?" without requiring internet — in trekking, hiking,
trail running and nature walks — is the product's identity.

The MVP's focus:

- Very fast activity start: open the app → START.
- GPS-based walk/run recording; live route, distance, duration, pace, elevation.
- Real-time compass — with true north and a magnetic disturbance warning.
- Offline map: region packs produced on our own pipeline.
- Waypoints and GPX exchange: the data belongs to the user and is portable.
- Return to Start: straight-line direction + distance + estimated time back to
  the start.
- **No** account, social network, cloud, routing or calories.

## 2. MVP scope

| Feature | MVP | Note |
|---|---|---|
| Walking | Yes | Activity type |
| Running | Yes | Activity type |
| GPS tracking | Yes | GNSS-based, filtered |
| Distance | Yes | Geodesic distance between accepted points |
| Duration | Yes | Active time, excluding pauses |
| Speed / Pace | Yes | Location-based; with the motion filter |
| Elevation gain/loss | Yes | GNSS altitude + hysteresis accumulation (5.4); barometric refinement later |
| Auto-pause | Yes | 20 s without motion → pause; 8 m from the anchor → resume (5.5) |
| Live route | Yes | Canvas layer on top of the map |
| Compass | Yes | Rotation vector; time-constant smoothing (section 6) |
| True north | Yes | `GeomagneticField` declination; same frame as the map |
| Magnetic disturbance warning | Yes | Field-strength comparison, with hysteresis (6.3) |
| Offline map | Yes | Raster tiles; MBTiles packs (section 7) |
| Map pack download | Yes | Region packs produced on our own pipeline (7.2) |
| Return to Start | Yes | Straight-line bearing + distance + ETA (section 9) |
| Waypoints | Yes | Named points; on the map and the compass (2.1) |
| GPX import/export | Yes | `<trk>` + `<wpt>` together; file exchange via SAF |
| Activity history | Yes | Local SQLite |
| Download by free area selection | Next release | Ready-made region packs in the MVP (7.2) |
| Breadcrumb navigation | Yes (v1.2.0) | Follow the recorded track back to the start (9.3) |
| Daylight budget | Next release | Sunset time × return pace warning |
| Night mode | Yes (v1.3.0) | Red filter over every screen, automatic at civil dusk (3.7) |
| Voice announcements / records / weekly summary | Next release | |
| Turn-by-turn routing | No | Out of scope |
| Account / Cloud / Social | No | Out of scope |
| Calories | No | A low-reliability metric is not put at the center of the product |
| Third-party service integration | No | Conflicts with the network rule (section 11) |
| AI / recommendations / weather / watch–heart rate | No | Out of scope |

### 2.1 Waypoint behavior

- A waypoint can be added at any time: with a single tap while recording (car,
  camp, water source, trail fork) or by long-pressing the map.
- A new point is named "Point N" — N is the first free number in the list; it
  can be renamed and deleted.
- No limit on the count; sorted by distance on the list screen.
- Shown as a marker on the map, and as bearing and distance on the compass.
- Exported/imported as GPX `<wpt>`.

## 3. Screens and user flow

Bottom navigation: **Home · Compass · Activities · Maps** (+ Settings gear).

### 3.1 Home

```
  HOME
  ────────────────────────
  247° SW          0.0 km

        [ START ]

     Walk        Run
  ────────────────────────
```

As empty as possible. A small heading indicator + START with a single tap.

### 3.2 Activity (recording screen)

```
  ┌──────────────────────┐
  │                      │
  │     OFFLINE MAP      │
  │        ───●          │
  │       /              │
  │      /       ◆ Camp  │
  ├──────────────────────┤
  │ 5.82 km              │
  │ 6:14 /km       38:21 │
  │ ▲ 124 m   ▼ 96 m     │
  │                      │
  │ [+WPT] [PAUSE] [STOP]│
  └──────────────────────┘
```

The map is the screen's main visual; the track and waypoints are drawn on top
of it. The metrics are limited in number: distance, pace, duration, elevation
gain/loss. `+WPT` turns your current position into a waypoint. The auto-pause
state is shown explicitly ("Auto-paused").

### 3.3 Compass

```
        N
      ╱ ▲ ╲
     │ 247° │
  W ◀│  SW  │▶ E
     │      │
      ╲    ╱
        S
  ── Start 312° · 3.2 km
  ── Camp   18° · 640 m
```

Device heading and target bearing are separate concepts and are not mixed up
on screen. The bottom lines: bearing + distance of the start point and the
nearest waypoints. True north is indicated in the label; if the declination is
unknown it reads "Magnetic".

### 3.4 Activities

```
  ACTIVITIES
  Today ────────────────
  Running   5.82 km  38:21  ▲124 m
  Yesterday ────────────
  Walking   3.41 km  52:18  ▲83 m
```

List grouped by month → detail screen (track map, metrics, GPX export,
delete).

### 3.5 Maps

```
  OFFLINE MAPS
  Downloaded ───────────
  Istanbul   482 MB  ✓
  Uludağ     126 MB  ↓ 40%
  Available ────────────
  Kaçkar     210 MB  [DOWNLOAD]
  ──────────────────────
  © OpenStreetMap contributors
```

The pack list comes from `index.json` in the repository (7.2). Downloads are
verified with SHA-256; packs can be deleted one by one. The attribution appears
on this screen and in About. Free area selection is post-MVP.

### 3.6 Return to Start

```
  RETURN TO START
       ↖
  312° NW · 3.21 km · ~38 min
```

One tap away from every screen while recording. Straight-line bearing +
distance + estimated time from the recent pace window. This is not a
road-network route; that is a deliberate design decision (no routing engine,
works offline, works even without a map pack).

### 3.7 Night mode (v1.3.0)

```
  Night mode: automatic     ← foot of Home; one tap cycles
                               automatic → on → off
```

Eyes that have adapted to the dark lose that adaptation to a bright screen in
a second and take twenty minutes to get it back. Red light costs the least:
the rod cells that carry night vision are nearly blind to it. So night mode
does not repaint the screens — it lays a single filter over the window that
multiplies everything beneath it by a dark red (`NightMode`). White turns red,
black stays black, green and blue all but vanish. Every screen keeps one
palette, and the map tiles, the compass dial and the buttons pass through the
filter exactly as they are drawn by day.

- **Automatic** is the default: night begins at **civil dusk**, the moment the
  sun drops 6° below the horizon, and ends at civil dawn. The sun's altitude
  is computed in the pure core (`core/sun/Sun`, low-precision solar position,
  well under a degree — minutes of dusk), the decision in
  `core/sun/NightPolicy`.
- The altitude needs a rough position: the running recording's last point, the
  system's last known location, or the last point ever recorded, in that
  order. Without any of them the screen stays in daylight — a filter that
  appears for no visible reason is worse than one switched on by hand.
- **On** and **off** are the user's word and ignore the sun.
- **Three strengths**, chosen by long-pressing the same line: soft strips the
  blue, **amber** is the default, deep red protects dark adaptation best. Deep
  red is also the hardest colour to read — the eye focuses red and green at
  different distances, and astigmatism widens that gap — which is what the
  first field night reported (F-16). Which one reads well depends on the eye,
  so it is a setting rather than a constant, and the default is the middle.
- The decision is re-made on every screen resume and once a minute while a
  screen stays open, so dusk falling mid-recording tints the screen without
  anyone leaving it.
- Outside the filter: dialogs, which live in their own windows, and the system
  status bar, which SystemUI draws over the app.

No extra permission, no network, no new sensor: a date, a position and
arithmetic.

## 4. Technical architecture

```
com.aripd.norda
│
├── core/                    pure Kotlin — Android imports FORBIDDEN, all of it tested on the JVM
│   ├── geo/                 distance, bearing, angle arithmetic
│   ├── track/               TrackPoint, GpsFilter, AutoPauseDetector,
│   │                        PauseAwareStopwatch, Stats, Elevation
│   ├── nav/                 ReturnToStart, WaypointLogic
│   ├── heading/             Smoothing, declination model
│   ├── map/                 MapProjection (WebMercator), TileMath
│   ├── io/                  Gpx (trk+wpt), row↔model mappers
│   └── db/                  Schema — DDL + migration plan (8.1)
│
├── location/                GpsLocationSource (LocationManager wrapper)
├── compasshw/               sensor registration, rotation vector, calibration/disturbance warnings
├── tracking/                TrackingService (foreground), notification
├── map/                     CustomMapView, TileStore, TileCache, TileDownloader
├── storage/                 AppDatabase, ActivityDao, WaypointDao, MapDao (thin layer)
└── ui/                      HomeView, ActivityView, CompassView, ActivitiesView,
                             MapsView, SettingsActivity, Palette
```

Rule: **every module ships with its tests; untested code does not get merged.**
The MVP's biggest new investment is the map engine (`core/map` + `map/`); the
logic of the other modules is built on known, tested patterns.

## 5. GPS / Location Engine

```
GNSS / LocationManager
        │
        ▼
  Accuracy filter ──► Motion/distance filter ──► Speed ceiling
        │
        ▼
    TrackPoint ──► SQLite (WAL) ──► Statistics ──► UI / map layer
```

### 5.1 TrackPoint

`timestamp, latitude, longitude, altitude, accuracy, speed, bearing` —
a pure model, no Android dependency.

### 5.2 Filter starting values

| Filter | Value | Rationale |
|---|---|---|
| Accuracy ceiling | 30 m | A worse fix is not recorded; `accuracy ≤ 0` (a device that does not report it) is accepted |
| Speed ceiling | 10 m/s | A physically meaningless jump ("teleport") is discarded. It started at 15; when the first-fix settling jump (12.7 · 12.85 m/s) slipped under the ceiling in two tours, it was calibrated with the field (F-11) — in a walking/running product, movement above 36 km/h is not running |
| Jitter threshold | 2 m | Stationary GPS jitter does not count as distance |

These are **starting values**, not fixed rules; they are calibrated with the
field matrix in section 13 (forest, narrow street, signal loss, slow steep
climb). While paused (manually or automatically), point generation and distance
accumulation stop. Statistics are always computed from filtered data.

**Settling gate (F-11):** the first fix is a candidate, not an anchor. In two
tours the first fix arrived 13–26 m off, and because it was made the anchor,
phantom distance — even when the jumping point was rejected — was still
counted with the next point. The candidate does not enter the recording until
physical consistency is confirmed with the second fix; if that yields a double
teleport, the culprit is the first fix and the candidate is replaced. The cost
is a one-fix delay (~1–2 s); the return is a clean start.

### 5.3 Sampling

Start: ~1 s interval. Instead of a fixed "record every second" rule, an
adaptive approach is the goal; the actual values are determined by device and
battery tests. A distance filter is **not** given to `LocationManager` — a
measured trap: when a distance filter is given, Android sends an update only
when both the interval has elapsed and that much distance has been covered, so
a phone standing still never receives a GPS fix at all.

### 5.4 Elevation gain/loss

GNSS vertical error is 2–3 times the horizontal; summing raw differences
produces hundreds of meters of phantom climb even on flat ground. **Hysteresis
accumulation**: no gain/loss is written unless the altitude clearly diverges
from the last anchored value by the threshold (start: 4 m; calibrated in the
field within the 3–5 m range); once it diverges, the difference is applied in
a single step and the anchor is updated. Test: a noisy series at constant
altitude → gain 0; a known staircase profile → the expected total.
Barometric refinement is post-MVP. Gain and loss are computed from the raw
ellipsoid heights; the geoid correction (5.7) is a near-constant offset and
cancels in a difference.

**What the accumulator is fed (v1.8.0, field item Y-3).** The threshold alone
was not enough. GNSS vertical noise separates from the anchor by 4 m often
enough that the oscillation riding on a real climb was booked as climb: across
sixteen field tours the figure ran about a quarter high, and against a
companion's DEM-corrected track two to three times high. It was also unstable
— perturbing a real 1274-point series by ±1 cm moved the gain between 50 m and
76 m, because a perturbation flips which step crosses the threshold. So the
series passes through a **±5 s median** first (`core/track/AltitudeSmoother`):
a median is unmoved by the outliers in its window, so spikes go and slopes
stay. On the field tours that removes about a quarter of the total gain and
roughly halves the instability. Three properties are deliberate:

- The window is bounded in **time**, not in samples. At the usual ~1 Hz
  cadence nine samples are nine seconds, but on the sparse battery-saver tour
  (61 points over 33 minutes) the same nine samples span ten minutes and
  flatten the outing to nothing.
- A window holding fewer than three samples passes its value through
  untouched: a median of one or two samples is not a filter.
- The window is centred, so a sample is booked only once samples five seconds
  newer exist. The live figure therefore trails the walk by about five
  seconds, and taking the summary books the tail.

The threshold stays at 4 m. Lowering it after smoothing was measured and
re-inflates the total (at 2 m the sixteen tours come back to where they
started), so the pair is calibrated together, not separately. Recovery after
process death and GPX import feed the same smoother, or a recovered activity
would read higher than the same outing recorded without interruption.

### 5.5 Auto-pause

- If **20 s** pass without an accepted fix (while fixes keep arriving)
  → auto-pause; the time does not count.
- While paused, a fix that moves **8 m** away from the anchor point and passes
  the accuracy requirement (≤ 30 m) → auto-resume.
- Manual pause/resume resets the detector. The state is clearly visible on
  screen.

### 5.6 Foreground service

START launches `TrackingService` (type: `location`); it shows a persistent
notification. Recording continues while the screen is off and the app is in
the background. Recording is not lost on process death (8.3). The Android 10+
service type, the Android 13+ notification permission and the current Play
policies are re-verified during development together with the target SDK.

### 5.7 Absolute altitude: the geoid correction (v1.5.0)

The receiver reports height above the **WGS84 ellipsoid**, a smooth
mathematical figure. Maps, signposts, DEMs and every other tool use height
above the **geoid**, which is mean sea level. The two differ by up to ±107 m
on Earth and by about 37 m in Istanbul, so Norda used to read 39 m at the
water's edge (field item Y-1, measured on two separate seaside walks, the same
median twice).

- The separation comes from a table of the **EGM96** model at a 1° step,
  shipped as a resource and interpolated bilinearly in the pure core
  (`core/geo/Geoid`). Provenance and the reason for 1° are in
  `tools/geoid/EGM96-PROVENANCE.md`: measured against the 15′ grid, 1° costs
  0.76 m RMS where the app is used and 130 KB of data, which the APK
  compresses to ~84 KB. That is an order of magnitude below GNSS vertical
  noise and two orders below the error it removes.
- **The database keeps the raw ellipsoid height**, exactly as the receiver
  reported it. The correction is applied at the edges where a human or another
  tool reads the number: the Diagnostics screen and GPX. So elevation gain and
  loss (5.4) are untouched — a near-constant offset cancels in a difference —
  past recordings gain the correction for free, and the raw record stays raw.
- Without the table nothing is corrected and the screen says "ellipsoid": a
  wrong correction would be worse than none.
- Single-fix vertical noise is a different problem and stays with the DEM and
  barometer candidate (5.4).

## 6. Compass / Heading Engine

### 6.1 Heading readout

`TYPE_ROTATION_VECTOR` takes priority; if the device lacks it, accelerometer +
magnetometer (`getRotationMatrix`). The axes are mapped to the screen
orientation with `remapCoordinateSystem` — the same code runs in portrait and
landscape.

### 6.2 Smoothing

The angle is filtered through its `sin`/`cos` components rather than directly
(so the needle does not spin a full turn at the 359°→0° crossing). What is
stored is a **time constant** (0.35 / 0.17 / 0.08 s), not a coefficient; the
coefficient is computed from the actual interval at every sample, so the feel
stays the same even if the sampling rate changes.

### 6.3 True north and magnetic disturbance

- Declination via `GeomagneticField(lat, lon, alt, time).declination`; `true =
  magnetic + declination`. The declination is cached (it shifts by a degree over
  hundreds of km; it is not recomputed until 1 km has been covered) and is ready
  instantly on the next launch.
- Map, bearing and compass use **the same north frame**; if the declination is
  unknown the label says "Magnetic". Targets are stored in the magnetic frame:
  if location permission is granted later, the declination kicks in and the
  angles on screen shift; keeping the target magnetic means the locked-in
  physical direction stays the same.
- Disturbance warning: the measured total field strength is compared with the
  expected one (`getFieldStrength()`); if a deviation exceeding 25% lasts 2.5 s
  uninterrupted, the warning is shown, and it clears below 15%. Warning
  priority: disturbance > calibration > tilt (disturbance gives no visual cue
  at all, which makes it the most dangerous).

### 6.4 Three angles, three concepts

| Value | Meaning |
|---|---|
| Device heading | The direction the device is facing |
| Target bearing | The direction from the current position to the target (start, waypoint) |
| Relative angle | The difference between the two — "12° right" |

## 7. Offline Map Engine

The hardest part of the zero-dependency goal. The MVP solution: instead of
writing a vector map engine, a small custom renderer that uses **raster XYZ
tiles** + **our own packaging pipeline** that produces the tiles.

### 7.1 Renderer

- Web Mercator projection, XYZ tile math (pure Kotlin in `core/map`,
  JVM-tested: lat/lon ↔ tile/pixel conversions against known fixed points).
- Only the tiles intersecting the viewport are loaded and drawn; bitmap cache
  (LRU); pan/zoom; no per-frame allocation.
- Track, waypoints and the position cursor are a separate layer above the tiles.
- MapLibre is **not used** in the MVP. Rationale: MapLibre is a client
  rendering library (it draws vector tiles) and is not a packaging pipeline on
  its own; the custom raster renderer preserves the zero-dependency identity
  and is sufficient for the MVP goal ("we are not building a map engine
  product, we are validating an outdoor tracker"). After the Phase 4–5
  measurements, a move to vector tiles + MapLibre can be re-evaluated as "the
  single controlled dependency" (gains: ~10× smaller packs, crisp text,
  styling/night map; cost: dependency + integration).

### 7.2 Our own tile packaging pipeline (GitHub Actions)

Public OSM tile servers forbid bulk downloading in their usage policy; Norda
does not depend on any live tile server. Tiles are produced on our own
pipeline:

```
map-pack.yml  (workflow_dispatch: region name + bbox + zoom range)
  1. Fetch the region's OSM data from the Overpass API (ODbL): coastline
     for the tile-aligned extent of the smallest zoom, then — in ≤0.2° cells
     with retries — water and green areas (ways + multipolygon outers),
     rivers, roads by class and rails
  2. Render 256-px raster tiles with our own standard-library renderer
     (tools/osmrender.py): coastline → sea polygons by OSM's land-left /
     water-right rule, even-odd fills, cased roads, 2× supersampling
  3. Raster .mbtiles pack + SHA-256
  4. Publish as an asset on a GitHub Release (tag: maps/<region>-vN)
  5. Update docs/maps/index.json (commit)
```

- The pipeline has **no build-time dependencies either**: Python 3 standard
  library only — no tilemaker, no MapLibre, no containers. The first draft
  planned a Geofabrik → vector tiles → headless-renderer chain; a renderer of
  our own turned out smaller, testable (unit tests in `tools/tests`) and free
  of a live tile server or heavyweight tooling.
- Cartography is deliberately minimalist and outdoor-first: land, sea, water,
  forest/park, roads by class (motorways down to residential; tracks and
  paths from z13), rails, and — since the second Istanbul pack — labels: place
  names (city → neighbourhood, each from its own zoom), peaks with their
  elevation, sea/strait/bay and lake names. Text comes from a bitmap glyph
  table of DejaVu Sans rasterized once (`tools/fontdata.py`, generated by
  `tools/fonts/build_font_table.py` with a headless browser) so the renderer
  stays dependency-free; labels are drawn last with a halo, greedily by
  priority so nothing overlaps, and placement looks one tile beyond the edge
  so neighbouring tiles agree. Street names are not drawn (line labels are a
  later step). Style values are starting points to be calibrated in the
  field like the GPS filter.
- Requesting a new region = manually triggering `map-pack.yml` in Actions; the
  pack lands on a Release and appears in the list on the phone. The map
  pipeline needs no secrets (`GITHUB_TOKEN` is enough); the signing secrets are
  in section 15.
- `index.json` schema: `[{id, name, bbox, minZoom, maxZoom, sizeBytes,
  sha256, url, version}]` — the app reads it, downloads the pack and verifies
  it.
- Zoom range in practice: z8–z14; the app zooms continuously (fractional
  zoom, F-14) and draws up to two levels past the pack ceiling by scaling
  the ceiling tiles (over-zoom, F-13). The renderer runs in minutes in CI
  at this range.
- **Attribution is mandatory**: "© OpenStreetMap contributors" on the Maps
  screen and in About; the ODbL license note in the README.

### 7.3 Pack storage

Each pack is a separate `.mbtiles` file (standard MBTiles schema: `metadata` +
`tiles(zoom_level, tile_column, tile_row, tile_data)`). Caution: the MBTiles
row is in **TMS** order — `tile_row = 2^z − 1 − y`; the renderer performs
this flip and has a test for it. Thanks to the standard schema, packs can also
be opened and verified with desktop tools. Packs are separate from the app
database: they can be deleted and re-downloaded without affecting activity
data.

### 7.4 An empty map says why

The renderer draws a procedural grid wherever a tile is missing, and that grid
looks identical for three different reasons: no pack is installed, a pack is
installed but its bounds end before this area, or the pack covers the area and
the tile itself is missing. From the field those three are indistinguishable —
"the map doesn't show" is all one can report. So the screen names the cause,
the same discipline as the GPS status line (F-4, F-5, F-9):

- The decision is pure (`core/map/MapCoverage`): installed pack count, whether
  the open pack claims the view centre, tiles the last frame needed and tiles
  it actually painted. A single painted tile means OK — the rest are decoding,
  and a half-drawn map is not a fault to report.
- The recording screen and the map screen show the same one-line hint over the
  map; `MapHint` turns the state into the sentence.
- Diagnostics lists the installed packs with their zoom range, size and
  bounds, so "which pack am I looking at, and how far up does it go" is
  answered on the device.

Two mechanics belong with it. The map view closes its pack when it is detached
from the window, so it reopens the same file when it is attached again instead
of showing the grid for the rest of the outing. And the recording screen opens
a pack whenever it has none rather than once per recording, so a pack
downloaded in the middle of an outing appears on the next fix.

## 8. Data model

### 8.1 app.db (SQLite, WAL)

```
activity                      track_point                   waypoint
─────────────────────         ─────────────────────         ─────────────────────
id INTEGER PK                 id INTEGER PK                 id INTEGER PK
type TEXT (WALK|RUN)          activity_id INTEGER FK        name TEXT
start_time INTEGER            timestamp INTEGER             latitude REAL
end_time INTEGER NULL         latitude REAL                 longitude REAL
distance_m REAL               longitude REAL                altitude REAL NULL
duration_ms INTEGER           altitude REAL                 created_at INTEGER
elevation_gain_m REAL         accuracy REAL
elevation_loss_m REAL         speed REAL
start_battery INT NULL        bearing REAL
end_battery INT NULL
start_charge_uah INT NULL     after_pause INT NULL
end_charge_uah INT NULL
```

Waypoints are global, not tied to a recording. The DDL and migration plan live
in the pure `core/db/Schema` module and are JVM-tested (schema version 1:
activity + track_point; version 2: + waypoint; version 3: battery columns on
activity; version 4: the battery charge counter in µAh on activity; version 5:
`track_point.after_pause`). A fresh install is produced as the v1 base + the migration chain,
and a parity test guarantees that both paths arrive at the same schema — a
table cannot make it into create and be forgotten in the migration.

Map packs are not in app.db but in a per-pack `.mbtiles` file (7.3); app.db
holds only the metadata of the downloaded packs.

### 8.2 Thin-layer rule

`android.database` (and other Android classes such as `org.json`) do not exist
in JVM unit tests. Rule: **the layer that touches SQL stays dumb** — the DAO
only reads/writes; filters, statistics, elevation, bearing, tile math, GPX
generation/parsing are JVM-tested in the pure core. Row↔model converters are
pure functions and are tested; a small instrumented smoke-test set for the
DAOs is added later.

### 8.3 Crash resilience

A single `INSERT` per fix, WAL mode. An activity with `end_time NULL` = an
unfinished recording; it is found at launch and either the recording is
resumed or it is recovered to History. The recovery scenarios in section 13
(process kill, reboot) verify this.

### 8.4 Battery measurement

Consumption is a field metric, so it is measured twice. The level the system
reports (`BATTERY_PROPERTY_CAPACITY`) is a whole percent, and the gauge sits on
a level for a long stretch before dropping several points at once: on the
September 12 night walk it read 80% at both ends of 38:50, so History showed
0 %/h — a true reading of a useless number (field item B-1). So the recording
also stores the **charge counter** (`BATTERY_PROPERTY_CHARGE_COUNTER`, µAh) at
start and end, which moves with every milliamp-hour.

- Consumption in mAh is the counter difference. The percentage is that
  difference over a full-charge estimate, and the estimate comes from one
  counter reading at a known level: 3 200 000 µAh at 80% is a ~4000 mAh
  battery. No system capacity value is needed, and the percentage becomes
  fractional instead of integer.
- The rate's denominator is the **wall clock** (F-1), not the active time: GPS
  drains during pauses too.
- The cleanliness rule is unchanged (`core/track/Battery`, JVM-tested): a
  number appears only when it was measured. A missing reading, a counter the
  device does not serve (it answers 0 or a sentinel), charging during the
  recording, or a span under five minutes all yield null — never a made-up
  value. Without a counter the whole percent remains the source — and so it is
  when the counter does not move at all while the gauge does, because a
  counter that is not live on that device is not a measurement either.
- A recording made on the charger has no consumption to report, and the
  History row says "charging" rather than leaving the line empty — an empty
  line reads as "not measured" (field tour, Sept 13: 16% → 57%).
- What is measured is the **device**, not the app: the counter includes the
  screen and everything else running. Four field tours spread over 0.92 to
  8.64 mAh/min, so the honest reading is the floor — about 55 mA, ~1.4 %/h,
  which is a recording with the screen off — and the rest is screen time
  (field item B-1). Logging screen-on time per recording is the candidate
  that would turn that argument into a measurement.
- Diagnostics shows the level, the counter and the capacity estimate, so
  whether a device serves the counter at all is visible on that device.
- Both readings travel inside the GPX report (`norda:battery`), so a tour can
  be recomputed afterwards from the file alone.

## 9. Return to Start math

Start `S(φs, λs)`, current position `C(φc, λc)`. Bearing **from the current
position to the start**:

```
Δλ      = λs − λc
y       = sin(Δλ) · cos(φs)
x       = cos(φc) · sin(φs) − sin(φc) · cos(φs) · cos(Δλ)
bearing = atan2(y, x)          → normalized to 0–360°
distance = geodesic distance (WGS84)
eta      = distance × recent pace window (currentPace)
```

Sanity check: on the equator, if the start is due east (`λs > λc`) →
`Δλ > 0`, `y > 0`, `x = 0` → bearing 90° (east). ✓

> Note: the first draft had `Δλ = λ2 − λ1` (opposite sign); that gives an
> east–west mirrored bearing (270° in the same example). This document
> contains the corrected formula, and the implementation is protected by
> physical-constant tests: the equator test closes this class of error
> permanently (`core/geo` tests).

The limitation is deliberate: this is not a road-network route. It requires no
routing engine, works offline, works even without a map pack, has low
technical risk, and delivers value to the outdoor user immediately.

### 9.3 Breadcrumb navigation (v1.2.0)

The straight line is the promise; the recorded track is the path you know
exists. Breadcrumb guidance (`core/nav/Breadcrumb`) lives on the Compass under
the Return to Start line and uses the active recording as the trail:

- `Trail` keeps the accepted points with their cumulative distance; per fix it
  finds the nearest recorded point.
- On the trail (≤ 25 m from the nearest point) the pointer aims at the point
  about 20 m back along the trail — far enough that GPS jitter does not make
  it twitch, near enough to follow — and the line reads the trail distance
  that remains to the start plus the ETA at the current pace.
- Off the trail (> 25 m) the pointer and the line switch to the way back onto
  it: bearing and distance to the nearest recorded point.
- Within 15 m of the start: "trail complete".
- Loops: the nearest point wins, so a shortcut across a loop is taken
  naturally, as a walker would.

No routing engine, no map required; the trail is the data the recording
already produces. The Return to Start line stays as it is.

## 10. GPX exchange

- **Segments**: a manual pause starts a new `<trkseg>`, which is how GPX marks
  a break in a track (F-17). The ground covered while paused is not distance —
  the live session skips it — so no tool, ours included, should draw or count a
  line across it. The flag lives on the point (`track_point.after_pause`), so
  the file can say where the pause was instead of leaving a gap that looks like
  a GPS outage; the two are indistinguishable otherwise, and the difference is
  hundreds of metres.
- **Export**: activity → `<trk>/<trkseg>/<trkpt>` (with `ele`, `time`);
  waypoints → `<wpt name=...>`. Track + points in a single file. Saved via SAF
  (`ACTION_CREATE_DOCUMENT`). `ele` is written as height above **mean sea
  level**, which is what other tools read it as: the geoid correction (5.7) is
  applied on the way out and undone on the way in, so a round trip returns the
  same recording.
- **Telemetry** (F-3): the app's summary (distance/active time/elevation),
  battery and filter counters are embedded as `norda:report` inside GPX 1.1
  `extensions` — the field report is a single file. Other tools ignore the
  block; the filter counters are added only if the last recording is that
  activity.
- **Import**: `<trkpt>`s as an activity (`lat/lon/ele/time`), `<wpt>`s as
  waypoints; a second `<trkseg>` is read back as a break, so a round trip
  returns the same distance. Malformed input is tolerated by skipping the line, tested.
  `norda:report` does not count as data on import — statistics are always
  recomputed from the points.
- "Route sharing" is not a separate feature in the MVP: sharing a GPX file
  already is route sharing.

## 11. Permissions and the network rule

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS/GNSS recording, coordinates |
| `ACCESS_COARSE_LOCATION` | Platform requirement (together with fine); sufficient for declination |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Recording while the screen is off |
| `POST_NOTIFICATIONS` | Service notification (Android 13+) |
| `INTERNET` | **Map pack download only** |

**Network rule:** the only class that touches the network is `TileDownloader`;
tracking, compass, navigation and GPX work without internet. This sentence is
written in the README — it is the counterpart of the "permission list =
verifiable privacy" stance. Location data stays on the device by default;
there is no telemetry.

**Clean-track stance (product feature):** only **real GNSS points** that pass
the quality gate (5.2) enter the recording; network/WiFi location never
becomes part of the track. To appear to "start instantly", the track is not
padded with coarse points to be corrected later — that is what assisted apps
do. The cost is a visible wait until the device gets a GPS lock (the GPS
readiness line and satellite count on Home manage it); the return is that
every point of the track is real. This gate is the source of the
zero-difference distance validations in the field tours (the `docs/SAHA.md`
log). During warm-up the network provider may be listened to solely to seed
the GNSS engine (F-10): the network fix enters neither the display nor the
track, and it is released with the first real GPS fix.

## 12. Development roadmap

A minor version tag is cut when each phase is completed; within a phase, every
app-affecting merge to `main` gets its own version (section 15).

| Phase | Version | Content | Output |
|---|---|---|---|
| 1 · Sensor Core | v0.1.0 | Project skeleton, CI + release pipeline (`check-tag`), permission flow, GPS + heading debug screen, device verification | Signed APK from the first tag; sensors verified in the field |
| 2 · Activity Engine | v0.2.0 | Start/Pause/Resume/Stop, TrackPoint + filters, auto-pause, distance/duration/pace/elevation, SQLite, history | An app that records Walk/Run |
| 3 · Foreground Tracking | v0.3.0 | TrackingService, notification, screen-off recording, recovery, battery/battery-saver tests | Put it in your pocket, walk, trust it |
| 4 · Custom MapView | v0.4.0 | Web Mercator, XYZ math, pan/zoom, tile cache, track layer — with a small test pack produced from CI | Live route on the map |
| 5 · Offline Maps | v0.5.0 | `map-pack.yml` pipeline, Maps screen (index.json, download, SHA-256, delete), attribution | Map in airplane mode |
| 6 · Navigation | v0.6.0 | Return to Start (bearing + distance + ETA), relative arrow, true north + disturbance warning | The "don't lose your way" promise is kept |
| 7 · Waypoints + GPX | v0.7.0 | Waypoint add/list/display on map and compass, GPX import/export (`trk`+`wpt`) | The data loop closes |
| 8 · Polish | v0.8.0 | Battery measurement, filter calibration, permission UX, error handling, accessibility | Quality bar on every screen |
| 9 · Release Candidate | v0.9.x | Section 13 field matrix tour by tour; fixes as z-releases | Field proof + Play readiness |
| MVP | **v1.0.0** | The scope in this document verified in the field | Norda stands |

### Sprint 1 concrete tasks (Phase 1)

- `com.aripd.norda` Kotlin project; `minSdk 26` / `targetSdk 35`; edge-to-edge
  drawing.
- CI: test + lint + debug APK on every push (`ci.yml`); signed release on tag
  (`release.yml` + `check-tag.sh`).
- `ACCESS_FINE_LOCATION` permission flow; denied / permanently denied states.
- `LocationManager` GPS updates; lat/lon/accuracy/speed on the debug screen.
- Rotation vector heading; angle + accuracy flag on the debug screen.
- Verification on different Android devices; the `v0.1.0` tag.

End-of-sprint goal: *pick up the phone → open the app → position and heading
on screen, CI green, the first signed APK in Releases.*

## 13. Test strategy

### 13.1 TDD working rules

1. Behavior arrives first as a **red test**: red → green → refactor.
2. The core is pure; tests run without a device, on the JVM, in seconds. The
   layers that touch SQL and Android are thin (8.2).
3. Wherever possible, **test against physical constants**: verify geodesy/the
   world, not your own output (equator bearing 90°, known tile coordinates,
   gain 0 on a flat series). The formula error in section 9 is the rationale
   for this rule.
4. Every module ships with its tests; untested code does not get merged.
5. The **"do the tests bite"** table: a deliberate bug is introduced and which
   tests fail is kept in the README.
6. For sensors, vibration and battery, manual verification on the device is
   placed **alongside** the unit test, not in its place; it is marked in the
   release notes. (Neither a `dumpsys` entry appearing nor the API returning
   `true` means the behavior actually happened — the only valid verification
   is the device itself.)

### 13.2 Unit test areas

Filters, statistics, elevation hysteresis, auto-pause decisions, stopwatch,
smoothing, disturbance hysteresis, bearing/distance/ETA, trail guidance
(nearest point, look-back, off-trail), Web Mercator and tile math (including
the TMS flip), over-zoom and continuous-zoom arithmetic, the empty-map
reason, geoid interpolation, the elevation median, solar altitude and the night-mode decision, GPX generation/parsing, row↔model mappers, waypoint
naming.

### 13.3 Field test matrix

| Area | Test |
|---|---|
| Location | Open field, city, narrow street, forest |
| Accuracy | GPS accuracy distribution across different devices |
| Distance | Comparison against known distances |
| Speed | Walking / running / standing transitions |
| Auto-pause | Distance/pace behavior during a 5–10 min pause |
| Elevation | Comparison against a known climb profile; ~0 on flat ground |
| Compass | Magnetically noisy and clean environments; in the pocket/in the hand |
| Background | Screen off / another app / lock screen |
| Battery | 30 min / 1 h / 2 h tracking — the app stores the level and the µAh charge counter at the start/end of a recording; the History row shows the consumption in percent and mAh plus the %/h rate (8.4) |
| Offline | Airplane mode + downloaded pack; Return to Start without a pack |
| Map | Pan / zoom / cache / large pack |
| Recovery | Process kill, reboot, service interruption |
| Permissions | Deny / revoke / change from settings |
| Night mode | Walking past dusk with "automatic"; the red filter and the toggle |

## 14. Non-functional requirements

| Criterion | Target |
|---|---|
| Startup | Home opens as fast as possible |
| Tracking | No data loss on long activities |
| Battery | GPS/sensor sampling is not kept at maximum unnecessarily; sensors are registered only when needed |
| Offline | Tracking, compass, navigation and GPX work without internet |
| Storage | Map packs are managed separately; activity data is independent |
| Privacy | Location data stays on the device; no telemetry; network rule (section 11) |
| Dependency | No external dependencies at runtime |
| Reliability | Data integrity is preserved after a process interruption |
| Release | A published release is immutable; red test = no release |

## 15. Process: versioning and release

### 15.1 SemVer map

- Before 1.0.0, `0.y.z`: **y** = new feature (typically a phase completion),
  **z** = fix. `1.0.0` = the scope in this document verified in the field.
- After that: **MAJOR** breaking change (data format, permissions), **MINOR**
  feature, **PATCH** fix.
- `versionCode = MAJOR×10000 + MINOR×100 + PATCH`; CI verifies its
  monotonicity.
- Every merge to `main` that affects the app is a release; docs-only changes
  do not get a version.

### 15.2 Release flow

```
1. Bump versionCode/versionName → commit
2. git tag vX.Y.Z && git push origin vX.Y.Z
3. release.yml: check-tag (tag ↔ versionName) → test + lint →
   signed APK + SHA-256 → GitHub Release (with automatic commit notes)
```

- `ci.yml`: test + lint + debug APK + report artifacts on every push; Gradle
  wrapper validation.
- Scripts live under `.github/scripts/` and also run locally (`check-tag.sh`,
  `collect-apk.sh`, `android-sdk.sh`).
- Signing: the key never enters the repository; Actions secrets —
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` (optional:
  `ANDROID_KEY_ALIAS`, `norda` if empty; `ANDROID_KEY_PASSWORD`, the keystore
  password if empty). If the secrets are missing the run does not break: the
  APK is produced unsigned and says so in its name.
- A published release is **immutable**: the files of the same version are never
  overwritten.
- If a release is needed from an environment without tag-push rights (e.g. a
  session that can only write to its own branch): `release.yml` is triggered
  manually with the `tag` input; `gh release create --target` creates the tag
  on the CI side. `check-tag` runs on both paths.

### 15.3 Commit discipline

Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` …) +
`CHANGELOG.md` (Keep a Changelog). Release notes become meaningful; the y/z
bump decision is read from the commits. It requires no tooling — it is
discipline.

## 16. Technical risks

| Risk | Level | Approach |
|---|---|---|
| Offline map renderer | High | Start with raster; evaluate the MapLibre gate after the Phase 4–5 measurements (7.1) |
| Tile pipeline setup cost | High | Start with a small test pack in Phase 4; pick the rasterization tool early and prove it in CI (7.2) |
| GPS accuracy | High | Tested filters + field matrix |
| Battery drain | High | Sampling + sensor lifecycle; a culture of measurement |
| Background restrictions | High | Foreground service + current Android/Play rules |
| Compass interference | Medium | Rotation vector + disturbance warning + calibration UX |
| Large pack storage | Medium | A separate `.mbtiles` per pack; measure and adjust |
| Routing expectation | Medium | Don't build it in the MVP; explain the Return to Start limit clearly |

## 17. Post-MVP roadmap

| Feature | Value |
|---|---|
| Download by free area selection | Rectangular area selection beyond the pack list |
| Daylight budget | Sunset time × return pace → "caught in the dark" warning |
| Get/share coordinates | `geo:`/text/map-link parser |
| Elevation profile | Cross-section chart in the activity detail |
| Voice announcements | Kilometer + split pace (TTS) |
| Records + weekly summary | Longest, fastest 1/5/10 km; weekly totals |
| Barometric altitude | Gain precision with the pressure sensor |
| Offline routing | Navigation on the road network |
| Wear OS · Cloud backup (opt.) · iOS | Later; iOS is a UI port as long as the core stays pure |

## Sources

- Android: [LocationManager](https://developer.android.com/reference/android/location/LocationManager) ·
  [SensorManager](https://developer.android.com/reference/android/hardware/SensorManager) ·
  [Location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions) ·
  [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types) ·
  [Play background location policy](https://support.google.com/googleplay/android-developer/answer/9799150)
- Map: [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/) ·
  [ODbL / attribution](https://www.openstreetmap.org/copyright) ·
  [MBTiles specification](https://github.com/mapbox/mbtiles-spec) ·
  [Geofabrik extracts](https://download.geofabrik.de/) ·
  [tilemaker](https://github.com/systemed/tilemaker) · [Planetiler](https://github.com/onthegomap/planetiler) ·
  [MapLibre Native](https://github.com/maplibre/maplibre-native)
