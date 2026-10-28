#!/usr/bin/env python3
import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sdk

ROOT = Path(__file__).resolve().parent.parent
MATRIX = [
    {"name": "AbsentMatrix28", "image": "system-images;android-28;google_apis;arm64-v8a", "device": "pixel_4", "flavour": "compat", "port": 5600, "ram": 2048,
     "why": "Compatible floor, Android 9 with 2 GB of memory: legacy signature, installer and storage APIs"},
    {"name": "AbsentMatrix33", "image": "system-images;android-33;google_apis;arm64-v8a", "device": "pixel_6", "flavour": "foss", "port": 5602,
     "why": "foss and play floor, Android 13"},
    {"name": "AbsentMatrix34", "image": "system-images;android-34;google_apis;arm64-v8a", "device": "pixel_6", "flavour": "foss", "port": 5604,
     "why": "Android 14: typed intent extras, foreground service types, partial media access"},
    {"name": "AbsentMatrix36", "image": "system-images;android-36;google_apis;arm64-v8a", "device": "pixel_7", "flavour": "foss", "port": 5606,
     "why": "Android 16 phone"},
    {"name": "AbsentMatrix37k16", "image": "system-images;android-37.1;google_apis_ps16k;arm64-v8a", "device": "pixel_8", "flavour": "foss", "port": 5608,
     "why": "Android 17 with 16 KB memory pages, the target SDK"},
    {"name": "AbsentMatrixTab36", "image": "system-images;android-36;google_apis;arm64-v8a", "device": "pixel_tablet", "flavour": "foss", "port": 5610,
     "why": "Android 16 tablet layout"},
]
CONFIG = {"disk.dataPartition.size": "4G", "hw.ramSize": "3072", "hw.keyboard": "yes", "fastboot.forceColdBoot": "yes",
          "firstboot.saveToLocalSnapshot": "no", "firstboot.bootFromLocalSnapshot": "no", "hw.audioInput": "no"}
CHECKS = ["smoke", "memory", "engine", "device", "audio", "export", "speed"]
AUDIO_SHORT = ["wav16-48000-2", "m4a-48000-2-64", "mp3-44100-1", "ogg-48000-2", "flac-44100-2"]
SPEED_SCENE = """<scene title="matrix speed" width="1280" height="720" fps="30" totalTime="3000" bgcolor="#ff101828">
<shape id="1" label="red" startTime="0" endTime="3000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="320,360,0"/></transform><fillColor value="#ffff0000"/><property name="size" type="vec2" value="300,300"/></shape>
<shape id="2" label="blue" startTime="0" endTime="3000" fillType="color" mediaFillMode="fill" s=".roundrect"><transform><location value="960,360,0"/></transform><fillColor value="#ff0000ff"/><property name="size" type="vec2" value="300,300"/></shape>
<shape id="3" label="green" startTime="1000" endTime="3000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="640,360,0"/></transform><fillColor value="#ff00c853"/><property name="size" type="vec2" value="200,200"/></shape>
</scene>
"""
CRASH = re.compile(r"FATAL EXCEPTION|Fatal signal|UnsatisfiedLinkError|dlopen failed|ANR in " + re.escape(sdk.PACKAGE))


def avd_home():
    return Path(os.environ.get("ANDROID_AVD_HOME") or Path.home() / ".android/avd")


def free_gb(path):
    probe = Path(path)
    while not probe.exists():
        probe = probe.parent
    return shutil.disk_usage(probe).free / (1 << 30)


def image_dir(root, image):
    return root.joinpath(*image.split(";"))


def image_ready(root, image):
    folder = image_dir(root, image)
    return (folder / "system.img").is_file() and (folder / "source.properties").is_file()


def running_avds():
    out = subprocess.run(["ps", "-axo", "command"], capture_output=True, text=True).stdout
    found = {}
    for line in out.splitlines():
        if "qemu-system" not in line and "/emulator " not in line:
            continue
        name = re.search(r"-avd\s+(\S+)", line)
        port = re.search(r"-port\s+(\d+)", line)
        if name:
            found[name.group(1)] = int(port.group(1)) if port else None
    return found


