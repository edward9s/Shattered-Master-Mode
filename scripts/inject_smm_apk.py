#!/usr/bin/env python3
"""Inject the complete compiled SMM payload into an SPD-derived APK.

The target APK remains the base artifact and all original target DEX files stay
byte-for-byte unchanged.  This front-end reuses the mature ModAnkh injector for
package rebasing, API validation, manifest handling, DEX overlay packaging and
signing, but expands the payload to all compiled ``com.spd.mod`` classes and
switches the entry hook from ``Dungeon.init()`` to the traditional ``WndGame``
menu entry.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Sequence

import inject_apk as injector


FULL_SMM_PREFIX = "Lcom/spd/mod/"
MOD_ANKH_STORE_PREFIX = injector.MOD_ANKH_STORE[:-1]

# Every compiled SMM class is an explicit payload root. ModAnkh and its store
# remain separately handled by the mature injector because ModAnkh still needs
# the Item.setCurrent compatibility adapter on older forks.
injector.MOD_ITEM_DESCRIPTOR_PREFIX = FULL_SMM_PREFIX

# Full-SMM roots may legitimately call ModAnkh/ModAnkhStore. They are supplied
# separately in the same overlay, so do not mistake those edges for donor-only
# R8 helpers while building the debug/features closure.
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
    # Cross-family SMM references are not target APIs, but they are present in
    # the same injected overlay. Add their rebased signatures to the lookup
    # index without suppressing validation of the payload currently being
    # checked.
    game_prefix = allowed_target_prefixes[0]
    support = injector.rebase_smali_payload(_full_donor_payload, game_prefix)
    augmented = dict(target_index)
    augmented.update(support)
    allowed = allowed_target_prefixes + (FULL_SMM_PREFIX,)
    return _original_payload_compatibility_errors(payload, augmented, allowed)


def find_wndgame_instead_of_dungeon(root: Path, descriptor: str):
    # The legacy injector asks for Dungeon only so it can patch the startup
    # hook. In full-SMM mode we deliberately overlay WndGame instead. Other
    # class lookups (notably donor ModAnkh) remain untouched.
    if descriptor.endswith("/Dungeon;"):
        wnd_game = descriptor[:-len("Dungeon;")] + "windows/WndGame;"
        return _original_find_class(root, wnd_game)
    return _original_find_class(root, descriptor)


def patch_wndgame(text: str, *_unused: str) -> str:
    start, end, block = injector.method_block(text, "<init>", "()V")
    if "Lcom/spd/mod/ModGame;->installInjectedMenu(Ljava/lang/Object;)V" in block:
        raise injector.InjectError("WndGame already contains SMM menu injection")

    # WndGame's own super() call is the unique no-arg constructor invocation on
    # p0. Anonymous button constructors later in the method use local registers.
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
        f"{indent}# SMM full menu injection\n"
        f"{indent}invoke-static {{p0}}, Lcom/spd/mod/ModGame;"
        "->installInjectedMenu(Ljava/lang/Object;)V"
    )
    patched = block[:match.end()] + injected + block[match.end():]
    return text[:start] + patched + text[end:]


injector.build_debug_payload = build_full_debug_payload
injector.payload_compatibility_errors = full_payload_compatibility_errors
injector.find_class = find_wndgame_instead_of_dungeon
injector.patch_dungeon = patch_wndgame


def main(argv: Sequence[str] | None = None) -> int:
    return injector.main(argv)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
