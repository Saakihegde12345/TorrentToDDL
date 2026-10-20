#!/usr/bin/env python3
"""Verify zero skipped JNI tests and run the launcher from the bundled image."""
import hashlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

host = sys.argv[1]
extension, launcher = {
    "linux": ("deb", "TorrentToDDL/bin/TorrentToDDL"),
    "windows": ("msi", "TorrentToDDL/TorrentToDDL.exe"),
    "macos": ("dmg", "TorrentToDDL.app/Contents/MacOS/TorrentToDDL"),
}[host]

reports = list(Path("core/build/test-results/test").glob("TEST-*.xml"))
assert reports, "Missing core test results"
engine_tests = 0
for report in reports:
    root = ET.parse(report).getroot()
    for attribute in ("failures", "errors", "skipped"):
        assert int(root.get(attribute, "0")) == 0, f"{report}: {attribute}"
    if root.get("name", "").endswith("TorrentStreamEngineTest"):
        engine_tests += int(root.get("tests", "0"))
assert engine_tests >= 2, "Real JNI regression tests must run"

base = Path("desktop/build/compose/binaries/main")
executable = (base / "app" / launcher).resolve()
assert executable.is_file(), f"Packaged launcher not found: {executable}"
subprocess.run([str(executable), "--smoke-test"], check=True, timeout=60)

installers = list((base / extension).glob("*." + extension))
assert len(installers) == 1, f"Expected one {extension} installer: {installers}"
destination = Path("dist")
destination.mkdir(exist_ok=True)
artifact = destination / f"TorrentToDDL-{host}-x64.{extension}"
shutil.copy2(installers[0], artifact)

print(f"{artifact}: {artifact.stat().st_size:,} bytes")
print("SHA-256:", hashlib.sha256(artifact.read_bytes()).hexdigest())