def port_free(port):
    for candidate in (port, port + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.3)
            if probe.connect_ex(("127.0.0.1", candidate)) == 0:
                return False
    return True


def selected(only):
    names = {name.strip() for name in only.split(",")} if only else None
    entries = [entry for entry in MATRIX if names is None or entry["name"] in names or entry["name"].removeprefix("AbsentMatrix") in names]
    if names and not entries:
        sys.exit(f"device_matrix: no matrix entry matches {only}")
    return entries


def adb(serial, *args, timeout=120):
    try:
        result = subprocess.run([sdk.adb_path(), "-s", serial, *map(str, args)], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return result.stdout


def status(args):
    root = sdk.sdk_root()
    running = running_avds()
    print(f"free disk: {free_gb(avd_home()):.1f} GB")
    for entry in selected(args.only):
        exists = (avd_home() / f"{entry['name']}.avd").is_dir()
        state = "running" if entry["name"] in running else ("created" if exists else "missing")
        image = "image ready" if root and image_ready(root, entry["image"]) else "image missing"
        print(f"{entry['name']:<20} {state:<8} {image:<14} {entry['flavour']:<7} {entry['why']}")


def create(args):
    root = sdk.sdk_root()
    if root is None:
        sys.exit("device_matrix: Android SDK not found")
    manager = root / "cmdline-tools/latest/bin/avdmanager"
    for entry in selected(args.only):
        folder = avd_home() / f"{entry['name']}.avd"
        if folder.is_dir():
            print(f"{entry['name']}: already created")
            continue
        if not image_ready(root, entry["image"]):
            print(f"{entry['name']}: image missing; install it with sdkmanager \"{entry['image']}\" after checking free disk and the emulator version it needs")
            continue
        if free_gb(avd_home()) < args.min_free_gb:
            sys.exit(f"device_matrix: only {free_gb(avd_home()):.1f} GB free, {args.min_free_gb} GB required to create {entry['name']}")
        result = subprocess.run([str(manager), "create", "avd", "-n", entry["name"], "-k", entry["image"], "-d", entry["device"]],
                                input="no\n", capture_output=True, text=True)
        if result.returncode or not folder.is_dir():
            sys.exit(f"device_matrix: avdmanager failed for {entry['name']}: {result.stderr.strip() or result.stdout.strip()}")
        config = folder / "config.ini"
        settings = {**CONFIG, "hw.ramSize": str(entry.get("ram", CONFIG["hw.ramSize"]))}
        lines = [line for line in config.read_text().splitlines() if line.split("=")[0].strip() not in settings]
        config.write_text("\n".join(lines + [f"{key}={value}" for key, value in settings.items()]) + "\n")
        print(f"{entry['name']}: created")


def boot(entry, wipe, timeout, gpu="swiftshader_indirect"):
    root = sdk.sdk_root()
    serial = f"emulator-{entry['port']}"
    if entry["name"] in running_avds():
        raise RuntimeError(f"{entry['name']} is already running; the matrix only boots emulators it starts")
    if not port_free(entry["port"]):
        raise RuntimeError(f"port {entry['port']} is in use")
    command = [str(root / "emulator/emulator"), "-avd", entry["name"], "-port", str(entry["port"]), "-no-window", "-no-audio", "-no-boot-anim",
               "-no-snapshot", "-gpu", gpu] + (["-wipe-data"] if wipe else [])
    log = open(Path(tempfile.gettempdir()) / f"{entry['name']}.log", "w")
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{entry['name']} exited during boot (log {log.name})")
        if adb(serial, "shell", "getprop", "sys.boot_completed", timeout=10).strip() == "1":
            adb(serial, "shell", "settings", "put", "global", "package_verifier_user_consent", "-1", timeout=20)
            adb(serial, "shell", "input", "keyevent", "82", timeout=20)
            return serial, process
        time.sleep(3)
    shutdown(serial, process)
    raise RuntimeError(f"{entry['name']} did not finish booting in {timeout} s")


def shutdown(serial, process):
    adb(serial, "emu", "kill", timeout=30)
    try:
        process.wait(timeout=90)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=30)


