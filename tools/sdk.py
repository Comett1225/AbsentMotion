import os
import shutil
import subprocess
from pathlib import Path

PACKAGE = "app.absentmotion"


def sdk_root():
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value and Path(value).is_dir():
            return Path(value)
    for candidate in (Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"):
        if candidate.is_dir():
            return candidate
    return None


def adb_path():
    explicit = os.environ.get("ADB")
    if explicit:
        return explicit
    root = sdk_root()
    if root and (root / "platform-tools/adb").is_file():
        return str(root / "platform-tools/adb")
    return shutil.which("adb") or "adb"


def serial():
    return os.environ.get("ANDROID_SERIAL") or "emulator-5554"


def adb_command():
    return [adb_path(), "-s", serial()]


def adb(*args, check=False):
    result = subprocess.run(adb_command() + [str(a) for a in args], capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError(f"adb {' '.join(map(str, args))} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def api_level():
    text = adb("shell", "getprop", "ro.build.version.sdk").strip()
    return int(text) if text.isdigit() else 0


def pull_app_file(remote, local):
    def pull():
        return subprocess.run(adb_command() + ["pull", str(remote), str(local)], capture_output=True).returncode == 0
    if pull():
        return
    if adb("shell", "getprop", "ro.debuggable").strip() == "1":
        adb("root")
        adb("wait-for-device")
        if pull():
            return
    raise RuntimeError(f"cannot read {remote} from the device: adb pull is refused and the device cannot run adb as root")
