#!/usr/bin/env python3
import argparse
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path.home() / "Library/Android/sdk"
SYSTEM_LIBS = ["libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libvulkan.so", "libjnigraphics.so", "libEGL.so", "libGLESv1_CM.so",
               "libGLESv2.so", "libGLESv3.so", "libnativewindow.so", "libmediandk.so", "libcamera2ndk.so", "libaaudio.so", "libOpenSLES.so", "libz.so",
               "libsync.so", "libbinder_ndk.so"]
ARCH = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "arm-linux-androideabi", "x86_64": "x86_64-linux-android", "x86": "i686-linux-android"}


def find_ndk():
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    version = next(line.split('"')[1] for line in gradle.splitlines() if "ndkVersion" in line)
    ndk = SDK / "ndk" / version
    if not ndk.is_dir():
        sys.exit(f"api_floor: NDK {version} not found in {SDK}")
    return ndk


def nm(tool, args, path):
    out = subprocess.run([str(tool), "-D", *args, "--just-symbol-name", str(path)], capture_output=True, text=True).stdout
    return [line.split("@")[0].strip() for line in out.splitlines() if line.strip()]


def undefined(tool, path):
    out = subprocess.run([str(tool), "-D", "--undefined-only", str(path)], capture_output=True, text=True).stdout
    result = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            result[parts[-1].split("@")[0]] = parts[-2] == "w"
    return result


def exported_by_api(tool, sysroot, arch, apis):
    first = {}
    for api in apis:
        folder = sysroot / arch / str(api)
        if not folder.is_dir():
            continue
        for lib in SYSTEM_LIBS:
            stub = folder / lib
            if stub.is_file():
                for name in nm(tool, ["--defined-only"], stub):
                    first.setdefault((lib, name), api)
    return first


def main():
    parser = argparse.ArgumentParser(description="Lowest Android API level the native libraries of an APK or folder can load on")
    parser.add_argument("target", nargs="?", default=str(ROOT / "app/build/outputs/apk/foss/release/app-foss-release.apk"), help="an APK or a folder with .so files")
    parser.add_argument("--abi", default="arm64-v8a")
    parser.add_argument("--min", type=int, default=0, help="report symbols that need more than this API level (default: the minSdk in app/build.gradle.kts)")
    parser.add_argument("--all", action="store_true", help="list every strong symbol above the limit, not just the first 40")
    args = parser.parse_args()
    ndk = find_ndk()
    prebuilt = next((ndk / "toolchains/llvm/prebuilt").iterdir())
    tool = prebuilt / "bin/llvm-nm"
    sysroot = prebuilt / "sysroot/usr/lib"
    arch = ARCH[args.abi]
    limit = args.min or int(next(line.split("=")[1] for line in (ROOT / "app/build.gradle.kts").read_text().splitlines() if line.strip().startswith("minSdk")))
    work = Path(tempfile.mkdtemp(prefix="api-floor-"))
    target = Path(args.target)
    if target.suffix in (".apk", ".zip"):
        with zipfile.ZipFile(target) as z:
            for name in z.namelist():
                if name.startswith(f"lib/{args.abi}/") and name.endswith(".so"):
                    (work / Path(name).name).write_bytes(z.read(name))
        libs = sorted(work.glob("*.so"))
    else:
        libs = sorted(target.rglob("*.so"))
    if not libs:
        sys.exit("api_floor: no .so files found")
    apis = sorted(int(p.name) for p in (sysroot / arch).iterdir() if p.name.isdigit())
    first = exported_by_api(tool, sysroot, arch, apis)
    by_name = defaultdict(list)
    for (lib, name), api in first.items():
        by_name[name].append(api)
    own = {lib.name for lib in libs}
    defined = set()
    for lib in libs:
        defined.update(nm(tool, ["--defined-only"], lib))
    floor, strong = 0, []
    for lib in libs:
        for name, weak in undefined(tool, lib).items():
            if name in defined or name not in by_name:
                continue
            api = min(by_name[name])
            if weak:
                continue
            floor = max(floor, api)
            if api > limit:
                strong.append((api, name, lib.name))
    strong.sort(reverse=True)
    print(f"{len(libs)} libraries ({', '.join(sorted(own))})")
    print(f"lowest API level the native symbols need: {floor}")
    shown = strong if args.all else strong[:40]
    for api, name, lib in shown:
        print(f"  API {api}: {name} (used by {lib})")
    if len(strong) > len(shown):
        print(f"  ... and {len(strong) - len(shown)} more (use --all)")
    print(f"symbols above API {limit}: {len(strong)}")
    sys.exit(1 if strong else 0)


main()