def page_size(serial):
    page = adb(serial, "shell", "getconf", "PAGE_SIZE", timeout=20).strip()
    if page.isdigit():
        return int(page)
    found = re.search(r"KernelPageSize:\s+(\d+) kB", adb(serial, "shell", "cat", "/proc/self/smaps", timeout=20))
    return int(found.group(1)) * 1024 if found else None


def facts(serial):
    get = lambda name: adb(serial, "shell", "getprop", name, timeout=20).strip()
    return {"api": get("ro.build.version.sdk"), "release": get("ro.build.version.release"), "abi": get("ro.product.cpu.abi"),
            "page_size": page_size(serial), "model": get("ro.product.model"), "memory_mb": memory_mb(serial)}


def memory_mb(serial):
    found = re.search(r"MemTotal:\s+(\d+) kB", adb(serial, "shell", "cat", "/proc/meminfo", timeout=20))
    return int(found.group(1)) // 1024 if found else None


def pid(serial):
    return adb(serial, "shell", "pidof", sdk.PACKAGE, timeout=20).strip()


def crashes(serial):
    log = adb(serial, "logcat", "-d", "-b", "main,crash,system", timeout=60)
    return [line.strip() for line in log.splitlines() if CRASH.search(line) and (sdk.PACKAGE in line or "AndroidRuntime" in line or "DEBUG" in line)][:8]


def memory_check(serial):
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "start", "-W", "-n", f"{sdk.PACKAGE}/.MainActivity", timeout=60)
    time.sleep(4)
    first = pid(serial)
    notes = []
    for level in ("RUNNING_MODERATE", "RUNNING_LOW", "RUNNING_CRITICAL"):
        adb(serial, "shell", "am", "send-trim-memory", sdk.PACKAGE, level, timeout=30)
        time.sleep(1)
    if not first or pid(serial) != first:
        notes.append("the app did not survive trim-memory levels while in front")
    adb(serial, "shell", "input", "keyevent", "3", timeout=20)
    time.sleep(2)
    for level in ("HIDDEN", "BACKGROUND", "MODERATE", "COMPLETE"):
        adb(serial, "shell", "am", "send-trim-memory", sdk.PACKAGE, level, timeout=30)
        time.sleep(1)
    adb(serial, "shell", "am", "kill", sdk.PACKAGE, timeout=30)
    time.sleep(2)
    adb(serial, "shell", "am", "start", "-W", "-n", f"{sdk.PACKAGE}/.MainActivity", timeout=60)
    time.sleep(6)
    if not pid(serial):
        notes.append("the app did not start again after a background kill")
    notes += crashes(serial)
    return (0 if not notes else 1), ["trim-memory in front and behind, background kill and restart"] + notes


def debuggable(apk):
    root = sdk.sdk_root()
    tool = root / "build-tools/37.0.0/aapt2"
    if not tool.is_file():
        return False
    dump = subprocess.run([str(tool), "dump", "badging", str(apk)], capture_output=True, text=True).stdout
    return "application-debuggable" in dump


def launch_check(serial, seconds):
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "start", "-W", "-n", f"{sdk.PACKAGE}/.MainActivity", timeout=60)
    time.sleep(seconds)
    alive = bool(adb(serial, "shell", "pidof", sdk.PACKAGE, timeout=20).strip())
    log = adb(serial, "logcat", "-d", "-s", "AndroidRuntime:E", "linker:*", "nativeloader:*", "AMEngine:*", timeout=60)
    problems = [line for line in log.splitlines() if re.search(r"FATAL EXCEPTION|UnsatisfiedLinkError|dlopen failed|not page-aligned|16 ?KB|page size", line, re.I)]
    return alive and not problems, problems[:8]


def wanted(text):
    names = [name.strip() for name in (text or "").split(",") if name.strip()]
    unknown = [name for name in names if name not in CHECKS]
    if unknown:
        sys.exit(f"device_matrix: unknown check {', '.join(unknown)} (choose from {', '.join(CHECKS)})")
    return names


