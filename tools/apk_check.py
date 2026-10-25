#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

from sdk import sdk_root


def run(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError(result.stderr.strip() or result.stdout.strip() or f"{Path(args[0]).name} failed")
    return result.stdout


def elf_alignment(data, name):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"{name}: expected an arm64 ELF library")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or not count or offset + size * count > len(data):
        raise ValueError(f"{name}: invalid ELF program headers")
    loads = 0
    for index in range(count):
        position = offset + size * index
        if struct.unpack_from("<I", data, position)[0] != 1:
            continue
        loads += 1
        file_offset, address = struct.unpack_from("<QQ", data, position + 8)
        alignment = struct.unpack_from("<Q", data, position + 48)[0]
        if alignment < 16384 or alignment & (alignment - 1) or file_offset % 16384 != address % 16384:
            raise ValueError(f"{name}: ELF load segment is not aligned for 16 KB pages")
    if not loads:
        raise ValueError(f"{name}: no ELF load segments")


def zip_packaging(path, extract):
    with zipfile.ZipFile(path) as archive, path.open("rb") as source:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate ZIP entries")
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f"ZIP checksum failure: {corrupt}")
        if "resources.arsc" not in names:
            raise ValueError("resources.arsc is missing")
        libraries = 0
        for entry in archive.infolist():
            native = entry.filename.startswith("lib/") and entry.filename.endswith(".so")
            if not native and entry.filename != "resources.arsc":
                continue
            source.seek(entry.header_offset)
            header = source.read(30)
            name_size, extra_size = struct.unpack_from("<HH", header, 26)
            offset = entry.header_offset + 30 + name_size + extra_size
            if not native:
                if entry.compress_type != zipfile.ZIP_STORED or offset % 4:
                    raise ValueError("resources.arsc must be uncompressed and four-byte aligned")
                continue
            libraries += 1
            if not entry.filename.startswith("lib/arm64-v8a/"):
                raise ValueError(f"unexpected native ABI: {entry.filename}")
            if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError(f"unsupported native-library compression: {entry.filename}")
            if not extract and (entry.compress_type != zipfile.ZIP_STORED or offset % 16384):
                raise ValueError(f"{entry.filename}: direct loading requires uncompressed, 16 KB aligned libraries")
            elf_alignment(archive.read(entry), entry.filename)
        if not libraries:
            raise ValueError("no arm64 native libraries")
    return libraries


def check(path, build_tools, unsigned, max_api):
    badging = run(build_tools / "aapt2", "dump", "badging", path)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", badging, re.M)
    if not package or not minimum or package[1] != "app.absentmotion":
        raise ValueError("missing or unexpected package/version/minimum Android metadata")
    api = int(minimum[1])
    if api not in (28, 33) or api > max_api:
        raise ValueError(f"unexpected minimum API {api}")
    manifest = run(build_tools / "aapt2", "dump", "xmltree", path, "--file", "AndroidManifest.xml")
    if re.search(r":(?:debuggable|testOnly)\([^\n]*=true", manifest):
        raise ValueError("release APK must not be debuggable or test-only")
    extract = not re.search(r":extractNativeLibs\([^\n]*=false", manifest)
    libraries = zip_packaging(path, extract)
    run(build_tools / "zipalign", "-c", "-P", "16", "4", path)
    certificate = None
    if not unsigned:
        for platform in range(api, max_api + 1):
            try:
                run(build_tools / "apksigner", "verify", "--min-sdk-version", platform, "--max-sdk-version", platform, path)
            except ValueError as error:
                raise ValueError(f"API {platform}: {error}") from error
        certs = run(build_tools / "apksigner", "verify", "--print-certs", path)
        digests = {value.lower() for value in re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", certs, re.M)}
        if len(digests) != 1:
            raise ValueError("expected exactly one signing certificate")
        certificate = next(iter(digests))
    print(f"{path.name}: API {api}-{max_api}, {libraries} libraries, native extraction {'on' if extract else 'off'}: PASS")
    return package[1], package[2], package[3], certificate


def main():
    parser = argparse.ArgumentParser(description="Check release APK identity, signatures, ZIP packaging and 16 KB ELF alignment")
    parser.add_argument("apks", type=Path, nargs="+")
    parser.add_argument("--unsigned", action="store_true")
    parser.add_argument("--max-api", type=int, default=37)
    args = parser.parse_args()
    root = Path(os.environ["SDK_ROOT"]) if os.environ.get("SDK_ROOT") else sdk_root()
    if root is None:
        sys.exit("apk_check: Android SDK not found")
    build_tools = root / "build-tools/37.0.0"
    identities = []
    try:
        for path in args.apks:
            try:
                identities.append(check(path, build_tools, args.unsigned, args.max_api))
            except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
                raise ValueError(f"{path.name}: {error}") from error
        if len(set(identities)) != 1:
            raise ValueError("APK package IDs, versions or signing certificates do not match")
    except ValueError as error:
        sys.exit(f"apk_check: {error}")
    print("apk_check: all release checks passed.")


if __name__ == "__main__":
    main()
