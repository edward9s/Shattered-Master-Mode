#!/usr/bin/env python3
"""Inject SMM into an SPD-derived desktop JAR."""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Sequence

import _inject_jar_core as injector
import _inject_jar_ankh as ankh_mode
import _inject_jar_full as full_mode


_original_ensure_java = injector.ensure_java


def _ensure_java() -> Path:
    if injector.platform.system().lower() != "android":
        return _original_ensure_java()

    java = injector.shutil.which("java")
    if java:
        return Path(java).resolve()

    pkg = injector.shutil.which("pkg")
    if pkg is None:
        raise injector.InjectError(
            "Android host detected, but Termux package manager 'pkg' was not found"
        )

    injector.step("Installing minimal Termux dependencies")
    injector.run([pkg, "install", "-y", "openjdk-21"])
    java = injector.shutil.which("java")
    if not java:
        raise injector.InjectError(
            "Termux installed openjdk-21 but java is still unavailable on PATH"
        )
    return Path(java).resolve()


injector.ensure_java = _ensure_java

DEFAULT_DONOR = Path(__file__).resolve().with_name("smm-inject-donor.jar")
FULL_SMM_CLASS_PREFIX = "com/spd/mod/"
injector.MOD_ITEM_CLASS_PREFIX = FULL_SMM_CLASS_PREFIX
_original_patch_classes = injector.patch_classes

# The core JAR helper originally registered only ModAnkhStore. Both current
# full injection and Ankh-only dependency-closure validation need to resolve
# references against every com.spd.mod class supplied in the helper payload.
injector.JAVA_HELPER = injector.JAVA_HELPER.replace(
    '''    static boolean isStoreClass(String name) {
        return MOD_ANKH_STORE.equals(name) || name.startsWith(MOD_ANKH_STORE_PREFIX);
    }
''',
    '''    static boolean isStoreClass(String name) {
        return name.startsWith("com/spd/mod/");
    }
''',
).replace(
    '''        if (!classes.containsKey(MOD_ANKH_STORE)) {
            throw new IllegalStateException("Donor JAR has no ModAnkhStore class");
        }
''',
    '',
).replace(
    'System.out.println("ModAnkhStore payload classes registered: " + storeClassCount);',
    'System.out.println("SMM helper payload classes registered: " + storeClassCount);',
)


def output_path(target: Path, ankh_only: bool = False) -> Path:
    if ankh_only:
        return ankh_mode.output_path(target)
    return target.with_name(target.stem + "-SMM" + (target.suffix or ".jar"))


def print_help() -> None:
    print(
        "usage: inject_jar.py TARGET.jar [--ankh-only] [--out OUTPUT.jar] [--keep-work]\n\n"
        "Inject SMM into an SPD-derived desktop JAR using smm-inject-donor.jar beside this script.\n\n"
        "modes:\n"
        "  default       inject the full supported SMM payload\n"
        "  --ankh-only   inject ModAnkh + Store + Loot + Console only\n\n"
        "options:\n"
        "  --out PATH    output JAR (default: <target>-SMM.jar or <target>-SMM-Ankh.jar)\n"
        "  --keep-work   keep temporary work files\n"
        "  -h, --help    show this help"
    )


def main(argv: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or "-h" in args or "--help" in args:
        print_help()
        return 0 if args else 2
    if not DEFAULT_DONOR.is_file():
        raise injector.InjectError(f"SMM donor JAR not found beside injector: {DEFAULT_DONOR}")

    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("target")
    parser.add_argument("--ankh-only", action="store_true")
    parser.add_argument("--out")
    parser.add_argument("--keep-work", action="store_true")
    parsed = parser.parse_args(args)

    source = DEFAULT_DONOR.resolve()
    target = Path(os.path.expanduser(parsed.target)).resolve()
    output = (
        Path(os.path.expanduser(parsed.out)).resolve()
        if parsed.out
        else output_path(target, parsed.ankh_only)
    )

    if not target.is_file():
        raise injector.InjectError(f"Target JAR not found: {target}")
    if output in {source, target}:
        raise injector.InjectError("Refusing to overwrite an input JAR")

    injector.validate_jar(source, [injector.MOD_ANKH_ENTRY])
    injector.validate_jar(target)
    with zipfile.ZipFile(target) as target_zip:
        target_game_root = injector.detect_target_game_root(target_zip.namelist())
    dungeon_entry = target_game_root + "/Dungeon.class"
    wnd_entry = target_game_root + "/windows/WndGame.class"
    char_entry = target_game_root + "/actors/Char.class"
    injector.log("Target SPD-family package: " + target_game_root.replace("/", "."))
    injector.log(
        "Injection mode: "
        + ("ModAnkh only (Store + Loot + Console)" if parsed.ankh_only else "full SMM")
    )
    java = injector.ensure_java()

    if parsed.keep_work:
        work = Path(tempfile.mkdtemp(prefix="smm-jar-inject-"))
        cleanup = False
    else:
        temp = tempfile.TemporaryDirectory(prefix="smm-jar-inject-")
        work = Path(temp.name)
        cleanup = True
    injector.log(f"Working directory: {work}")

    try:
        if parsed.ankh_only:
            result = ankh_mode.run(
                injector=injector,
                patch_classes=_original_patch_classes,
                source=source,
                target=target,
                output=output,
                java=java,
                work=work,
                target_game_root=target_game_root,
                dungeon_entry=dungeon_entry,
            )
            if parsed.keep_work:
                injector.log(f"Work files kept at: {work}")
            return result

        donor_modankh = work / "donor-ModAnkh.class"
        with zipfile.ZipFile(source) as zf:
            donor_modankh.write_bytes(
                injector.rebase_class_bytes(
                    zf.read(injector.MOD_ANKH_ENTRY), target_game_root
                )
            )
            payload_names = sorted(
                name for name in zf.namelist()
                if name.startswith(FULL_SMM_CLASS_PREFIX)
                and name.endswith(".class")
                and name != injector.MOD_ANKH_ENTRY
            )
            if not payload_names:
                raise injector.InjectError("Donor JAR contains no SMM payload classes")
            payload = {
                name: injector.rebase_class_bytes(zf.read(name), target_game_root)
                for name in payload_names
            }

        helper_payload = work / "rebased-smm-payload.jar"
        injector.write_helper_payload_jar(helper_payload, payload)

        injector.step("Adapting and validating donor ModAnkh against target JAR")
        patched_modankh, patched_wndgame, patched_char = full_mode.patch_classes(
            injector,
            _original_patch_classes,
            java,
            target,
            helper_payload,
            donor_modankh,
            work,
            target_game_root,
        )

        injector.step("Repacking target JAR")
        output.parent.mkdir(parents=True, exist_ok=True)
        unsigned_tmp = work / "output.jar"
        full_mode.rebuild_jar(
            injector,
            target,
            patched_wndgame,
            patched_char,
            patched_modankh,
            payload,
            unsigned_tmp,
            dungeon_entry,
        )
        injector.validate_jar(
            unsigned_tmp,
            [wnd_entry, char_entry, injector.MOD_ANKH_ENTRY, *payload_names],
        )
        shutil.copy2(unsigned_tmp, output)

        injector.step("Done")
        injector.log(f"Output : {output}")
        injector.log(f"SHA-256: {injector.sha256(output)}")
        injector.log(
            f"Injected: ModAnkh + full SMM payload ({len(payload)} additional classes)"
        )
        if parsed.keep_work:
            injector.log(f"Work files kept at: {work}")
        return 0
    finally:
        if cleanup:
            temp.cleanup()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)
