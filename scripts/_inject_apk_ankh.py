#!/usr/bin/env python3
"""Mode adapter for the small ModAnkh tools + Last Stand + Instant Kill + Force Hit APK payload."""
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
_LAST_STAND = "Lcom/spd/mod/mechanics/ModLastStand;"
_INSTANT_KILL = "Lcom/spd/mod/mechanics/ModInstantKill;"
_FORCE_HIT = "Lcom/spd/mod/mechanics/ModForceHit;"
_LAST_STAND_OVERLAY = "Lcom/spd/mod/journal/ModLastStandOverlay;"
_LAST_STAND_OVERLAY_INNER_PREFIX = "Lcom/spd/mod/journal/ModLastStandOverlay$"


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


def _is_last_stand_overlay(descriptor: str) -> bool:
    return descriptor == _LAST_STAND_OVERLAY or descriptor.startswith(
        _LAST_STAND_OVERLAY_INNER_PREFIX
    )


def _find_buff_click_overlay(injector, target_index, game_prefix):
    """Find the target BuffIndicator button that normally opens WndInfoBuff."""

    ui_prefix = injector.game_descriptor(game_prefix, "ui/BuffIndicator$")[:-1]
    wnd_info = injector.game_descriptor(game_prefix, "windows/WndInfoBuff")
    candidates = []

    for descriptor, item in target_index.items():
        if not descriptor.startswith(ui_prefix) or wnd_info not in item.text:
            continue
        try:
            _start, _end, block = injector.method_block(item.text, "onClick", "()V")
        except injector.InjectError:
            continue
        if wnd_info in block:
            candidates.append((descriptor, item.text))

    if len(candidates) != 1:
        detail = ", ".join(descriptor for descriptor, _ in candidates) or "none"
        raise injector.InjectError(
            "Unable to identify exactly one BuffIndicator onClick target for "
            "Last Stand; candidates: " + detail
        )
    return candidates[0]


def _first_instruction(block: str) -> tuple[int, str]:
    offset = 0
    saw_registers = False
    in_annotation = False
    for line in block.splitlines(keepends=True):
        stripped = line.strip()
        if not saw_registers:
            if re.match(r"\.(?:locals|registers)\b", stripped):
                saw_registers = True
            offset += len(line)
            continue

        if in_annotation:
            if stripped == ".end annotation":
                in_annotation = False
            offset += len(line)
            continue
        if stripped.startswith(".annotation"):
            in_annotation = True
            offset += len(line)
            continue
        if (
            not stripped
            or stripped.startswith("#")
            or stripped.startswith(".")
            or stripped.startswith(":")
        ):
            offset += len(line)
            continue

        indent = line[: len(line) - len(line.lstrip())]
        return offset, indent

    raise RuntimeError("onClick has no executable instruction")


def _ensure_click_scratch_register(injector, block: str) -> tuple[str, str]:
    locals_re = re.compile(r"(?m)^(?P<indent>\s*)\.locals\s+(?P<count>\d+)\s*$")
    match = locals_re.search(block)
    if match:
        count = int(match.group("count"))
        if count == 0:
            block = (
                block[: match.start()]
                + f"{match.group('indent')}.locals 1"
                + block[match.end() :]
            )
        return block, "v0"

    registers_re = re.compile(
        r"(?m)^(?P<indent>\s*)\.registers\s+(?P<count>\d+)\s*$"
    )
    match = registers_re.search(block)
    if not match:
        raise injector.InjectError("BuffIndicator.onClick has neither .locals nor .registers")

    count = int(match.group("count"))
    if count >= 2:
        return block, "v0"

    # onClick()V is an instance method with only p0. With exactly one register,
    # any explicit v0 in the original body aliases p0. Normalize it before adding
    # one real local register so the existing method keeps its original meaning.
    body_start = match.end()
    suffix = re.sub(r"\bv0\b", "p0", block[body_start:])
    block = (
        block[: match.start()]
        + f"{match.group('indent')}.registers 2"
        + suffix
    )
    return block, "v0"