def check_command(name, args, work):
    tools = ROOT / "tools"
    if name == "smoke":
        return [sys.executable, str(tools / "smoke_check.py"), "--timeout", "180"]
    if name == "engine":
        return [sys.executable, str(tools / "engine_check.py"), str(work / "engine")]
    if name == "device":
        return [sys.executable, str(tools / "device_export_check.py")] + (["--tiny-only"] if args.quick else [])
    if name == "audio":
        return [sys.executable, str(tools / "audio_export_check.py")] + ([] if args.long else AUDIO_SHORT)
    if name == "export":
        if not (args.scene and args.stills):
            return None
        return [sys.executable, str(tools / "export_check.py"), str(Path(args.scene).resolve()), str(Path(args.stills).resolve())]
    if name == "speed":
        scene = Path(args.scene).resolve() if args.scene else work / "speed.xml"
        if not args.scene:
            scene.write_text(SPEED_SCENE)
        return ["sh", str(tools / "export_speed.sh"), str(scene)]
    raise ValueError(name)


def run_checks(serial, names, args, work):
    work.mkdir(parents=True, exist_ok=True)
    results = []
    for name in names:
        started = time.time()
        if adb(serial, "get-state", timeout=20).strip() != "device":
            results.append({"check": name, "result": "FAIL", "note": f"{serial} stopped answering before this check"})
            print(f"  {serial} {name}: FAIL ({serial} stopped answering)", flush=True)
            continue
        print(f"  {serial} {name}: running", flush=True)
        if name == "memory":
            code, lines = memory_check(serial)
        else:
            command = check_command(name, args, work)
            if command is None:
                results.append({"check": name, "result": "SKIP", "note": "needs --scene and --stills"})
                print(f"  {serial} {name}: SKIP (needs --scene and --stills)", flush=True)
                continue
            try:
                done = subprocess.run(command, capture_output=True, text=True, cwd=ROOT, env={**os.environ, "ANDROID_SERIAL": serial},
                                      timeout=args.check_timeout)
                code, output = done.returncode, (done.stdout + done.stderr)
            except subprocess.TimeoutExpired as error:
                code, output = 124, f"timed out after {args.check_timeout} s\n" + ((error.stdout or b"").decode("utf-8", "replace") if isinstance(error.stdout, bytes) else (error.stdout or ""))
            (work / f"{name}.log").write_text(output)
            lines = output.strip().splitlines()[-6:]
        result = {"check": name, "result": "PASS" if code == 0 else "FAIL", "exit": code, "seconds": round(time.time() - started), "tail": lines}
        if code and adb(serial, "get-state", timeout=20).strip() != "device":
            result["note"] = f"{serial} stopped answering during this check (emulator exit or crash, see its log)"
        results.append(result)
        print(f"  {serial} {name}: {result['result']} in {result['seconds']} s", flush=True)
    return results


def run_entry(entry, apks, args, out):
    result = {"name": entry["name"], "why": entry["why"], "flavour": entry["flavour"]}
    apk = apks.get(entry["flavour"]) or (apks.get("foss") if entry["flavour"] != "compat" else None)
    if not (avd_home() / f"{entry['name']}.avd").is_dir():
        return {**result, "result": "SKIP", "note": "AVD not created"}
    if apk is None:
        return {**result, "result": "SKIP", "note": f"no {entry['flavour']} APK given"}
    if free_gb(avd_home()) < args.min_free_gb:
        return {**result, "result": "SKIP", "note": f"less than {args.min_free_gb} GB free"}
    started = time.time()
    try:
        serial, process = boot(entry, not args.keep_data, args.boot_timeout, args.gpu)
    except RuntimeError as error:
        return {**result, "result": "FAIL", "note": str(error)}
    try:
        result.update(facts(serial))
        result["boot_s"] = round(time.time() - started)
        installed = subprocess.run([sdk.adb_path(), "-s", serial, "install", "-r", str(apk)], capture_output=True, text=True, timeout=600)
        if installed.returncode or "Success" not in installed.stdout:
            return {**result, "result": "FAIL", "note": "install: " + (installed.stderr.strip() or installed.stdout.strip())[-300:]}
        dump = adb(serial, "shell", "dumpsys", "package", sdk.PACKAGE, timeout=60)
        result["page_compat"] = sorted(set(re.findall(r"(?i)(pageSizeCompat\S*|PAGE_SIZE_APP_COMPAT\S*)", dump)))
        ok, problems = launch_check(serial, args.launch_seconds)
        result["launch"] = "ok" if ok else "problem"
        result["problems"] = problems
        names = wanted(args.checks)
        if names and not debuggable(apk):
            names = [name for name in names if name == "memory"]
        result["checks"] = run_checks(serial, names, args, out / entry["name"])
        ok = ok and all(check["result"] != "FAIL" for check in result["checks"])
        expected = 16384 if "ps16k" in entry["image"] else 4096
        if result.get("page_size") != expected:
            ok = False
            result["problems"] = result.get("problems", []) + [f"page size {result.get('page_size')} instead of {expected}"]
        return {**result, "result": "PASS" if ok else "FAIL"}
    finally:
        shutdown(serial, process)


