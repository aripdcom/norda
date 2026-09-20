# Field tour protocol — Phase 9 (RC)

The walkable form of the matrix in MVP.md 13.3. Every tour is run in this
order; findings are reported with the template below and fixes flow out as
z-releases (v0.9.1, v0.9.2, …). Manual verification on the device sits
*beside* the unit tests, not in their place (13.1/6).

## Before the tour

1. Install the latest RC release (signed APK from Releases); verify the
   version under Settings → Apps.
2. Download a map pack (Maps screen) or import one.
3. Start with the battery near 100% and battery saver **off** (on the first
   tours; on later tours it is deliberately tried on).
4. **Wait for GPS to settle** (on Home since v0.9.6): the app starts warming
   up GPS while Home is open; start once the line above START turns from
   "Searching for GPS…" into "GPS ready · ± X m". Closing and reopening the
   app does not affect the chip — the lock happens on the chip side, and
   waiting is the only remedy. Indoor spaces and 2–3 min attempts are not
   enough for the GPS warm-up; if no point ever enters, the recording is
   discarded together with the reason.
5. **If the search drags on even in the open** (satellite count on screen
   since v0.9.8 — "satellites 0/9" = many seen, no lock): Norda injects no
   assistance data (aGPS) (no Play Services); if the assistance is stale, the
   raw GPS almanac is downloaded from the satellites, which can take 2–15
   min. Shortcut: open Google Maps and, once the blue dot sharpens, return to
   Norda — the system's GNSS assistance is refreshed and the lock usually
   arrives within seconds. If "satellites 0/0" persists in the open, the
   problem is the device's GNSS/antenna.

## Tour steps

| # | Area | Step | Expected |
|---|---|---|---|
| 1 | Permissions | Revoke the permissions in Settings → open the app → START | Denial reason on screen; on permanent denial the "Open settings" dialog |
| 2 | Permissions | Turn off the location service → START | "Turn on location / Start anyway" dialog; once turned on, fixes flow by themselves |
| 3 | Location | 10+ min walk recording in the open | Track smooth, no scratches; filter counters in Diagnostics reasonable |
| 4 | Location | Narrow street / right beside buildings section | Spikes are filtered; accuracy/teleport counters rise |
| 5 | Distance | Recording over a known distance (e.g. 400 m running track ×2) | Target deviation ≤ 3%; write the value into the report |
| 6 | Speed | Walk → run → stop transitions | Pace settles with reasonable delay |
| 7 | Auto-pause | Stand still for 5–10 min | Auto-pauses (≈20 s), distance/pace do not inflate; resumes with movement |
| 8 | Elevation | Known climb (or flat road) | On flat road ▲ ≈ 0; on the climb the profile is reasonable |
| 9 | Background | Turn the screen off, switch to another app, lock (30+ min) | Recording continues; the notification shows state · duration · distance |
| 10 | Battery | 30 min / 1 h / 2 h tours | Write the History row's 🔋 line into the report — since v1.4.0 it reads "2.9% · 116 mAh · 4.5 %/h" from the charge counter; if it shows whole percent only, note what Diagnostics → BATTERY says about the counter |
| 11 | Compass | Compass ↔ known bearing in a clean area | "True north" label; deviation reasonable (±5°) |
| 12 | Compass | Approach metal/a magnet | Disturbance warning appears, disappears when moving away |
| 13 | Return to Start | Walk away during a recording → Compass | Bearing + distance + ETA; "on course" ±5° |
| 14 | Waypoints | "+ Point" while recording, long-press on the map, rename/delete | Shows correctly on the map and the compass |
| 15 | GPX | Export → delete from history → import the same file | Track + waypoints come back exactly as they were |
| 16 | Offline | Airplane mode: map, recording, compass, RTS | Everything works with/without a pack |
| 17 | Map | Pan/zoom/cache, large pack | No stutter; grid when there is no pack |
| 18 | Recovery | Kill the app's process during a recording | Service comes back, recording continues; if not, recovery at launch |
| 19 | Recovery | Restart the device (reboot) during a recording | At launch the unfinished recording is recovered into History |
| 20 | Battery saver | 30 min recording with battery saver on | If there is data loss, into the report with a duration/interval note |
| 21 | Breadcrumb (v1.2.0) | Record 300+ m out, then walk back following the Compass trail line; step 40 m off the trail once | The chevron and "Trail … back …" follow the recorded track (not the straight line); off the trail the line switches to "Off the trail … back to it"; near the start: "trail complete" |
| 22 | Night mode (v1.3.0+) | Set the Home line to "Night mode: automatic" and walk from before civil dusk until after it; then try "on" and "off" | Within a minute of civil dusk the screen reddens by itself, without leaving the screen; the map, the compass and the stats stay legible; the toggle forces the filter on and off immediately |

## Report template

```
Version: v0.9.x · Device: <model, Android version>
Tour date/duration: … · Weather/environment: …

Step | Result (✓ / ✗ + note)
...

Measurements:
- Distance: known … m ↔ app … m (deviation …%)
- Battery: 🔋 …% · … mAh (…%/h) — screen off/on ratio: …
- Filter counters (Diagnostics; since v0.9.2 also readable after the tour
  ends as "RECORDING FILTER (LAST)"): accepted … · accuracy … · jitter … ·
  teleport … · time …
- Elevation: expected ▲… ↔ app ▲…
```

Finding → issue or a direct message; every fix ships as its own z-release and
the tour is repeated on that release. Three clean tours = the v1.0.0 gate.

