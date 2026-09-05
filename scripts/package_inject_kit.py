#!/usr/bin/env python3
"""Package the compiled full-SMM injection donors and injector scripts."""

from __future__ import annotations

import argparse
import shutil
import tempfile
import zipfile
from pathlib import Path


def copy_required(src: Path, dst: Path) -> None:
    if not src.is_file():
        raise FileNotFoundError(src)
    shutil.copy2(src, dst)


def main() -> int:
    parser = argparse.ArgumentParser(description="Package the SMM full injection kit")
    parser.add_argument("upstream_tag")
    parser.add_argument("mod_version")
    parser.add_argument("donor_apk")
    parser.add_argument("donor_jar")
    parser.add_argument("output_zip")
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args()

    repo = Path(args.repo_root).resolve()
    donor_apk = Path(args.donor_apk).resolve()
    donor_jar = Path(args.donor_jar).resolve()
    output = Path(args.output_zip).resolve()

    if not donor_apk.is_file():
        raise FileNotFoundError(f"APK donor not found: {donor_apk}")
    if not donor_jar.is_file():
        raise FileNotFoundError(f"JAR donor not found: {donor_jar}")

    stem = f"SMM-{args.upstream_tag}-m{args.mod_version}"
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="smm-inject-kit-") as td:
        kit = Path(td)
        copy_required(donor_apk, kit / f"{stem}-inject-donor.apk")
        copy_required(donor_jar, kit / f"{stem}-inject-donor.jar")

        for relative in (
            "scripts/inject_smm_apk.py",
            "scripts/inject_apk.py",
            "scripts/inject_smm_jar.py",
            "scripts/inject_jar.py",
            "docs/modankh_payload_rules.md",
        ):
            source = repo / relative
            copy_required(source, kit / source.name)

        (kit / "README.txt").write_text(
            "Shattered Master Mode full injection kit\n\n"
            "APK:\n"
            f"  python inject_smm_apk.py {stem}-inject-donor.apk TARGET.apk --out TARGET-SMM.apk\n\n"
            "Desktop JAR:\n"
            f"  python inject_smm_jar.py {stem}-inject-donor.jar TARGET.jar --out TARGET-SMM.jar\n\n"
            "The APK donor is intentionally the non-minified GitHub Actions debug build.\n"
            "Do not replace it with the release APK: release R8 optimization may create\n"
            "donor-only obfuscated helper classes that are unsafe to transplant.\n\n"
            "The target game remains the base. Full SMM injection patches the target\n"
            "WndGame menu entry and injects com.spd.mod; it does not give the Hero a\n"
            "startup ModAnkh.\n",
            encoding="utf-8",
        )

        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zf:
            for path in sorted(kit.iterdir()):
                zf.write(path, path.name)

    if not zipfile.is_zipfile(output):
        raise RuntimeError(f"Failed to create injection kit: {output}")

    with zipfile.ZipFile(output) as zf:
        bad = zf.testzip()
        if bad:
            raise RuntimeError(f"Corrupt injection kit entry: {bad}")

    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