def output_folder(args):
    out = Path(args.out).resolve()
    if out == ROOT or ROOT in out.parents:
        sys.exit("device_matrix: write results outside the repository")
    out.mkdir(parents=True, exist_ok=True)
    return out


def run(args):
    apks = {key: Path(value).resolve() for key, value in (("foss", args.foss), ("compat", args.compat)) if value}
    for path in apks.values():
        if not path.is_file():
            sys.exit(f"device_matrix: {path} not found")
    wanted(args.checks)
    out = output_folder(args)
    results = []
    for entry in selected(args.only):
        print(f"{entry['name']}: running", flush=True)
        result = run_entry(entry, apks, args, out)
        results.append(result)
        summary = " ".join(f"{check['check']}={check['result']}" for check in result.get("checks", []))
        print(f"{entry['name']}: {result['result']} {result.get('note', '')} api={result.get('api', '?')} page={result.get('page_size', '?')} "
              f"launch={result.get('launch', '-')} {summary}", flush=True)
        (out / "matrix.json").write_text(json.dumps(results, indent=1) + "\n")
    print(f"results: {out / 'matrix.json'}")
    if any(result["result"] == "FAIL" for result in results):
        sys.exit(1)


def checks(args):
    names = wanted(args.checks)
    out = output_folder(args)
    apk = Path(args.apk).resolve() if args.apk else None
    if apk and not apk.is_file():
        sys.exit(f"device_matrix: {apk} not found")
    process = None
    if args.avd:
        entries = selected(args.avd)
        if len(entries) != 1:
            sys.exit("device_matrix: --avd needs exactly one matrix entry")
        if free_gb(avd_home()) < args.min_free_gb:
            sys.exit(f"device_matrix: less than {args.min_free_gb} GB free")
        try:
            serial, process = boot(entries[0], not args.keep_data, args.boot_timeout, args.gpu)
        except RuntimeError as error:
            sys.exit(f"device_matrix: {error}")
    else:
        serial = args.serial or sdk.serial()
        if not serial.startswith("emulator-") and not args.device:
            sys.exit(f"device_matrix: {serial} is not an emulator; pass --device to test a physical device on purpose")
        if adb(serial, "get-state", timeout=20).strip() != "device":
            sys.exit(f"device_matrix: no device answers for {serial}")
    try:
        result = {"serial": serial, **facts(serial)}
        if apk:
            installed = subprocess.run([sdk.adb_path(), "-s", serial, "install", "-r", str(apk)], capture_output=True, text=True, timeout=600)
            if installed.returncode or "Success" not in installed.stdout:
                sys.exit("device_matrix: install failed: " + (installed.stderr.strip() or installed.stdout.strip())[-300:])
            result["apk"] = apk.name
        dump = adb(serial, "shell", "dumpsys", "package", sdk.PACKAGE, timeout=60)
        if "versionName=" not in dump:
            sys.exit(f"device_matrix: {sdk.PACKAGE} is not installed on {serial}; pass --apk")
        result["version"] = re.search(r"versionName=(\S+)", dump).group(1)
        result["page_compat"] = sorted(set(re.findall(r"(?i)(pageSizeCompat=\S+)", dump)))
        print(f"{serial}: Android {result['release']} (API {result['api']}), page size {result['page_size']}, {result['memory_mb']} MB, "
              f"{sdk.PACKAGE} {result['version']} {' '.join(result['page_compat'])}", flush=True)
        result["checks"] = run_checks(serial, names, args, out / serial)
    finally:
        if process is not None:
            shutdown(serial, process)
    report = out / f"checks-{serial}.json"
    report.write_text(json.dumps(result, indent=1) + "\n")
    print(f"results: {report}")
    if any(check["result"] == "FAIL" for check in result["checks"]):
        sys.exit(1)


