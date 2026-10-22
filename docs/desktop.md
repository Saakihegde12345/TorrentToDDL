# Desktop workspace 1.1.0

TorrentToDDL Desktop provides a torrent queue, file browser, download history,
persistent settings and an authenticated HTTP gateway. Windows x64 is tested
locally; macOS and Linux packages still require their platform CI and manual QA.
Packages remain unsigned. The desktop version is independent of Android's
application ID, version and signing identity.

## Windows quick start

Extract `TorrentToDDL-windows-x64.zip` and run `TorrentToDDL/TorrentToDDL.exe`.
Java is included. Alternatively install `TorrentToDDL-windows-x64.msi`.

From source, double-click `Start-Windows.cmd`. On first use it prepares
checksum-verified Java 17 and Gradle 8.5 in `.tools/`, runs tests and builds
the portable app. To rebuild after changing source:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows.ps1 -Installer
```

Packages and `SHA256SUMS` appear in `dist/`. Quit an older source-built app
before rebuilding. Keep an existing installed transfer running until it
finishes before switching to the new application.

## Controls and layout

- **Torrents:** add magnets or metadata, inspect progress/rates/peers, select a
  torrent to browse/filter files, pause/resume, or remove it from the queue.
- **Save:** save a file to the configured download folder; **Save as** chooses
  another destination; **Save all files** preserves the torrent's folder tree.
- **Downloads:** inspect queued saves and exact bytes written, cancel a save,
  or open a completed file's folder. Disk exports run sequentially while
  torrents can receive data concurrently.
- **Settings:** download/cache folders, HTTP port, LAN/loopback access,
  rotating sharing key, simultaneous downloads, per-torrent bandwidth and
  connection limits, completed-torrent seeding, and close-to-tray behavior.
  Stop the backend before applying changes.

**Stop backend** terminates the separate Java backend process, closes HTTP
streams, cancels pending exports, tears down native sessions and releases its
port. The desktop window remains open. Its status bar shows that no backend
process remains. A stalled native shutdown is bounded and the owned child is
terminated if it fails to exit cleanly. The desktop process never loads JNI.

**Quit application** stops the backend and exits the desktop. Transfers continue
while minimized. With close-to-tray enabled and a system tray available, closing
the window hides it; the tray menu can reopen, stop or quit the app. Without
tray support, closing the window quits. There is no automatic desktop idle stop.

## Queue, storage and safety

Up to 100 torrents can be retained, with 1–8 concurrent download slots. Each
torrent has its own cache subdirectory and native session so equal filenames
cannot overwrite another torrent's cache. Completed torrents can seed, or pause
their peer traffic according to Settings. Cache and saved output remain separate.

Torrent sources and pause state survive backend restart. `.torrent` inputs are
copied into app storage. Magnets may need metadata resolution again on restart.
Byte-verified cached data is reused. Unfinished export copies are removed when
cancelled or stopped; exports do not resume after process restart. Removing a
torrent from the queue preserves cached data and saved files. Existing output
files are never silently overwritten.

Settings, queue metadata and the backend log are stored at:

- Windows: `%LOCALAPPDATA%/TorrentToDDL` (home fallback).
- macOS: `~/Library/Application Support/TorrentToDDL`.
- Linux: `$XDG_CONFIG_HOME/TorrentToDDL` (default `~/.config/TorrentToDDL`).

The cache location can be changed in Settings. Old cache data is preserved.
Allow space for both cache and saved copies. File selection/filtering does not
exclude other files from the torrent's underlying cache download.

## Sharing

Desktop URLs contain a torrent ID, file index and generated access key:

```text
http://host:8080/torrents/TORRENT_ID/files/0/filename?token=DOWNLOAD_KEY
```

Missing/wrong keys return HTTP 401. GET/HEAD, single ranges, ETags and matching
If-Match requests are supported. Keep links private: anyone holding a link can
use its download key. Turn LAN sharing off for loopback-only access. LAN links
use HTTP; public access needs an HTTPS reverse proxy or VPN. The application
does not create router port mappings for HTTP or deploy public infrastructure.

An optional [headless backend](server.md) can be hosted on a remote machine.
Its administration key is separate from shared download keys.

## Other platforms and verification

JDK 17 and Gradle 8.5 are required for manual desktop builds:

```sh
gradle -PdesktopOnly=true :desktop:run
gradle -PdesktopOnly=true :core:test :desktop:test :desktop:smokeTest
gradle -PdesktopOnly=true :desktop:createDistributable :desktop:packageDistributionForCurrentOS
```

Android SDK is unnecessary with `desktopOnly=true`. MSI/DMG/DEB packages must
be built on their respective operating systems. Apple Silicon needs its native
binding built first; see [Apple platforms](apple-platforms.md).

Tests exercise a real BitTorrent peer download, cross-piece HTTP ranges,
two simultaneous cached torrents and isolated exports, access-key enforcement,
administration/download key separation, settings/queue persistence, native
seeding/bandwidth options, backend process exit, port reuse, restart and partial
file cleanup. Packaged-launcher checks start and stop a real child backend.
