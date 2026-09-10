#!/usr/bin/env python3
"""Package the compiled full-SMM injection donors and injector scripts."""

from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path


def copy_required(src: Path, dst: Path) -> None:
    if not src.is_file():
        raise FileNotFoundError(src)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def populate_kit(
    kit: Path,
    repo: Path,
    donor_apk: Path,
    donor_jar: Path,
) -> None:
    if kit.exists():
        if kit.is_dir():
            shutil.rmtree(kit)
        else:
            kit.unlink()
    kit.mkdir(parents=True)

    copy_required(donor_apk, kit / "smm-inject-donor.apk")
    copy_required(donor_jar, kit / "smm-inject-donor.jar")

    for relative in (
        "scripts/inject_apk.py",
        "scripts/_inject_apk_core.py",
        "scripts/_inject_apk_ankh.py",
        "scripts/inject_jar.py",
        "scripts/_inject_jar_core.py",
        "scripts/_inject_jar_full.py",
        "scripts/_inject_jar_ankh.py",
    ):
        source = repo / relative
        copy_required(source, kit / source.name)

    (kit / "README.txt").write_text(
        "Shattered Master Mode Injection Kit\n\n"
        "APK (full SMM):\n"
        "  python inject_apk.py TARGET.apk\n\n"
        "APK (ModAnkh + Store + Loot + Console only):\n"
        "  python inject_apk.py TARGET.apk --ankh-only\n\n"
        "Desktop JAR (full SMM):\n"
        "  python inject_jar.py TARGET.jar\n\n"
        "Desktop JAR (ModAnkh + Store + Loot + Console only):\n"
        "  python inject_jar.py TARGET.jar --ankh-only\n\n"
        "Optional: --out OUTPUT\n"
        "Keep the kit files together. The donors are compiled payloads required for injection.\n",
        encoding="utf-8",
    )


def make_zip(source_dir: Path, output_zip: Path) -> None:
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    output_zip.unlink(missing_ok=True)
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(source_dir.iterdir()):
            zf.write(path, path.name)

    if not zipfile.is_zipfile(output_zip):
        raise RuntimeError(f"Failed to create injection kit: {output_zip}")
    with zipfile.ZipFile(output_zip) as zf:
        bad = zf.testzip()
        if bad:
            raise RuntimeError(f"Corrupt injection kit entry: {bad}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Package the SMM full injection kit")
    parser.add_argument("donor_apk")
    parser.add_argument("donor_jar")
    parser.add_argument("output")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument(
        "--zip",
        action="store_true",
        help="write output as a ZIP instead of a directory",
    )
    args = parser.parse_args()

    repo = Path(args.repo_root).resolve()
    donor_apk = Path(args.donor_apk).resolve()
    donor_jar = Path(args.donor_jar).resolve()
    output = Path(args.output).resolve()

    if not donor_apk.is_file():
        raise FileNotFoundError(f"APK donor not found: {donor_apk}")
    if not donor_jar.is_file():
        raise FileNotFoundError(f"JAR donor not found: {donor_jar}")

    if args.zip:
        staging = output.parent / (output.stem + "-contents")
        populate_kit(staging, repo, donor_apk, donor_jar)
        make_zip(staging, output)
        shutil.rmtree(staging)
    else:
        populate_kit(output, repo, donor_apk, donor_jar)

    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