def _add_legacy_last_stand_long_click(
    injector,
    text: str,
    descriptor: str,
    original_click: str,
    field_owner: str,
    field_name: str,
    buff_descriptor: str,
) -> str:
    """Add a long-click bridge only when the target button has none of its own."""

    try:
        injector.method_block(text, "onLongClick", "()Z")
        return text
    except injector.InjectError:
        pass

    helper_name = "smmOpenLastStandInfo"
    helper_re = re.compile(
        r"(?m)^\.method\b[^\n]*\s+" + re.escape(helper_name) + r"\(\)V\s*$"
    )
    if helper_re.search(text):
        raise injector.InjectError(
            "BuffIndicator already contains the Last Stand info helper: " + descriptor
        )

    first_newline = original_click.find("\n")
    if first_newline < 0:
        raise injector.InjectError(
            "Unable to clone BuffIndicator.onClick for Last Stand info: " + descriptor
        )
    helper = ".method private " + helper_name + "()V" + original_click[first_newline:]

    super_match = re.search(r"(?m)^\.super\s+(L[^;\s]+;)\s*$", text)
    if super_match is None:
        raise injector.InjectError(
            "Unable to identify BuffIndicator button superclass: " + descriptor
        )
    super_descriptor = super_match.group(1)

    long_click = (
        ".method protected onLongClick()Z\n"
        "    .locals 1\n\n"
        f"    iget-object v0, p0, {field_owner}->{field_name}:{buff_descriptor}\n"
        f"    instance-of v0, v0, {_LAST_STAND}\n"
        "    if-eqz v0, :smm_last_stand_long_click_super\n\n"
        f"    invoke-direct {{p0}}, {descriptor}->{helper_name}()V\n"
        "    const/4 v0, 0x1\n"
        "    return v0\n\n"
        ":smm_last_stand_long_click_super\n"
        f"    invoke-super {{p0}}, {super_descriptor}->onLongClick()Z\n"
        "    move-result v0\n"
        "    return v0\n"
        ".end method"
    )

    injector.log("Last Stand BuffIndicator legacy long-click bridge: added")
    return text.rstrip() + "\n\n" + helper + "\n\n" + long_click + "\n"


def _patch_last_stand_buff_click(injector, text: str, descriptor: str, game_prefix: str) -> str:
    start, end, block = injector.method_block(text, "onClick", "()V")
    hook = _LAST_STAND + "->open()V"
    if hook in block:
        return text

    buff_descriptor = injector.game_descriptor(game_prefix, "actors/buffs/Buff")
    field_re = re.compile(
        r"(?m)^\s*iget-object\s+(?:[vp]\d+),\s*p0,\s*"
        r"(?P<owner>L[^;\s]+;)->(?P<field>[^:\s]+):"
        + re.escape(buff_descriptor)
        + r"\s*$"
    )
    fields = list(field_re.finditer(block))
    unique_fields = {(m.group("owner"), m.group("field")) for m in fields}
    if len(unique_fields) != 1:
        raise injector.InjectError(
            "Unable to identify exactly one Buff field in " + descriptor + "->onClick()V"
        )
    field_owner, field_name = next(iter(unique_fields))

    # Some legacy BuffIndicator buttons (for example ARK_PD 0.5.2) inherit the
    # default Button.onLongClick(), which returns false. Preserve the target's
    # original info-window click body before replacing short-click behavior, and
    # use it only for Last Stand long presses. Targets with their own long-click
    # handler are intentionally left untouched.
    text = _add_legacy_last_stand_long_click(
        injector,
        text,
        descriptor,
        block,
        field_owner,
        field_name,
        buff_descriptor,
    )

    block, scratch = _ensure_click_scratch_register(injector, block)
    insert_at, indent = _first_instruction(block)
    label = ":smm_last_stand_click_continue"
    if label in block:
        raise injector.InjectError("BuffIndicator.onClick already contains Last Stand hook label")

    injected = (
        f"{indent}# SMM Last Stand direct buff-click hook\n"
        f"{indent}iget-object {scratch}, p0, {field_owner}->{field_name}:{buff_descriptor}\n"
        f"{indent}instance-of {scratch}, {scratch}, {_LAST_STAND}\n"
        f"{indent}if-eqz {scratch}, {label}\n"
        f"{indent}iget-object {scratch}, p0, {field_owner}->{field_name}:{buff_descriptor}\n"
        f"{indent}check-cast {scratch}, {_LAST_STAND}\n"
        f"{indent}invoke-virtual {{{scratch}}}, {hook}\n"
        f"{indent}return-void\n"
        f"{indent}{label}\n\n"
    )
    patched = block[:insert_at] + injected + block[insert_at:]
    return text[:start] + patched + text[end:]


