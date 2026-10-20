# TorrentToDDL

TorrentToDDL is an on-device Android torrent gateway. It resolves a magnet URI
or opens a `.torrent` file, downloads pieces with libtorrent, and exposes the
files as local HTTP direct-download links.

## User flow

1. Press **Start Gateway**, then paste a `magnet:` URI or choose a `.torrent` file.
2. Wait for torrent metadata. The app lists the files as soon as the metadata
   is available; the complete torrent does not need to finish first.
3. Use the on-device URL with Android DownloadManager, or copy/open the LAN
   URL from another device on the same Wi-Fi or hotspot.
4. Use **Reset Gateway** to stop the active torrent. Loading another torrent starts a fresh session.

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
- Names the APK `TorrentToDDL.apk`.
- On successful push/manual builds, publishes it as a GitHub prerelease asset
  and adds a direct **Download TorrentToDDL.apk** link to the workflow summary.
- Uses Actions artifacts only for job transfer and pull-request previews;
  downloading an Actions artifact still produces a ZIP.
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

## Installing updates without package conflicts

Android requires an update to have the same application ID and signing
certificate as the installed app, and a compatible version code. This project
keeps `com.example.torrenttoddl` and now uses version code 5 (version 1.0.4).
Increasing the version code does not fix a certificate mismatch.

Fresh GitHub Actions runners generate different debug keystores by default.
For repeatable debug updates, create the repository Actions secret
`DEBUG_KEYSTORE_BASE64` using the debug keystore that signed your installed
APK. It must use the standard debug alias `androiddebugkey`, with both store
and key passwords set to `android`. Never use a release keystore here.

On the computer that built your installed debug app, with GitHub CLI signed in:

```bash
base64 < "$HOME/.android/debug.keystore" | tr -d '\n' | \
  gh secret set DEBUG_KEYSTORE_BASE64 --repo Saakihegde12345/TorrentToDDL
```

Alternatively, add the Base64 value under repository **Settings → Secrets and
variables → Actions → New repository secret**. Keep a private backup of the
original keystore. Do not commit it or paste it into logs or issues.

Push/manual builds restore that secret before Gradle signs the APK. Without
the secret they still build, but warn that the signing identity is temporary.
Pull-request builds deliberately do not receive the persistent key; their APKs
are previews and should not be used as update builds. Successful builds also
include `apk-signing-certificate.txt` in the build reports so certificates can
be compared without exposing private keys.

If the original key is unavailable (for example, an earlier ephemeral CI key),
the new APK cannot update that installation. Back up anything needed before
uninstalling the old app; uninstalling removes app data and its torrent cache.
Install a build made with your persistent key, then keep using that same key
for later updates. An old APK alone cannot recover its private signing key.

The release publishing job has `contents: write` permission to create the
release and attach the APK. It runs only after successful build checks, never
for pull requests. Open the workflow run summary and click **Download
TorrentToDDL.apk**, or find the same APK under the corresponding release Assets.

## Gateway lifetime, downloads and battery use

- The app opens with the gateway off. **Start Gateway** starts the foreground
  service and HTTP listener; **Stop Gateway** stops networking and cancels
  unfinished downloads started through this app. Opening an incoming torrent
  or magnet also starts the gateway.
- An unused gateway stops after 30 seconds. Metadata resolution gets up to
  three minutes. Queued downloads and active HTTP streams keep it alive.
- In-app downloads display Android DownloadManager's received bytes, percentage,
  waiting state and completion/failure reason. Errors also produce a toast.
  Results remain visible while the app is bound, after the gateway auto-stops.
- Once all in-app transfers finish and HTTP streams close, the gateway stops.
  External HTTP clients get a 30-second gap between requests before idle-stop.
- A managed download with no byte progress for three minutes is cancelled with
  an error. Individual torrent stream waits are bounded at two minutes.
- Home keeps downloads running. Back, removal from Recents, Stop and Reset stop
  the gateway. Reset requires Start before another load. Native teardown runs
  off the Android main thread. The service does not restart itself after being killed.
- Allow notifications to see the gateway notification. If app or channel
  notifications are blocked, the app shows **Enable notifications**, which opens
  Android settings. Denial does not prevent Android from running a foreground
  service, but can hide its notification from the drawer.
- HTTP responses carry a stable torrent/file ETag and accept matching If-Match
  range requests so interrupted Android downloads can resume without mixing
  bytes from another torrent. Sparse swarms can still stall or fail; the app
  now displays the Android failure reason.
- Once cached, peer transfers and discovery pause until another explicit load.

The JVM tests exercise real HTTP download/resume, stale torrent rejection,
idle policy and native libtorrent handle recycling/reset. Android device testing
is still needed for notification settings, task behavior, actual torrents and
battery consumption. Active downloads use network, CPU and storage; no measured
battery-life improvement is claimed.

## Reading progress

The dark graphite interface uses lime for gateway controls/local saves and cyan
for torrent caching. All sections share one scrolling list, so files and controls
remain accessible on smaller screens and with the keyboard open. Direct URLs
are expandable rather than filling every file card.

**Torrent cache** shows the wanted payload bytes present versus wanted bytes,
peer count and payload receive rate. File-checking task progress is not used as
download progress, and automatic pause requires the completed/seeding state. **Saving to Downloads / Local save** shows actual Android
DownloadManager byte counts and total length. Its rate is calculated from byte
differences over elapsed monotonic time; there is no simulated progress or speed
cap. These are separate stages: already-cached data travels over loopback to
Downloads and can move much faster than the current internet connection. A fast
local save is not evidence of a faster swarm download. Completion/failure stays
visible in the transfer history while the service remains bound.