> Shortcut (v0.9.3+): finish the tour → export the track as GPX → share the
> file. Summary, battery and filter counters come inside the file
> (`norda:report`); of the measurements in the template only the
> known-distance comparison is written by hand.

## Tour log

| Tour | Date | Version | Result |
|---|---|---|---|
| 1 | 2026-08-25 | v0.9.0 | **Clean** — no issues. Measurements: 2.98 km · 28:16 active (42:46 wall clock) · ▲135 m · 🔋 2%. The GPX export was recomputed with the core: 962 points + 1 wpt; distance ✓ (raw 3513.7 − pause spikes 531.2 ≈ 2982.5), elevation ✓ (exactly 135.0), duration ✓. Analysis finding **F-1** → v0.9.1 |
| — | 2026-08-25 | v0.9.3 | *Invalid attempt* (does not count as a tour): 2 min run inside a tennis court, **battery saver ON**. Root cause proven by the F-2 counters: **all 0** — GPS never delivered a fix to the app (not an accuracy problem); the empty recording was discarded by design. Findings **F-4** → v0.9.4 (GPS state live on screen, discard message states the reason) and **F-5** → v0.9.5 (when battery saver is on, this is said in the status line and in the discard message) |
| 2 | 2026-08-27 | v0.9.5 | *With findings* (repeat required): the recording pipeline was flawless — cross-validation matched exactly: distance 3513.2 ↔ 3513.2 m (a 13.5 m pause spike correctly excluded), ▲123/▼144 exact, 1086 points = accepted counter, accuracy/teleport rejections 0. The user's report is consistent with the data: out 1677 m · 7:54/km run, back 1850 m · 9:42/km walk. 🔋 6% (~11 %/h, screen on — 4× Tour 1; the screen cost will be separated out with the matrix). Finding **F-6**: GPS acquisition — 1+ min of blind waiting after START, app restarts ineffective → repeat with v0.9.6 |
| 2r (repeat) | 2026-08-27 | **v0.9.8** | **Clean** — cross-validation accurate to 0.1 m: raw 1692.9 − manual-pause spike 22.6 = 1670.3 ↔ app 1670.4 m; ▲141/▼92 exact; 532 points = accepted counter. The 23.5 min manual break was correctly excluded; two ~35 s auto-pauses are included in the distance by design. Accuracy rejections 1, teleport 0. 🔋 3% (~4.0 %/h). The report carried `app="0.9.8"` (F-3+F-6 ✓ in the field). Note: the GPS lock came after Google Maps was opened — causality could not be verified; the satellite line (F-9) will give the answer on the next tours. A single settling spike of 12.7 m/s in the 2nd second of the first fix stayed under the filter ceiling (impact ~13 m, noted as an observation) |
| 3 | 2026-08-29 | v0.9.8 | **Clean** — 57:17, battery saver off. Cross-validation **ZERO difference**: 5613.7 ↔ 5613.7 m (no manual break; 4 micro gaps ≤7 s); ▲202/▼189 exact; 1738 points = accepted counter; accuracy/teleport/time rejections 0. 🔋 9% (~9.4 %/h, **Norda alone, screen mostly OFF** — only 4-5 short glances at the clock; Strava did not run in parallel. Highish for screen-off → **watch item (B-1)**: possible contributors are the long acquisition period [full power until the GNSS lock], chip effort without aGPS, non-linearity of the 99% upper band. A battery-saver tour + one normal tour will triangulate; if it stays in the 9-10 band, a sampling improvement is discussed for v1.0.x). Acquisition: the satellite line did its first job in the field — the user watched "GPS 3/22" (in fix/seen) and started with the lock; seeing 22 satellites established that the sky was perfect and that the acquisition is slow by its aGPS-less nature. The section walked before the lock is unrecorded by design (only quality real GPS is written; Strava fills the same interval with a network point). **Strava cross-validation:** the same GPX in Strava 5.62 km · 56:36 — Norda 5.61 km, point span 56:36 → three sources agree ✓ |

