# TorrentToDDL

TorrentToDDL is an on-device Android torrent gateway. It resolves a magnet URI
or opens a `.torrent` file, downloads pieces with libtorrent, and exposes the
files as local HTTP direct-download links.

## User flow

1. Start the app and paste a `magnet:` URI, or choose a `.torrent` file.
2. Wait for torrent metadata. The app lists the files as soon as the metadata
   is available; the complete torrent does not need to finish first.
3. Use the on-device URL with Android DownloadManager, or copy/open the LAN
   URL from another device on the same Wi-Fi or hotspot.
4. Use **Reset Gateway** to stop the active torrent and start a fresh session.

The local HTTP gateway listens on port `8080` and supports normal downloads,
HTTP byte ranges, open-ended ranges, and suffix ranges. The URL filename is
display-only; file access is resolved from torrent metadata and the app cache.

## Build with GitHub Actions

The workflow in `.github/workflows/build-apk.yml` runs on pushes and pull
requests targeting `main` or `master`, and can also be started manually from
the **Actions** tab with **Build Android APK** → **Run workflow**.

The workflow:

- Uses Temurin JDK 17.
- Uses Gradle 8.5 through `gradle/actions/setup-gradle`.
- Runs `lintDebug` and `check`.
- Builds `assembleDebug`.
- Uploads `app-debug.apk` as the `TorrentToDDL-debug-apk` artifact.
- Uploads Android build and test reports when available.

This repository intentionally uses the existing Gradle configuration and does
not include a generated wrapper. GitHub Actions provisions Gradle 8.5 directly
when `./gradlew` is absent; the workflow will automatically use a wrapper if
one is added later.

## Local development

The project requires JDK 17 and Android SDK 34. From the project root, use the
same checks as CI:

```text
gradle lintDebug check assembleDebug --no-daemon
```

The app targets Android 34 and requires Android 8.0 (API 26) or newer. It uses
cleartext HTTP because the gateway is intentionally a local/LAN HTTP service.
Anyone who can reach the device on that network may request an exposed file;
use the gateway only on a trusted network.
