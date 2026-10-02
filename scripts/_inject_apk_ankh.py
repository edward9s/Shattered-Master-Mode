#!/usr/bin/env python3
"""Mode adapter for the narrow ModAnkh core plus optional combat/UI APK payloads."""
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
    """Replace the full-injection hooks with the narrow ModAnkh tools pipeline."""

    injector = public_module.injector
    full_prefix = public_module.FULL_SMM_PREFIX
    last_stand = full_prefix + "mechanics/ModLastStand;"
    last_stand_tag = full_prefix + "journal/ModLastStandTag;"
    parry_riposte = full_prefix + "mechanics/ModParryRiposte;"
    instant_kill = full_prefix + "mechanics/ModInstantKill;"
    force_hit = full_prefix + "mechanics/ModForceHit;"
    assassinate = full_prefix + "mechanics/ModAssassinate;"
    enemy_surge = full_prefix + "mechanics/ModEnemySurge;"

    def detect_target_game_prefix(target_index):
        game_prefix = public_module._original_detect_target_game_prefix(target_index)
        public_module._current_game_prefix = game_prefix
        public_module._ankh_parry_riposte_enabled = False
        public_module._ankh_instant_kill_enabled = False
        public_module._ankh_force_hit_enabled = False
        public_module._ankh_assassinate_enabled = False
        public_module._ankh_enemy_surge_enabled = False
        public_module._pending_hit_call_overlays = {}
        public_module._pending_hit_call_rewrite_count = 0
        public_module._pending_parry_feedback_overlays = {}
        public_module._pending_parry_feedback_rewrite_count = 0

        char_descriptor = injector.game_descriptor(game_prefix, "actors/Char")
        char_class = target_index.get(char_descriptor)
        if char_class is None:
            raise injector.InjectError("Target Char class is missing")
        public_module._pending_char_overlay = (
            char_descriptor,
            char_class.text,
        )

        public_module._pending_buff_click_patch = (
            public_module.buff_click.find_target(
                injector, target_index, game_prefix
            )
        )
        public_module._pending_action_name_overlay = (
            public_module.action_name.find_target(
                injector, target_index, game_prefix
            )
        )

        profile = public_module.AbiProfile()
        profile.add(public_module._probe_item_set_current(target_index, game_prefix))

        attack_capability = public_module._probe_char_attack_hook(
            target_index, game_prefix
        )
        attack_capability.required = False
        profile.add(attack_capability)

        hit_capability = public_module._probe_hit_hook(
            target_index, game_prefix
        )
        hit_capability.required = False
        profile.add(hit_capability)

        public_module._current_abi_profile = profile
        profile.log()
        profile.require_compatible()

        hit_method = hit_capability.data.get("method")
        hit_proto = hit_capability.data.get("proto")
        if hit_capability.compatible and hit_method and hit_proto:
            (
                public_module._pending_hit_call_overlays,
                public_module._pending_hit_call_rewrite_count,
            ) = public_module.canonicalize_inherited_static_hit_calls(
                target_index,
                char_descriptor,
                hit_method,
                hit_proto,
            )
            (
                public_module._pending_parry_feedback_overlays,
                public_module._pending_parry_feedback_rewrite_count,
            ) = public_module.rewrite_parry_defense_verb_calls(
                target_index,
                char_descriptor,
                public_module._pending_hit_call_overlays,
            )

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
            if dep.startswith(full_prefix)
            and dep != injector.MOD_ANKH
        )
        if not direct:
            raise injector.InjectError(
                "ModAnkh has no SMM dependency closure in the donor; rebuild the injection donor"
            )

        if public_module._current_abi_profile is None:
            raise injector.InjectError("Target ABI profile was not initialized")

        def collect(roots):
            closure = {}
            queue = list(roots)
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

            return closure, unresolved

        core_closure, core_unresolved = collect([*direct, last_stand_tag])
        if core_unresolved:
            raise injector.InjectError(
                "ModAnkh core dependency closure has unresolved donor classes: "
                + ", ".join(sorted(core_unresolved))
            )

        required_roots = {last_stand, last_stand_tag}
        missing_roots = sorted(required_roots.difference(core_closure))
        if missing_roots:
            raise injector.InjectError(
                "SMM donor is too old for --ankh-only injection; rebuild the "
                "Injection Kit. Missing core payload root(s): "
                + ", ".join(missing_roots)
            )

        if public_module._current_game_prefix is not None:
            listener_descriptor = injector.game_descriptor(
                public_module._current_game_prefix,
                "scenes/CellSelector$Listener",
            )
            listener = target_index.get(listener_descriptor)
            if listener is not None and _is_interface(listener):
                legacy_required = {
                    full_prefix + "items/WndModLoot;",
                    full_prefix + "mechanics/ModLootStorage;",
                    full_prefix + "mechanics/ModLoot;",
                    full_prefix + "mechanics/ModDebug$Console;",
                    full_prefix + "mechanics/ModDebug;",
                    full_prefix + "mechanics/ModLegacyCompat;",
                    full_prefix + "mechanics/ModLastStand;",
                }
                missing_roots = sorted(legacy_required.difference(core_closure))
                if missing_roots:
                    raise injector.InjectError(
                        "SMM donor is too old for legacy --ankh-only injection; "
                        "rebuild the injection donor from current source. Missing "
                        "core ModAnkh dependency root(s): "
                        + ", ".join(missing_roots)
                    )

        optional_specs = (
            (
                "parry",
                parry_riposte,
                ("char.incomingAttackHook", "char.hitHook"),
                "Parry/Riposte",
            ),
            ("instant", instant_kill, ("char.incomingAttackHook",), "Instant Kill"),
            ("force", force_hit, ("char.hitHook",), "Force Hit"),
            ("assassinate", assassinate, (), "Assassinate"),
            ("enemy_surge", enemy_surge, (), "Enemy Surge"),
        )
        optional_closures = {}

        for feature, root, capability_keys, label in optional_specs:
            incompatible = None
            for capability_key in capability_keys:
                capability = public_module._current_abi_profile.get(capability_key)
                if not capability.compatible:
                    incompatible = capability
                    break
            if incompatible is not None:
                injector.log(
                    f"Optional {label} skipped: {incompatible.detail}"
                )
                continue
            if root not in donor_index:
                injector.log(
                    f"Optional {label} skipped: donor class is missing"
                )
                continue

            if feature == "parry":
                source_char = (
                    injector.SOURCE_GAME_DESCRIPTOR_PREFIX + "actors/Char;"
                )
                required_hooks = (
                    ("onIncomingAttack", f"({source_char}{source_char})V"),
                    ("onIncomingAttackComplete", "()V"),
                    ("shouldParry", f"({source_char}{source_char})Z"),
                    ("defenseVerb", f"({source_char})Ljava/lang/String;"),
                )
                donor_parry = donor_index[root]
                missing_hooks = [
                    name + proto
                    for name, proto in required_hooks
                    if not {"public", "static"}.issubset(
                        donor_parry.methods.get((name, proto), frozenset())
                    )
                ]
                if missing_hooks:
                    injector.log(
                        "Optional Parry/Riposte skipped: donor is missing hook ABI: "
                        + ", ".join(missing_hooks)
                    )
                    continue

            feature_closure, unresolved = collect([root])
            if unresolved:
                injector.log(
                    f"Optional {label} skipped: unresolved donor classes: "
                    + ", ".join(sorted(unresolved))
                )
                continue
            optional_closures[feature] = feature_closure

        closure = dict(core_closure)
        for feature_closure in optional_closures.values():
            closure.update(feature_closure)

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

        def mapped(descriptors):
            return {
                relocations.get(descriptor, descriptor)
                for descriptor in descriptors
            }

        public_module._ankh_core_payload_descriptors = mapped(core_closure)
        public_module._ankh_optional_payload_descriptors = {
            feature: mapped(feature_closure)
            for feature, feature_closure in optional_closures.items()
        }
        public_module._ankh_parry_riposte_enabled = "parry" in optional_closures
        public_module._ankh_instant_kill_enabled = "instant" in optional_closures
        public_module._ankh_force_hit_enabled = "force" in optional_closures
        public_module._ankh_assassinate_enabled = "assassinate" in optional_closures
        public_module._ankh_enemy_surge_enabled = "enemy_surge" in optional_closures

        public_module._full_donor_payload = {
            descriptor: item
            for descriptor, item in donor_index.items()
            if descriptor.startswith(full_prefix)
        }
        injector.log(
            f"ModAnkh core dependency closure: {len(core_closure)} class(es) "
            "(Store + Loot + Console + Last Stand + Tag)"
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
        allowed = allowed_target_prefixes + (injector.MOD_ANKH,)

        core_keys = set(
            getattr(public_module, "_ankh_core_payload_descriptors", set())
        )
        optional_sets = dict(
            getattr(public_module, "_ankh_optional_payload_descriptors", {})
        )

        core_payload = {
            descriptor: item
            for descriptor, item in payload.items()
            if descriptor in core_keys
        }
        core_errors = public_module._original_payload_compatibility_errors(
            core_payload,
            validation_target,
            allowed,
        )
        if core_errors:
            return core_errors

        feature_flags = {
            "parry": "_ankh_parry_riposte_enabled",
            "instant": "_ankh_instant_kill_enabled",
            "force": "_ankh_force_hit_enabled",
            "assassinate": "_ankh_assassinate_enabled",
            "enemy_surge": "_ankh_enemy_surge_enabled",
        }
        labels = {
            "parry": "Parry/Riposte",
            "instant": "Instant Kill",
            "force": "Force Hit",
            "assassinate": "Assassinate",
            "enemy_surge": "Enemy Surge",
        }

        for feature, descriptors in optional_sets.items():
            if not bool(getattr(public_module, feature_flags[feature], False)):
                continue
            feature_payload = {
                descriptor: item
                for descriptor, item in payload.items()
                if descriptor in descriptors
            }
            feature_errors = public_module._original_payload_compatibility_errors(
                feature_payload,
                validation_target,
                allowed,
            )
            if feature_errors:
                setattr(public_module, feature_flags[feature], False)
                injector.log(
                    f"Optional {labels[feature]} skipped: target API is incompatible"
                )
                for error in feature_errors:
                    injector.log("  - " + error)

        keep = set(core_keys)
        for feature, descriptors in optional_sets.items():
            if bool(getattr(public_module, feature_flags[feature], False)):
                keep.update(descriptors)

        for descriptor in list(payload):
            if descriptor not in keep:
                del payload[descriptor]

        return public_module._original_payload_compatibility_errors(
            payload,
            validation_target,
            allowed,
        )

    def compile_smali_with_buff_click(
        java: Path,
        smali_jar: Path,
        directory: Path,
        output: Path,
        api: int,
    ) -> None:
        pending_char = getattr(public_module, "_pending_char_overlay", None)
        parry_enabled = bool(
            getattr(public_module, "_ankh_parry_riposte_enabled", False)
        )
        instant_enabled = bool(
            getattr(public_module, "_ankh_instant_kill_enabled", False)
        )
        force_enabled = bool(
            getattr(public_module, "_ankh_force_hit_enabled", False)
        )
        assassinate_enabled = bool(
            getattr(public_module, "_ankh_assassinate_enabled", False)
        )
        enemy_surge_enabled = bool(
            getattr(public_module, "_ankh_enemy_surge_enabled", False)
        )
        if public_module._current_game_prefix is None:
            raise injector.InjectError("Target game prefix was not initialized")
        if public_module._current_abi_profile is None:
            raise injector.InjectError("Target ABI profile was not initialized")

        public_module.write_action_name_overlay(directory)

        if pending_char is None:
            if parry_enabled:
                injector.log(
                    "Optional Parry/Riposte skipped: target Char overlay is unavailable"
                )
                parry_enabled = False
                public_module._ankh_parry_riposte_enabled = False
            if instant_enabled:
                injector.log(
                    "Optional Instant Kill skipped: target Char overlay is unavailable"
                )
                instant_enabled = False
                public_module._ankh_instant_kill_enabled = False
            if force_enabled:
                injector.log(
                    "Optional Force Hit skipped: target Char overlay is unavailable"
                )
                force_enabled = False
                public_module._ankh_force_hit_enabled = False

        if (parry_enabled or instant_enabled or force_enabled) and pending_char is not None:
            char_descriptor, original_char = pending_char
            patched_char = original_char
            char_changed = False

            # Parry and Force Hit share the selected Char.hit entry. Force Hit
            # is emitted first so it keeps its established precedence over Parry.
            if parry_enabled or force_enabled:
                try:
                    hit_capability = public_module._current_abi_profile.get(
                        "char.hitHook"
                    )
                    hit_method = hit_capability.data.get("method")
                    hit_proto = hit_capability.data.get("proto")
                    if not hit_method or not hit_proto:
                        raise injector.InjectError(
                            "Selected hit-check hook was not preserved"
                        )
                    patched_char = public_module.patch_char_hit(
                        patched_char,
                        char_descriptor,
                        hit_method,
                        hit_proto,
                        force=force_enabled,
                        parry=parry_enabled,
                    )
                    char_changed = True
                    enabled = []
                    if force_enabled:
                        enabled.append("Force Hit")
                    if parry_enabled:
                        enabled.append("Parry")
                    injector.log(
                        " + ".join(enabled)
                        + " pre-hit hook "
                        + f"{hit_method}{hit_proto} "
                        + f"({hit_capability.strategy}): OK"
                    )
                except injector.InjectError as exc:
                    if force_enabled:
                        force_enabled = False
                        public_module._ankh_force_hit_enabled = False
                    if parry_enabled:
                        parry_enabled = False
                        public_module._ankh_parry_riposte_enabled = False
                    injector.log(
                        "Optional combat hit hooks skipped after structural patch attempt: "
                        + str(exc)
                    )

            if instant_enabled:
                attack_capability = public_module._current_abi_profile.get(
                    "char.incomingAttackHook"
                )
                proto = attack_capability.data.get("proto")
                if attack_capability.compatible and proto:
                    try:
                        patched_char = public_module.patch_char_instant_kill(
                            patched_char,
                            char_descriptor,
                            proto,
                            force_combo=force_enabled,
                        )
                        char_changed = True
                        combo = " + Force Hit entry" if force_enabled else ""
                        injector.log(
                            "Char.attack Instant Kill attack-context return hook"
                            + combo
                            + f" ({proto}): OK"
                        )
                    except injector.InjectError as exc:
                        instant_enabled = False
                        public_module._ankh_instant_kill_enabled = False
                        injector.log(
                            "Optional Instant Kill skipped after attack patch attempt: "
                            + str(exc)
                        )
                else:
                    instant_enabled = False
                    public_module._ankh_instant_kill_enabled = False
                    injector.log(
                        "Optional Instant Kill skipped: "
                        + attack_capability.detail
                    )

            if parry_enabled:
                attack_capability = public_module._current_abi_profile.get(
                    "char.incomingAttackHook"
                )
                proto = attack_capability.data.get("proto")
                if attack_capability.compatible and proto:
                    try:
                        patched_char = public_module.patch_char_attack(
                            patched_char,
                            char_descriptor,
                            proto,
                        )
                        char_changed = True
                        injector.log(
                            f"Char.attack Parry/Riposte entry/return hooks ({proto}): OK"
                        )
                    except injector.InjectError as exc:
                        parry_enabled = False
                        public_module._ankh_parry_riposte_enabled = False
                        injector.log(
                            "Optional Parry/Riposte skipped after attack patch attempt: "
                            + str(exc)
                        )
                else:
                    parry_enabled = False
                    public_module._ankh_parry_riposte_enabled = False
                    injector.log(
                        "Optional Parry/Riposte skipped: "
                        + attack_capability.detail
                    )

            if parry_enabled:
                patched_char, char_feedback_count = (
                    public_module.rewrite_char_parry_defense_verb_calls(
                        patched_char,
                        char_descriptor,
                    )
                )
                if char_feedback_count:
                    injector.log(
                        "Routed Parry defense feedback for "
                        + str(char_feedback_count)
                        + " Char defenseVerb call(s): OK"
                    )

            if char_changed:
                char_path = directory / Path(char_descriptor[1:-1] + ".smali")
                if char_path.exists():
                    raise injector.InjectError(
                        "Overlay already contains target Char class: " + char_descriptor
                    )
                char_path.parent.mkdir(parents=True, exist_ok=True)
                char_path.write_text(patched_char, encoding="utf-8")

        if parry_enabled or force_enabled:
            public_module.write_combat_call_overlays(
                directory,
                parry=parry_enabled,
                force=force_enabled,
            )

        public_module.write_buff_click_patch(
            directory,
            parry=parry_enabled,
            instant=instant_enabled,
            force=force_enabled,
            assassinate=assassinate_enabled,
            enemy_surge=enemy_surge_enabled,
        )

        core_keys = set(
            getattr(public_module, "_ankh_core_payload_descriptors", set())
        )
        optional_sets = dict(
            getattr(public_module, "_ankh_optional_payload_descriptors", {})
        )
        keep = set(core_keys)
        if parry_enabled:
            keep.update(optional_sets.get("parry", set()))
        if instant_enabled:
            keep.update(optional_sets.get("instant", set()))
        if force_enabled:
            keep.update(optional_sets.get("force", set()))
        if assassinate_enabled:
            keep.update(optional_sets.get("assassinate", set()))
        if enemy_surge_enabled:
            keep.update(optional_sets.get("enemy_surge", set()))

        optional_all = set()
        for descriptors in optional_sets.values():
            optional_all.update(descriptors)
        for descriptor in optional_all.difference(keep):
            payload_path = directory / Path(descriptor[1:-1] + ".smali")
            payload_path.unlink(missing_ok=True)

        try:
            public_module._original_compile_smali(
                java, smali_jar, directory, output, api
            )
        finally:
            public_module._pending_buff_click_patch = None
            public_module._pending_action_name_overlay = None
            public_module._pending_char_overlay = None
            public_module._pending_hit_call_overlays = {}
            public_module._pending_hit_call_rewrite_count = 0
            public_module._pending_parry_feedback_overlays = {}
            public_module._pending_parry_feedback_rewrite_count = 0

    def output_path(target: Path) -> Path:
        return target.with_name(
            target.stem + "-SMM-Ankh" + (target.suffix or ".apk")
        )

    # Ankh-only guarantees the ModAnkh + Last Stand core, including the runtime
    # Last Stand Tag. Parry/Riposte and Instant Kill require a safe terminal
    # Char.attack hook; Force Hit separately requires a safe selected hit-check.
    # No attackProc fallback is kept, so optional features never silently degrade
    # their semantics. Assassinate is separate and needs no extra Char hook.
    injector.detect_target_game_prefix = detect_target_game_prefix
    injector.build_debug_payload = build_ankh_payload
    injector.payload_compatibility_errors = payload_compatibility_errors
    injector.find_class = public_module._original_find_class
    injector.patch_dungeon = public_module._original_patch_dungeon
    injector.compile_smali = compile_smali_with_buff_click
    injector.output_path = output_path
