# Offline map background (WIP)

This branch builds on `navigation-maneuver-icons`. The existing PR branch is
unchanged. Map support is isolated in `OfflineMaps`, `OfflineMapView`, and
`app/assets/offline-map`; there are no new firmware RPCs or build dependencies.
The same code and resources can be ported to SWI69, SWI132 and SWI133.

## Behaviour

Settings → Maps enables offline maps (off by default), controls Wi-Fi-only
downloads (on by default), and lists region sizes, progress, pause/resume,
verification, deletion, and available storage. Downloads continue if Settings
closes, but are not a foreground service: Android may end the process. An
interrupted download can be resumed explicitly after restarting the launcher.
One download runs at a time. Partial downloads count toward used storage.

Settings also selects the download location: head-unit internal storage or a
mounted removable volume exposed by Android through an app-specific files
directory. Every available drive is listed separately with its label, UUID, free/total
space and the map directory. This allows choosing a different stick from the
360-camera drive connected to USB-C. Physical USB-A/USB-C port mapping is not
inferred from the storage API. The selected removable volume is identified by
its UUID; no absolute path is persisted. USB and SD volumes are accepted through the same API, without
firmware-specific mount paths or extra broad storage permissions. Some OEM USB
implementations may not expose a usable app-specific directory; this must be
checked on all four firmware variants.

Only the selected location is used. Switching locations does not copy, move, or
delete maps, and is blocked while a download or verification is running. Existing
maps remain accessible when that location is selected again. Removing a selected
USB cancels the running download and falls back to the static background; it
never redirects a download to internal storage. Reinserting the same device is
detected while the Maps screen or enabled map panel is visible. Maps use their
own `Android/data/com.mg4.launcher/files/offline-maps` directory on removable
storage; camera recordings and unrelated files are never scanned or changed.
App-specific files may be removed when the app is uninstalled. Downloads reserve
512 MiB free internally or 256 MiB on removable storage, including space needed
for the new package alongside any existing map. This is headroom, not a quota
or guarantee of future camera-recording capacity.

Each region is an app-private vector MBTiles database. Downloads use immutable
HTTPS URLs, exact byte counts and SHA-256 from the bundled catalog. Verification
checks SQLite integrity and the tile schema. Installation uses an atomic rename
after verification, so a failed update keeps the existing map. An update is
offered when a later launcher ships a newer catalog entry; no remote catalog or
automatic update requests are made.

During navigation, the map follows a recent GPS fix, with day/night colours,
street labels, water, a position marker, and a recenter control. Dragging stops
following until recentered. It does not draw MG's route, recalculate directions,
or display live traffic. Existing navigation instructions, ETA and the
missing-icon report button remain separate overlays. The button still appears
for unknown MG maneuver codes, with or without a map.

The decorative background remains visible when disabled, outside downloaded
coverage, with no actual tile at the current position, without permission or a
recent usable location, or if the WebView renderer fails. A fix expires after
30 seconds and must report accuracy of 200 metres or better. Tracking stops
when the map tab is hidden or the launcher is in the background. Location is
held in memory only and is not written to preferences, diagnostic logs or reports.

## Renderer and packages

