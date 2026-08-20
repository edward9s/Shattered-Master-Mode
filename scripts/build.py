#!/usr/bin/env python3
"""一鍵在本機編譯 mod release。

用法：
    python scripts/build.py ~/Desktop/spd

唯一必要參數是 Shattered Pixel Dungeon 原始碼的路徑，流程與 .github/workflows/build.yml
相同：patch 原始碼 -> 注入 mod -> 合併 mod 原始碼 -> Gradle 編譯 -> 簽章 -> 產出到 out/。

本檔案不會修改 scripts/ 內其他供 GitHub Actions 使用的 script，只以匯入函式或
子行程的方式重用它們。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MOD_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

import patch_android  # noqa: E402  (與本檔同目錄，僅定義函式)
from extract_version import extract_version  # noqa: E402

WNDGAME = Path("core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndGame.java")
MODGAME = Path("core/src/main/java/com/spd/mod/ModGame.java")
OVERLAY_DIRS = ("core", "android", "desktop", "assets")
UPSTREAM_REPO = "00-Evan/shattered-pixel-dungeon"

DEBUG_KEYSTORE = Path.home() / ".android" / "debug.keystore"
DEBUG_KEYSTORE_PASS = "android"
DEBUG_KEY_ALIAS = "androiddebugkey"


if hasattr(sys.stdout, "reconfigure"):
    # 避免在非 UTF-8 主控台（例如部分 Windows 終端機）因中文訊息而中斷
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")


def step(message: str) -> None:
    print(f"\n==> {message}", flush=True)


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("$ " + subprocess.list2cmdline(cmd), flush=True)
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


# --- 環境檢查 -----------------------------------------------------------------

def resolve_spd(raw_path: str) -> Path:
    spd = Path(os.path.expanduser(raw_path)).resolve()
    required = ["settings.gradle", "build.gradle", "gradlew", str(WNDGAME)]
    missing = [name for name in required if not (spd / name).exists()]
    if missing:
        sys.exit(
            f"Error: {spd} 不像是 Shattered Pixel Dungeon 原始碼\n"
            f"       缺少: {', '.join(missing)}"
        )
    return spd


def upstream_tag(spd: Path) -> str:
    """由 build.gradle 的 appVersionName 推出官方 tag，例如 v3.3.8。"""
    data = (spd / "build.gradle").read_text(encoding="utf-8")
    match = re.search(r"appVersionName\s*=\s*'([^']+)'", data)
    if not match:
        sys.exit(f"Error: 無法從 {spd / 'build.gradle'} 取得 appVersionName")
    return f"v{match.group(1)}"


def android_sdk_root(spd: Path) -> Path | None:
    local_properties = spd / "local.properties"
    if local_properties.exists():
        match = re.search(
            r"^\s*sdk\.dir\s*=\s*(.+)$",
            local_properties.read_text(encoding="utf-8"),
            re.MULTILINE,
        )
        if match:
            # local.properties 會把路徑跳脫成 C\:\\Users\\...
            sdk = Path(match.group(1).strip().replace("\\\\", "\\").replace("\\:", ":"))
            if sdk.is_dir():
                return sdk

    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_var)
        if value and Path(value).is_dir():
            return Path(value)
    return None


def build_tools_dir(sdk: Path) -> Path | None:
    candidates = [p for p in (sdk / "build-tools").glob("*") if p.is_dir()]
    if not candidates:
        return None
    return max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))


def tool_path(build_tools: Path, name: str) -> Path | None:
    for suffix in ("", ".exe", ".bat"):
        candidate = build_tools / f"{name}{suffix}"
        if candidate.exists():
            return candidate
    return None


# --- Patch / 注入 -------------------------------------------------------------

def patch_sources(spd: Path, depth: int) -> None:
    build_gradle = spd / "build.gradle"

    step("Patch build.gradle / AndroidManifest.xml")
    gradle_text = build_gradle.read_text(encoding="utf-8")
    if re.search(r"appPackageName\s*=\s*'[^']*\.mod'", gradle_text):
        # patch_android.patch_gradle 不是冪等的，重跑會變成 .mod.mod
        print(f"{build_gradle} 已套用過 mod patch，略過")
    else:
        patch_android.patch_gradle(str(build_gradle))
        print(f"Patched {build_gradle}")

    patch_android.patch_play_games_version(
        str(build_gradle), str(spd / "android" / "build.gradle")
    )
    patch_android.patch_manifest(str(spd / "android/src/main/AndroidManifest.xml"))
    print(f"Patched {spd / 'android/src/main/AndroidManifest.xml'}")

    step("注入 mod 選單到 WndGame")
    run([sys.executable, str(SCRIPT_DIR / "inject_mod.py"), str(spd / WNDGAME)])

    step("合併 mod 原始碼")
    for name in OVERLAY_DIRS:
        source = MOD_ROOT / name
        if source.is_dir():
            shutil.copytree(source, spd / name, dirs_exist_ok=True)
            print(f"Copied {name}/ -> {spd / name}")

    step(f"設定 ModGame.maxDepth() = {depth}")
    modgame = spd / MODGAME
    content = modgame.read_text(encoding="utf-8")
    content, count = re.subn(
        r"(public static int maxDepth\(\)\s*\{\s*return\s+)\d+(;\s*\})",
        rf"\g<1>{depth}\g<2>",
        content,
    )
    if count != 1:
        sys.exit(f"Error: 在 {modgame} 找不到 maxDepth()")
    modgame.write_text(content, encoding="utf-8")
    print(f"ModGame.maxDepth() patched to {depth}")


# --- 編譯 ---------------------------------------------------------------------

def gradle_build(spd: Path, tasks: list[str]) -> None:
    step(f"Gradle 編譯: {' '.join(tasks)}")
    if os.name == "nt":
        wrapper = spd / "gradlew.bat"
    else:
        wrapper = spd / "gradlew"
        wrapper.chmod(0o755)
    run([str(wrapper), *tasks], cwd=spd)


def find_release_apk(spd: Path) -> Path:
    release_dir = spd / "android/build/outputs/apk/release"
    apks = [p for p in release_dir.glob("*.apk") if not p.name.endswith("-signed.apk")]
    if not apks:
        sys.exit(f"Error: 在 {release_dir} 找不到編譯出的 APK")
    return max(apks, key=lambda p: p.stat().st_mtime)


def find_desktop_jar(spd: Path) -> Path:
    libs_dir = spd / "desktop/build/libs"
    jars = sorted(libs_dir.glob("desktop-*.jar"))
    if not jars:
        sys.exit(f"Error: 在 {libs_dir} 找不到編譯出的 JAR")
    return max(jars, key=lambda p: p.stat().st_mtime)


# --- 簽章 ---------------------------------------------------------------------

def ensure_debug_keystore() -> None:
    if DEBUG_KEYSTORE.exists():
        return
    keytool = shutil.which("keytool")
    if not keytool and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / "keytool"
        keytool = str(candidate) if candidate.exists() or candidate.with_suffix(".exe").exists() else None
    if not keytool:
        sys.exit("Error: 找不到 keytool，無法建立 debug keystore（可改用 --keystore 或 --no-sign）")

    DEBUG_KEYSTORE.parent.mkdir(parents=True, exist_ok=True)
    step("建立 Android debug keystore")
    run([
        keytool, "-genkeypair", "-v",
        "-keystore", str(DEBUG_KEYSTORE),
        "-storepass", DEBUG_KEYSTORE_PASS,
        "-alias", DEBUG_KEY_ALIAS,
        "-keypass", DEBUG_KEYSTORE_PASS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US",
    ])


def sign_apk(apk: Path, dest: Path, build_tools: Path, keystore: Path,
             store_pass: str, key_alias: str, key_pass: str) -> None:
    zipalign = tool_path(build_tools, "zipalign")
    apksigner = tool_path(build_tools, "apksigner")
    if not zipalign or not apksigner:
        sys.exit(f"Error: {build_tools} 內找不到 zipalign / apksigner")

    with tempfile.TemporaryDirectory() as tmp:
        aligned = Path(tmp) / "aligned.apk"
        run([str(zipalign), "-p", "-f", "4", str(apk), str(aligned)])
        run([
            str(apksigner), "sign",
            "--ks", str(keystore),
            "--ks-pass", f"pass:{store_pass}",
            "--ks-key-alias", key_alias,
            "--key-pass", f"pass:{key_pass}",
            # v4 簽章會另外產生 .idsig 檔，一般安裝用不到
            "--v4-signing-enabled", "false",
            "--out", str(dest),
            str(aligned),
        ])


# --- Windows ZIP（選用，需連線下載官方發行檔）---------------------------------

def build_windows_zip(tag: str, new_jar: Path, dest: Path) -> None:
    step(f"重新打包官方 Windows ZIP ({tag})")
    api_url = f"https://api.github.com/repos/{UPSTREAM_REPO}/releases/tags/{tag}"
    with urllib.request.urlopen(api_url) as response:
        release = json.load(response)

    zip_url = next((
        asset["browser_download_url"]
        for asset in release["assets"]
        if asset["name"].lower().endswith("-windows.zip")
    ), None)
    if zip_url is None:
        sys.exit(f"Error: 官方 {tag} 沒有 Windows ZIP")

    with tempfile.TemporaryDirectory() as tmp:
        official_zip = Path(tmp) / "official.zip"
        extracted = Path(tmp) / "win"
        print(f"Downloading {zip_url}")
        urllib.request.urlretrieve(zip_url, official_zip)
        with zipfile.ZipFile(official_zip) as archive:
            archive.extractall(extracted)

        old_jar = next(
            (p for p in extracted.rglob("desktop-*.jar")),
            None,
        )
        if old_jar is None:
            sys.exit("Error: 官方 ZIP 內找不到 desktop-*.jar")
        shutil.copy2(new_jar, old_jar)

        with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as new_zip:
            for path in sorted(extracted.rglob("*")):
                if path.is_file():
                    new_zip.write(path, path.relative_to(extracted))


# --- 主流程 -------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="注入 mod 到 SPD 原始碼並編譯出 release 檔案",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="範例: python scripts/build.py ~/Desktop/spd",
    )
    parser.add_argument("spd_path", help="Shattered Pixel Dungeon 原始碼路徑，例如 ~/Desktop/spd")
    parser.add_argument("--depth", type=int, default=26, help="ModGame.maxDepth() 值（預設 26）")
    parser.add_argument("--out", default=str(MOD_ROOT / "out"), help="產出目錄（預設 <mod>/out）")
    parser.add_argument("--skip-android", action="store_true", help="不編譯 Android APK")
    parser.add_argument("--skip-desktop", action="store_true", help="不編譯桌面版 JAR")
    parser.add_argument("--windows-zip", action="store_true",
                        help="額外下載官方 Windows ZIP 並換上 mod JAR（需網路）")
    parser.add_argument("--no-sign", action="store_true", help="APK 不簽章（產出 unsigned）")
    parser.add_argument("--keystore", help="簽章用 keystore（預設使用 Android debug keystore）")
    parser.add_argument("--keystore-pass", default=DEBUG_KEYSTORE_PASS, help="keystore 密碼")
    parser.add_argument("--key-alias", default=DEBUG_KEY_ALIAS, help="key alias")
    parser.add_argument("--key-pass", help="key 密碼（預設與 keystore 密碼相同）")
    args = parser.parse_args()

    if args.skip_android and args.skip_desktop:
        sys.exit("Error: --skip-android 與 --skip-desktop 不能同時使用")

    spd = resolve_spd(args.spd_path)
    out_dir = Path(os.path.expanduser(args.out)).resolve()
    mod_ver = extract_version(str(MOD_ROOT / MODGAME))
    tag = upstream_tag(spd)

    build_tools = None
    if not args.skip_android:
        sdk = android_sdk_root(spd)
        if sdk is None:
            sys.exit(
                "Error: 找不到 Android SDK\n"
                "       請設定 ANDROID_HOME，或在 SPD 原始碼放 local.properties (sdk.dir=...)\n"
                "       只要桌面版可加 --skip-android"
            )
        build_tools = build_tools_dir(sdk)
        print(f"Android SDK: {sdk}")

    print(f"Mod 版本   : {mod_ver}")
    print(f"官方版本   : {tag}")
    print(f"SPD 原始碼 : {spd}")
    print(f"產出目錄   : {out_dir}")

    patch_sources(spd, args.depth)

    tasks = []
    if not args.skip_android:
        tasks.append("android:assembleRelease")
    if not args.skip_desktop:
        tasks.append(":desktop:release")
    gradle_build(spd, tasks)

    step("收集產出物")
    out_dir.mkdir(parents=True, exist_ok=True)
    artifacts: list[Path] = []

    if not args.skip_android:
        apk = find_release_apk(spd)
        if args.no_sign:
            dest = out_dir / f"SPD-{tag}-m{mod_ver}-Android-unsigned.apk"
            shutil.copy2(apk, dest)
            print("警告: APK 未簽章，無法直接安裝")
        else:
            if args.keystore:
                keystore = Path(os.path.expanduser(args.keystore)).resolve()
                if not keystore.exists():
                    sys.exit(f"Error: keystore 不存在: {keystore}")
            else:
                ensure_debug_keystore()
                keystore = DEBUG_KEYSTORE
            if build_tools is None:
                sys.exit("Error: Android SDK 內找不到 build-tools，無法簽章（可加 --no-sign）")
            dest = out_dir / f"SPD-{tag}-m{mod_ver}-Android.apk"
            sign_apk(
                apk, dest, build_tools, keystore,
                args.keystore_pass, args.key_alias,
                args.key_pass or args.keystore_pass,
            )
            if keystore == DEBUG_KEYSTORE:
                print("提示: 使用 debug keystore 簽章，僅供本機安裝測試")
        artifacts.append(dest)

    if not args.skip_desktop:
        jar = find_desktop_jar(spd)
        dest = out_dir / f"SPD-{tag}-m{mod_ver}-Java.jar"
        shutil.copy2(jar, dest)
        artifacts.append(dest)

        if args.windows_zip:
            windows_zip = out_dir / f"SPD-{tag}-m{mod_ver}-Windows.zip"
            build_windows_zip(tag, jar, windows_zip)
            artifacts.append(windows_zip)
    elif args.windows_zip:
        print("警告: --windows-zip 需要桌面版 JAR，已略過")

    step("完成")
    for path in artifacts:
        print(f"  {path}  ({path.stat().st_size / 1024 / 1024:.1f} MB)")


if __name__ == "__main__":
    main()
