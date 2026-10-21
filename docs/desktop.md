# Desktop preview

TorrentToDDL keeps its Android app and adds a Compose Desktop interface over a
shared JVM torrent engine, input stream, HTTP server and gateway idle policy.

## Targets

| Platform | Preview architecture | Package |
| --- | --- | --- |
| Windows | x64 | TorrentToDDL-windows-x64.msi |
| macOS | Intel x64 | TorrentToDDL-macos-x64.dmg |
| macOS | Apple Silicon ARM64 | TorrentToDDL-macos-arm64.dmg |
| Linux | x64, Debian/Ubuntu package | TorrentToDDL-linux-x64.deb |
| Android | Existing ARM64, ARMv7, x64 | TorrentToDDL.apk |

The desktop preview pins the same libtorrent4j 2.1.0-31 as Android.
Windows ARM, ARM Linux and web clients are not included. iOS has a separate
[developer preview](apple-platforms.md). Apple Silicon uses a matching native
binding built from source; see the same guide for its build prerequisites.
The installers are unsigned; macOS is not notarized. This is an experimental
preview requiring manual desktop testing, rather than a signed stable release.

## Run or package

Use JDK 17 (matching your x64 or ARM64 architecture) and Gradle 8.5 on the target operating system:

    gradle -PdesktopOnly=true :desktop:run
    gradle -PdesktopOnly=true :core:test :desktop:test :desktop:smokeTest
    gradle -PdesktopOnly=true :desktop:createDistributable :desktop:packageDistributionForCurrentOS

Android SDK is not required when desktopOnly=true. Installers must be built
on their corresponding operating system. Windows packaging uses WiX 3;
Linux packaging uses the Debian package tools; macOS packaging produces a DMG.
The packaged app includes its own Java runtime.

## Use

1. Start the gateway, or paste a magnet / choose a .torrent file to start it.
2. Wait for metadata. The cache meter shows real wanted payload bytes.
3. Save a selected file to a location you choose. The save meter counts bytes
   actually copied to the destination, separately from the torrent cache.
4. Copy a LAN link for another device, or open a loopback link locally.

One local save runs at a time. Cancel it before selecting another torrent.
Changing torrents is blocked while HTTP streams or local saves are active.
Existing destination files are preserved. Partial saves are removed on error
or cancellation. Desktop saves do not resume after restarting this preview;
the HTTP server retains its existing byte-range and ETag support.

The gateway uses TCP port 8080. Port conflicts appear as errors. LAN links
have no authentication: use a trusted network and allow only the access you
intend through your firewall. A LAN address is selected from active interfaces;
VPNs and multiple adapters may require using the appropriate host address.

A visible window stays online even when another window has focus. Minimizing
allows idle shutdown after 30 seconds, with 3 minutes for metadata loading.
Active saves, HTTP streams and incoming torrent data keep it alive. Completed
exports allow shutdown while minimized. Fully cached torrents pause peer
transfers. Closing the window cancels saves and stops the server and session.

## Data and testing

Cache persists for reuse at:

- Windows: LOCALAPPDATA/TorrentToDDL/cache (home fallback).
- macOS: ~/Library/Caches/TorrentToDDL.
- Linux: XDG_CACHE_HOME/TorrentToDDL (default ~/.cache/TorrentToDDL).

Use Stop or close the app before manually removing cached files. Saved output
is separate and remains at the destination you selected.

The Desktop Preview workflow runs real JNI lifetime/reset tests, HTTP
range/resume tests, idle-policy tests, export integrity and cleanup tests.
It then runs native startup/HTTP/shutdown through the packaged launcher.
All four desktop targets and the Android regression build must pass before direct
installer assets are published to a prerelease. Android's signing identity
and stable release flow are retained.

CI does not substitute for interactive tests. Check file dialogs, accessibility,
clipboard, browser links, swarm downloads, cancellation, minimized completion
and process exit on real machines before promoting a stable desktop release.
