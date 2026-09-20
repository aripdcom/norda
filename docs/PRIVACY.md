# Norda — Privacy Policy

Türkçe sürüm: [docs/tr/PRIVACY.md](tr/PRIVACY.md)

_Last updated: 2026-08-25_

Norda is an offline-first outdoor navigation app. Its privacy stance is a
single sentence: **your location data stays on your device.**

- **Data collected:** While recording, GPS positions, altitude and battery
  percentage are written only to the on-device SQLite database. There are
  no accounts, no cloud, no telemetry/analytics/ads SDKs.
- **Network access:** The only component in the app that touches the network
  is the map pack downloader (`TileDownloader`); it fetches only the map
  packs published on GitHub Releases and their index. No identity, location
  or usage data is sent with these requests. Recording, compass, navigation
  and GPX work fully offline.
- **Permissions:** Location (for recording and the compass), notifications
  (for the persistent notification while recording). Both are used only for
  those purposes.
- **Sharing:** Data leaves the device only when you export it (GPX), to a
  destination you choose.
- **Deletion:** Activities and waypoints can be deleted in-app;
  uninstalling removes all data.

Contact: <https://github.com/aripdcom/norda/issues>
