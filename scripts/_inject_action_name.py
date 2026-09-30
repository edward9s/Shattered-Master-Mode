#!/usr/bin/env python3
"""Shared legacy WndUseItem -> Item.actionName bridge for APK injection."""
from __future__ import annotations

import re

HELPER = "smmActionName"
MESSAGE_PROTO = (
    "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
    "Ljava/lang/String;"
)


def _messages_get_re(messages: str) -> re.Pattern[str]:
    return re.compile(
        r"(?m)^(?P<indent>\s*)invoke-static(?P<range>/range)?\s+"
        r"\{(?P<args>[^}]+)\},\s*"
        + re.escape(messages)
        + r"->get"
        + re.escape(MESSAGE_PROTO)
        + r"\s*(?:#.*)?$"
    )


def _constructor_blocks(text: str) -> list[tuple[int, int, str]]:
    header_re = re.compile(
        r"(?m)^\.method\s+[^\n]*\s+<init>\([^)]*\)V\s*$"
    )
    result: list[tuple[int, int, str]] = []
    for match in header_re.finditer(text):
        end = text.find("\n.end method", match.end())
        if end < 0:
            raise ValueError("Unterminated WndUseItem constructor")
        end += len("\n.end method")
        result.append((match.start(), end, text[match.start():end]))
    return result


def _legacy_constructor_blocks(
    text: str,
    messages: str,
) -> list[tuple[int, int, str]]:
    call_re = _messages_get_re(messages)
    ac_re = re.compile(
        r'(?m)^\s*const-string(?:/jumbo)?\s+[vp]\d+,\s*"ac_"\s*(?:#.*)?$'
    )
    return [
        block
        for block in _constructor_blocks(text)
        if ac_re.search(block[2]) and call_re.search(block[2])
    ]


def find_target(injector, target_index, game_prefix):
    wnd = injector.game_descriptor(game_prefix, "windows/WndUseItem")
    item = injector.game_descriptor(game_prefix, "items/Item")
    hero = injector.game_descriptor(game_prefix, "actors/hero/Hero")
    messages = injector.game_descriptor(game_prefix, "messages/Messages")

    target = target_index.get(wnd)
    if target is None:
        raise injector.InjectError("Target WndUseItem class is missing")

    try:
        legacy = _legacy_constructor_blocks(target.text, messages)
    except ValueError as exc:
        raise injector.InjectError(str(exc)) from exc
    if not legacy:
        return None

    action_proto = f"(Ljava/lang/String;{hero})Ljava/lang/String;"
    resolved = injector.resolve_member(
        target_index, item, ("actionName", action_proto), method=True
    )
    if resolved is None or "public" not in resolved[1]:
        raise injector.InjectError(
            "Legacy WndUseItem bypasses Item.actionName(), but target Item "
            "does not expose public actionName(String, Hero)"
        )

    return wnd, target.text


def patch(injector, text: str, descriptor: str, game_prefix: str) -> str:
    item = injector.game_descriptor(game_prefix, "items/Item")
    hero = injector.game_descriptor(game_prefix, "actors/hero/Hero")
    dungeon = injector.game_descriptor(game_prefix, "Dungeon")
    messages = injector.game_descriptor(game_prefix, "messages/Messages")

    if re.search(
        r"(?m)^\.method\b[^\n]*\s+" + re.escape(HELPER)
        + re.escape(MESSAGE_PROTO) + r"\s*$",
        text,
    ):
        raise injector.InjectError(
            "WndUseItem already contains SMM action-name compatibility bridge"
        )

    try:
        legacy = _legacy_constructor_blocks(text, messages)
    except ValueError as exc:
        raise injector.InjectError(str(exc)) from exc
    if not legacy:
        raise injector.InjectError(
            "Legacy WndUseItem bridge was requested, but no ac_* "
            "Messages.get constructor path was found"
        )

    invoke_re = _messages_get_re(messages)

    def repl(match: re.Match[str]) -> str:
        return (
            f"{match.group('indent')}invoke-static{match.group('range') or ''} "
            f"{{{match.group('args')}}}, {descriptor}->{HELPER}{MESSAGE_PROTO}"
        )

    pieces: list[str] = []
    cursor = 0
    count = 0
    for start, end, block in legacy:
        pieces.append(text[cursor:start])
        patched_block, block_count = invoke_re.subn(repl, block)
        if block_count == 0:
            raise injector.InjectError(
                "Legacy WndUseItem constructor lost its Messages.get call"
            )
        pieces.append(patched_block)
        count += block_count
        cursor = end
    pieces.append(text[cursor:])
    patched = "".join(pieces)

    helper = (
        f".method private static {HELPER}{MESSAGE_PROTO}\n"
        "    .locals 2\n\n"
        "    if-eqz p1, :smm_action_name_fallback\n"
        f"    instance-of v0, p0, {item}\n"
        "    if-eqz v0, :smm_action_name_fallback\n"
        '    const-string v0, "ac_"\n'
        "    invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :smm_action_name_fallback\n\n"
        f"    check-cast p0, {item}\n"
        "    const/4 v0, 0x3\n"
        "    invoke-virtual {p1, v0}, Ljava/lang/String;->substring(I)Ljava/lang/String;\n"
        "    move-result-object v0\n"
        f"    sget-object v1, {dungeon}->hero:{hero}\n"
        f"    invoke-virtual {{p0, v0, v1}}, {item}->actionName"
        f"(Ljava/lang/String;{hero})Ljava/lang/String;\n"
        "    move-result-object v0\n"
        "    return-object v0\n\n"
        "    :smm_action_name_fallback\n"
        f"    invoke-static {{p0, p1, p2}}, {messages}->get{MESSAGE_PROTO}\n"
        "    move-result-object v0\n"
        "    return-object v0\n"
        ".end method\n"
    )

    injector.log(
        f"WndUseItem action labels: bridged {count} legacy Messages.get call(s) "
        "through Item.actionName()"
    )
    return patched.rstrip() + "\n\n" + helper
