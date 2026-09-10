#!/usr/bin/env python3
"""Mode adapter for the small ModAnkh + Store/Loot/Console APK payload."""
from __future__ import annotations

import re
import zipfile
from pathlib import Path


_ACTION_MESSAGE_BUNDLE_RE = re.compile(
    r"^assets/messages/items/items(?:_[^/]+)?\.properties$"
)
_ACTION_MESSAGES = (
    (b"com.spd.mod.items.modankh.ac_store", b"Store"),
    (b"com.spd.mod.items.modankh.ac_loot", b"Loot"),
    (b"com.spd.mod.items.modankh.ac_console", b"Console"),
    (b"com.spd.mod.items.modankh.ac_unbless", b"Unbless"),
)


def _append_action_messages(data: bytes) -> tuple[bytes, int]:
    missing = []
    for key, value in _ACTION_MESSAGES:
        if re.search(rb"(?m)^" + re.escape(key) + rb"\s*=", data) is None:
            missing.append((key, value))

    if not missing:
        return data, 0

    out = bytearray(data)
    if out and not out.endswith((b"\n", b"\r")):
        out.extend(b"\n")
    out.extend(b"\n# SMM ModAnkh injected action labels\n")
    for key, value in missing:
        out.extend(key + b"=" + value + b"\n")
    return bytes(out), len(missing)


def _patch_action_message_bundles(injector, apk: Path) -> None:
    """Add only ModAnkh action keys needed by legacy WndUseItem implementations."""

    temp = apk.with_name(apk.name + ".modankh-messages.tmp")
    temp.unlink(missing_ok=True)
    matched = 0
    added = 0

    try:
        with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(
            temp, "w", allowZip64=True
        ) as zout:
            zout.comment = zin.comment
            for info in zin.infolist():
                data = zin.read(info.filename)
                if _ACTION_MESSAGE_BUNDLE_RE.fullmatch(info.filename):
                    matched += 1
                    data, count = _append_action_messages(data)
                    added += count
                zout.writestr(injector.clone_zipinfo(info), data)

        if matched == 0:
            temp.unlink(missing_ok=True)
            injector.log(
                "No standard SPD item message bundle found; "
                "ModAnkh action labels rely on target actionName() support"
            )
            return

        temp.replace(apk)
        if added:
            injector.log(
                f"Injected ModAnkh action labels into {matched} item message bundle(s)"
            )
        else:
            injector.log("ModAnkh action labels already present in target message bundles")
    finally:
        temp.unlink(missing_ok=True)


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

    rewritten = injector.SmaliClass.from_text(item.path, text)

    # The core injector snapshots injected_payload before compatibility validation.
    # Replacing payload[descriptor] here would leave that earlier dict pointing at
    # the stale pre-rewrite SmaliClass, so validation would pass while the overlay
    # still contained `.super CellSelector$Listener`. Mutate the shared object in
    # place so every alias sees the legacy class-to-interface rewrite.
    if rewritten.descriptor != item.descriptor:
        raise injector.InjectError(
            "Legacy CellSelector.Listener rewrite unexpectedly changed descriptor: "
            + item.descriptor
        )
    item.text = rewritten.text
    item.superclass = rewritten.superclass
    item.interfaces = rewritten.interfaces
    item.methods = rewritten.methods
    item.fields = rewritten.fields
    return item, True


def configure(public_module) -> None:
    """Replace the full-injection hooks with the narrow ModAnkh-only pipeline."""

    injector = public_module.injector
    full_prefix = public_module.FULL_SMM_PREFIX
    original_rebuild_apk = injector.rebuild_apk

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

        # The legacy class-to-interface adapter depends on the current donor's
        # runtime compatibility helper. Reject a stale donor only for targets
        # that actually need that legacy path; modern ankh-only targets stay
        # compatible with ordinary dependency-closure validation.
        if public_module._current_game_prefix is not None:
            listener_descriptor = injector.game_descriptor(
                public_module._current_game_prefix,
                "scenes/CellSelector$Listener",
            )
            listener = target_index.get(listener_descriptor)
            if listener is not None and _is_interface(listener):
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
                        "SMM donor is too old for legacy --ankh-only injection; "
                        "rebuild the injection donor from current source. Missing "
                        "ModAnkh dependency root(s): "
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
                # _rewrite_listener_subclass mutates item in place intentionally;
                # retain the assignment for clarity and for future implementations.
                payload[descriptor] = rewritten
                changed += 1
        return changed

    def payload_compatibility_errors(
        payload,
        target_index,
        allowed_target_prefixes=injector.TARGET_API_PREFIXES,
    ):
        game_prefix = allowed_target_prefixes[0]
        if payload:
            changed = adapt_legacy_payload(payload, target_index, game_prefix)
            if changed:
                injector.log(
                    "  adapted "
                    + str(changed)
                    + " CellSelector.Listener subclass(es) for legacy interface ABI"
                )

        # javac can emit nestmate/access bridge calls from ModAnkh$* classes
        # back into ModAnkh itself (for example -$$Nest$msyncCount). The core
        # injector validates the dependency payload before it separately adds
        # the adapted ModAnkh root, so without this validation-only support class
        # those internal calls look like external com.spd.mod references.
        #
        # Add only ModAnkh itself to the validation universe; do not whitelist the
        # whole SMM namespace, because unrelated payload dependencies must still
        # fail closed. ModAnkh's own executable target references are validated
        # later by the core modankh_compatibility_errors() pass.
        validation_target = dict(target_index)
        donor_ankh = public_module._full_donor_payload.get(injector.MOD_ANKH)
        if donor_ankh is None:
            raise injector.InjectError(
                "SMM donor ModAnkh root was unavailable during payload validation"
            )
        rebased_ankh = injector.SmaliClass.from_text(
            donor_ankh.path,
            injector.rebase_smali_text(donor_ankh.text, game_prefix),
        )
        validation_target[injector.MOD_ANKH] = rebased_ankh

        return public_module._original_payload_compatibility_errors(
            payload,
            validation_target,
            allowed_target_prefixes + (injector.MOD_ANKH,),
        )

    def rebuild_apk(target, overlay_dex, output, manifest=None):
        mapping = original_rebuild_apk(
            target,
            overlay_dex,
            output,
            manifest,
        )
        _patch_action_message_bundles(injector, output)
        return mapping

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
    injector.rebuild_apk = rebuild_apk
    injector.output_path = output_path
