#!/usr/bin/env python3
"""Inject the compiled SMM payload into an SPD-derived APK."""
from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Sequence

import _inject_apk_core as injector


FULL_SMM_PREFIX = "Lcom/spd/mod/"
MOD_ANKH_STORE_PREFIX = injector.MOD_ANKH_STORE[:-1]
DEFAULT_DONOR = Path(__file__).resolve().with_name("smm-inject-donor.apk")

injector.MOD_ITEM_DESCRIPTOR_PREFIX = FULL_SMM_PREFIX
injector.TARGET_API_PREFIXES = injector.TARGET_API_PREFIXES + (
    injector.MOD_ANKH,
    MOD_ANKH_STORE_PREFIX,
)

_original_build_debug_payload = injector.build_debug_payload
_original_payload_compatibility_errors = injector.payload_compatibility_errors
_original_find_class = injector.find_class
_full_donor_payload: dict[str, injector.SmaliClass] = {}


def build_full_debug_payload(
    donor_index: dict[str, injector.SmaliClass],
    target_index: dict[str, injector.SmaliClass],
):
    global _full_donor_payload
    _full_donor_payload = {
        desc: item
        for desc, item in donor_index.items()
        if desc.startswith(FULL_SMM_PREFIX)
    }
    return _original_build_debug_payload(donor_index, target_index)


def full_payload_compatibility_errors(
    payload: dict[str, injector.SmaliClass],
    target_index: dict[str, injector.SmaliClass],
    allowed_target_prefixes: tuple[str, ...] = injector.TARGET_API_PREFIXES,
):
    game_prefix = allowed_target_prefixes[0]
    support = injector.rebase_smali_payload(_full_donor_payload, game_prefix)
    augmented = dict(target_index)
    augmented.update(support)
    allowed = allowed_target_prefixes + (FULL_SMM_PREFIX,)
    return _original_payload_compatibility_errors(payload, augmented, allowed)


def adapt_modankh(
    text: str,
    target_index: dict[str, injector.SmaliClass],
    item_descriptor: str = injector.ITEM,
    hero_descriptor: str = injector.HERO,
) -> tuple[str, list[str]]:
    notes: list[str] = []
    key = ("setCurrent", f"({hero_descriptor})V")
    if injector.resolve_member(target_index, item_descriptor, key, method=True):
        return text, notes

    pattern = re.compile(
        r"(?m)^(?P<indent>\s*)invoke-virtual(?P<range>/range)?\s+"
        r"\{(?P<args>[^}]*)\},\s*"
        r"L[^;\s]+;->setCurrent\("
        + re.escape(hero_descriptor)
        + r"\)V\s*$"
    )
    matches = list(pattern.finditer(text))
    if not matches:
        return text, notes

    item = target_index.get(item_descriptor)
    if item is None:
        raise injector.InjectError("Target has no Item class")
    cur_user = ("curUser", hero_descriptor)
    cur_item = ("curItem", item_descriptor)
    if cur_user not in item.fields or cur_item not in item.fields:
        raise injector.InjectError(
            "Target lacks Item.setCurrent(Hero) and compatible curUser/curItem fields"
        )

    def registers(m: re.Match[str]) -> tuple[str, str]:
        raw = m.group("args").strip()
        if m.group("range"):
            parts = [part.strip() for part in raw.split("..")]
            if len(parts) != 2:
                raise injector.InjectError("Unexpected ModAnkh setCurrent register range")
            start, end = parts
            sm = re.fullmatch(r"([vp])(\d+)", start)
            em = re.fullmatch(r"([vp])(\d+)", end)
            if (
                sm is None
                or em is None
                or sm.group(1) != em.group(1)
                or int(em.group(2)) != int(sm.group(2)) + 1
            ):
                raise injector.InjectError("Unexpected ModAnkh setCurrent register range")
            return start, end

        args = [part.strip() for part in raw.split(",")]
        if len(args) != 2:
            raise injector.InjectError("Unexpected ModAnkh setCurrent register form")
        return args[0], args[1]

    def repl(m: re.Match[str]) -> str:
        this_reg, hero_reg = registers(m)
        ind = m.group("indent")
        return (
            f"{ind}sput-object {hero_reg}, {item_descriptor}->curUser:{hero_descriptor}\n"
            f"{ind}sput-object {this_reg}, {item_descriptor}->curItem:{item_descriptor}"
        )

    text2, count = pattern.subn(repl, text)
    notes.append(
        f"adapted {count} Item.setCurrent(Hero) call(s) via curUser/curItem"
    )
    return text2, notes


def find_wndgame_instead_of_dungeon(root: Path, descriptor: str):
    if descriptor.endswith("/Dungeon;"):
        wnd_game = descriptor[:-len("Dungeon;")] + "windows/WndGame;"
        return _original_find_class(root, wnd_game)
    return _original_find_class(root, descriptor)


def patch_wndgame(text: str, *_unused: str) -> str:
    start, end, block = injector.method_block(text, "<init>", "()V")
    if "Lcom/spd/mod/ModGame;->installInjectedMenu(Ljava/lang/Object;)V" in block:
        raise injector.InjectError("WndGame already contains SMM menu injection")

    anchor_re = re.compile(
        r"(?m)^(?P<line>\s*invoke-direct(?:/range)?\s+\{p0\},\s*"
        r"L[^;]+;-><init>\(\)V\s*)$"
    )
    matches = list(anchor_re.finditer(block))
    if len(matches) != 1:
        raise injector.InjectError(
            "WndGame.<init>() does not contain exactly one super() anchor; "
            "refusing heuristic patch"
        )

    match = matches[0]
    indent = re.match(r"\s*", match.group("line")).group(0)
    injected = (
        "\n"
        f"{indent}# SMM menu injection\n"
        f"{indent}invoke-static {{p0}}, Lcom/spd/mod/ModGame;"
        "->installInjectedMenu(Ljava/lang/Object;)V"
    )
    patched = block[:match.end()] + injected + block[match.end():]
    return text[:start] + patched + text[end:]


def output_path(target: Path) -> Path:
    return target.with_name(target.stem + "-SMM" + (target.suffix or ".apk"))


def print_help() -> None:
    print(
        "usage: inject_apk.py TARGET.apk [--out OUTPUT.apk] [options]\n\n"
        "Inject SMM into an SPD-derived APK using smm-inject-donor.apk beside this script.\n\n"
        "options:\n"
        "  --out PATH          output APK (default: <target>-SMM.apk)\n"
        "  --cache PATH        injector tool cache\n"
        "  --offline           do not download missing tools\n"
        "  --keep-work         keep temporary work files\n"
        "  --keystore PATH     signing keystore\n"
        "  --keystore-pass S   keystore password\n"
        "  --key-alias NAME    signing key alias\n"
        "  --key-pass S        signing key password\n"
        "  -h, --help          show this help"
    )


injector.build_debug_payload = build_full_debug_payload
injector.payload_compatibility_errors = full_payload_compatibility_errors
injector.adapt_modankh = adapt_modankh
injector.find_class = find_wndgame_instead_of_dungeon
injector.patch_dungeon = patch_wndgame
injector.output_path = output_path


def main(argv: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or "-h" in args or "--help" in args:
        print_help()
        return 0 if args else 2
    if not DEFAULT_DONOR.is_file():
        raise injector.InjectError(f"SMM donor APK not found beside injector: {DEFAULT_DONOR}")
    return injector.main([str(DEFAULT_DONOR), *args])


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)
