#!/usr/bin/env python3
"""Build pinned native libraries. Run on Apple Silicon with Xcode + CMake 3.31.

macos-arm64: exact libtorrent4j 2.1.0-31 JNI ABI, packaged as a private native jar.
ios: C API XCFramework for arm64 devices and arm64 iOS simulators.
No signing identities or credentials are read by this script.
"""
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".native-build"
LOCK = json.loads((ROOT / "native/dependencies.json").read_text())
JOBS = str(min(4, os.cpu_count() or 2))


def run(*args, cwd=None, env=None):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def output(*args):
    return subprocess.check_output(list(map(str, args)), text=True).strip()


def checkout(name):
    spec = LOCK[name]
    directory = WORK / "deps" / (name + "-" + spec["version"])
    if not (directory / ".git").exists():
        directory.mkdir(parents=True, exist_ok=True)
        run("git", "init", directory)
        run("git", "-C", directory, "remote", "add", "origin", spec["repository"])
        run("git", "-C", directory, "fetch", "--depth", "1", "origin", spec["commit"])
        run("git", "-C", directory, "checkout", "--detach", "FETCH_HEAD")
    if output("git", "-C", directory, "rev-parse", "HEAD") != spec["commit"]:
        raise SystemExit(f"Unexpected source revision in {directory}")
    if output("git", "-C", directory, "status", "--porcelain", "--untracked-files=no"):
        raise SystemExit(f"Dependency source was modified: {directory}")
    # Disabled optional features do not need their large submodule trees.
    if name == "libtorrent":
        run("git", "-C", directory, "submodule", "update", "--init", "--depth", "1", "deps/try_signal")
    elif name == "libtorrent4j":
        run("git", "-C", directory, "submodule", "update", "--init", "--depth", "1", "swig/deps/libtorrent")
        run("git", "-C", directory / "swig/deps/libtorrent", "submodule", "update", "--init", "--depth", "1", "deps/try_signal")
    return directory


def boost(version):
    name = "boost_" + version
    spec = LOCK[name]
    directory = WORK / "deps" / name
    archive = WORK / "deps" / (name + ".tar.bz2")
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not directory.exists():
        if not archive.exists():
            temporary = archive.with_suffix(".download")
            urllib.request.urlretrieve(spec["url"], temporary)
            temporary.replace(archive)
        with archive.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != spec["sha256"]:
            archive.unlink()
            raise SystemExit("Boost archive checksum mismatch")
        with tarfile.open(archive) as stream:
            stream.extractall(archive.parent, filter="data")
    return directory


def ssl(source, sdk, target, minimum):
    sdk_version = output("xcrun", "--sdk", sdk, "--show-sdk-version")
    directory = WORK / "openssl" / (LOCK["openssl"]["version"] + "-" + sdk + "-" + sdk_version)
    prefix = directory / "installed"
    if (prefix / "lib/libssl.a").exists():
        return prefix
    directory.mkdir(parents=True, exist_ok=True)
    sdk_path = output("xcrun", "--sdk", sdk, "--show-sdk-path")
    env = os.environ.copy()
    env["CROSS_TOP"] = str(Path(sdk_path).parents[1])
    env["CROSS_SDK"] = Path(sdk_path).name
    env["CC"] = output("xcrun", "--sdk", sdk, "--find", "clang")
    # Paths with spaces are passed as one compiler flag through the environment.
    env["CFLAGS"] = f'-arch arm64 -isysroot "{sdk_path}" {minimum}'
    run("perl", source / "Configure", target, "no-shared", "no-tests",
        "no-module", "no-apps", "--libdir=lib", "--prefix=" + str(prefix),
        cwd=directory, env=env)
    run("make", "-j" + JOBS, cwd=directory, env=env)
    run("make", "install_sw", cwd=directory, env=env)
    return prefix


def cmake(source, build, boost_root, ssl_root, sdk, **options):
    args = ["cmake", "-S", source, "-B", build, "-G", "Unix Makefiles",
            "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=" + output("xcrun", "--sdk", sdk, "--show-sdk-path"),
            "-DBOOST_ROOT=" + str(boost_root), "-DBoost_NO_BOOST_CMAKE=ON",
            "-DBoost_NO_SYSTEM_PATHS=ON", "-DOPENSSL_ROOT_DIR=" + str(ssl_root),
            "-DOPENSSL_INCLUDE_DIR=" + str(ssl_root / "include"),
            "-DOPENSSL_SSL_LIBRARY=" + str(ssl_root / "lib/libssl.a"),
            "-DOPENSSL_CRYPTO_LIBRARY=" + str(ssl_root / "lib/libcrypto.a"),
            "-DOPENSSL_USE_STATIC_LIBS=ON", "-DCMAKE_POLICY_DEFAULT_CMP0167=OLD"]
    args += ["-D" + name + "=" + str(value) for name, value in options.items()]
    run(*args)
    run("cmake", "--build", build, "--parallel", JOBS)


