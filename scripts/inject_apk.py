#!/usr/bin/env python3
"""Inject the compiled SMM payload into an SPD-derived APK."""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence

import _inject_apk_core as injector


FULL_SMM_PREFIX = "Lcom/spd/mod/"
MOD_ANKH_STORE_PREFIX = injector.MOD_ANKH_STORE[:-1]
DEFAULT_DONOR = Path(__file__).resolve().with_name("smm-inject-donor.apk")

ABI_DIRECT = "direct"
ABI_REWRITE = "rewrite"
ABI_STRUCTURAL = "structural"
ABI_RUNTIME = "runtime"
ABI_UNSUPPORTED = "unsupported"


@dataclass
class AbiCapability:
    key: str
    strategy: str
    detail: str
    required: bool = True
    data: dict[str, str] = field(default_factory=dict)

    @property
    def compatible(self) -> bool:
        return self.strategy != ABI_UNSUPPORTED


@dataclass
class AbiProfile:
    capabilities: dict[str, AbiCapability] = field(default_factory=dict)

    def add(self, capability: AbiCapability) -> None:
        self.capabilities[capability.key] = capability

    def get(self, key: str) -> AbiCapability:
        capability = self.capabilities.get(key)
        if capability is None:
            raise injector.InjectError(f"Target ABI profile has no capability: {key}")
        return capability

    def log(self) -> None:
        injector.step("Target ABI profile")
        for capability in self.capabilities.values():
            injector.log(
                f"  {capability.key}: {capability.strategy} - {capability.detail}"
            )
        counts: dict[str, int] = {}
        for capability in self.capabilities.values():
            counts[capability.strategy] = counts.get(capability.strategy, 0) + 1
        injector.log(
            "Target ABI strategies: "
            + ", ".join(
                f"{count} {strategy}"
                for strategy, count in sorted(counts.items())
            )
        )

    def require_compatible(self) -> None:
        failures = [
            capability
            for capability in self.capabilities.values()
            if capability.required and not capability.compatible
        ]
        if failures:
            raise injector.InjectError(
                "Target ABI is unsupported: "
                + "; ".join(
                    f"{capability.key}: {capability.detail}"
                    for capability in failures
                )
            )


injector.MOD_ITEM_DESCRIPTOR_PREFIX = FULL_SMM_PREFIX
injector.TARGET_API_PREFIXES = injector.TARGET_API_PREFIXES + (
    injector.MOD_ANKH,
    MOD_ANKH_STORE_PREFIX,
)

_original_build_debug_payload = injector.build_debug_payload
_original_payload_compatibility_errors = injector.payload_compatibility_errors
_original_find_class = injector.find_class
_original_detect_target_game_prefix = injector.detect_target_game_prefix
_full_donor_payload: dict[str, injector.SmaliClass] = {}
_current_abi_profile: AbiProfile | None = None


def _has_method(
    index: dict[str, injector.SmaliClass],
    owner: str,
    name: str,
    proto: str,
) -> bool:
    return injector.resolve_member(index, owner, (name, proto), method=True) is not None


def _unique_declared_field(
    item: injector.SmaliClass | None,
    typ: str,
    *,
    require_static: bool,
) -> str | None:
    if item is None:
        return None
    names = [
        name
        for (name, field_type), flags in item.fields.items()
        if field_type == typ and (("static" in flags) == require_static)
    ]
    return names[0] if len(names) == 1 else None


def _probe_item_set_current(
    target_index: dict[str, injector.SmaliClass],
    game_prefix: str,
) -> AbiCapability:
    item_descriptor = injector.game_descriptor(game_prefix, "items/Item")
    hero_descriptor = injector.game_descriptor(game_prefix, "actors/hero/Hero")
    proto = f"({hero_descriptor})V"

    if _has_method(target_index, item_descriptor, "setCurrent", proto):
        return AbiCapability(
            "item.setCurrent",
            ABI_DIRECT,
            "Item.setCurrent(Hero) is available",
        )

    item = target_index.get(item_descriptor)
    if item is None:
        return AbiCapability(
            "item.setCurrent",
            ABI_UNSUPPORTED,
            "Item class is missing",
        )

    named_user = item.fields.get(("curUser", hero_descriptor))
    named_item = item.fields.get(("curItem", item_descriptor))
    if (
        named_user is not None
        and named_item is not None
        and "static" in named_user
        and "static" in named_item
    ):
        return AbiCapability(
            "item.setCurrent",
            ABI_REWRITE,
            "use Item.curUser and Item.curItem",
            data={"user_field": "curUser", "item_field": "curItem"},
        )

    user_field = _unique_declared_field(
        item,
        hero_descriptor,
        require_static=True,
    )
    item_field = _unique_declared_field(
        item,
        item_descriptor,
        require_static=True,
    )
    if user_field is not None and item_field is not None:
        return AbiCapability(
            "item.setCurrent",
            ABI_STRUCTURAL,
            "use unique static Hero/Item state fields",
            data={"user_field": user_field, "item_field": item_field},
        )

    return AbiCapability(
        "item.setCurrent",
        ABI_UNSUPPORTED,
        "no method or unambiguous static Hero/Item state fields",
    )