def start(args):
    for entry in selected(args.only):
        if free_gb(avd_home()) < args.min_free_gb:
            sys.exit(f"device_matrix: less than {args.min_free_gb} GB free")
        try:
            serial, _ = boot(entry, args.wipe, args.boot_timeout, args.gpu)
        except RuntimeError as error:
            sys.exit(f"device_matrix: {error}")
        print(f"{entry['name']}: {serial} {json.dumps(facts(serial))}")


def stop(args):
    running = running_avds()
    for entry in selected(args.only):
        if entry["name"] in running:
            adb(f"emulator-{running[entry['name']] or entry['port']}", "emu", "kill", timeout=30)
            print(f"{entry['name']}: stopping")


def clean(args):
    running = running_avds()
    for entry in selected(args.only):
        folder = avd_home() / f"{entry['name']}.avd"
        if not folder.is_dir() or entry["name"] in running:
            continue
        removed = 0
        for item in folder.iterdir():
            if item.name.startswith(("userdata-qemu.img", "cache.img", "encryptionkey.img", "sdcard.img")) or item.name.endswith(".qcow2"):
                removed += item.stat().st_size
                item.unlink()
        snapshots = folder / "snapshots"
        if snapshots.is_dir():
            shutil.rmtree(snapshots)
        print(f"{entry['name']}: removed {removed / (1 << 20):.0f} MB of device data")


def main():
    parser = argparse.ArgumentParser(description="Create and run AbsentMotion's emulator test matrix without touching other emulators")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("status", "create", "run", "checks", "boot", "stop", "clean"):
        command = commands.add_parser(name)
        if name != "checks":
            command.add_argument("--only", help="comma-separated matrix names, for example 28,37k16")
        command.add_argument("--min-free-gb", type=float, default=15.0 if name == "create" else 5.0)
        if name in ("run", "checks", "boot"):
            command.add_argument("--gpu", default="swiftshader_indirect", help="emulator GPU mode: swiftshader_indirect or host")
        if name in ("run", "checks"):
            command.add_argument("--checks", default="smoke,memory" if name == "run" else "smoke,memory,engine,device,audio,export,speed",
                                 help="comma-separated: " + ",".join(CHECKS))
            command.add_argument("--out", default=str(Path(tempfile.gettempdir()) / "absent-matrix"))
            command.add_argument("--keep-data", action="store_true", help="boot without wiping the matrix device first")
            command.add_argument("--boot-timeout", type=int, default=600)
            command.add_argument("--check-timeout", type=int, default=1800)
            command.add_argument("--quick", action="store_true", help="short device export check")
            command.add_argument("--long", action="store_true", help="every audio export format")
            command.add_argument("--scene", help="scene XML for the export and speed checks")
            command.add_argument("--stills", help="reference stills for the export check")
        if name == "run":
            command.add_argument("--foss", help="debug APK for foss entries")
            command.add_argument("--compat", help="debug APK for the Android 9 entry")
            command.add_argument("--launch-seconds", type=int, default=12)
        if name == "checks":
            command.add_argument("--serial", help="running emulator to test (default: ANDROID_SERIAL, then emulator-5554)")
            command.add_argument("--avd", help="matrix entry to boot, test and stop instead, for example 37k16")
            command.add_argument("--apk", help="APK to install first with adb install -r")
            command.add_argument("--device", action="store_true", help="allow a serial that is not an emulator")
        if name == "boot":
            command.add_argument("--wipe", action="store_true")
            command.add_argument("--boot-timeout", type=int, default=600)
    args = parser.parse_args()
    {"status": status, "create": create, "run": run, "checks": checks, "boot": start, "stop": stop, "clean": clean}[args.command](args)


if __name__ == "__main__":
    main()
