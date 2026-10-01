#!/usr/bin/env python3
"""Shared structural SMM BuffIndicator click patch for APK injection."""
from __future__ import annotations

import re

LAST_STAND = "Lcom/spd/mod/mechanics/ModLastStand;"
INSTANT_KILL = "Lcom/spd/mod/mechanics/ModInstantKill;"
FORCE_HIT = "Lcom/spd/mod/mechanics/ModForceHit;"
PARRY_RIPOSTE = "Lcom/spd/mod/mechanics/ModParryRiposte;"
ASSASSINATE = "Lcom/spd/mod/mechanics/ModAssassinate;"

LAST_STAND_HANDLER = (LAST_STAND, "open")
INSTANT_KILL_HANDLER = (INSTANT_KILL, "openInfo")
FORCE_HIT_HANDLER = (FORCE_HIT, "openInfo")
PARRY_RIPOSTE_HANDLER = (PARRY_RIPOSTE, "openInfo")
ASSASSINATE_HANDLER = (ASSASSINATE, "openInfo")

INFO_HELPER = "smmNativeInfo"
LONG_HELPER = "smmNativeLongClick"
_LEGACY_HELPERS = ("smmLastStandInfo", "smm$lastStandInfo")


def selected_handlers(*, instant: bool, force: bool, full: bool):
    handlers = [LAST_STAND_HANDLER]
    if full:
        handlers.append(PARRY_RIPOSTE_HANDLER)
    if instant:
        handlers.append(INSTANT_KILL_HANDLER)
    if force:
        handlers.append(FORCE_HIT_HANDLER)
    if full:
        handlers.append(ASSASSINATE_HANDLER)
    return tuple(handlers)


def _method_flags(block: str, name: str, proto: str) -> list[str]:
    first_line = block.splitlines()[0] if block else ""
    match = re.fullmatch(
        r"\.method\s+(?P<flags>.*?)\b"
        + re.escape(name)
        + re.escape(proto),
        first_line.strip(),
    )
    if match is None:
        raise ValueError(f"Unable to parse method header for {name}{proto}")
    flags = [flag for flag in match.group("flags").split() if flag]
    if "static" in flags:
        raise ValueError(f"{name}{proto} must be an instance method")
    return flags


def _private_flags(flags: list[str]) -> str:
    kept = [
        flag
        for flag in flags
        if flag not in {"public", "protected", "private"}
    ]
    return " ".join(["private", *kept])


def _visible_flags(flags: list[str], default: str = "protected") -> str:
    visibility = next(
        (flag for flag in flags if flag in {"public", "protected", "private"}),
        default,
    )
    kept = [
        flag
        for flag in flags
        if flag not in {"public", "protected", "private"}
    ]
    return " ".join([visibility, *kept])


def _rename_method_header(
    block: str,
    old_name: str,
    proto: str,
    new_name: str,
    flags: str,
) -> str:
    lines = block.splitlines(keepends=True)
    if not lines:
        raise ValueError(f"Empty method block for {old_name}{proto}")
    newline = "\n" if lines[0].endswith("\n") else ""
    lines[0] = f".method {flags} {new_name}{proto}{newline}"
    return "".join(lines)


def _buff_field(
    injector,
    block: str,
    descriptor: str,
    buff_descriptor: str,
) -> tuple[str, str]:
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
            "Unable to identify exactly one Buff field in "
            + descriptor
            + "->onClick()V"
        )
    return next(iter(unique_fields))


def find_target(injector, target_index, game_prefix):
    """Find the one BuffIndicator button whose normal click opens buff info."""

    ui_prefix = injector.game_descriptor(game_prefix, "ui/BuffIndicator$")[:-1]
    wnd_info = injector.game_descriptor(game_prefix, "windows/WndInfoBuff")
    buff_descriptor = injector.game_descriptor(game_prefix, "actors/buffs/Buff")
    candidates = []

    for descriptor, item in target_index.items():
        if not descriptor.startswith(ui_prefix):
            continue
        try:
            _start, _end, block = injector.method_block(item.text, "onClick", "()V")
        except injector.InjectError:
            continue
        if wnd_info not in block:
            continue
        try:
            _method_flags(block, "onClick", "()V")
            _buff_field(injector, block, descriptor, buff_descriptor)
        except (injector.InjectError, ValueError):
            continue
        candidates.append((descriptor, item.text))

    if len(candidates) != 1:
        detail = ", ".join(descriptor for descriptor, _ in candidates) or "none"
        raise injector.InjectError(
            "Unable to identify exactly one BuffIndicator onClick target; "
            "candidates: " + detail
        )
    return candidates[0]