def notices(destination, lib_source, boost_root, ssl_source, jni_source=None):
    destination.mkdir(parents=True, exist_ok=True)
    candidates = [(lib_source / "COPYING", "libtorrent.txt"),
                  (boost_root / "LICENSE_1_0.txt", "Boost.txt"),
                  (ssl_source / "LICENSE.txt", "OpenSSL.txt")]
    if jni_source:
        candidates.append((jni_source / "LICENSE.md", "libtorrent4j.txt"))
    for path, name in candidates:
        if not path.is_file():
            raise SystemExit(f"Missing third-party license: {path}")
        shutil.copy2(path, destination / name)
    shutil.copy2(ROOT / "native/dependencies.json", destination / "dependencies.json")


def macos_arm64(ssl_source):
    source = checkout("libtorrent4j")
    boost_root = boost("1_81_0")
    openssl = ssl(ssl_source, "macosx", "darwin64-arm64-cc", "-mmacosx-version-min=12.0")
    build = WORK / "macos-arm64"
    cmake(source / "swig", build, boost_root, openssl, "macosx",
          CMAKE_OSX_DEPLOYMENT_TARGET="12.0", webtorrent="OFF")
    library = build / "libtorrent4j.dylib"
    run("lipo", "-verify_arch", "arm64", library)
    dependencies = output("otool", "-L", library)
    for line in dependencies.splitlines()[2:]:
        dependency = line.strip().split(" (", 1)[0]
        if not dependency.startswith(("/usr/lib/", "/System/Library/")):
            raise SystemExit("Unbundled native dependency: " + dependency)
    run("codesign", "--force", "--sign", "-", library)
    license_dir = build / "licenses"
    notices(license_dir, source / "swig/deps/libtorrent", boost_root, ssl_source, source)
    jar = build / "libtorrent4j-macos-arm64-2.1.0-31.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(library, "lib/arm64/libtorrent4j.dylib")
        for path in sorted(license_dir.iterdir()):
            archive.write(path, "META-INF/licenses/" + path.name)
    print("Native ARM64 JNI jar:", jar)


def ios(ssl_source):
    source = checkout("libtorrent")
    boost_root = boost("1_86_0")
    slices = []
    for sdk, target, minimum in [
        ("iphoneos", "ios64-xcrun", "-miphoneos-version-min=17.0"),
        ("iphonesimulator", "iossimulator-arm64-xcrun", "-mios-simulator-version-min=17.0")
    ]:
        openssl = ssl(ssl_source, sdk, target, minimum)
        build = WORK / sdk
        cmake(ROOT / "native", build, boost_root, openssl, sdk,
              CMAKE_SYSTEM_NAME="iOS", CMAKE_OSX_DEPLOYMENT_TARGET="17.0",
              TTD_LIBTORRENT_SOURCE=source)
        merged = build / "libTorrentNative-combined.a"
        run("xcrun", "libtool", "-static", "-o", merged,
            build / "libTorrentNative.a", build / "libtorrent/libtorrent-rasterbar.a",
            openssl / "lib/libssl.a", openssl / "lib/libcrypto.a")
        run("lipo", "-verify_arch", "arm64", merged)
        slices += ["-library", merged, "-headers", ROOT / "native/include"]
    output_dir = ROOT / "ios/Generated/TorrentNative.xcframework"
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    run("xcodebuild", "-create-xcframework", *slices, "-output", output_dir)
    notices(ROOT / "ios/Resources/ThirdPartyLicenses", source, boost_root, ssl_source)
    print("iOS arm64 device and simulator framework:", output_dir)


if __name__ == "__main__":
    if platform.system() != "Darwin" or platform.machine().lower() not in ("arm64", "aarch64"):
        raise SystemExit("Run this builder on Apple Silicon with Xcode installed.")
    if len(sys.argv) != 2 or sys.argv[1] not in ("macos-arm64", "ios"):
        raise SystemExit("Usage: python scripts/build-apple-native.py macos-arm64|ios")
    ssl_source = checkout("openssl")
    {"macos-arm64": macos_arm64, "ios": ios}[sys.argv[1]](ssl_source)
