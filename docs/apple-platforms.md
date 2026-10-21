# Apple platform previews

## Apple Silicon desktop

The ARM64 Mac package uses the existing Compose Desktop interface and JVM core.
Its JNI binding is built from the exact libtorrent4j **2.1.0-31** revision in
`native/dependencies.json`, matching the Java API used by Android and Intel Macs.
It is a native ARM64 build, not an Intel package running through Rosetta.

The builder statically links OpenSSL, verifies the dylib architecture, rejects
Homebrew/build-directory runtime dependencies, and includes third-party licenses
inside the native jar. Its local ad-hoc dylib signature is not a Developer ID
signature or notarization. Desktop installers remain unsigned previews.

On an Apple Silicon Mac with Xcode, an ARM64 JDK 17, Gradle 8.5 and Python 3.12:

```sh
python -m pip install cmake==3.31.6
python scripts/build-apple-native.py macos-arm64
gradle -PdesktopOnly=true :core:test :desktop:test :desktop:smokeTest
gradle -PdesktopOnly=true :desktop:run
gradle -PdesktopOnly=true :desktop:createDistributable :desktop:packageDistributionForCurrentOS
```

The native jar is generated at
`.native-build/macos-arm64/libtorrent4j-macos-arm64-2.1.0-31.jar`.
The desktop workflow tests an ARM64 JVM and packages
`TorrentToDDL-macos-arm64.dmg` alongside the three existing x64 installers.
The ARM64 preview targets macOS 12 or newer.

## iPhone and iPad

iOS cannot execute the existing JVM engine. The iOS target therefore uses
**SwiftUI**, a small C interface over native **libtorrent 2.0.15**, and an HTTP
server built with Apple's Network framework. Android and desktop keep their
current JVM implementation and signing configuration.

The preview targets iOS/iPadOS **17 or newer** and provides:

- Start/Stop, magnets, Files-based `.torrent` import and incoming document URLs.
- Real cached payload bytes, peer rate/count and separate file-save progress.
- Downloads to app storage, then sharing, Save to Files and Quick Look preview.
- LAN HTTP downloads with GET/HEAD, bounded single ranges and stable ETags.
- Matching `If-Match` resumes and rejection of stale torrent identities.
- Bounded reads of verified torrent pieces, transfer cancellation and cleanup.
- Visible errors for invalid metadata, networking, stalled reads and storage.
- The established graphite, lime and cyan brand and logo.

The iOS gateway and active saves **stop when the app enters the background**.
Permission prompts and file pickers making a scene inactive do not stop it.
A short UIKit background task is used only to finish cancelling and closing
connections. This preview does not promise continuous background torrenting,
register fake audio/VPN modes, or start networking when the app merely launches.
Fully cached torrents pause peer transfers until another explicit load.

Cache lives in the app's Caches directory; exported files live in
Documents/Downloads and are available through Files. Both stages need storage.
Existing export files are preserved by choosing a new name. Cancelled or failed
exports remove their temporary file. Interrupted exports are not resumable after
relaunch; HTTP clients can resume while the same torrent identity is active.

LAN sharing uses port 8080 without authentication. Use trusted networks and keep
the app visible. The copy-link control currently selects the Wi-Fi IPv4 address.
LSD multicast and automatic router port mapping are disabled on iOS.

### Build and test

Use an Apple Silicon Mac with Xcode and an installed iPhone simulator:

```sh
python -m pip install cmake==3.31.6 cairosvg==2.7.1 Pillow==11.1.0
brew install cairo libffi xcodegen
python scripts/build-apple-native.py ios
DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix cairo)/lib" python scripts/generate-ios-assets.py
xcodegen generate --spec ios/project.yml
open ios/TorrentToDDL.xcodeproj
```

The builder generates an XCFramework with separate ARM64 **device** and
**simulator** slices, static TLS dependencies and bundled dependency licenses.
`python scripts/build-ios-preview.py` runs native/HTTP/export/cancellation tests,
a UI start/background-stop test, and an unsigned device archive build.
The workflow requires at least six successful tests with none failed/skipped.
It preserves the test result bundle and UI screenshot attachment.

### Installing on a device

The simulator `.app` archive runs in Xcode's ARM64 iOS Simulator. The device
`.xcarchive` is **unsigned** and cannot be installed directly on an iPhone.
No `.ipa` is published under a misleading installable filename.

For your own device, open the generated Xcode project, select your Apple team
under Signing & Capabilities, choose a bundle identifier registered to that
team if needed, select the device and build/run. Certificates, profiles and
account credentials stay in your Xcode setup; none belong in this repository.
TestFlight/App Store distribution requires its own signing, review and privacy
submission work. These developer previews are not App Store releases.

### TLS, privacy and dependency maintenance

HTTPS tracker verification remains enabled. OpenSSL on iOS uses the bundled
Mozilla CA roots from `https://curl.se/ca/cacert.pem` rather than the iOS
Keychain trust store. The checked-in bundle's SHA-256 is
`796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe`.
Update this bundle with app releases, verify its published checksum and retain
Mozilla's license notice. No certificate-verification bypass is installed.

The privacy manifest declares app-container file metadata, elapsed-time timers
and space checks for writing downloads. There is no analytics SDK, tracking
domain or developer-hosted download server. BitTorrent itself communicates
with peers/trackers/DHT and exposes the network information required by that
protocol. Recheck manifests and disclosures against the final signed binary
before a store submission.

The dependency lock pins source revisions and Boost archive checksums.
The iOS native C API exposes owned status/error strings and typed bounded reads;
it does not expose JVM handles or borrow memory from libtorrent alerts.
Lifecycle commands must be serialized by the caller; status and bounded reads
may run concurrently. Destroy the engine only after all calls have returned.

Automated checks do not replace real-device tests of Wi-Fi permission, Files,
Quick Look, different swarms/trackers, low storage, thermal/battery behavior and
application termination. CI success alone is not a claim of measured battery
life or production readiness.
