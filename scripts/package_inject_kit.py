#!/usr/bin/env python3
"""Package the compiled full-SMM injection donors and injector scripts."""

from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path


PARRY_CLASS = "com/spd/mod/mechanics/ModParryRiposte.class"
OBSOLETE_PARRY_CLASSES = {
    "com/spd/mod/mechanics/ModParryRiposte$IncomingAttackContext.class",
    "com/spd/mod/mechanics/ModParryRiposte$ParryDetachSink.class",
}


def copy_required(src: Path, dst: Path) -> None:
    if not src.is_file():
        raise FileNotFoundError(src)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def validate_parry_donors(donor_apk: Path, donor_jar: Path) -> None:
    """Fail packaging if combat donor and injector source are from different generations."""

    with zipfile.ZipFile(donor_jar) as jar:
        names = set(jar.namelist())
        if PARRY_CLASS not in names:
            raise RuntimeError("JAR donor is missing ModParryRiposte.class")
        obsolete = sorted(OBSOLETE_PARRY_CLASSES.intersection(names))
        if obsolete:
            raise RuntimeError(
                "JAR donor still contains obsolete Parry lifecycle classes: "
                + ", ".join(obsolete)
            )
        parry_class = jar.read(PARRY_CLASS)
        for required in (b"onHitCheck", b"resolveDirectDamage", b"defenseVerb"):
            if required not in parry_class:
                raise RuntimeError(
                    "JAR donor ModParryRiposte is stale; missing "
                    + required.decode("ascii")
                )
        for obsolete_name in (
            b"onIncomingAttack",
            b"onIncomingAttackComplete",
            b"shouldParry",
        ):
            if obsolete_name in parry_class:
                raise RuntimeError(
                    "JAR donor ModParryRiposte still exposes obsolete hook "
                    + obsolete_name.decode("ascii")
                )

    with zipfile.ZipFile(donor_apk) as apk:
        dex_names = sorted(
            name
            for name in apk.namelist()
            if name.startswith("classes") and name.endswith(".dex")
        )
        if not dex_names:
            raise RuntimeError("APK donor contains no classes*.dex")
        dex = b"".join(apk.read(name) for name in dex_names)
        for required in (b"onHitCheck", b"resolveDirectDamage"):
            if required not in dex:
                raise RuntimeError(
                    "APK donor is stale; ModParryRiposte."
                    + required.decode("ascii")
                    + " is absent from DEX"
                )
        for obsolete_name in (
            b"ModParryRiposte$IncomingAttackContext",
            b"ModParryRiposte$ParryDetachSink",
            b"onIncomingAttack",
            b"onIncomingAttackComplete",
            b"shouldParry",
        ):
            if obsolete_name in dex:
                raise RuntimeError(
                    "APK donor still contains obsolete Parry ABI marker "
                    + obsolete_name.decode("ascii")
                )


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
        "scripts/_attack_hook_common.py",
        "scripts/_inject_apk_core.py",
        "scripts/_inject_apk_ankh.py",
        "scripts/_inject_action_name.py",
        "scripts/_inject_buff_click.py",
        "scripts/inject_jar.py",
        "scripts/_inject_jar_core.py",
    ):
        source = repo / relative
        copy_required(source, kit / source.name)

    (kit / "README.txt").write_text(
        "Shattered Master Mode Injection Kit\n\n"
        "Full SMM:\n"
        "  python inject_apk.py TARGET.apk\n"
        "  python inject_jar.py TARGET.jar\n\n"
        "Minimal (ModAnkh tools + combat helpers):\n"
        "  python inject_apk.py TARGET.apk --ankh-only\n"
        "  python inject_jar.py TARGET.jar --ankh-only\n\n"
        "Includes Last Stand, Instant Kill, Store, Loot, and Debug Console support.\n"
        "Optional: --out OUTPUT\n"
        "Keep all kit files together.\n\n"
        "APK signing key:\n"
        "  inject_apk.py creates smm-inject.keystore in this directory on first use\n"
        "  and reuses it for later APK injections. Keep this file if you want future\n"
        "  injected APKs to update an already-installed APK with the same package name.\n"
        "  When extracting a newer Injection Kit, copy your existing\n"
        "  smm-inject.keystore into the new kit directory before injecting.\n"
        "  If the key changes, Android normally requires uninstalling the old APK first.\n"
        "  Keep the keystore local; do not publish or commit it.\n",
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

    validate_parry_donors(donor_apk, donor_jar)

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