| + | 2026-08-30 | v1.0.0 | *Post-1.0 verification tour, clean*: ZERO difference 4621.3 ↔ 4621.3 m; ▲124/▼159 exact; 1421 points = accepted; rejections 0/0/0. 🔋 3% (~4.0 %/h; screen mostly off, single app, battery saver off) → **B-1 updated:** normal band ~4 %/h (2.8 · 4.0 · 4.0); Tour 3's 9.4 is an outlier (long acquisition + screen suspected). Finding **F-10**: after waiting 2:30 for a lock, entering and leaving Compass brought the lock INSTANTLY — the compass's network-provider request seeds the GNSS (the same mechanism as the Google Maps shortcut, second field evidence; the acquisition gap of active − point span = 2:25 matches the user's report exactly) → v1.0.1 |

| + | 2026-08-30 | v1.0.1 | *Night tour (4:45 warm-up walk + 30:05 run), clean — **F-10 confirmed in the field:** the wait for a lock dropped from 2:30 to seconds (the user's report; seeding happened by itself, no shortcut). Cross-validation again ZERO difference: 4776.2 ↔ 4776.2 m; ▲174/▼182 exact; 1372 points = accepted; jitter 344 (natural for walking/standing at ~1 s cadence), teleport 1, accuracy/time 0. 🔋 3% / 36:02 ≈ 5.0 %/h (the B-1 band holds at ~4–5; percentage granularity on a short tour is ±1). **Second-device comparison (first time):** the Strava recording of the friend who ran along captured only the run — run segment Norda 4281.2 ↔ Strava 4379.7 m (difference −2.25%; Strava's unfiltered raw total inflates slightly, the direction is as expected), route agreement median 2.1 m / p90 6.4 m. Finding **F-11**: settling spike for the SECOND time (12.85 m/s, 25.7 m in the 2nd s of the first fix; 12.7 m/s on 2r) → v1.0.2. Watch item **Y-1**: elevation compared against an independent reference for the first time — where Norda's raw GPS sees a ~35 m band on the run section, Strava's DEM-corrected track gives a ~17 m band; raw GPS vertical noise reads the gain high, DEM/baro correction is a post-MVP candidate; data will be collected over the tours* |

| + | 2026-09-01 | v1.0.2 | *Matrix 20 (battery saver ON, 33 min open-air walk) — step completed, finding **F-12**: the system STOPPED location while the screen was off. 61 points remained from the 33:28 tour (at normal cadence it would be ~1300); 6 gaps >5 s totalling 31:39, the longest **20:28**. Fixes flowed only while the device was awake: the moment of START, glances at the screen and the 1:24 phone call at 13:12 — a 54-point cluster coinciding exactly with the user's report, the very proof of "awake CPU = flowing GPS". GPS lock fast (F-10 ✓ for the third time); first fix clean, no settling spike (first field tour of the F-11 gate). Cross-validation again ZERO difference: 2074.7 ↔ 2074.7 m — but 91% of the distance is the straight line of the gap legs: recorded 2.07 km, a lower bound of the real path (~2.6–3 km). 🔋 2% / 33:31 ≈ 3.6 %/h (the battery gain from battery saver is marginal, the data loss heavy). **Y-1 gathered evidence:** ▲132/▼133 on a flat seaside walk — including the absurd ele=115 m of a single fix at 12:51 (its surroundings 40 m); with sparse+poor fixes, vertical noise dominates the gain. The seaside was later confirmed by the user's report too, and a calibration gift fell out of it: median ele at the water's edge 39 m (10th–90th percentile: 38–43) — since the WGS84 geoid separation in Istanbul is ~+37 m, this is sea level itself: the device reports ELLIPSOID height, as `getAltitude` documents, and applies no geoid correction. A constant offset does not affect ▲/▼, but absolute altitude reads ~37 m high on screen/in the GPX; the 115 fix means a ~75 m single-fix vertical error. The future fix is two layers (post-MVP): geoid separation for absolute altitude, DEM/baro for gain. → v1.0.3* |

| + | 2026-09-02 | v1.0.3 | *First tour of the free period (brisk walk 6.27 km, 58:52 point span / 61:38 active), clean — no finding in the recording pipeline. Cross-validation ZERO difference: 6268.6 ↔ 6268.6 m; ▲215/▼244 exact; 1885 points = accepted. Acquisition 2:46 (the user's report "~2 min" + the Finish tail): the recording was started while leaving an indoor space — with the sky blocked, network seeding cannot change the physics (F-10 solves almanac starvation, not the wall), expected by design. **The indoor passage (reported at 09:03–09:05) matches the data exactly:** point density thinned 33→12/min, a 48 s gap was bridged with a 102 m straight line (2.13 m/s, plausible) — inside, the device went quiet instead of producing bad fixes (accuracy rejections 1 over the whole tour); filter+bridge carried the passage gracefully. A below-ceiling settling jitter at the start: first step 8.73 m/s (the tour's maximum, impact ~10 m) — the documented residual band of the F-11 gate; teleport rejections 1. 🔋 6% / 61:38 ≈ 5.8 %/h — the start was at 100%: upper-band non-linearity suspected (B-1 note: tours starting from a full charge may read high), band recording continues* |

| + | 2026-09-06 | v1.0.4 | *Morning walk (4.32 km, 44:29 point span / 46:42 active), clean — no finding. Cross-validation ZERO difference: 4317.4 ↔ 4317.4 m; ▲113/▼138 exact; 1360 points = accepted; rejections: accuracy 1, teleport 0 (jitter 931 — the usual ~1 s cadence alternation, no distance lost). Start clean: first steps 2.6–3.9 m at walking pace, no settling spike (second field tour of the F-11 gate). Acquisition + finish tail 2:13 (active − point span). Five micro gaps of 6–9 s, longest 9 s. 🔋 4% / 46:42 ≈ 5.1 %/h — mid band (B-1: 2.8 · 4.0 · 4.0 · 5.0 · 5.8 · 5.1). One waypoint. First recording made with the OSM map pack available (Istanbul v2); map feedback: "the colors are nice but I could not zoom" → **F-13** → v1.0.5* |

| + | 2026-09-12 | v1.1.0 | *Night walk (3.42 km, 36:21 point span / 38:50 active), clean pipeline — first field tour of v1.1.0 (continuous zoom; map impressions still pending). Cross-validation ZERO difference: 3420.8 ↔ 3420.8 m; ▲79/▼71 exact; 1074 points = accepted; rejections: accuracy 1, teleport 0 (jitter 727, ~1 s cadence). Start clean: first steps 2.6–4.6 m at 2 s (F-11 gate, third field tour). Acquisition + finish tail 2:29; one 6 s gap. 🔋 80% → 80% over 38:50 — a 0 %/h reading is a gauge plateau, not physics (B-1 note: some phones hold 80% for a while; the band stays ~4–5 %/h). **Second-device comparison (companion's Strava, walking, no timestamps in the export):** the route is a loop, so start/end were matched by distance consistency — Norda idx 85→920 (22:47:26→23:14:57, 27.5 min): **Norda 2652.1 ↔ Strava 2866.2 m (−7.5%)**; Norda recorded a further 290 m before and 479 m after the companion's window. Route agreement median 3.5 m / p90 7.9 m / max 14.7 m — the tracks lie on each other; the gap is in the per-step sum: at ~1 Hz walking pace a raw sum adds every metre of GPS wobble, the 2 m jitter gate does not. Which is closer to the truth cannot be decided from two phones — **open item D-1:** a hand-measured reference (matrix step 5, e.g. a 400 m track ×2 or a marked seaside kilometre) to calibrate the distance once and for all. Elevation: Strava DEM 102–119 m vs Norda 135–165 m (the ~37 m ellipsoid offset again, Y-1)* |
| + | 2026-09-13 | v1.3.0 | *Morning walk (3.04 km, 29:10 point span / 32:58 active), clean pipeline — cross-validation ZERO difference: 3044.0 ↔ 3044.0 m; ▲92/▼74 exact; 823 points = accepted; rejections: accuracy 7, teleport 4, jitter 504. Median step 1.80 m/s (6.5 km/h, brisk walk). **3:37 GPS outage mid-walk** (08:45:54→08:49:31 local, 317 m crossed as an air line — the honest lower bound of a covered stretch, as in F-12). **Settling spikes past the first fix (Y-2):** the first nine seconds hold 9.1 m in 1 s, **48.3 m in 5 s (9.65 m/s)** and 7.2 m in 1 s — about 52 m of phantom distance that the F-11 gate does not catch, because it validates the first fix only and the 10 m/s cap is a running cap. Over the whole walk 48 steps are faster than 3 m/s, 251 m in total (8% of the distance) on a track whose median step is 1.8 m/s. 🔋 84% → 81% over 32:58 ≈ 5.5 %/h (B-1 band ~4–5, slightly above). **Report: "the map does not show during use" → F-15.** Night mode could not be tested: the walk was 08:43–09:12 local with the sun at +22°, so automatic mode was correctly in daylight — checked by running `core/sun/Sun` against the file's own timestamps (that day: sunrise 06:45, civil dusk 19:45 local)*
| + | 2026-09-13 | v1.4.0 | *Second walk of the day, seaside (3.21 km, 34:49 point span / 35:11 active) — **the cleanest tour so far.** Cross-validation ZERO difference: 3212.1 ↔ 3212.1 m; ▲68/▼71 exact; 983 points = accepted; rejections **accuracy 0, teleport 0** (jitter 814). Acquisition + finish tail **22 s** — the fastest lock recorded (F-10 seeding plus open sky at the water). One 21 s gap while standing (2 m of movement), two gaps over 5 s totalling 27 s. Max step 5.04 m/s, and only 3 steps above 3 m/s totalling 16.7 m (0.5% of the distance) — **Y-2: the morning walk's 48 m settling jump did not repeat**, the first four seconds here hold 7.4 + 4.2 + 5.0 m and then settle; the spike is episodic, not a constant. **Y-1 confirmed a second time, same number:** median elevation at the water's edge **39 m** (p5–p95 34–43, mean 38.6) — identical to the Sept 1 seaside median, and the band is only 9 m wide, so this is bias and not noise. Sea level is a few metres, the Istanbul geoid separation is ~+37 m: the device reports ellipsoid height, exactly as `getAltitude` documents. **The charge counter works on this device (B-1, first v1.4.0 recording):** 627 165 µAh at 16% and 2 174 172 µAh at 57% — two independent readings give a full charge of 3920 and 3814 mAh, 2.7% apart, so the capacity estimator holds. No consumption number, and correctly so: the phone was fed during the walk (16% → 57%, counter +1547 mAh). That silence was itself a finding → v1.4.1 shows "🔋 charging" instead of an empty line*
| + | 2026-09-13 | v1.5.0 | *Night run with a companion (4.07 km, 33:53 point span / 34:03 active) — **the cleanest continuity so far: not a single gap over 5 s** (longest 4 s), acquisition + finish tail **10 s** (the recording was started during the warm-up). Cross-validation ZERO difference: 4069.8 ↔ 4069.8 m; 1274 points = accepted; rejections **accuracy 0, teleport 0** (jitter 456). Median step 2.12 m/s (7.6 km/h, a run), max 4.42 m/s — no settling spike, Y-2 stays episodic. **First real charge-counter measurement (B-1):** 47% → 45%, counter 1 828 281 → 1 725 654 µAh = **103 mAh · 2.64% · 4.67 %/h** — the band holds, and the measurement no longer waits for a whole percent to tick. **Geoid correction validated by an independent source (Y-1):** against the companion's DEM-corrected Strava track at matched positions, Norda's corrected elevation sits at a median of **−1.8 m** (p10 −5.2, p90 +3.0); the raw ellipsoid heights would have been **+35.5 m** out. **Second-device comparison, the best yet:** route agreement median 2.1 m / p90 6.8 m / max 17.4 m; over the matched window (20:32:05→21:02:17, 30.2 min) **Norda 3686.8 ↔ Strava 3740.5 m, −1.44%** — Norda also recorded 367 m of warm-up before the companion started. Elevation ▲71/▼107 from the app; the same file recomputes to ▲61/▼97 because its `ele` is now sea level, and adding the separation back reproduces the app exactly → watch item **Y-3** and a fixed analysis tool. **Night mode came on by itself (matrix 22 ✓)** but the screen was "blazing red, very hard to read with astigmatism" → **F-16** → v1.6.0*
| + | 2026-09-15 | v1.6.0 | *Short evening walk (1.64 km, 16:47 point span / 20:40 active, 22:54–23:11 local) — cross-validation ZERO difference: 1640.0 ↔ 1640.0 m; 480 points = accepted; rejections accuracy 1, teleport 0 (jitter 344); three gaps over 5 s, longest 8 s; acquisition + finish tail 3:53 (started indoors). **Second charge-counter measurement (B-1):** 3 352 482 → 3 333 477 µAh = **19 mAh · 0.50% · 1.78 %/h** — far below the 4.67 %/h of the Sept 13 run, and the counter is precise enough that the difference is real rather than rounding; the old "4–5 %/h band" came from integer readings whose error bars were ±1.5 %/h, so the band itself needs rebuilding from counter data. **▲118 m on a 1.64 km walk → Y-3 evidence:** the profile is a genuine climb (123 → 180 m raw over the outing, ~57 m net) with ±10 m oscillations riding on it; a 9-sample median leaves ▲84. **Y-2 again, strongest yet:** 54 steps above 3 m/s totalling 280 m, **17.1% of the distance**, top step 8.88 m/s (32 km/h) on a walk whose median step is 1.61 m/s. Night mode was on (amber by default since v1.6.0); no legibility complaint this time, awaiting the verdict*
| + | 2026-09-17 | v1.6.0 | *Afternoon walk with a long indoor stretch (app 3704.5 m, 76:59 point span / 63:58 active, 14:26–15:43 local) — 1242 points = accepted; rejections accuracy 0, teleport 1 (jitter 1317); 16 gaps over 5 s totalling 30:50. **First cross-validation that did not match at first sight:** the raw sum of the exported points is 4021.4 m against the app's 3704.5 m, and the difference — **316.9 m** — is exactly the one jump across the 24:36 gap at 15:06 (317.0 m). So the app skipped that leg, which in the code only a **manual pause** does (`resumeManual` sets the break; the settling gate cannot reopen mid-recording). The user reports going into a shopping mall with the recording left running → **F-17**: the file cannot tell a pause from a GPS outage, the two differ by hundreds of metres, and only the pause path drops the leg. **Resolved by the user the same day: the Pause button was tapped deliberately, a while after going inside.** So nothing misbehaved, and the pocket-tap suspicion is closed without a finding. The arithmetic fits the account exactly: of the tour's 76:59 span, 13:01 was not active (manual pause plus any auto-pauses), so **at least 11:34 of the 24:36 gap was recorded-but-fixless indoor time** and the rest was the deliberate pause. Two behaviours are visible in one gap: indoors the device offered nothing at all rather than bad fixes (accuracy rejections 0 over the whole tour — the clean-track stance doing its job), and the paused leg was dropped from the distance. **Third charge-counter measurement (B-1):** 3 500 721 → 3 189 039 µAh = **312 mAh · 8.19% · 6.38 %/h** over wall time (7.68 %/h over active time), and the integer gauge agrees for once (9% → 7.0 %/h) because the tour is long enough for granularity not to dominate. Counter band so far: 1.78 · 4.67 · 6.38 %/h. Elevation ▲138/▼219 raw over 3.7 km with a 97 m corrected span — Y-3 again*
| + | 2026-09-18 | v1.6.0 | *Short morning walk (1.12 km, 12:19 point span / 13:35 active, 09:28–09:40 local) — clean: cross-validation ZERO difference (1120.4 ↔ 1120.4 m), 358 points = accepted, accuracy rejections 0, one 6 s gap; the raw reconstruction reproduces ▲44/▼34 exactly. Teleport rejections 4 on a 12-minute walk, all filtered. **Amber confirmed in the field (F-16 closed, matrix 22 complete):** the user tested the strengths on the previous night walk — "amber looks better this way" — so the default stays where v1.6.0 put it. **Fourth charge-counter measurement, and it reframes B-1:** 106.4 mAh in 12.3 minutes = 8.64 mAh/min, against 0.92 on the Sept 15 evening walk of similar length. A 9× spread across four tours is not a GPS figure: the counter measures the **device**, so it includes the screen at outdoor brightness and anything else running*

Gate status: **3/3 clean tours — v1.0.0 CUT (Aug 29).**

> The matrix was completed on September 1: the last step, 20 (battery saver
> on), surfaced F-12 and was closed with v1.0.3 — all 20 steps have been run
> in the field.

### Findings

- **F-1** (Tour 1 analysis, fixed → v0.9.1): the battery rate in History was
  divided by active time; since GPS stays on during pauses too, battery
  drains by wall clock. What showed as 4.2 %/h on the tour was really
  2.8 %/h. The denominator is now the wall clock from recording start→end;
  the field data was added to the core as a regression test.
- **F-2** (after Tour 1, fixed → v0.9.2): the filter counters were visible
  only while a recording was running — hard to find and note down in the
  field. When a recording ends, the last recording's counters are stored
  persistently (surviving process death) and stay visible in Diagnostics as
  "RECORDING FILTER (LAST)"; they are written for an empty/discarded
  recording too (the answer to "why is the recording empty" is usually
  found here).
- **F-3** (after Tour 1, fixed → v0.9.3): so that nothing needs to be noted
  by hand for the tour report, telemetry travels inside the GPX: the app
  summary + battery + filter counters are embedded in the
  `extensions`/`norda:report` block. Sharing the GPX = sharing the tour
  report.
- **F-4** (court attempt, fixed → v0.9.4): the time passing while GPS had not
  settled was not visible on screen — the 2 min attempt silently stayed
  empty, was discarded, and the reason could not be understood. While no
  point has entered the recording, the status line shows live GPS quality
  ("Waiting for a fix…" / "GPS ± X m"); the discard message states the
  reason: "GPS never delivered a fix" ↔ "GPS accuracy never got under the
  threshold (best ± X m)". The filter threshold did not change — the 30 m
  quality gate is deliberate.
- **F-5** (same attempt, fixed → v0.9.5): battery saver was on during the
  attempt as well, and many devices throttle GPS in this mode — the app could
  see this (`isPowerSaveMode`) but did not say so. Now, while GPS has not
  settled, "· battery saver on" is appended to the status line; the empty
  recording's discard message also carries the note
  "Battery saver was on — it can restrict GPS." The mode is not blocked:
  step 20 of the matrix (recording with battery saver on) is a supported
  scenario; it is only made visible.
- **F-10** (post-1.0 tour, fixed → v1.0.1): entering and leaving the Compass
  screen while waiting for a lock brought the lock instantly — the compass
  also listens to the network provider for declination, and on many devices
  this request seeds the GNSS engine with a coarse position, cutting the lock
  from minutes to seconds (the same mechanism as the Google Maps shortcut;
  two independent pieces of field evidence). The same seeding is now
  deliberate: the Home warm-up and the recording service listen to the
  network ONLY as a seed — a provider filter keeps a network fix out of both
  the indicator and the track (the clean-track stance is preserved), and the
  seed is released with the first real GPS fix (battery rule). Confirmed in
  the field on the night tour: lock in seconds instead of 2:30.
- **F-11** (2r + night tour, fixed → v1.0.2): during first-fix "settling" the
  first fix came in 13–26 m off on two tours (12.7 and 12.85 m/s — just
  under the old 15 m/s ceiling), and because it was made the anchor, the
  phantom distance was still counted with the next point even when the
  jumping point was rejected. Two-part fix: the speed ceiling was calibrated
  to 10 m/s with field data (the calibration MVP 5.2 foresaw; in a
  walking/running product, movement above 36 km/h is not running), and the
  first fix is now a CANDIDATE, not an anchor — it does not enter the
  recording until physical consistency is confirmed by the second fix; if
  that yields a double teleport, the culprit is the first fix and the
  candidate is replaced. Expected effect on the night data: 4776.2 → 4750.5 m
  (the 25.7 m phantom start would never have been born). The cost is a
  one-fix delay (~1–2 s).
- **F-12** (matrix 20 tour, fixed → v1.0.3): in battery saver the system
  (documented Android 9+ behavior) can stop the location service while the
  screen is off: on the 33 min tour fixes flowed only while the device was
  awake — 61 points, a single 20:28 gap; the 1:24 phone call coincided
  exactly with a 54-point cluster (awake CPU = flowing GPS). F-5 said this in
  the status line; now, if battery saver is on when START is pressed, a twin
  of the location-off dialog appears: "Battery saver is on. While the screen
  is off the system can stop GPS — the track may come out sparse." →
  Battery settings / Start anyway. The mode remains supported (the matrix 20
  scenario); data loss is no longer a surprise but a conscious choice. Gap
  legs continue to count in the distance as a straight line — an honest
  lower bound of the real path.
- **F-9** ("keeps searching for GPS" report after 0.9.7 → v0.9.8):
  "Searching for GPS…" alone did not say why — no sky, a lock not arriving,
  or a faulty device could not be told apart. Satellite visibility was added
  (`GnssStatus`): the Home line says "Searching for GPS… · satellites 0/7",
  and the location section of Diagnostics carries a
  "satellites: 0 in fix / 7 seen" line. How to read it: 0 seen = no
  sky/antenna (indoors); many seen–0 in fix = cannot lock (wait, or the
  device's aGPS is stale); ≥4 in fix = a fix is moments away. Note: the quick
  "fix" in Diagnostics comes from the network provider and cannot enter the
  recording — recording wants real GPS only.
- **F-7** (field use, fixed → v0.9.7): the installed version was not shown
  anywhere on screen — the question "which version are you on" went to
  Settings. The bottom of Home now reads "v0.9.7"; together with the `app`
  attribute in the GPX report, the version is both on screen and in the file.
- **F-8** (field use, fixed → v0.9.7): returning to Home while a recording
  was running, the button still said "START" — it was perceived as starting a
  new recording (technically no new recording was opened; the service is
  protected). While a recording is running the button becomes
  "BACK TO RECORDING", the type selection is locked, and a tap returns
  straight to the recording without entering the permission/location dialogs.
- **F-6** (Tour 2, fixed → v0.9.6): the GPS chip only starts searching when
  someone asks for it; while waiting on Home nobody was asking → 1+ min of
  blind waiting after START, app restarts ineffective (chip side). The "fix"
  in Diagnostics was misleading because it came from the network provider.
  Fix: GPS is pre-warmed while Home is open (released on leaving the screen)
  and a readiness line appears above START: "Searching for GPS…" →
  "GPS ready · ± X m". The `app` version was also added to the GPX report —
  the file now says which version wrote it.
- **F-13** (first look at the OSM pack, fixed → v1.0.5): "the colors are
  nice but I could not zoom". The map locked its zoom to the pack's range;
  with the parity grid nobody missed the levels above z13, with real
  cartography z13 (~19 m/px) is too coarse for street level. The map now
  zooms up to three levels past the pack ceiling by scaling the ceiling
  tiles (`core/map/Overzoom`, JVM-tested); lines soften with each level —
  the honest cost of not having the data. Rendering packs to z14 stays a
  candidate if the field asks for sharper streets.
- **F-17** (Sept 17 walk, fixed → v1.7.0): the file could not say where the
  recording was paused. A 24:36 gap carrying a 317 m jump looks identical in
  the GPX whether the user paused deliberately, the app auto-paused, or GPS
  went silent indoors — yet the three mean different things for distance: the
  live session skips a manually paused leg and counts an outage as an air line.
  It took a code reading to tell which had happened, and no third-party tool
  could tell at all. From this version the point that opens a leg after a
  resume carries a flag (`track_point.after_pause`, schema v5), GPX export
  starts a new `<trkseg>` there — the standard way to mark a break, so other
  tools stop drawing and counting across it — and import reads the break back,
  so a round trip returns the same distance. Two recomputations that used to
  re-count those legs were fixed with it: recovery after process death and GPX
  import both call the pause-aware distance now (`core/track/Stats`, JVM
  tests; core: 168).

  One nuance the same tour documents: when the pause begins inside a fixless
  stretch, there is no fix to split the leg at, so the whole leg is dropped —
  here about 11½ minutes of indoor walking shares its leg with 13 minutes of
  deliberate pause. That is the conservative and reproducible choice (the live
  session did the same), and it is the honest lower bound of a distance nobody
  measured.

- **F-16** (first field night of night mode, fixed → v1.6.0): "the screen was
  blazing red; with astigmatism it was very hard to read". Automatic mode
  itself worked — the filter arrived by itself at civil dusk, which closes the
  behaviour half of matrix step 22 — but the colour was wrong for the eye
  behind it. Deep red protects dark adaptation best precisely because the eye
  is least sensitive to it, and that is also why it reads worst: red and green
  come to focus at different distances, and an astigmatic eye widens the gap.
  The filter now has **three strengths** on a long press of the Home line —
  soft (blue stripped), **amber (the new default)**, deep red — and the
  multiplier colour is chosen per strength rather than being a constant. The
  mode stays a tap away, because turning the filter off is the thing one wants
  quickly in the dark. **Confirmed in the field** on the next night walk:
  "amber looks better this way" — the default stays, and matrix step 22 is
  complete on both halves, the automatic arrival and the legibility.

- **Y-3** (elevation metric, fixed → v1.8.0): gain and loss were inflated and
  unstable. *Instability:* perturbing a real 1274-point series by ±1 cm moved
  the gain between **50 m and 76 m**, because a perturbation flips which step
  crosses the 4 m threshold. *Magnitude:* across sixteen field tours a median
  before the accumulator removes about a **quarter** of the total gain, and on
  the three tours with a companion's DEM-corrected track the same accumulator
  over that smooth series gives 35, 29 and 46 m where Norda gave 71, 79 and
  174. A DEM erases genuine micro-relief, so the truth sits between, but the
  direction was never in doubt.

  Fixed by feeding the accumulator a **±5 s median** (`core/track/
  AltitudeSmoother`, 6 JVM tests; core: 174). Measured on the field files
  through the production path: Sept 18 ▲44 → ▲36.5, Sept 15 ▲118 → ▲91,
  Sept 17 ▲138 → ▲121, and the ±1 cm band narrows from 50–76 m to 35–49 m.
  Three decisions are worth keeping: the window is bounded in **time** rather
  than samples (nine samples span ten minutes on the sparse battery-saver
  tour and flatten it to zero); a window with fewer than three samples passes
  through untouched; and the **4 m threshold stays** — lowering it after
  smoothing was measured and re-inflates the total, so the pair is calibrated
  together. Past recordings keep the numbers they were recorded with: ▲/▼ was
  always a noisy estimate, and rewriting stored figures retroactively would be
  worse than a dated method change. The analysis tool prints both methods, so
  older files can still be cross-validated.

- **Y-1** (elevation, fixed → v1.5.0): absolute altitude read about 37 m
  high. Three independent measurements agreed. Two seaside walks (Sept 1 and
  Sept 13) put the median elevation at the water's edge at **39 m**, the same
  number twice, with a 9 m wide p5–p95 band — bias, not noise. A companion's
  DEM-corrected Strava track read 102–119 m where Norda read 135–165 m on the
  same path. And the cause is documented behaviour: the platform's
  `getAltitude` returns height above the **WGS84 ellipsoid**, while maps, DEMs
  and signposts use height above the geoid, which is mean sea level.
  Four options were weighed on accuracy, coverage, size and runtime cost
  (labelling only; the receiver's own NMEA value; a value per map pack; an
  embedded global table). The geoid grid was chosen and measured before being
  built: against the 15′ EGM96 reference, a 1° table costs **0.76 m RMS** in
  Turkey and 130 KB of data, while 2° would be 1.90 m for 33 KB and 0.5° would
  be 0.21 m for 520 KB. On the Sept 13 seaside walk the shipped table gives a
  separation of **37.4 m**, turning the 39 m median into **1.6 m** — the
  water's edge, which is where the walk was. The database still stores raw
  ellipsoid heights; the correction is applied on the Diagnostics screen and in
  GPX, so gain and loss are untouched and past recordings are corrected too.
  Single-fix vertical noise (the 115 m fix of Sept 1, ~75 m off) is a separate
  problem and stays with the DEM/baro candidate.

- **B-1** (battery measurement, sharpened → v1.4.0; reframed by the counter
  data): the whole-percent gauge could not measure an outing — the Sept 12
  night walk read **80% → 80% over 38:50**, and tours starting from 100% read
  high. From v1.4.0 a recording stores the **µAh charge counter** at both ends
  (`BATTERY_PROPERTY_CHARGE_COUNTER`): consumption comes out in mAh, the
  percentage is fractional (the difference over a full-charge estimate, which
  a counter reading at a known level gives), and the rate keeps its wall-clock
  denominator (F-1). The cleanliness rule stands: no counter on the device,
  charging during the recording or a span under five minutes yields no number,
  and Diagnostics → BATTERY shows whether the device serves the counter at all.

  Four real measurements later, the old "4–5 %/h band" turns out to have been
  an artifact of rounding, and the counter says something more useful:

  | Tour | Minutes | mAh | mAh/min | Start |
  |---|---|---|---|---|
  | Sept 15 evening walk | 20.7 | 19 | 0.92 | 88% |
  | Sept 13 night run | 34.0 | 103 | 3.02 | 47% |
  | Sept 17 afternoon walk | 77.0 | 312 | 4.05 | 92% |
  | Sept 18 morning walk | 12.3 | 106 | 8.64 | 95% |

  A ninefold spread, with the highest figure on the shortest walk, is not a
  GPS number: the counter measures the **device**, not the app, so it includes
  the screen at outdoor brightness and whatever else the phone was doing. The
  useful reading is the **floor**: 0.92 mAh/min is about 55 mA, roughly
  **1.4 %/h** on this ~3.85 Ah battery — that is what a recording with the
  screen off costs, and it is a good number for an outdoor app. Everything
  above it is screen and company. To make the split measurable rather than
  argued, the candidate is to log the **screen-on time** during a recording
  and carry it in the report; then energy decomposes into a GPS baseline plus
  screen minutes, and %/h stops being a single band that cannot exist.

- **F-15** (v1.3.0 field report, fixed → v1.3.1): "the map does not show
  during use". The renderer draws its grid wherever a tile is missing, and
  that grid is the same picture for three causes — no pack installed, a pack
  whose bounds end before this area, a tile the pack never got — so the report
  could not be narrowed down afterwards. From this version the screen names
  the cause instead: a one-line hint over the map on both the recording screen
  and the map screen ("No map package — download one from the Maps screen", "X
  doesn't cover this area", "X has no tile for this spot (z14)"), and
  Diagnostics lists the installed packs with zoom range, size and bounds. The
  decision is pure (`core/map/MapCoverage`, 6 JVM tests). Two real defects
  that produce exactly this symptom and never recover were fixed with it: the
  map view closed its pack when detached from the window and never reopened it
  (grid for the rest of the outing), and the recording screen opened a pack
  only once per recording, so a pack downloaded in the middle of an outing was
  ignored until the next recording. The tour data itself was clean (zero
  difference), which is consistent with the map being a display problem only.

- **Y-2** (watch item, v1.3.0 walk): the filter is calibrated for running,
  and a walk shows it. The 10 m/s cap (F-11) let a **48.3 m step in 5 s
  (9.65 m/s)** through four seconds after the first fix — settling drift that
  the F-11 gate does not see, because that gate validates the first fix
  against the second and says nothing about the third. Over the same walk 48
  steps were faster than 3 m/s, 251 m in total (8% of the distance) where the
  median step was 1.8 m/s. Two candidates: a type-aware cap (walk ~4 m/s, run
  ~7 m/s) and an accuracy-proportional gate for the acquisition phase, when
  fixes sit near the 30 m accuracy limit. The seaside walk the same afternoon
  did **not** repeat it — 3 steps above 3 m/s, 16.7 m in total (0.5%), maximum
  5.04 m/s — so the pattern is episodic and tied to a poor acquisition, not a
  constant tax on every walk. Both candidates wait for **D-1**: the
  known-distance calibration decides whether the distance error is dominated
  by these spikes or by the 2 m jitter gate, and the second-device comparison
  said Norda reads *lower* than an unfiltered sum — so tightening blindly
  would be a guess, not a fix.

- **F-14** (v1.0.5 map test, fixed → v1.1.0): "zooming is not smooth, it
  goes step by step, and once zoomed in the pixels are very visible". Two
  separate causes. The pinch handler jumped a whole level once the fingers
  had moved 1.4× — the map now carries a fractional zoom and draws tiles
  scaled between 0.71× and 1.41× around the fingers (`core/map/
  ContinuousZoom`, JVM-tested). The pixels were the +3 over-zoom (8× of a
  z13 tile); the cap is now +2 and the Istanbul pack is rendered to z14, so
  the map reaches z16 with at most 4× magnification and is crisp up to z14
  (pack ~4× larger). A z14 pack alone would not have smoothed the gesture.
  Confirmed in the field (Sept 12, v1.1.0 + Istanbul v3): "the sharpness of
  the v3 pack is quite good, the pinch smoothness is very nice".
