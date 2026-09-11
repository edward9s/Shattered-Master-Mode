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
_LAST_STAND = "Lcom/spd/mod/mechanics/ModLastStand;"
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
        public_module._pending_char_overlay = None
        public_module._pending_ankh_buff_click_overlay = _find_buff_click_overlay(
            injector, target_index, game_prefix
        )

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
            if dep.startswith(full_prefix)
            and dep != injector.MOD_ANKH
            and not _is_last_stand_overlay(dep)
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
                    full_prefix + "mechanics/ModLastStand;",
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

    def compile_smali_with_last_stand_click(
        java: Path,
        smali_jar: Path,
        directory: Path,
        output: Path,
        api: int,
    ) -> None:
        pending = getattr(public_module, "_pending_ankh_buff_click_overlay", None)
        if pending is None:
            raise injector.InjectError("Last Stand BuffIndicator click overlay source was not captured")
        if public_module._current_game_prefix is None:
            raise injector.InjectError("Target game prefix was not initialized")

        descriptor, original_text = pending
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

        try:
            public_module._original_compile_smali(
                java, smali_jar, directory, output, api
            )
        finally:
            public_module._pending_ankh_buff_click_overlay = None

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
    # independent Riposte Char.attack hook. Last Stand's buff click is patched
    # directly into the target BuffIndicator instead of shipping its newer overlay.
    injector.detect_target_game_prefix = detect_target_game_prefix
    injector.build_debug_payload = build_ankh_payload
    injector.payload_compatibility_errors = payload_compatibility_errors
    injector.find_class = public_module._original_find_class
    injector.patch_dungeon = public_module._original_patch_dungeon
    injector.compile_smali = compile_smali_with_last_stand_click
    injector.rebuild_apk = rebuild_apk
    injector.output_path = output_path
