#!/usr/bin/env python3
"""Inject SMM's ModAnkh and debug console into an SPD-derived APK.

Usage:
    python scripts/inject_apk.py <source-smm.apk> <target.apk> [--out output.apk]

Scope is intentionally narrow:
  * copies ModAnkh plus the controlled ModDebug payload;
  * patches Dungeon.init() immediately after HeroClass.initHero(Hero);
  * leaves AndroidManifest.xml and all resources untouched;
  * builds one small first-dex overlay containing patched Dungeon + ModAnkh/debug classes;
  * preserves every original target DEX byte-for-byte and shifts them back one slot;
  * validates ModAnkh's executable target API references before packaging.

The output keeps the target package name. Because it is re-signed, an installed
copy of the original target normally must be uninstalled first unless the same
signing key is supplied.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

DEFAULT_CACHE = Path(os.environ.get("SMM_INJECT_CACHE", Path.home() / ".cache" / "smm-apk-injector"))
APKTOOL_VERSION = "3.0.3"
APKTOOL_SHA256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"
SMALI_VERSION = "3.0.9"

MOD_ANKH = "Lcom/spd/mod/items/ModAnkh;"
MOD_DEBUG_PREFIX = "Lcom/spd/mod/mechanics/ModDebug"
MOD_DEBUG = "Lcom/spd/mod/mechanics/ModDebug;"
DUNGEON = "Lcom/shatteredpixel/shatteredpixeldungeon/Dungeon;"
HERO_CLASS = "Lcom/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass;"
HERO = "Lcom/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero;"
ITEM = "Lcom/shatteredpixel/shatteredpixeldungeon/items/Item;"
JAVA_FRAMEWORK_PREFIXES = (
    "Landroid/", "Landroidx/", "Ldalvik/", "Ljava/", "Ljavax/", "Ljdk/", "Lsun/",
)

CLASS_RE = re.compile(r"(?m)^\.class\b[^\n]*?\s+(L[^;\s]+;)")
SUPER_RE = re.compile(r"(?m)^\.super\s+(L[^;\s]+;)")
IMPLEMENTS_RE = re.compile(r"(?m)^\.implements\s+(L[^;\s]+;)")
METHOD_DEF_RE = re.compile(r"(?m)^\.method\s+([^\n]*?)\s+([^\s(]+)(\([^)]*\)\S+)")
FIELD_DEF_RE = re.compile(r"(?m)^\.field\s+([^\n]*?)\s+([^\s:=]+):(\S+)")
METHOD_INSN_RE = re.compile(
    r"(?m)^\s*(invoke-(?:virtual|super|direct|static|interface)(?:/range)?)"
    r"\s+\{[^}]*\},\s*(L[^;\s]+;)->([^\s(]+)(\([^)]*\)\S+)"
)
FIELD_INSN_RE = re.compile(
    r"(?m)^\s*((?:i|s)(?:get|put)(?:-[a-z0-9_]+)*)\b[^\n]*,\s*"
    r"(L[^;\s]+;)->([^\s:]+):(\S+)"
)
TYPE_INSN_RE = re.compile(
    r"(?m)^\s*(?:new-instance|check-cast|instance-of|const-class|new-array|"
    r"filled-new-array(?:/range)?)\b[^\n]*?\s\[*(L[^;\s]+;)"
)
DEX_RE = re.compile(r"^classes(?:(\d+))?\.dex$")
SMALI_DIR_RE = re.compile(r"^smali(?:_classes(\d+))?$")


class InjectError(RuntimeError):
    pass


def log(message: str = "") -> None:
    print(message, flush=True)


def step(message: str) -> None:
    log(f"\n==> {message}")


def command_string(cmd: Sequence[object]) -> str:
    return subprocess.list2cmdline([str(x) for x in cmd])


def run(cmd: Sequence[object], *, cwd: Path | None = None, capture: bool = False) -> str:
    cmd = [str(x) for x in cmd]
    log("$ " + command_string(cmd))
    result = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=False,
    )
    if result.returncode:
        out = result.stdout or ""
        raise InjectError(
            f"Command failed ({result.returncode}): {command_string(cmd)}"
            + (f"\n{out}" if out else "")
        )
    return result.stdout or ""


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, destination: Path, expected_sha256: str | None = None) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = destination.with_suffix(destination.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "SMM-ModAnkh-Injector/1"})
    log(f"Downloading {url}")
    try:
        with urllib.request.urlopen(req, timeout=90) as response, part.open("wb") as out:
            shutil.copyfileobj(response, out)
    except (OSError, urllib.error.URLError) as exc:
        with contextlib.suppress(OSError):
            part.unlink()
        raise InjectError(f"Download failed: {url}\n{exc}") from exc
    if expected_sha256:
        actual = sha256(part)
        if actual.lower() != expected_sha256.lower():
            part.unlink(missing_ok=True)
            raise InjectError(f"SHA-256 mismatch for {url}: {actual}")
    part.replace(destination)
    return destination


def read_json(url: str) -> object:
    req = urllib.request.Request(url, headers={"User-Agent": "SMM-ModAnkh-Injector/1"})
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise InjectError(f"Unable to read JSON: {url}\n{exc}") from exc


def executable(path: Path) -> Path | None:
    names = [path]
    if os.name == "nt":
        names += [path.with_suffix(".exe"), path.with_suffix(".bat")]
    return next((p for p in names if p.is_file()), None)


def first_descendant(root: Path, names: Iterable[str]) -> Path | None:
    wanted = set(names)
    if not root.exists():
        return None
    return next((p for p in root.rglob("*") if p.is_file() and p.name in wanted), None)


def host_os() -> str:
    name = platform.system().lower()
    if name == "windows":
        return "windows"
    if name == "linux":
        return "linux"
    if name == "darwin":
        return "macosx"
    raise InjectError(f"Unsupported OS: {platform.system()}")


def host_arch_adoptium() -> str:
    name = platform.machine().lower()
    if name in {"x86_64", "amd64"}:
        return "x64"
    if name in {"aarch64", "arm64"}:
        return "aarch64"
    if name in {"x86", "i386", "i686"}:
        return "x86"
    raise InjectError(f"Unsupported CPU: {platform.machine()}")


def extract_archive(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(destination)
        return
    try:
        with tarfile.open(archive) as tf:
            root = destination.resolve()
            for member in tf.getmembers():
                target = (destination / member.name).resolve()
                if root != target and root not in target.parents:
                    raise InjectError(f"Unsafe archive path: {member.name}")
            tf.extractall(destination)
    except tarfile.TarError as exc:
        raise InjectError(f"Unsupported archive: {archive}") from exc


@dataclass
class JavaTools:
    java: Path
    keytool: Path


@dataclass
class AndroidTools:
    zipalign: Path
    apksigner: Path


class Toolchain:
    def __init__(self, cache: Path, offline: bool = False) -> None:
        self.cache = cache
        self.offline = offline
        cache.mkdir(parents=True, exist_ok=True)

    def ensure_java(self) -> JavaTools:
        java = shutil.which("java")
        keytool = shutil.which("keytool")
        if java and keytool:
            return JavaTools(Path(java), Path(keytool))
        cached = self.cache / "jdk"
        cj = first_descendant(cached, {"java.exe" if os.name == "nt" else "java"})
        ck = first_descendant(cached, {"keytool.exe" if os.name == "nt" else "keytool"})
        if cj and ck:
            return JavaTools(cj, ck)
        if self.offline:
            raise InjectError("Java/keytool not found and --offline is enabled")
        step("Downloading portable Temurin JDK 21")
        os_name = {"windows": "windows", "linux": "linux", "macosx": "mac"}[host_os()]
        url = (
            "https://api.adoptium.net/v3/assets/latest/21/hotspot"
            f"?architecture={urllib.parse.quote(host_arch_adoptium())}&image_type=jdk"
            f"&os={urllib.parse.quote(os_name)}&vendor=eclipse"
        )
        data = read_json(url)
        if not isinstance(data, list) or not data:
            raise InjectError("Adoptium returned no JDK")
        package = data[0].get("binary", {}).get("package", {})
        link = package.get("link")
        name = package.get("name", "temurin-jdk")
        if not link:
            raise InjectError("Adoptium response has no JDK URL")
        archive = self.cache / name
        download(str(link), archive)
        shutil.rmtree(cached, ignore_errors=True)
        extract_archive(archive, cached)
        cj = first_descendant(cached, {"java.exe" if os.name == "nt" else "java"})
        ck = first_descendant(cached, {"keytool.exe" if os.name == "nt" else "keytool"})
        if not cj or not ck:
            raise InjectError("Downloaded JDK lacks java/keytool")
        if os.name != "nt":
            for p in (cj, ck):
                p.chmod(p.stat().st_mode | stat.S_IXUSR)
        return JavaTools(cj, ck)

    def ensure_apktool(self) -> tuple[Path, JavaTools]:
        java = self.ensure_java()
        env = os.environ.get("APKTOOL_JAR")
        if env and Path(env).is_file():
            return Path(env).resolve(), java
        jar = self.cache / "apktool.jar"
        if jar.is_file() and sha256(jar).lower() == APKTOOL_SHA256:
            return jar, java
        if self.offline:
            raise InjectError("apktool not found and --offline is enabled")
        download(
            f"https://github.com/iBotPeaches/Apktool/releases/download/v{APKTOOL_VERSION}/apktool_{APKTOOL_VERSION}.jar",
            jar,
            APKTOOL_SHA256,
        )
        return jar, java

    def ensure_smali(self) -> Path:
        env = os.environ.get("SMALI_JAR")
        if env and Path(env).is_file():
            return Path(env).resolve()
        jar = self.cache / f"smali-{SMALI_VERSION}-fat-release.jar"
        if jar.is_file() and jar.stat().st_size > 1_000_000:
            return jar
        if self.offline:
            raise InjectError("smali not found and --offline is enabled")
        download(
            f"https://github.com/baksmali/smali/releases/download/{SMALI_VERSION}/smali-{SMALI_VERSION}-fat-release.jar",
            jar,
        )
        return jar

    def _local_android_tools(self) -> AndroidTools | None:
        roots: list[Path] = []
        for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            if os.environ.get(var):
                roots.append(Path(os.environ[var]))
        roots.append(Path.home() / "Android" / "Sdk")
        if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
            roots.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
        elif platform.system() == "Darwin":
            roots.append(Path.home() / "Library" / "Android" / "sdk")
        for root in roots:
            bt = root / "build-tools"
            if not bt.is_dir():
                continue
            versions = sorted(
                [p for p in bt.iterdir() if p.is_dir()],
                key=lambda p: tuple(int(x) for x in re.findall(r"\d+", p.name)) or (0,),
                reverse=True,
            )
            for version in versions:
                za = executable(version / "zipalign")
                signer = executable(version / "apksigner")
                if za and signer:
                    return AndroidTools(za, signer)
        return None

    def ensure_android_tools(self) -> AndroidTools:
        local = self._local_android_tools()
        if local:
            return local
        root = self.cache / "android-build-tools"
        za = first_descendant(root, {"zipalign.exe" if os.name == "nt" else "zipalign"})
        signer = first_descendant(root, {"apksigner.bat" if os.name == "nt" else "apksigner"})
        if za and signer:
            return AndroidTools(za, signer)
        if self.offline:
            raise InjectError("Android build-tools not found and --offline is enabled")
        step("Downloading Android build-tools")
        req = urllib.request.Request(
            "https://dl.google.com/android/repository/repository2-1.xml",
            headers={"User-Agent": "SMM-ModAnkh-Injector/1"},
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                xml = ET.fromstring(response.read())
        except (OSError, urllib.error.URLError, ET.ParseError) as exc:
            raise InjectError(f"Unable to read Android SDK repository: {exc}") from exc

        def lname(tag: str) -> str:
            return tag.rsplit("}", 1)[-1]

        candidates: list[tuple[tuple[int, int, int], str]] = []
        wanted = host_os()
        for pkg in xml.iter():
            if lname(pkg.tag) != "remotePackage":
                continue
            if not pkg.attrib.get("path", "").startswith("build-tools;"):
                continue
            rev = next((c for c in pkg if lname(c.tag) == "revision"), None)
            archives = next((c for c in pkg if lname(c.tag) == "archives"), None)
            if rev is None or archives is None:
                continue
            nums = {lname(c.tag): int(c.text or "0") for c in rev}
            if nums.get("preview", 0):
                continue
            version = (nums.get("major", 0), nums.get("minor", 0), nums.get("micro", 0))
            for archive in archives:
                host = next((c for c in archive if lname(c.tag) == "host-os"), None)
                if host is not None and (host.text or "").strip() != wanted:
                    continue
                complete = next((c for c in archive if lname(c.tag) == "complete"), None)
                if complete is None:
                    continue
                u = next((c for c in complete if lname(c.tag) == "url"), None)
                if u is not None and u.text:
                    candidates.append((version, u.text.strip()))
        if not candidates:
            raise InjectError(f"No Android build-tools archive for {wanted}")
        version, rel = max(candidates, key=lambda x: x[0])
        archive = self.cache / f"build-tools-{'.'.join(map(str, version))}-{wanted}.zip"
        download("https://dl.google.com/android/repository/" + rel, archive)
        shutil.rmtree(root, ignore_errors=True)
        extract_archive(archive, root)
        za = first_descendant(root, {"zipalign.exe" if os.name == "nt" else "zipalign"})
        signer = first_descendant(root, {"apksigner.bat" if os.name == "nt" else "apksigner"})
        if not za or not signer:
            raise InjectError("Downloaded build-tools lacks zipalign/apksigner")
        if os.name != "nt":
            for p in (za, signer):
                p.chmod(p.stat().st_mode | stat.S_IXUSR)
        return AndroidTools(za, signer)


@dataclass
class SmaliClass:
    descriptor: str
    path: Path
    text: str
    superclass: str | None
    interfaces: tuple[str, ...]
    methods: dict[tuple[str, str], frozenset[str]] = field(default_factory=dict)
    fields: dict[tuple[str, str], frozenset[str]] = field(default_factory=dict)

    @classmethod
    def parse(cls, path: Path) -> "SmaliClass":
        text = path.read_text(encoding="utf-8", errors="replace")
        m = CLASS_RE.search(text)
        if not m:
            raise InjectError(f"Cannot parse smali class: {path}")
        sm = SUPER_RE.search(text)
        methods = {
            (name, proto): frozenset(flags.split())
            for flags, name, proto in METHOD_DEF_RE.findall(text)
        }
        fields = {
            (name, typ): frozenset(flags.split())
            for flags, name, typ in FIELD_DEF_RE.findall(text)
        }
        return cls(
            m.group(1),
            path,
            text,
            sm.group(1) if sm else None,
            tuple(IMPLEMENTS_RE.findall(text)),
            methods,
            fields,
        )


def smali_dirs(root: Path) -> list[Path]:
    result = [
        p for p in root.iterdir()
        if p.is_dir() and SMALI_DIR_RE.match(p.name)
    ]

    def key(p: Path) -> int:
        m = SMALI_DIR_RE.match(p.name)
        return int(m.group(1) or "1") if m else 99999

    return sorted(result, key=key)


def smali_dir_dex_name(directory: Path) -> str:
    m = SMALI_DIR_RE.match(directory.name)
    if not m:
        raise InjectError(f"Not a smali dex directory: {directory}")
    n = int(m.group(1) or "1")
    return "classes.dex" if n == 1 else f"classes{n}.dex"


def index_smali(root: Path) -> dict[str, SmaliClass]:
    out: dict[str, SmaliClass] = {}
    for directory in smali_dirs(root):
        for path in directory.rglob("*.smali"):
            item = SmaliClass.parse(path)
            out.setdefault(item.descriptor, item)
    return out


def find_class(root: Path, descriptor: str) -> tuple[Path, Path]:
    rel = Path(descriptor[1:-1] + ".smali")
    hits: list[tuple[Path, Path]] = []
    for directory in smali_dirs(root):
        p = directory / rel
        if p.is_file():
            hits.append((directory, p))
    if len(hits) != 1:
        raise InjectError(f"Expected exactly one {descriptor}, found {len(hits)}")
    return hits[0]


def min_sdk(decoded: Path) -> int:
    yml = decoded / "apktool.yml"
    if not yml.is_file():
        return 21
    text = yml.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"(?m)^\s*minSdkVersion:\s*['\"]?(\d+)", text)
    return int(m.group(1)) if m else 21


def resolve_member(
    index: dict[str, SmaliClass],
    owner: str,
    key: tuple[str, str],
    *,
    method: bool,
    seen: set[str] | None = None,
):
    if seen is None:
        seen = set()
    if owner in seen:
        return None
    seen.add(owner)
    item = index.get(owner)
    if item is None:
        return None
    table = item.methods if method else item.fields
    if key in table:
        return owner, table[key]
    if method and key[0] == "<init>":
        return None
    if item.superclass:
        r = resolve_member(index, item.superclass, key, method=method, seen=seen)
        if r:
            return r
    for interface in item.interfaces:
        r = resolve_member(index, interface, key, method=method, seen=seen)
        if r:
            return r
    return None


def adapt_modankh(
    text: str,
    target_index: dict[str, SmaliClass],
) -> tuple[str, list[str]]:
    notes: list[str] = []
    key = ("setCurrent", f"({HERO})V")
    if resolve_member(target_index, ITEM, key, method=True):
        return text, notes

    item = target_index.get(ITEM)
    if item is None:
        raise InjectError("Target has no Item class")
    cur_user = ("curUser", HERO)
    cur_item = ("curItem", ITEM)
    if cur_user not in item.fields or cur_item not in item.fields:
        raise InjectError(
            "Target lacks Item.setCurrent(Hero) and compatible curUser/curItem fields"
        )

    pattern = re.compile(
        r"(?m)^(?P<indent>\s*)invoke-virtual(?:/range)?\s+"
        r"\{(?P<args>[^}]*)\},\s*"
        + re.escape(ITEM)
        + r"->setCurrent\("
        + re.escape(HERO)
        + r"\)V\s*$"
    )

    def repl(m: re.Match[str]) -> str:
        args = [x.strip() for x in m.group("args").split(",")]
        if len(args) != 2:
            raise InjectError("Unexpected ModAnkh setCurrent register form")
        this_reg, hero_reg = args
        ind = m.group("indent")
        return (
            f"{ind}sput-object {hero_reg}, {ITEM}->curUser:{HERO}\n"
            f"{ind}sput-object {this_reg}, {ITEM}->curItem:{ITEM}"
        )

    text2, count = pattern.subn(repl, text)
    if count != 2:
        raise InjectError(f"Expected two ModAnkh setCurrent calls, found {count}")
    notes.append("adapted missing Item.setCurrent(Hero) via curUser/curItem")
    return text2, notes


def modankh_compatibility_errors(
    text: str,
    target_index: dict[str, SmaliClass],
) -> list[str]:
    errors: set[str] = set()

    sm = SUPER_RE.search(text)
    if not sm or sm.group(1) not in target_index:
        errors.add(f"missing superclass: {sm.group(1) if sm else '<none>'}")

    for dep in TYPE_INSN_RE.findall(text):
        if dep == MOD_ANKH or dep.startswith(JAVA_FRAMEWORK_PREFIXES):
            continue
        if dep not in target_index:
            errors.add(f"missing executable type: {dep}")

    for _, owner, name, proto in METHOD_INSN_RE.findall(text):
        if owner == MOD_ANKH or owner.startswith(JAVA_FRAMEWORK_PREFIXES):
            continue
        if owner not in target_index:
            errors.add(f"missing method owner: {owner}->{name}{proto}")
            continue
        if not resolve_member(target_index, owner, (name, proto), method=True):
            errors.add(f"missing method: {owner}->{name}{proto}")

    for _, owner, name, typ in FIELD_INSN_RE.findall(text):
        if owner == MOD_ANKH or owner.startswith(JAVA_FRAMEWORK_PREFIXES):
            continue
        if owner not in target_index:
            errors.add(f"missing field owner: {owner}->{name}:{typ}")
            continue
        if not resolve_member(target_index, owner, (name, typ), method=False):
            errors.add(f"missing field: {owner}->{name}:{typ}")

    return sorted(errors)



def debug_payload_compatibility_errors(
    payload: dict[str, SmaliClass],
    target_index: dict[str, SmaliClass],
) -> list[str]:
    """Reject donor-only synthetic/helper references in the debug payload."""

    errors: set[str] = set()
    combined = dict(target_index)
    combined.update(payload)
    safe_target_prefixes = (
        "Lcom/shatteredpixel/",
        "Lcom/watabou/",
    ) + JAVA_FRAMEWORK_PREFIXES

    for descriptor, item in payload.items():
        deps: list[str] = []
        if item.superclass:
            deps.append(item.superclass)
        deps.extend(item.interfaces)
        deps.extend(TYPE_INSN_RE.findall(item.text))

        for dep in deps:
            if dep in payload or dep.startswith(JAVA_FRAMEWORK_PREFIXES):
                continue
            if not dep.startswith(safe_target_prefixes):
                errors.add(
                    f"{descriptor}: unsafe donor-only/R8 type {dep}"
                )
            elif dep not in target_index:
                errors.add(
                    f"{descriptor}: missing target type {dep}"
                )

        for _, owner, name, proto in METHOD_INSN_RE.findall(item.text):
            if owner in payload or owner.startswith(JAVA_FRAMEWORK_PREFIXES):
                continue
            if not owner.startswith(safe_target_prefixes):
                errors.add(
                    f"{descriptor}: unsafe donor-only/R8 method "
                    f"{owner}->{name}{proto}"
                )
                continue
            if owner not in target_index:
                errors.add(
                    f"{descriptor}: missing method owner "
                    f"{owner}->{name}{proto}"
                )
                continue
            if not resolve_member(
                combined,
                owner,
                (name, proto),
                method=True,
            ):
                errors.add(
                    f"{descriptor}: missing target method "
                    f"{owner}->{name}{proto}"
                )

        for _, owner, name, typ in FIELD_INSN_RE.findall(item.text):
            if owner in payload or owner.startswith(JAVA_FRAMEWORK_PREFIXES):
                continue
            if not owner.startswith(safe_target_prefixes):
                errors.add(
                    f"{descriptor}: unsafe donor-only/R8 field "
                    f"{owner}->{name}:{typ}"
                )
                continue
            if owner not in target_index:
                errors.add(
                    f"{descriptor}: missing field owner "
                    f"{owner}->{name}:{typ}"
                )
                continue
            if not resolve_member(
                combined,
                owner,
                (name, typ),
                method=False,
            ):
                errors.add(
                    f"{descriptor}: missing target field "
                    f"{owner}->{name}:{typ}"
                )

    return sorted(errors)

def method_block(
    text: str,
    method_name: str,
    proto: str,
    require_static: bool = False,
) -> tuple[int, int, str]:
    pat = re.compile(
        r"(?m)^\.method\s+([^\n]*?)\s+"
        + re.escape(method_name)
        + re.escape(proto)
        + r"\s*$"
    )
    hits: list[tuple[int, int, str]] = []
    for m in pat.finditer(text):
        flags = set(m.group(1).split())
        if require_static and "static" not in flags:
            continue
        end = text.find("\n.end method", m.end())
        if end < 0:
            raise InjectError(f"Unterminated method {method_name}{proto}")
        end += len("\n.end method")
        hits.append((m.start(), end, text[m.start():end]))
    if len(hits) != 1:
        raise InjectError(
            f"Expected one {method_name}{proto}, found {len(hits)}"
        )
    return hits[0]


def allocate_local(block: str) -> tuple[str, str]:
    m = re.search(r"(?m)^(\s*)\.locals\s+(\d+)\s*$", block)
    if m:
        old = int(m.group(2))
        reg = f"v{old}"
        block = (
            block[:m.start()]
            + f"{m.group(1)}.locals {old + 1}"
            + block[m.end():]
        )
        return block, reg

    m = re.search(r"(?m)^(\s*)\.registers\s+(\d+)\s*$", block)
    if m:
        old = int(m.group(2))
        reg = f"v{old}"
        block = (
            block[:m.start()]
            + f"{m.group(1)}.registers {old + 1}"
            + block[m.end():]
        )
        return block, reg

    raise InjectError("Dungeon.init() has neither .locals nor .registers")


def patch_dungeon(text: str) -> str:
    start, end, block = method_block(text, "init", "()V", require_static=True)
    if MOD_ANKH in block:
        raise InjectError("Dungeon.init() already contains ModAnkh injection")

    block, reg = allocate_local(block)
    anchor_re = re.compile(
        r"(?m)^(?P<line>\s*invoke-virtual(?:/range)?\s+\{[^}]*\},\s*"
        + re.escape(HERO_CLASS)
        + r"->initHero\("
        + re.escape(HERO)
        + r"\)V\s*)$"
    )
    matches = list(anchor_re.finditer(block))
    if len(matches) != 1:
        raise InjectError(
            "Dungeon.init() does not contain exactly one "
            "HeroClass.initHero(Hero) anchor; refusing heuristic patch"
        )

    m = matches[0]
    indent = re.match(r"\s*", m.group("line")).group(0)
    injected = (
        "\n"
        f"{indent}# SMM ModAnkh injection\n"
        f"{indent}new-instance {reg}, {MOD_ANKH}\n"
        f"{indent}invoke-direct {{{reg}}}, {MOD_ANKH}-><init>()V\n"
        f"{indent}invoke-virtual {{{reg}}}, {MOD_ANKH}->collect()Z"
    )
    block = block[:m.end()] + injected + block[m.end():]
    return text[:start] + block + text[end:]


def compile_smali(
    java: Path,
    smali_jar: Path,
    directory: Path,
    output: Path,
    api: int,
) -> None:
    run([
        java,
        "-jar",
        smali_jar,
        "assemble",
        "--api",
        str(api),
        "--output",
        output,
        directory,
    ])
    if not output.is_file() or not output.read_bytes().startswith(b"dex\n"):
        raise InjectError(f"smali did not produce a valid dex: {output}")


def dex_number(name: str) -> int:
    m = DEX_RE.match(name)
    if not m:
        raise ValueError(name)
    return int(m.group(1) or "1")


def shifted_dex_name(number: int) -> str:
    return "classes.dex" if number == 1 else f"classes{number}.dex"


def clone_zipinfo(
    info: zipfile.ZipInfo,
    name: str | None = None,
) -> zipfile.ZipInfo:
    z = zipfile.ZipInfo(name or info.filename, date_time=info.date_time)
    z.compress_type = info.compress_type
    z.comment = info.comment
    z.extra = info.extra
    z.internal_attr = info.internal_attr
    z.external_attr = info.external_attr
    z.create_system = info.create_system
    z.flag_bits = info.flag_bits
    return z


def is_signature(name: str) -> bool:
    u = name.upper()
    if not u.startswith("META-INF/"):
        return False
    leaf = u.rsplit("/", 1)[-1]
    return leaf == "MANIFEST.MF" or leaf.endswith((".SF", ".RSA", ".DSA", ".EC"))


def rebuild_apk(
    target: Path,
    overlay_dex: Path,
    output: Path,
) -> list[tuple[str, str]]:
    """Prepend a tiny overlay dex and preserve every target dex byte-for-byte.

    Reassembling a large R8 target dex can move a method/field/string index from
    65535 to 65536 and make a non-jumbo instruction unencodable.  Therefore the
    injector never rewrites target dex content.  The overlay is classes.dex and
    original target dex files are renamed to classes2.dex, classes3.dex, ...
    in their original order.
    """
    overlay = overlay_dex.read_bytes()
    if not overlay.startswith(b"dex\n"):
        raise InjectError("Overlay is not a valid dex file")

    with zipfile.ZipFile(target, "r") as zin:
        dex_infos = sorted(
            [info for info in zin.infolist() if DEX_RE.match(info.filename)],
            key=lambda info: dex_number(info.filename),
        )
        if not dex_infos or dex_infos[0].filename != "classes.dex":
            raise InjectError("Target APK has no classes.dex")

        mapping = [
            (info.filename, shifted_dex_name(index + 2))
            for index, info in enumerate(dex_infos)
        ]
        mapped = dict(mapping)
        first_dex = dex_infos[0]

        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            inserted_overlay = False
            for info in zin.infolist():
                if is_signature(info.filename):
                    continue

                if DEX_RE.match(info.filename):
                    if not inserted_overlay:
                        zout.writestr(
                            clone_zipinfo(first_dex, "classes.dex"),
                            overlay,
                        )
                        inserted_overlay = True

                    zout.writestr(
                        clone_zipinfo(info, mapped[info.filename]),
                        zin.read(info.filename),
                    )
                    continue

                zout.writestr(
                    clone_zipinfo(info),
                    zin.read(info.filename),
                )

            if not inserted_overlay:
                raise InjectError("Failed to insert overlay dex")

    return mapping


def ensure_debug_keystore(java: JavaTools, cache: Path) -> Path:
    ks = cache / "modankh-debug.keystore"
    if ks.is_file():
        return ks
    step("Creating debug signing key")
    run([
        java.keytool,
        "-genkeypair",
        "-v",
        "-keystore",
        ks,
        "-storepass",
        "android",
        "-alias",
        "androiddebugkey",
        "-keypass",
        "android",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-dname",
        "CN=SMM ModAnkh Injector,O=Android,C=US",
    ])
    return ks


def sign_apk(
    tools: AndroidTools,
    unsigned: Path,
    output: Path,
    keystore: Path,
    storepass: str,
    alias: str,
    keypass: str,
) -> None:
    aligned = output.with_suffix(".aligned.apk")
    run([tools.zipalign, "-p", "-f", "4", unsigned, aligned])
    run([
        tools.apksigner,
        "sign",
        "--ks",
        keystore,
        "--ks-pass",
        f"pass:{storepass}",
        "--ks-key-alias",
        alias,
        "--key-pass",
        f"pass:{keypass}",
        "--v4-signing-enabled",
        "false",
        "--out",
        output,
        aligned,
    ])
    aligned.unlink(missing_ok=True)
    run([tools.apksigner, "verify", "--verbose", output])


def output_path(target: Path) -> Path:
    return target.with_name(
        target.stem + "-ModAnkh" + (target.suffix or ".apk")
    )


def main(argv: Sequence[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Inject only SMM ModAnkh into an SPD-derived APK"
    )
    p.add_argument("source_apk", help="SMM donor APK containing ModAnkh")
    p.add_argument("target_apk", help="SPD-derived target APK")
    p.add_argument("--out", help="output APK (default: <target>-ModAnkh.apk)")
    p.add_argument("--cache", default=str(DEFAULT_CACHE))
    p.add_argument("--offline", action="store_true")
    p.add_argument("--keep-work", action="store_true")
    p.add_argument("--keystore")
    p.add_argument("--keystore-pass", default="android")
    p.add_argument("--key-alias", default="androiddebugkey")
    p.add_argument("--key-pass")
    args = p.parse_args(argv)

    source = Path(os.path.expanduser(args.source_apk)).resolve()
    target = Path(os.path.expanduser(args.target_apk)).resolve()
    out = (
        Path(os.path.expanduser(args.out)).resolve()
        if args.out
        else output_path(target)
    )
    cache = Path(os.path.expanduser(args.cache)).resolve()

    if not source.is_file():
        raise InjectError(f"Source APK not found: {source}")
    if not target.is_file():
        raise InjectError(f"Target APK not found: {target}")
    if source == target:
        raise InjectError("Source and target must differ")
    if out in {source, target}:
        raise InjectError("Refusing to overwrite input APK")

    tc = Toolchain(cache, args.offline)
    apktool, java = tc.ensure_apktool()
    smali = tc.ensure_smali()

    os.environ["JAVA_HOME"] = str(java.java.parent.parent)
    os.environ["PATH"] = (
        str(java.java.parent) + os.pathsep + os.environ.get("PATH", "")
    )
    android_tools = tc.ensure_android_tools()

    if args.keep_work:
        work = Path(tempfile.mkdtemp(prefix="modankh-inject-"))
        cleanup = False
    else:
        temp = tempfile.TemporaryDirectory(prefix="modankh-inject-")
        work = Path(temp.name)
        cleanup = True

    log(f"Working directory: {work}")

    try:
        donor = work / "donor"
        tgt = work / "target"

        step("Decoding donor and target DEX only")
        run([
            java.java, "-jar", apktool, "d", "-f", "-r",
            "-o", donor, source,
        ])
        run([
            java.java, "-jar", apktool, "d", "-f", "-r",
            "-o", tgt, target,
        ])

        api = min_sdk(tgt)
        if api < 21:
            raise InjectError(
                f"Target minSdk={api}; appending a new dex is not "
                "guaranteed on pre-21 Android"
            )

        target_index = index_smali(tgt)
        donor_index = index_smali(donor)
        _, donor_ankh = find_class(donor, MOD_ANKH)
        debug_payload = {
            desc: cls for desc, cls in donor_index.items()
            if desc.startswith(MOD_DEBUG_PREFIX)
        }
        if MOD_DEBUG not in debug_payload:
            raise InjectError("Donor APK is missing com.spd.mod.mechanics.ModDebug")
        collisions = sorted(desc for desc in debug_payload if desc in target_index)
        if collisions:
            raise InjectError(
                "Target already contains injected debug classes: "
                + ", ".join(collisions)
            )
        dungeon_dir, dungeon_path = find_class(tgt, DUNGEON)
        log(f"Dungeon source dex: {smali_dir_dex_name(dungeon_dir)}")

        step("Adapting and validating ModAnkh against target API")
        mod_text = donor_ankh.read_text(
            encoding="utf-8",
            errors="replace",
        )
        mod_text, notes = adapt_modankh(mod_text, target_index)
        for note in notes:
            log("  " + note)

        debug_errors = debug_payload_compatibility_errors(
            debug_payload,
            target_index,
        )
        if debug_errors:
            log("Debug payload compatibility errors:")
            for e in debug_errors:
                log("  - " + e)
            raise InjectError(
                "Donor debug payload is not self-contained for this target. "
                "Rebuild the SMM donor from current source before injecting."
            )
        log("Debug payload target API check: OK")

        compatibility_index = dict(target_index)
        compatibility_index.update(debug_payload)
        errors = modankh_compatibility_errors(mod_text, compatibility_index)
        if errors:
            log("ModAnkh compatibility errors:")
            for e in errors:
                log("  - " + e)
            raise InjectError(
                f"Target is incompatible with ModAnkh "
                f"({len(errors)} unresolved executable reference(s))"
            )
        log("ModAnkh target API check: OK")

        step("Building first-dex overlay: patched Dungeon + ModAnkh debug console")
        original_dungeon = dungeon_path.read_text(
            encoding="utf-8",
            errors="replace",
        )
        patched_dungeon = patch_dungeon(original_dungeon)

        overlay_root = work / "overlay-smali"

        overlay_dungeon = overlay_root / Path(DUNGEON[1:-1] + ".smali")
        overlay_dungeon.parent.mkdir(parents=True, exist_ok=True)
        overlay_dungeon.write_text(
            patched_dungeon,
            encoding="utf-8",
        )

        overlay_ankh = overlay_root / Path(MOD_ANKH[1:-1] + ".smali")
        overlay_ankh.parent.mkdir(parents=True, exist_ok=True)
        overlay_ankh.write_text(
            mod_text,
            encoding="utf-8",
        )

        for descriptor, donor_class in sorted(debug_payload.items()):
            overlay_class = overlay_root / Path(descriptor[1:-1] + ".smali")
            overlay_class.parent.mkdir(parents=True, exist_ok=True)
            overlay_class.write_text(donor_class.text, encoding="utf-8")
        log(f"Debug payload classes: {len(debug_payload)}")

        overlay_dex = work / "overlay.dex"
        compile_smali(
            java.java,
            smali,
            overlay_root,
            overlay_dex,
            api,
        )

        step("Repacking APK with untouched target DEX files")
        unsigned = work / "unsigned.apk"
        dex_mapping = rebuild_apk(
            target,
            overlay_dex,
            unsigned,
        )
        with zipfile.ZipFile(unsigned) as zf:
            bad = zf.testzip()
            if bad:
                raise InjectError(
                    f"Corrupt APK entry after rebuild: {bad}"
                )
            if not zf.read("classes.dex").startswith(b"dex\n"):
                raise InjectError("Output overlay classes.dex is invalid")
            for _, shifted in dex_mapping:
                if not zf.read(shifted).startswith(b"dex\n"):
                    raise InjectError(f"Shifted target dex is invalid: {shifted}")

        log("DEX layout:")
        log("  overlay -> classes.dex")
        for original, shifted in dex_mapping:
            log(f"  {original} -> {shifted} (byte-for-byte)")

        step("Signing")
        if args.keystore:
            ks = Path(os.path.expanduser(args.keystore)).resolve()
            if not ks.is_file():
                raise InjectError(f"Keystore not found: {ks}")
        else:
            ks = ensure_debug_keystore(java, cache)

        out.parent.mkdir(parents=True, exist_ok=True)
        sign_apk(
            android_tools,
            unsigned,
            out,
            ks,
            args.keystore_pass,
            args.key_alias,
            args.key_pass or args.keystore_pass,
        )

        step("Done")
        log(f"Output : {out}")
        log(f"SHA-256: {sha256(out)}")
        log("Package: unchanged from target (re-signed APK)")
        log("Injected: ModAnkh + debug console")
        if not args.keystore:
            log(
                "Install note: uninstall the original target first "
                "if Android reports a signature conflict."
            )
        if args.keep_work:
            log(f"Work files kept at: {work}")
        return 0

    finally:
        if cleanup:
            temp.cleanup()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)
