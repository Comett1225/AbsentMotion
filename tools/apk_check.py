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

UPDATE_SOURCE = Path(__file__).resolve().parent.parent / "app/src/main/kotlin/app/absentmotion/ui/home/UpdateSource.kt"


def release_signer(source=UPDATE_SOURCE):
    found = re.findall(r'^internal const val RELEASE_SIGNER = "([0-9a-f]{64})"$', source.read_text(), re.M)
    if len(found) != 1:
        raise ValueError(f"{source.name}: expected exactly one RELEASE_SIGNER")
    return found[0]


def run(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError(result.stderr.strip() or result.stdout.strip() or f"{Path(args[0]).name} failed")
    return result.stdout


ELF_KINDS = {1: ("armeabi-v7a", 40, 4096), 2: ("arm64-v8a", 183, 16384)}


def elf_alignment(data, name):
    kind = ELF_KINDS.get(data[4]) if len(data) >= 52 and data[:4] == b"\x7fELF" and data[5] == 1 else None
    if not kind or struct.unpack_from("<H", data, 18)[0] != kind[1]:
        raise ValueError(f"{name}: expected an arm64 or 32-bit ARM ELF library")
    abi, _, page = kind
    wide = abi == "arm64-v8a"
    offset = struct.unpack_from("<Q" if wide else "<I", data, 32 if wide else 28)[0]
    size, count = struct.unpack_from("<HH", data, 54 if wide else 42)
    if size < (56 if wide else 32) or not count or offset + size * count > len(data):
        raise ValueError(f"{name}: invalid ELF program headers")
    loads = 0
    for index in range(count):
        position = offset + size * index
        if struct.unpack_from("<I", data, position)[0] != 1:
            continue
        loads += 1
        if wide:
            file_offset, address = struct.unpack_from("<QQ", data, position + 8)
            alignment = struct.unpack_from("<Q", data, position + 48)[0]
        else:
            file_offset, address = struct.unpack_from("<II", data, position + 4)
            alignment = struct.unpack_from("<I", data, position + 28)[0]
        if alignment < page or alignment & (alignment - 1) or file_offset % page != address % page:
            raise ValueError(f"{name}: ELF load segment is not aligned for {page // 1024} KB pages")
    if not loads:
        raise ValueError(f"{name}: no ELF load segments")
    return abi, page


def library_alignment(paths):
    count = 0
    pages = set()
    for path in paths:
        for library in sorted(path.glob("*.so")) if path.is_dir() else [path]:
            pages.add(elf_alignment(library.read_bytes(), library.name)[1])
            count += 1
    if not count:
        raise ValueError("no native libraries found")
    return count, " and ".join(f"{page // 1024} KB" for page in sorted(pages))


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
        abis = set()
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
            if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError(f"unsupported native-library compression: {entry.filename}")
            abi, page = elf_alignment(archive.read(entry), entry.filename)
            if not entry.filename.startswith(f"lib/{abi}/"):
                raise ValueError(f"unexpected native ABI: {entry.filename}")
            abis.add(abi)
            if not extract and (entry.compress_type != zipfile.ZIP_STORED or offset % page):
                raise ValueError(f"{entry.filename}: direct loading requires uncompressed, {page // 1024} KB aligned libraries")
        if not libraries:
            raise ValueError("no native libraries")
        if len(abis) != 1:
            raise ValueError(f"expected one native ABI, found {', '.join(sorted(abis))}")
    return libraries, abis.pop()


def check(path, build_tools, unsigned, max_api, signer=None):
    badging = run(build_tools / "aapt2", "dump", "badging", path)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", badging, re.M)
    if not package or not minimum or package[1] != "app.absentmotion":
        raise ValueError("missing or unexpected package/version/minimum Android metadata")
    api = int(minimum[1])
    if api not in (26, 33) or api > max_api:
        raise ValueError(f"unexpected minimum API {api}")
    manifest = run(build_tools / "aapt2", "dump", "xmltree", path, "--file", "AndroidManifest.xml")
    if re.search(r":(?:debuggable|testOnly)\([^\n]*=true", manifest):
        raise ValueError("release APK must not be debuggable or test-only")
    extract = not re.search(r":extractNativeLibs\([^\n]*=false", manifest)
    libraries, abi = zip_packaging(path, extract)
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
        if signer and certificate != signer:
            raise ValueError(f"signed with certificate {certificate[:16]}..., not the release key {signer[:16]}... the installed app accepts")
    print(f"{path.name}: API {api}-{max_api}, {libraries} {abi} libraries, native extraction {'on' if extract else 'off'}: PASS")
    return package[1], package[2], package[3], certificate


def main():
    parser = argparse.ArgumentParser(description="Check release APK identity, signatures, ZIP packaging and 16 KB ELF alignment")
    parser.add_argument("apks", type=Path, nargs="+", help="APKs, or with --libraries .so files and folders of them")
    parser.add_argument("--unsigned", action="store_true")
    parser.add_argument("--max-api", type=int, default=37)
    parser.add_argument("--signer", help="expected signing certificate SHA-256 (default: RELEASE_SIGNER in UpdateSource.kt)")
    parser.add_argument("--any-signer", action="store_true", help="accept any single signing certificate, for test keys")
    parser.add_argument("--libraries", action="store_true", help="only check that native libraries are aligned for their memory pages: 16 KB on arm64, 4 KB on 32-bit ARM")
    args = parser.parse_args()
    if args.libraries:
        try:
            count, pages = library_alignment(args.apks)
        except (OSError, ValueError, struct.error) as error:
            sys.exit(f"apk_check: {error}")
        print(f"apk_check: {count} native libraries are aligned for {pages} memory pages.")
        return
    root = Path(os.environ["SDK_ROOT"]) if os.environ.get("SDK_ROOT") else sdk_root()
    if root is None:
        sys.exit("apk_check: Android SDK not found")
    build_tools = root / "build-tools/37.0.0"
    identities = []
    try:
        signer = None if args.unsigned or args.any_signer else (args.signer or release_signer()).lower()
        for path in args.apks:
            try:
                identities.append(check(path, build_tools, args.unsigned, args.max_api, signer))
            except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
                raise ValueError(f"{path.name}: {error}") from error
        if len(set(identities)) != 1:
            raise ValueError("APK package IDs, versions or signing certificates do not match")
    except ValueError as error:
        sys.exit(f"apk_check: {error}")
    print("apk_check: all release checks passed.")


if __name__ == "__main__":
    main()