MapLibre GL JS **4.7.1** runs in the system Android WebView. Java intercepts
requests for `https://offline.mg4.invalid/` and serves bundled assets and local
SQLite tiles. This hostname is never resolved or contacted. Other origins are
blocked, network loading and WebView geolocation are disabled, and console
messages are suppressed. There is no local HTTP listener on the car. Fonts are
bundled for Latin scripts (including the launcher's supported languages).
WebGL and the car's WebView version/performance still need in-car validation.

The initial catalog uses published [Librescoot MBTiles](https://github.com/librescoot/osm-tiles)
with its `streets`, `street_labels` and water layers. These are **full source
packages**, despite the simplified display: roughly 886 MB for Netherlands,
584 MB for Belgium, 39 MB for Luxembourg and 30 MB for Hamburg (decimal MB).
Settings displays the exact catalog bytes converted to MiB, labelled MB like
the rest of the interface. There is no 480 MB Netherlands download in this
implementation; that earlier number was illustrative.

To create smaller packages, without buildings, address layers or geocoding tables:

```sh
python tools/prepare_offline_map.py source.mbtiles simplified.mbtiles
```

The tool preserves streets, labels, water, basic land, attribution and tile
coverage. It prints the resulting size and SHA-256. A Hamburg sample shrank
from 29,949,952 to 8,445,952 bytes; that result is not a Netherlands estimate.
Host the output on your own HTTPS download service, then replace the relevant
catalog entry's URL, size, checksum and version. Do not commit map databases.
The default catalog can be replaced without changing UI or downloader code.
Do not derive offline packages by scraping public OSM tile servers.

Bundled third-party assets retain their licenses. MapLibre is BSD licensed;
Noto Sans glyphs come from `maplibre/demotiles` (`gh-pages`) and use the SIL OFL.
Map data is OpenStreetMap under ODbL; attribution is shown whenever the live
map is visible. Tapping attribution, or the Maps settings licence row, opens a
notice with the OpenStreetMap copyright and ODbL URLs. Renderer inputs:

* `https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js`
  SHA-256 `be9633c4d870e26fb37f1cfe5c5a77181667114003ea16207ac7850d8da8add1`
* `https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css`
  SHA-256 `576b085fdd9487a65a19215328c1e086c07ce5bf6da09b666b3806d3d008dae9`

## Memory limits

The prototype uses one MapLibre worker, a 32-tile cache limit, pixel ratio 1,
no antialiasing, no animated fades, and no 3D buildings or terrain. SQLite tile
connections use a 1 MiB page cache each with memory mapping disabled. Region
files are queried tile by tile rather than loaded into RAM. The WebView and
SQLite handles are released after 10 seconds with the map inactive, and when
there is no usable fix/coverage. GPS updates stop when the launcher is paused.

These are resource limits, not measured total memory figures. The WebView/GPU
process adds overhead outside the Java heap; performance and total memory must
be measured on the 2 GB head unit, including while the camera is recording.
The native MapLibre renderer remains an option if it performs better, although
it would require native-library packaging in the current custom build.

## Maneuver privacy and review feedback

Missing-icon reports now contain **only** maneuver code, allowlisted instruction
type, and distance. Arbitrary navigation text becomes `unknown`, even when it
starts with a known phrase, because it may contain a street name or destination.
Road names, coordinates and the general diagnostic dump are never appended.
Road/instruction values remain available for on-screen navigation; navigation
diagnostics log only the safe type, code and distance. Pre-fix queued snapshots
are discarded once on upgrade to prevent later uploading of old location data.
Reports already uploaded by earlier builds are not changed by this code.

The temporary NAV DEBUG panel has been removed. Maneuver icons and buttons
continue to use the launcher's theme colours, as does the map position marker.
New UI labels have English fallback and Italian resources; translations for
the other supported locales can be added in separate `offline_maps.xml` files.

## Validation

```sh
python -m unittest discover -s tests -p 'test_*.py' -v
javac -encoding UTF-8 -d /tmp/mg4-map-tests \
  app/java/com/mg4/launcher/svc/ManeuverReport.java tests/ManeuverReportTest.java \
  app/java/com/mg4/launcher/svc/MapStoragePolicy.java tests/MapStoragePolicyTest.java
java -cp /tmp/mg4-map-tests ManeuverReportTest
java -cp /tmp/mg4-map-tests MapStoragePolicyTest
```

Android resources and Java sources were compiled with the existing Docker JDK
and Android API 28 tools. The local MapLibre page was exercised in headless
Chrome using a checksum-verified Hamburg map, with requests restricted to the
local asset origin; day and night rendering succeeded. The map packaging tool
was tested on both synthetic fixtures and that real map. A signed custom APK `1.0.4.2-debug` (version code 42) was built after adding
resource diagnostics; in-car validation is still pending.

Before marking this ready, test in the car: first fix and permission denial,
entering/leaving coverage, a stale/disabled GPS provider, screen off/on, tab
switching, a missing maneuver icon over a live map, Wi-Fi interruption and
resume, insufficient storage, a corrupt download, process restart during a
download, map deletion/update while the launcher is open, switching storage,
USB removal/reinsertion, and camera recording alongside USB map reads/downloads.

## Temporary map diagnostics

`MapDiagnostics` adds fixed `maps.*` status fields to the existing `Diag` log:
GPS service/provider availability, fine/coarse permissions, whether tracking is
requested, absent/usable/stale-or-inaccurate fix state, map coverage availability,
renderer lifecycle and fixed failure categories, numeric WebView version,
internal/removable storage availability and counts, free MiB, download state and
HTTP status, and periodic Java-heap/system-available memory snapshots. Memory
figures do not measure the separate WebView/GPU process. Most states are recorded
only on change; memory/free-space snapshots use quiet values to avoid event spam.
Actual fix usability is reported when map tracking is running; provider and
permission availability can be reported even with maps disabled.

Coordinates, raw Location objects, fix timestamps, road/instruction text, region
names, download URLs, tile coordinates, storage paths/UUIDs, exception messages
and WebView console output are not passed to these diagnostics. Missing-icon
reports remain minimal and do not append general diagnostics.

The new fields are included in ordinary automatic/manual log uploads through
the existing configured repository and token. Custom builds continue to use
`tools/log-repo.txt` and `tools/log-token.txt`; there is no additional destination
or credential. Automatic uploads must be enabled and the firmware recognized.
Timing remains 90 seconds initially, then 30 minutes; Save log requests an upload.
No actual GPS data is collected or uploaded by running desktop compile checks.

Resource logging also includes total/available/estimated-used system RAM,
launcher-process PSS (Java/native together, not the separate WebView/GPU process),
internal filesystem total/free/estimated-used space, selected map-drive
filesystem total/free/estimated-used space, and space occupied by installed map
files and partial downloads. Values use MiB and contain no paths or drive IDs.
Filesystem used estimates include other files on that volume; map-only usage is
reported separately. A fresh snapshot is taken whenever a general log is built,
even if the map is disabled, plus periodic samples while the map is active.