def configure(public_module) -> None:
    """Replace the full-injection hooks with the narrow ModAnkh tools pipeline."""

    injector = public_module.injector
    full_prefix = public_module.FULL_SMM_PREFIX
    original_rebuild_apk = injector.rebuild_apk

    def detect_target_game_prefix(target_index):
        game_prefix = public_module._original_detect_target_game_prefix(target_index)
        public_module._current_game_prefix = game_prefix
        public_module._ankh_instant_kill_enabled = False
        public_module._ankh_force_hit_enabled = False

        char_descriptor = injector.game_descriptor(game_prefix, "actors/Char")
        char_class = target_index.get(char_descriptor)
        if char_class is None:
            raise injector.InjectError("Target Char class is missing")
        public_module._pending_char_overlay = (
            char_descriptor,
            char_class.text,
        )

        public_module._pending_ankh_buff_click_overlay = _find_buff_click_overlay(
            injector, target_index, game_prefix
        )

        profile = public_module.AbiProfile()
        profile.add(public_module._probe_item_set_current(target_index, game_prefix))

        attack_capability = public_module._probe_char_attack_hook(
            target_index, game_prefix
        )
        attack_capability.required = False
        profile.add(attack_capability)

        hit_proto = f"({char_descriptor}{char_descriptor}FZ)Z"
        hit_flags = char_class.methods.get(("hit", hit_proto))
        if hit_flags is not None and "static" in hit_flags:
            force_capability = public_module.AbiCapability(
                "char.forceHitHook",
                public_module.ABI_DIRECT,
                "exact static Char.hit(Char, Char, float, boolean) is available",
                required=False,
                data={"proto": hit_proto},
            )
        else:
            force_capability = public_module.AbiCapability(
                "char.forceHitHook",
                public_module.ABI_UNSUPPORTED,
                "exact static Char.hit(Char, Char, float, boolean) is unavailable",
                required=False,
            )
        profile.add(force_capability)

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
            if dep.startswith(full_prefix)
            and dep != injector.MOD_ANKH
            and not _is_last_stand_overlay(dep)
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
                if (
                    descriptor == injector.MOD_ANKH
                    or descriptor in closure
                    or _is_last_stand_overlay(descriptor)
                ):
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
                        or _is_last_stand_overlay(dep)
                        or dep.startswith(injector.TARGET_API_PREFIXES)
                    ):
                        continue
                    queue.append(dep)

            return closure, unresolved

        core_closure, core_unresolved = collect(direct)
        if core_unresolved:
            raise injector.InjectError(
                "ModAnkh core dependency closure has unresolved donor classes: "
                + ", ".join(sorted(core_unresolved))
            )

        required_roots = {_LAST_STAND}
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
            ("instant", _INSTANT_KILL, None, "Instant Kill"),
            ("force", _FORCE_HIT, "char.forceHitHook", "Force Hit"),
        )
        optional_closures = {}

        for feature, root, capability_key, label in optional_specs:
            if capability_key is not None:
                capability = public_module._current_abi_profile.get(capability_key)
                if not capability.compatible:
                    injector.log(
                        f"Optional {label} skipped: {capability.detail}"
                    )
                    continue
            if root not in donor_index:
                injector.log(
                    f"Optional {label} skipped: donor class is missing"
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
        public_module._ankh_instant_kill_enabled = "instant" in optional_closures
        public_module._ankh_force_hit_enabled = "force" in optional_closures

        public_module._full_donor_payload = {
            descriptor: item
            for descriptor, item in donor_index.items()
            if descriptor.startswith(full_prefix)
        }
        injector.log(
            f"ModAnkh core dependency closure: {len(core_closure)} class(es) "
            "(Store + Loot + Console + Last Stand)"
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
            "instant": "_ankh_instant_kill_enabled",
            "force": "_ankh_force_hit_enabled",
        }
        labels = {
            "instant": "Instant Kill",
            "force": "Force Hit",
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

    def compile_smali_with_last_stand_click(
        java: Path,
        smali_jar: Path,
        directory: Path,
        output: Path,
        api: int,
    ) -> None:
        pending_click = getattr(
            public_module, "_pending_ankh_buff_click_overlay", None
        )
        pending_char = getattr(public_module, "_pending_char_overlay", None)
        instant_enabled = bool(
            getattr(public_module, "_ankh_instant_kill_enabled", False)
        )
        force_enabled = bool(
            getattr(public_module, "_ankh_force_hit_enabled", False)
        )
        if pending_click is None:
            raise injector.InjectError(
                "Last Stand BuffIndicator click overlay source was not captured"
            )
        if public_module._current_game_prefix is None:
            raise injector.InjectError("Target game prefix was not initialized")
        if public_module._current_abi_profile is None:
            raise injector.InjectError("Target ABI profile was not initialized")

        descriptor, original_text = pending_click
        patched = _patch_last_stand_buff_click(
            injector, original_text, descriptor, public_module._current_game_prefix
        )
        overlay_path = directory / Path(descriptor[1:-1] + ".smali")
        if overlay_path.exists():
            raise injector.InjectError(
                "Overlay already contains target BuffIndicator button class: " + descriptor
            )
        overlay_path.parent.mkdir(parents=True, exist_ok=True)
        overlay_path.write_text(patched, encoding="utf-8")
        injector.log("Last Stand BuffIndicator click hook: OK")

        if pending_char is None:
            if instant_enabled:
                injector.log(
                    "Optional Instant Kill pre-defense hook unavailable: "
                    "using attackProc fallback because target Char overlay is unavailable"
                )
            if force_enabled:
                injector.log(
                    "Optional Force Hit skipped: target Char overlay is unavailable"
                )
                force_enabled = False
                public_module._ankh_force_hit_enabled = False

        if (instant_enabled or force_enabled) and pending_char is not None:
            char_descriptor, original_char = pending_char
            patched_char = original_char
            char_changed = False

            if instant_enabled:
                capability = public_module._current_abi_profile.get(
                    "char.incomingAttackHook"
                )
                proto = capability.data.get("proto")
                if capability.compatible and proto:
                    try:
                        patched_char = public_module.patch_char_instant_kill(
                            patched_char, char_descriptor, proto
                        )
                        char_changed = True
                        injector.log(
                            f"Char.attack Instant Kill pre-defense hook ({proto}): OK"
                        )
                    except injector.InjectError as exc:
                        injector.log(
                            "Optional Instant Kill pre-defense hook unavailable; "
                            "keeping ModInstantKill with attackProc fallback: "
                            + str(exc)
                        )
                else:
                    injector.log(
                        "Optional Instant Kill pre-defense hook unavailable; "
                        "keeping ModInstantKill with attackProc fallback: "
                        + capability.detail
                    )

            if force_enabled:
                try:
                    patched_char = public_module.patch_char_hit(
                        patched_char, char_descriptor
                    )
                    char_changed = True
                    injector.log("Char.hit Force Hit pre-defense hook: OK")
                except injector.InjectError as exc:
                    force_enabled = False
                    public_module._ankh_force_hit_enabled = False
                    injector.log(
                        "Optional Force Hit skipped during patch: " + str(exc)
                    )

            if char_changed:
                char_path = directory / Path(char_descriptor[1:-1] + ".smali")
                if char_path.exists():
                    raise injector.InjectError(
                        "Overlay already contains target Char class: " + char_descriptor
                    )
                char_path.parent.mkdir(parents=True, exist_ok=True)
                char_path.write_text(patched_char, encoding="utf-8")

        core_keys = set(
            getattr(public_module, "_ankh_core_payload_descriptors", set())
        )
        optional_sets = dict(
            getattr(public_module, "_ankh_optional_payload_descriptors", {})
        )
        keep = set(core_keys)
        if instant_enabled:
            keep.update(optional_sets.get("instant", set()))
        if force_enabled:
            keep.update(optional_sets.get("force", set()))

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
            public_module._pending_ankh_buff_click_overlay = None
            public_module._pending_char_overlay = None

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

    # Ankh-only guarantees the ModAnkh + Last Stand core. Instant Kill and
    # Force Hit are capability-gated extras: an unsupported Char ABI skips only
    # that feature instead of aborting the core injection.
    injector.detect_target_game_prefix = detect_target_game_prefix
    injector.build_debug_payload = build_ankh_payload
    injector.payload_compatibility_errors = payload_compatibility_errors
    injector.find_class = public_module._original_find_class
    injector.patch_dungeon = public_module._original_patch_dungeon
    injector.compile_smali = compile_smali_with_last_stand_click
    injector.rebuild_apk = rebuild_apk
    injector.output_path = output_path