def patch(
    injector,
    text: str,
    descriptor: str,
    game_prefix: str,
    handlers,
) -> str:
    handlers = tuple(handlers)
    if not handlers:
        raise injector.InjectError("SMM BuffIndicator click bridge has no handlers")
    if len({buff for buff, _method in handlers}) != len(handlers):
        raise injector.InjectError("SMM BuffIndicator click bridge has duplicate buff handlers")

    click_start, click_end, click_block = injector.method_block(
        text, "onClick", "()V"
    )

    bridge_markers = [INFO_HELPER, LONG_HELPER, *_LEGACY_HELPERS]
    bridge_markers.extend(
        f"{buff}->{method}()V" for buff, method in handlers
    )
    if any(marker in text for marker in bridge_markers):
        raise injector.InjectError(
            "BuffIndicator already contains an SMM buff click bridge: " + descriptor
        )

    buff_descriptor = injector.game_descriptor(game_prefix, "actors/buffs/Buff")
    field_owner, field_name = _buff_field(
        injector, click_block, descriptor, buff_descriptor
    )

    try:
        click_flags = _method_flags(click_block, "onClick", "()V")
    except ValueError as exc:
        raise injector.InjectError(str(exc)) from exc

    super_match = re.search(r"(?m)^\.super\s+(L[^;\s]+;)\s*$", text)
    if super_match is None:
        raise injector.InjectError(
            "Unable to identify BuffIndicator button superclass: " + descriptor
        )
    super_descriptor = super_match.group(1)

    try:
        renamed_click = _rename_method_header(
            click_block,
            "onClick",
            "()V",
            INFO_HELPER,
            _private_flags(click_flags),
        )
    except ValueError as exc:
        raise injector.InjectError(str(exc)) from exc
    text = text[:click_start] + renamed_click + text[click_end:]

    has_native_long = False
    long_flags: list[str] = []
    long_headers = re.findall(
        r"(?m)^\.method\s+[^\n]*\s+onLongClick\(\)Z\s*$",
        text,
    )
    if len(long_headers) > 1:
        raise injector.InjectError(
            "Expected at most one onLongClick()Z in " + descriptor
        )
    if long_headers:
        long_start, long_end, long_block = injector.method_block(
            text, "onLongClick", "()Z"
        )
        try:
            long_flags = _method_flags(long_block, "onLongClick", "()Z")
            renamed_long = _rename_method_header(
                long_block,
                "onLongClick",
                "()Z",
                LONG_HELPER,
                _private_flags(long_flags),
            )
        except ValueError as exc:
            raise injector.InjectError(str(exc)) from exc
        text = text[:long_start] + renamed_long + text[long_end:]
        has_native_long = True

    click_access = _visible_flags(click_flags)
    long_access = _visible_flags(long_flags) if has_native_long else "protected"

    click_lines = [
        f".method {click_access} onClick()V",
        "    .locals 1",
        "",
    ]
    for index, (buff_type, method) in enumerate(handlers):
        next_label = f":smm_buff_click_next_{index}"
        click_lines.extend([
            f"    iget-object v0, p0, {field_owner}->{field_name}:{buff_descriptor}",
            f"    instance-of v0, v0, {buff_type}",
            f"    if-eqz v0, {next_label}",
            "",
            f"    iget-object v0, p0, {field_owner}->{field_name}:{buff_descriptor}",
            f"    check-cast v0, {buff_type}",
            f"    invoke-virtual {{v0}}, {buff_type}->{method}()V",
            "    return-void",
            "",
            f"    {next_label}",
        ])
    click_lines.extend([
        f"    invoke-direct {{p0}}, {descriptor}->{INFO_HELPER}()V",
        "    return-void",
        ".end method",
    ])
    click_wrapper = "\n".join(click_lines)

    if has_native_long:
        native_long = (
            f"    invoke-direct {{p0}}, {descriptor}->{LONG_HELPER}()Z\n"
            "    move-result v0\n"
            "    return v0\n"
        )
    else:
        native_long = (
            f"    invoke-super {{p0}}, {super_descriptor}->onLongClick()Z\n"
            "    move-result v0\n"
            "    return v0\n"
        )

    long_lines = [
        f".method {long_access} onLongClick()Z",
        "    .locals 1",
        "",
    ]
    for index, (buff_type, _method) in enumerate(handlers):
        next_label = f":smm_buff_long_next_{index}"
        long_lines.extend([
            f"    iget-object v0, p0, {field_owner}->{field_name}:{buff_descriptor}",
            f"    instance-of v0, v0, {buff_type}",
            f"    if-eqz v0, {next_label}",
            "",
            f"    invoke-direct {{p0}}, {descriptor}->{INFO_HELPER}()V",
            "    const/4 v0, 0x1",
            "    return v0",
            "",
            f"    {next_label}",
        ])
    long_lines.append(native_long.rstrip())
    long_lines.append(".end method")
    long_wrapper = "\n".join(long_lines)

    names = ", ".join(buff.rsplit("/", 1)[-1].rstrip(";") for buff, _ in handlers)
    injector.log(
        "SMM BuffIndicator click bridge: "
        + names
        + "; "
        + ("preserved native onLongClick" if has_native_long else "added long-click override")
    )
    return text.rstrip() + "\n\n" + click_wrapper + "\n\n" + long_wrapper + "\n"
