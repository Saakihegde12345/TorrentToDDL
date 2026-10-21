#!/usr/bin/env python3
"""Test on a real iOS simulator, build an unsigned device archive, collect previews."""
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "ios/build"
DIST = ROOT / "dist"


def run(*args, **kwargs):
    print("+", " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), cwd=ROOT, check=True, **kwargs)


run("xcodegen", "generate", "--spec", "ios/project.yml")
devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"], text=True))
candidates = [(runtime, device) for runtime, values in devices["devices"].items() if ".iOS-" in runtime
              for device in values if device.get("isAvailable") and device["name"].startswith("iPhone")]
assert candidates, "No available iPhone simulator"
runtime, device = sorted(candidates, key=lambda item: item[0], reverse=True)[0]
if device["state"] != "Booted":
    run("xcrun", "simctl", "boot", device["udid"])
run("xcrun", "simctl", "bootstatus", device["udid"], "-b")
BUILD.mkdir(parents=True, exist_ok=True)
DIST.mkdir(exist_ok=True)
project = ROOT / "ios/TorrentToDDL.xcodeproj"
result = BUILD / "TestResults.xcresult"
run("xcodebuild", "-project", project, "-scheme", "TorrentToDDL", "-configuration", "Debug",
    "-destination", "id=" + device["udid"], "-derivedDataPath", BUILD / "DerivedData",
    "-resultBundlePath", result, "-parallel-testing-enabled", "NO", "test", "CODE_SIGNING_ALLOWED=NO")
summary_text = subprocess.check_output(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(result)], text=True)
(BUILD / "test-summary.json").write_text(summary_text)
summary = json.loads(summary_text)
assert summary.get("failedTests", -1) == 0, summary
assert summary.get("skippedTests", -1) == 0, summary
assert summary.get("passedTests", 0) >= 6, summary
print("Simulator native, HTTP, export/cancellation and UI tests passed:", summary["passedTests"])
# Keep UI evidence even if attachment export changes between Xcode versions.
subprocess.run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(result),
                "--output-path", str(BUILD / "attachments")], cwd=ROOT, check=False)
archive = BUILD / "TorrentToDDL.xcarchive"
run("xcodebuild", "-project", project, "-scheme", "TorrentToDDL", "-configuration", "Release",
    "-destination", "generic/platform=iOS", "-archivePath", archive,
    "archive", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO")
app = BUILD / "DerivedData/Build/Products/Debug-iphonesimulator/TorrentToDDL.app"
assert app.is_dir() and (archive / "Products/Applications/TorrentToDDL.app").is_dir()
run("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app, DIST / "TorrentToDDL-iOS-Simulator-arm64.zip")
run("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", archive, DIST / "TorrentToDDL-iOS-unsigned.xcarchive.zip")
manifest = []
for path in sorted(DIST.glob("TorrentToDDL-iOS-*.zip")):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest.append(f"{digest}  {path.name}\n")
(DIST / "SHA256SUMS").write_text("".join(manifest))
print("Unsigned iOS developer previews collected. These are not installable signed IPAs.")
