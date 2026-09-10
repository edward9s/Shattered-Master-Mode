#!/usr/bin/env python3
"""Mode adapter for the small ModAnkh + Store/Loot/Console APK payload."""
from __future__ import annotations

import re
from pathlib import Path


def _is_interface(item) -> bool:
    return bool(re.search(r"(?m)^\.class\b[^\n]*\binterface\b", item.text))


def _rewrite_listener_subclass(injector, item, listener_descriptor):
    if item.superclass != listener_descriptor:
        return item, False

    text = item.text
    super_re = re.compile(
        r"(?m)^\.super\s+" + re.escape(listener_descriptor) + r"\s*$"
    )
    text, count = super_re.subn(
        ".super Ljava/lang/Object;\n.implements " + listener_descriptor,
        text,
        count=1,
    )
    if count != 1:
        raise injector.InjectError(
            "Unable to rewrite legacy CellSelector.Listener superclass for "
            + item.descriptor
        )

    ctor_re = re.compile(
        r"(?m)^(?P<indent>\s*)invoke-direct(?P<range>/range)?\s+"
        r"\{(?P<args>[^}]*)\},\s*"
        + re.escape(listener_descriptor)
        + r"-><init>\(\)V\s*$"
    )

    def repl(match: re.Match[str]) -> str:
        return (
            f"{match.group('indent')}invoke-direct{match.group('range') or ''} "
            f"{{{match.group('args')}}}, Ljava/lang/Object;-><init>()V"
        )

    text, ctor_count = ctor_re.subn(repl, text)
    if ctor_count != 1:
        raise injector.InjectError(
            "Expected exactly one CellSelector.Listener constructor call in "
            + item.descriptor
            + f", found {ctor_count}"
        )

    return injector.SmaliClass.from_text(item.path, text), True


def configure(public_module) -> None:
    """Replace the full-injection hooks with the narrow ModAnkh-only pipeline."""

    injector = public_module.injector
    full_prefix = public_module.FULL_SMM_PREFIX

    def detect_target_game_prefix(target_index):
        game_prefix = public_module._original_detect_target_game_prefix(target_index)
        public_module._current_game_prefix = game_prefix
        public_module._pending_char_overlay = None

        profile = public_module.AbiProfile()
        profile.add(public_module._probe_item_set_current(target_index, game_prefix))
        public_module._current_abi_profile = profile
        profile.log()
        profile.require_compatible()
        return game_prefix

    def build_ankh_payload(donor_index, target_index):
        donor_ankh = donor_index.get(injector.MOD_ANKH)
        if donor_ankh is None:
            raise injector.InjectError(
                "SMM donor is missing ModAnkh; rebuild the injection donor from current source"
            )

        direct = sorted(
            dep
            for dep in injector.smali_dependencies(donor_ankh)
            if dep.startswith(full_prefix) and dep != injector.MOD_ANKH
        )
        if not direct:
            raise injector.InjectError(
                "ModAnkh has no SMM dependency closure in the donor; rebuild the injection donor"
            )

        closure = {}
        queue = list(direct)
        unresolved = set()

        while queue:
            descriptor = queue.pop()
            if descriptor == injector.MOD_ANKH or descriptor in closure:
                continue
            if descriptor.startswith(injector.TARGET_API_PREFIXES):
                continue

            item = donor_index.get(descriptor)
            if item is None:
                unresolved.add(descriptor)
                continue

            closure[descriptor] = item
            for dep in injector.smali_dependencies(item):
                if (
                    dep == injector.MOD_ANKH
                    or dep in closure
                    or dep.startswith(injector.TARGET_API_PREFIXES)
                ):
                    continue
                queue.append(dep)

        if unresolved:
            raise injector.InjectError(
                "ModAnkh dependency closure has unresolved donor classes: "
                + ", ".join(sorted(unresolved))
            )

        required_roots = {
            full_prefix + "items/WndModLoot;",
            full_prefix + "mechanics/ModLootStorage;",
            full_prefix + "mechanics/ModLoot;",
            full_prefix + "mechanics/ModDebug$Console;",
            full_prefix + "mechanics/ModDebug;",
            full_prefix + "mechanics/ModLegacyCompat;",
        }
        missing_roots = sorted(required_roots.difference(closure))
        if missing_roots:
            raise injector.InjectError(
                "SMM donor is too old for --ankh-only; rebuild the injection donor "
                "from current source. Missing ModAnkh dependency root(s): "
                + ", ".join(missing_roots)
            )

        helpers = sorted(
            descriptor
            for descriptor in closure
            if not descriptor.startswith(full_prefix)
        )
        relocations = {
            descriptor: injector.relocated_helper_descriptor(descriptor)
            for descriptor in helpers
        }
        if len(set(relocations.values())) != len(relocations):
            raise injector.InjectError(
                "Generated duplicate ModAnkh donor-helper relocation names"
            )

        collisions = sorted(set(relocations.values()).intersection(target_index))
        if collisions:
            raise injector.InjectError(
                "Generated ModAnkh donor-helper names collide with target classes: "
                + ", ".join(collisions)
            )

        payload = {}
        for descriptor, item in closure.items():
            rewritten = injector.rewrite_smali_class(item, relocations)
            if rewritten.descriptor in payload:
                raise injector.InjectError(
                    "Duplicate ModAnkh payload class after relocation: "
                    + rewritten.descriptor
                )
            payload[rewritten.descriptor] = rewritten

        public_module._full_donor_payload = {
            descriptor: item
            for descriptor, item in donor_index.items()
            if descriptor.startswith(full_prefix)
        }
        injector.log(
            f"ModAnkh dependency closure: {len(payload)} class(es) "
            "(Store + Loot + Console)"
        )
        return payload, relocations

    def adapt_legacy_payload(payload, target_index, game_prefix):
        listener_descriptor = injector.game_descriptor(
            game_prefix, "scenes/CellSelector$Listener"
        )
        listener = target_index.get(listener_descriptor)
        if listener is None or not _is_interface(listener):
            return 0

        changed = 0
        for descriptor, item in list(payload.items()):
            rewritten, did_change = _rewrite_listener_subclass(
                injector, item, listener_descriptor
            )
            if did_change:
                payload[descriptor] = rewritten
                changed += 1
        return changed

    def payload_compatibility_errors(
        payload,
        target_index,
        allowed_target_prefixes=injector.TARGET_API_PREFIXES,
    ):
        if payload:
            game_prefix = allowed_target_prefixes[0]
            changed = adapt_legacy_payload(payload, target_index, game_prefix)
            if changed:
                injector.log(
                    "  adapted "
                    + str(changed)
                    + " CellSelector.Listener subclass(es) for legacy interface ABI"
                )
        return public_module._original_payload_compatibility_errors(
            payload,
            target_index,
            allowed_target_prefixes,
        )

    def output_path(target: Path) -> Path:
        return target.with_name(
            target.stem + "-SMM-Ankh" + (target.suffix or ".apk")
        )

    # Ankh-only uses the original narrow injector mechanics: patch Dungeon.init()
    # after HeroClass.initHero(Hero), and do not install the full SMM menu or the
    # independent Riposte Char.attack hook.
    injector.detect_target_game_prefix = detect_target_game_prefix
    injector.build_debug_payload = build_ankh_payload
    injector.payload_compatibility_errors = payload_compatibility_errors
    injector.find_class = public_module._original_find_class
    injector.patch_dungeon = public_module._original_patch_dungeon
    injector.compile_smali = public_module._original_compile_smali
    injector.output_path = output_path