def _probe_wndgame_menu_hook(
    target_index: dict[str, injector.SmaliClass],
    game_prefix: str,
) -> AbiCapability:
    wnd_descriptor = injector.game_descriptor(game_prefix, "windows/WndGame")
    red_button = injector.game_descriptor(game_prefix, "ui/RedButton")
    wnd = target_index.get(wnd_descriptor)
    if wnd is None:
        return AbiCapability(
            "wndgame.menuHook",
            ABI_UNSUPPORTED,
            "WndGame class is missing",
        )

    proto = f"({red_button})V"
    candidates = [
        name
        for (name, method_proto), flags in wnd.methods.items()
        if method_proto == proto and "static" not in flags
    ]
    if len(candidates) == 1:
        return AbiCapability(
            "wndgame.menuHook",
            ABI_RUNTIME,
            "one non-static void(RedButton) method is available",
            data={"method": candidates[0]},
        )

    return AbiCapability(
        "wndgame.menuHook",
        ABI_UNSUPPORTED,
        f"expected one non-static void(RedButton) method, found {len(candidates)}",
    )


def _probe_duelist_combo(
    target_index: dict[str, injector.SmaliClass],
    game_prefix: str,
) -> AbiCapability:
    tracker_descriptor = injector.game_descriptor(
        game_prefix,
        "items/weapon/melee/Sai$ComboStrikeTracker",
    )
    tracker = target_index.get(tracker_descriptor)
    if tracker is None:
        return AbiCapability(
            "duelist.comboHit",
            ABI_UNSUPPORTED,
            "Sai.ComboStrikeTracker class is missing",
        )

    if ("addHit", "()V") in tracker.methods:
        return AbiCapability(
            "duelist.comboHit",
            ABI_DIRECT,
            "ComboStrikeTracker.addHit() is declared",
        )

    # ModCombatCompat can call an R8-renamed addHit() when the tracker has one
    # unambiguous declared instance ()V method. Constructors are not Methods
    # and therefore must not be counted here.
    noarg_void = [
        name
        for (name, proto), flags in tracker.methods.items()
        if proto == "()V"
        and "static" not in flags
        and not name.startswith("<")
    ]
    if len(noarg_void) == 1:
        return AbiCapability(
            "duelist.comboHit",
            ABI_RUNTIME,
            "unique declared non-static ()V method can be invoked independent of its name",
            data={"method": noarg_void[0]},
        )

    int_field = _unique_declared_field(tracker, "I", require_static=False)
    float_field = _unique_declared_field(tracker, "F", require_static=False)
    if int_field is not None and float_field is not None:
        return AbiCapability(
            "duelist.comboHit",
            ABI_STRUCTURAL,
            "runtime adapter can identify the unique int counter and float timer",
            data={"hits_field": int_field, "time_field": float_field},
        )

    instance_shape = sorted(
        f"{name}:{typ}"
        for (name, typ), flags in tracker.fields.items()
        if "static" not in flags
    )
    return AbiCapability(
        "duelist.comboHit",
        ABI_UNSUPPORTED,
        "no exact/unique runtime method and tracker state is ambiguous"
        + (" (instance fields: " + ", ".join(instance_shape) + ")" if instance_shape else ""),
    )


ABI_PROBES: tuple[
    Callable[[dict[str, injector.SmaliClass], str], AbiCapability], ...
] = (
    _probe_item_set_current,
    _probe_wndgame_menu_hook,
    _probe_duelist_combo,
)


def detect_target_abi(
    target_index: dict[str, injector.SmaliClass],
    game_prefix: str,
) -> AbiProfile:
    profile = AbiProfile()
    for probe in ABI_PROBES:
        profile.add(probe(target_index, game_prefix))
    return profile


def detect_target_game_prefix(
    target_index: dict[str, injector.SmaliClass],
) -> str:
    global _current_abi_profile
    game_prefix = _original_detect_target_game_prefix(target_index)
    _current_abi_profile = detect_target_abi(target_index, game_prefix)
    _current_abi_profile.log()
    _current_abi_profile.require_compatible()
    return game_prefix


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
    if _current_abi_profile is None:
        raise injector.InjectError("Target ABI profile was not initialized")

    capability = _current_abi_profile.get("item.setCurrent")
    if capability.strategy == ABI_DIRECT:
        return text, []
    if capability.strategy not in {ABI_REWRITE, ABI_STRUCTURAL}:
        raise injector.InjectError(
            "Item.setCurrent(Hero) has no compatible target ABI strategy"
        )

    pattern = re.compile(
        r"(?m)^(?P<indent>\s*)invoke-virtual(?P<range>/range)?\s+"
        r"\{(?P<args>[^}]*)\},\s*"
        r"L[^;\s]+;->setCurrent\("
        + re.escape(hero_descriptor)
        + r"\)V\s*$"
    )
    matches = list(pattern.finditer(text))
    if not matches:
        return text, []

    user_field = capability.data["user_field"]
    item_field = capability.data["item_field"]

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
            f"{ind}sput-object {hero_reg}, {item_descriptor}->{user_field}:{hero_descriptor}\n"
            f"{ind}sput-object {this_reg}, {item_descriptor}->{item_field}:{item_descriptor}"
        )

    text2, count = pattern.subn(repl, text)
    return text2, [
        f"adapted {count} Item.setCurrent(Hero) call(s) using {capability.strategy} ABI strategy"
    ]


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


injector.detect_target_game_prefix = detect_target_game_prefix
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
