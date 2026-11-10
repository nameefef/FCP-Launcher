#!/usr/bin/env python3
"""Download Pojav JRE 17 and extract FCL 1.3.4.4 natives.

Writes:
  android/src/main/jniLibs/<abi>/*.so
  core/src/main/resources/fcp/android-runtime.json
  runtime/natives.lock.json

Re-run from the repo root when bumping the pinned FCL or Pojav release.
Downloads stay in /tmp/fcp-rt and are not committed.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path("/tmp/fcp-rt")
JNILIBS = ROOT / "android/src/main/jniLibs"
CATALOG = ROOT / "core/src/main/resources/fcp/android-runtime.json"
LOCK = ROOT / "runtime/natives.lock.json"

FCL_TAG = "1.3.4.4"
FCL_BASE = f"https://github.com/FCL-Team/FoldCraftLauncher/releases/download/{FCL_TAG}"
FCL_APKS = {
    "arm64-v8a": "FCL-release-1.3.4.4-arm64-v8a.apk",
    "armeabi-v7a": "FCL-release-1.3.4.4-armeabi-v7a.apk",
    "x86_64": "FCL-release-1.3.4.4-x86_64.apk",
    "x86": "FCL-release-1.3.4.4-x86.apk",
}

# Fold Craft Launcher does not ship a separate libglfw.so. Mojang's LWJGL
# loads that name; FCL's GLFW JNI entry points live in libpojavexec.so.
# The copy below is the same bytes under the name LWJGL asks for.
NATIVE_LIBS = [
    "libpojavexec.so",
    "libfcl.so",
    "libandroidnsbypass.so",
    "libbytehook.so",
    "libc++_shared.so",
    "libopenal.so",
    "libgl4es_114.so",
]
GLFW_ALIAS = "libglfw.so"
# Vendored from Pojav. Refreshing jniLibs must not delete it; the APK does not contain it.
UNPACK200 = "libunpack200.so"
# FCL 1.3.4.4 ships LWJGL 3 only. 1.12.2 and older use 3.3.3 plus lwjgl-lwjglx.jar.
LWJGL_VERSIONS = ("3.3.3", "3.4.1")

JRE17_TAG = "jre17-ec28559"
JRE17_BASE = (
    "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch"
    f"/releases/download/{JRE17_TAG}"
)
JRE17_FILES = {
    "arm64-v8a": "jre17-arm64-20210825-release.tar.xz",
    "armeabi-v7a": "jre17-arm-20210914-release.tar.xz",
    "x86_64": "jre17-x86_64-20210825-release.tar.xz",
    "x86": "jre17-x86-20220225-release.tar.xz",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, dest: Path) -> None:
    if dest.is_file() and dest.stat().st_size > 0:
        print(f"reuse {dest}")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"get {url}")
    subprocess.check_call(["curl", "-fL", "--retry", "3", "-o", str(dest), url])


def file_record(apk: zipfile.ZipFile, entry: str) -> dict[str, str]:
    data = apk.read(entry)
    return {
        "name": entry.rsplit("/", 1)[-1],
        "entry": entry,
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def lwjgl_members(apk: zipfile.ZipFile, version: str, abi: str) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    prefix = f"assets/app_runtime/lwjgl/{version}/"
    native_prefix = f"{prefix}natives/{abi}/"
    jars = []
    natives = []
    for name in apk.namelist():
        if not name.startswith(prefix) or name.endswith("/"):
            continue
        if name.startswith(native_prefix) and name.endswith(".so"):
            natives.append(file_record(apk, name))
        elif name.endswith(".jar") and name.count("/") == prefix.count("/"):
            jars.append(file_record(apk, name))
    if not jars or not natives:
        raise SystemExit(f"lwjgl {version} {abi} jars={len(jars)} natives={len(natives)}")
    jars.sort(key=lambda item: item["name"])
    natives.sort(key=lambda item: item["name"])
    return jars, natives


def jre_entries(apk: zipfile.ZipFile, major: int) -> list[str]:
    prefix = f"assets/app_runtime/java/jre{major}/"
    names = [name for name in apk.namelist() if name.startswith(prefix) and name.endswith(".tar.xz")]
    universal = [name for name in names if name.endswith("/universal.tar.xz")]
    binary = [name for name in names if "/bin-" in name]
    if len(universal) != 1 or len(binary) != 1:
        raise SystemExit(f"jre{major} entries in apk: {names}")
    return universal + binary


def main() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    # Earlier inspection saved the x86_64 APK under a short name.
    alias = CACHE / "fcl-x86_64.apk"
    canonical = CACHE / FCL_APKS["x86_64"]
    if alias.is_file() and not canonical.is_file():
        alias.replace(canonical)

    catalog_jre = []
    catalog_lwjgl = []
    lock_natives = []
    lock_lwjgl = []

    for abi, filename in JRE17_FILES.items():
        dest = CACHE / filename
        download(f"{JRE17_BASE}/{filename}", dest)
        catalog_jre.append(
            {
                "major": 17,
                "abi": abi,
                "url": f"{JRE17_BASE}/{filename}",
                "sha256": sha256(dest),
                "format": "tar.xz",
                "entries": [],
                "source": f"PojavLauncherTeam/android-openjdk-build-multiarch {JRE17_TAG}",
            }
        )

    for abi, filename in FCL_APKS.items():
        dest = CACHE / filename
        download(f"{FCL_BASE}/{filename}", dest)
        digest = sha256(dest)
        with zipfile.ZipFile(dest) as apk:
            out_dir = JNILIBS / abi
            unpack200 = None
            if out_dir.exists():
                saved = out_dir / UNPACK200
                if saved.is_file():
                    unpack200 = saved.read_bytes()
                shutil.rmtree(out_dir)
            out_dir.mkdir(parents=True)
            if unpack200 is not None:
                (out_dir / UNPACK200).write_bytes(unpack200)
            for lib in NATIVE_LIBS:
                member = f"lib/{abi}/{lib}"
                data = apk.read(member)
                target = out_dir / lib
                target.write_bytes(data)
                lock_natives.append(
                    {
                        "abi": abi,
                        "name": lib,
                        "sha256": hashlib.sha256(data).hexdigest(),
                        "apk": filename,
                        "apkSha256": digest,
                        "entry": member,
                    }
                )
            glfw = out_dir / GLFW_ALIAS
            shutil.copyfile(out_dir / "libpojavexec.so", glfw)
            lock_natives.append(
                {
                    "abi": abi,
                    "name": GLFW_ALIAS,
                    "sha256": hashlib.sha256(glfw.read_bytes()).hexdigest(),
                    "apk": filename,
                    "apkSha256": digest,
                    "entry": f"lib/{abi}/libpojavexec.so",
                    "note": "same bytes as libpojavexec.so; Mojang LWJGL loads libglfw.so",
                }
            )
            for version in LWJGL_VERSIONS:
                jars, natives = lwjgl_members(apk, version, abi)
                catalog_lwjgl.append(
                    {
                        "version": version,
                        "abi": abi,
                        "url": f"{FCL_BASE}/{filename}",
                        "sha256": digest,
                        "jars": jars,
                        "natives": natives,
                        "source": f"FCL-Team/FoldCraftLauncher {FCL_TAG} app_runtime/lwjgl/{version}",
                    }
                )
                for record in jars + natives:
                    lock_lwjgl.append(
                        {
                            "version": version,
                            "abi": abi,
                            "name": record["name"],
                            "sha256": record["sha256"],
                            "apk": filename,
                            "apkSha256": digest,
                            "entry": record["entry"],
                        }
                    )
            for major in (8, 21):
                entries = jre_entries(apk, major)
                catalog_jre.append(
                    {
                        "major": major,
                        "abi": abi,
                        "url": f"{FCL_BASE}/{filename}",
                        "sha256": digest,
                        "format": "fcl-apk",
                        "entries": entries,
                        "source": f"FCL-Team/FoldCraftLauncher {FCL_TAG}",
                    }
                )

    catalog_jre.sort(key=lambda item: (item["major"], item["abi"]))
    catalog_lwjgl.sort(key=lambda item: (item["version"], item["abi"]))
    catalog = {"jre": catalog_jre, "lwjgl": catalog_lwjgl}
    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(json.dumps(catalog, indent=2) + "\n")
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    LOCK.write_text(
        json.dumps(
            {
                "fclTag": FCL_TAG,
                "jre17Tag": JRE17_TAG,
                "natives": lock_natives,
                "lwjgl": lock_lwjgl,
            },
            indent=2,
        )
        + "\n"
    )
    print(f"wrote {CATALOG}")
    print(f"wrote {LOCK}")
    print(f"jniLibs {JNILIBS}")


if __name__ == "__main__":
    main()
