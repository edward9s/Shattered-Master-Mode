from __future__ import annotations

import re
from pathlib import Path


JAVA_ROOT = Path("core/src/main/java")
_REQUIRED_GAME_FILES = (
    "Dungeon.java",
    "actors/Char.java",
    "actors/hero/Hero.java",
    "actors/hero/HeroClass.java",
    "items/Item.java",
    "levels/Level.java",
    "scenes/GameScene.java",
)
_PACKAGE_RE = re.compile(r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;")


def detect_game_package(source_root: str | Path) -> str:
    root = Path(source_root)
    java_root = root / JAVA_ROOT
    if not java_root.is_dir():
        raise RuntimeError(f"Java source root not found: {java_root}")

    candidates: list[tuple[str, Path]] = []
    for wnd_path in java_root.rglob("windows/WndGame.java"):
        package_dir = wnd_path.parent.parent
        if not all((package_dir / relative).is_file() for relative in _REQUIRED_GAME_FILES):
            continue

        relative_dir = package_dir.relative_to(java_root)
        package_name = ".".join(relative_dir.parts)

        source = wnd_path.read_text(encoding="utf-8")
        match = _PACKAGE_RE.search(source)
        if match is None:
            raise RuntimeError(f"WndGame.java has no package declaration: {wnd_path}")
        if match.group(1) != package_name:
            raise RuntimeError(
                f"WndGame.java package/path mismatch: {match.group(1)} != {package_name}"
            )

        candidates.append((package_name, wnd_path))

    unique = {package for package, _ in candidates}
    if len(unique) != 1:
        detail = ", ".join(sorted(unique)) if unique else "none"
        raise RuntimeError(
            "Expected exactly one SPD-family source package, found "
            f"{len(unique)}: {detail}"
        )

    return next(iter(unique))


def game_package_dir(source_root: str | Path, package_name: str | None = None) -> Path:
    root = Path(source_root)
    package = package_name or detect_game_package(root)
    return root / JAVA_ROOT / Path(*package.split("."))


def wndgame_path(source_root: str | Path) -> Path:
    root = Path(source_root)
    package = detect_game_package(root)
    path = game_package_dir(root, package) / "windows" / "WndGame.java"
    if not path.is_file():
        raise RuntimeError(f"WndGame.java not found: {path}")
    return path
