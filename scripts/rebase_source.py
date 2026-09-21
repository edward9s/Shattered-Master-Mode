from __future__ import annotations

import sys
from pathlib import Path

from spd_source import detect_game_package


SOURCE_GAME_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon"
SOURCE_GAME_INTERNAL = SOURCE_GAME_PACKAGE.replace(".", "/")


def _mod_java_files(mod_root: Path):
    for path in mod_root.rglob("*.java"):
        normalized = "/" + path.relative_to(mod_root).as_posix()
        if "/com/spd/mod/" in normalized:
            yield path


def rebase_mod_sources(mod_root: str | Path, target_package: str) -> int:
    root = Path(mod_root)
    if not root.is_dir():
        raise RuntimeError(f"Mod source root not found: {root}")

    target_internal = target_package.replace(".", "/")
    changed = 0

    for path in _mod_java_files(root):
        source = path.read_text(encoding="utf-8")
        rebased = (
            source.replace(SOURCE_GAME_PACKAGE, target_package)
            .replace(SOURCE_GAME_INTERNAL, target_internal)
        )
        if rebased != source:
            path.write_text(rebased, encoding="utf-8")
            changed += 1

    if target_package != SOURCE_GAME_PACKAGE:
        residual: list[str] = []
        for path in _mod_java_files(root):
            for number, line in enumerate(
                path.read_text(encoding="utf-8").splitlines(), start=1
            ):
                if SOURCE_GAME_PACKAGE in line or SOURCE_GAME_INTERNAL in line:
                    residual.append(f"{path}:{number}: {line.strip()}")
                    if len(residual) >= 20:
                        break
            if len(residual) >= 20:
                break

        if residual:
            raise RuntimeError(
                "Source package rebase left stale SPD namespace references:\n"
                + "\n".join(residual)
            )

    return changed


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: rebase_source.py <SPD_ROOT> <MOD_ROOT>")

    spd_root = Path(sys.argv[1])
    mod_root = Path(sys.argv[2])
    target_package = detect_game_package(spd_root)
    changed = rebase_mod_sources(mod_root, target_package)
    print(f"Target SPD-family package: {target_package}")
    print(f"Rebased {changed} SMM Java source file(s)")


if __name__ == "__main__":
    main()
