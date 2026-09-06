#!/usr/bin/env python3
"""Termux-aware entry point for the APK injector.

The injector is intended to bootstrap the minimum toolchain automatically. On
Termux, Android reports itself as platform.system() == "Android", so the desktop
Temurin / Android SDK download paths are not usable. Patch the core Toolchain
before executing the original injector implementation.
"""
from __future__ import annotations

import os
import platform
import shutil
from pathlib import Path

import _inject_apk_core as injector


_original_ensure_java = injector.Toolchain.ensure_java
_original_ensure_android_tools = injector.Toolchain.ensure_android_tools


def _is_termux() -> bool:
    return platform.system().lower() == "android" and shutil.which("pkg") is not None


def _install_termux_packages(toolchain: injector.Toolchain, packages: list[str]) -> None:
    if not packages:
        return
    if toolchain.offline:
        raise injector.InjectError(
            "Missing Termux dependencies ("
            + ", ".join(packages)
            + ") and --offline is enabled"
        )
    pkg = shutil.which("pkg")
    if pkg is None:
        raise injector.InjectError("Termux package manager 'pkg' was not found")
    injector.step("Installing minimal Termux dependencies")
    injector.run([pkg, "install", "-y", *packages])


def _termux_java_tools(toolchain: injector.Toolchain) -> injector.JavaTools | None:
    java = shutil.which("java")
    keytool = shutil.which("keytool")
    if java and keytool:
        return injector.JavaTools(Path(java), Path(keytool))

    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        home_bin = Path(java_home) / "bin"
        hj = injector.executable(home_bin / "java")
        hk = injector.executable(home_bin / "keytool")
        if hj and hk:
            return injector.JavaTools(hj.resolve(), hk.resolve())

    cached = toolchain.cache / "jdk"
    cj = injector.first_descendant(cached, {"java"})
    ck = injector.first_descendant(cached, {"keytool"})
    if cj and ck:
        return injector.JavaTools(cj, ck)
    return None


def _ensure_java(self: injector.Toolchain) -> injector.JavaTools:
    if not _is_termux():
        return _original_ensure_java(self)

    tools = _termux_java_tools(self)
    if tools:
        return tools

    packages = ["openjdk-21"]
    if shutil.which("apksigner") is None:
        packages.append("apksigner")
    _install_termux_packages(self, packages)

    tools = _termux_java_tools(self)
    if tools:
        return tools
    raise injector.InjectError(
        "Termux installed openjdk-21 but java/keytool are still unavailable on PATH"
    )


def _termux_portable_zipalign_sentinel(toolchain: injector.Toolchain) -> Path:
    """Return a deliberately non-executable path that triggers the portable aligner."""
    path = toolchain.cache / "termux-portable-zipalign"
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text("built-in portable zipalign\n", encoding="utf-8")
    path.chmod(0o600)
    return path


def _ensure_android_tools(self: injector.Toolchain) -> injector.AndroidTools:
    if not _is_termux():
        return _original_ensure_android_tools(self)

    local = self._local_android_tools()
    if local:
        return local

    zipalign = shutil.which("zipalign")
    apksigner = shutil.which("apksigner")
    if apksigner is None:
        _install_termux_packages(self, ["apksigner"])
        apksigner = shutil.which("apksigner")
    if apksigner is None:
        raise injector.InjectError(
            "Termux installed apksigner but it is still unavailable on PATH"
        )

    if zipalign:
        return injector.AndroidTools(Path(zipalign), Path(apksigner))

    injector.log(
        "Termux: native zipalign is unavailable; using the built-in portable ZIP aligner"
    )
    return injector.AndroidTools(
        _termux_portable_zipalign_sentinel(self),
        Path(apksigner),
    )


injector.Toolchain.ensure_java = _ensure_java
injector.Toolchain.ensure_android_tools = _ensure_android_tools

# Execute the previous inject_apk.py in this module's globals. This preserves
# its CLI behaviour and module API while keeping the Termux bootstrap isolated.
_impl = Path(__file__).resolve().with_name("_inject_apk_impl.py")
exec(compile(_impl.read_bytes(), str(_impl), "exec"), globals(), globals())
