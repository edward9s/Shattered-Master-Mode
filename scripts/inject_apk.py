#!/usr/bin/env python3
"""Inject the compiled SMM payload into an SPD-derived APK."""
from __future__ import annotations

import copy
import errno
import platform
import re
import shutil
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence

import _inject_apk_core as injector


_original_toolchain_ensure_java = injector.Toolchain.ensure_java
_original_toolchain_ensure_android_tools = injector.Toolchain.ensure_android_tools


def _is_termux() -> bool:
    return platform.system().lower() == "android" and shutil.which("pkg") is not None


def _install_termux_packages(toolchain: injector.Toolchain, packages: list[str]) -> None:
    if not packages:
        return
    if toolchain.offline:
        raise injector.InjectError(
            "Missing Termux dependencies ("
            + ", ".join(packages)
            + ") and --offline is enabled"
        )
    pkg = shutil.which("pkg")
    if pkg is None:
        raise injector.InjectError("Termux package manager 'pkg' was not found")
    injector.step("Installing minimal Termux dependencies")
    injector.run([pkg, "install", "-y", *packages])


def _ensure_java(self: injector.Toolchain) -> injector.JavaTools:
    if not _is_termux():
        return _original_toolchain_ensure_java(self)

    java = shutil.which("java")
    keytool = shutil.which("keytool")
    if java and keytool:
        return injector.JavaTools(Path(java), Path(keytool))

    _install_termux_packages(self, ["openjdk-21"])
    java = shutil.which("java")
    keytool = shutil.which("keytool")
    if java and keytool:
        return injector.JavaTools(Path(java), Path(keytool))
    raise injector.InjectError(
        "Termux installed openjdk-21 but java/keytool are still unavailable on PATH"
    )


def _ensure_android_tools(self: injector.Toolchain) -> injector.AndroidTools:
    if not _is_termux():
        return _original_toolchain_ensure_android_tools(self)

    local = self._local_android_tools()
    if local:
        return local

    zipalign = shutil.which("zipalign")
    apksigner = shutil.which("apksigner")
    packages: list[str] = []
    if zipalign is None:
        packages.append("aapt")
    if apksigner is None:
        packages.append("apksigner")
    _install_termux_packages(self, packages)

    zipalign = shutil.which("zipalign")
    apksigner = shutil.which("apksigner")
    if not zipalign or not apksigner:
        missing = []
        if not zipalign:
            missing.append("zipalign")
        if not apksigner:
            missing.append("apksigner")
        raise injector.InjectError(
            "Termux dependencies were installed but these tools are still unavailable: "
            + ", ".join(missing)
        )
    return injector.AndroidTools(Path(zipalign), Path(apksigner))


if _is_termux():
    injector.Toolchain.ensure_java = _ensure_java
    injector.Toolchain.ensure_android_tools = _ensure_android_tools


FULL_SMM_PREFIX = "Lcom/spd/mod/"
DEFAULT_DONOR = Path(__file__).resolve().with_name("smm-inject-donor.apk")
DEFAULT_KEYSTORE = DEFAULT_DONOR.with_name("smm-inject.keystore")


def ensure_inject_keystore(java: injector.JavaTools, _cache: Path) -> Path:
    if DEFAULT_KEYSTORE.is_file():
        return DEFAULT_KEYSTORE

    injector.step("Creating persistent signing key beside donor APK")
    try:
        injector.run([
            java.keytool,
            "-genkeypair",
            "-v",
            "-keystore",
            DEFAULT_KEYSTORE,
            "-storepass",
            "android",
            "-alias",
            "androiddebugkey",
            "-keypass",
            "android",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=SMM Injector,O=Android,C=US",
        ])
    except OSError as exc:
        raise injector.InjectError(
            f"Unable to create signing key beside donor APK: {DEFAULT_KEYSTORE}\n{exc}"
        ) from exc
    injector.log(f"Signing key: {DEFAULT_KEYSTORE}")
    return DEFAULT_KEYSTORE


ABI_DIRECT = "direct"
ABI_REWRITE = "rewrite"
ABI_STRUCTURAL = "structural"
ABI_RUNTIME = "runtime"
ABI_UNSUPPORTED = "unsupported"

PORTABLE_ZIPALIGN_EXTRA_ID = 0xA11E
PORTABLE_ZIPALIGN_DEFAULT = 4
PORTABLE_ZIPALIGN_SO = 4096


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
injector.TARGET_API_PREFIXES = injector.TARGET_API_PREFIXES + (injector.MOD_ANKH,)
injector.LOOT_PAYLOAD_FAMILIES = tuple(
    family
    for family in injector.LOOT_PAYLOAD_FAMILIES
    if family[0]
    not in {
        "Lcom/spd/mod/mechanics/ModLootBuff;",
        "Lcom/spd/mod/journal/ModLootBuffOverlay;",
    }
) + (
    ("Lcom/spd/mod/mechanics/ModLastStand;", "Lcom/spd/mod/mechanics/ModLastStand$"),
    ("Lcom/spd/mod/journal/ModLastStandOverlay;", "Lcom/spd/mod/journal/ModLastStandOverlay$"),
)
injector.LOOT_REQUIRED_ROOTS = tuple(
    root for root, _ in injector.LOOT_PAYLOAD_FAMILIES
)

_original_build_debug_payload = injector.build_debug_payload
_original_payload_compatibility_errors = injector.payload_compatibility_errors
_original_find_class = injector.find_class
_original_detect_target_game_prefix = injector.detect_target_game_prefix
_original_patch_dungeon = injector.patch_dungeon
_original_compile_smali = injector.compile_smali
_full_donor_payload: dict[str, injector.SmaliClass] = {}
_current_abi_profile: AbiProfile | None = None
_current_game_prefix: str | None = None
_pending_char_overlay: tuple[str, str] | None = None
_ankh_only_mode = False


def _member_accessible_from_modankh(flags: frozenset[str]) -> bool:
    return "public" in flags or "protected" in flags


def _resolve_accessible_method(
    index: dict[str, injector.SmaliClass],
    owner: str,
    name: str,
    proto: str,
):
    resolved = injector.resolve_member(index, owner, (name, proto), method=True)
    if resolved is None:
        return None
    resolved_owner, flags = resolved
    if not _member_accessible_from_modankh(flags):
        return None
    return resolved_owner, flags


def _unique_declared_field(
    item: injector.SmaliClass | None,
    typ: str,
    *,
    require_static: bool,
    require_modankh_access: bool = False,
) -> str | None:
    if item is None:
        return None
    names = [
        name
        for (name, field_type), flags in item.fields.items()
        if field_type == typ
        and (("static" in flags) == require_static)
        and (
            not require_modankh_access
            or _member_accessible_from_modankh(flags)
        )
    ]
    return names[0] if len(names) == 1 else None


def _probe_item_set_current(
    target_index: dict[str, injector.SmaliClass],
    game_prefix: str,
) -> AbiCapability:
    item_descriptor = injector.game_descriptor(game_prefix, "items/Item")
    hero_descriptor = injector.game_descriptor(game_prefix, "actors/hero/Hero")
    proto = f"({hero_descriptor})V"

    if _resolve_accessible_method(
        target_index,
        item_descriptor,
        "setCurrent",
        proto,
    ) is not None:
        return AbiCapability(
            "item.setCurrent",
            ABI_DIRECT,
            "accessible Item.setCurrent(Hero) is available",
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
        and _member_accessible_from_modankh(named_user)
        and _member_accessible_from_modankh(named_item)
    ):
        return AbiCapability(
            "item.setCurrent",
            ABI_REWRITE,
            "use accessible Item.curUser and Item.curItem",
            data={"user_field": "curUser", "item_field": "curItem"},
        )

    user_field = _unique_declared_field(
        item,
        hero_descriptor,
        require_static=True,
        require_modankh_access=True,
    )
    item_field = _unique_declared_field(
        item,
        item_descriptor,
        require_static=True,
        require_modankh_access=True,
    )
    if user_field is not None and item_field is not None:
        return AbiCapability(
            "item.setCurrent",
            ABI_STRUCTURAL,
            "use unique accessible static Hero/Item state fields",
            data={"user_field": user_field, "item_field": item_field},
        )

    return AbiCapability(
        "item.setCurrent",
        ABI_UNSUPPORTED,
        "no accessible method or unambiguous accessible static Hero/Item state fields",
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
    char_descriptor = injector.game_descriptor(game_prefix, "actors/Char")
    tracker = target_index.get(tracker_descriptor)
    if tracker is None:
        return AbiCapability(
            "duelist.comboHit",
            ABI_RUNTIME,
            "Sai.ComboStrikeTracker is absent; combo bookkeeping will be skipped",
        )

    supported_protos = ("()V", f"({char_descriptor})V")
    exact = [
        (name, proto)
        for (name, proto), flags in tracker.methods.items()
        if name == "addHit"
        and proto in supported_protos
        and "static" not in flags
    ]
    if len(exact) == 1:
        name, proto = exact[0]
        return AbiCapability(
            "duelist.comboHit",
            ABI_DIRECT,
            f"ComboStrikeTracker.{name}{proto} is declared",
            data={"method": name, "proto": proto},
        )

    candidates = [
        (name, proto)
        for (name, proto), flags in tracker.methods.items()
        if proto in supported_protos
        and "static" not in flags
        and not name.startswith("<")
    ]
    if len(candidates) == 1:
        name, proto = candidates[0]
        return AbiCapability(
            "duelist.comboHit",
            ABI_RUNTIME,
            f"unique compatible method {name}{proto} can be invoked independent of its name",
            data={"method": name, "proto": proto},
        )

    int_field = _unique_declared_field(tracker, "I", require_static=False)
    float_field = _unique_declared_field(tracker, "F", require_static=False)
    if int_field is not None and float_field is not None:
        return AbiCapability(
            "duelist.comboHit",
            ABI_STRUCTURAL,
            "old tracker shape has one int counter and one float timer",
            data={"hits_field": int_field, "time_field": float_field},
        )

    instance_shape = sorted(
        f"{name}:{typ}"
        for (name, typ), flags in tracker.fields.items()
        if "static" not in flags
    )
    method_shape = sorted(
        f"{name}{proto}"
        for (name, proto), flags in tracker.methods.items()
        if "static" not in flags and not name.startswith("<")
    )
    details = []
    if method_shape:
        details.append("methods: " + ", ".join(method_shape))
    if instance_shape:
        details.append("fields: " + ", ".join(instance_shape))
    return AbiCapability(
        "duelist.comboHit",
        ABI_UNSUPPORTED,
        "no exact/unique semantic method and tracker state is ambiguous"
        + (" (" + "; ".join(details) + ")" if details else ""),
    )


def _probe_char_attack_hook(
    target_index: dict[str, injector.SmaliClass],
    game_prefix: str,
) -> AbiCapability:
    char_descriptor = injector.game_descriptor(game_prefix, "actors/Char")
    char_class = target_index.get(char_descriptor)
    if char_class is None:
        return AbiCapability(
            "char.incomingAttackHook",
            ABI_UNSUPPORTED,
            "Char class is missing",
        )

    modern_proto = f"({char_descriptor}FFF)Z"
    flags = char_class.methods.get(("attack", modern_proto))
    if flags is not None and "static" not in flags:
        return AbiCapability(
            "char.incomingAttackHook",
            ABI_DIRECT,
            "Char.attack(Char,float,float,float) is available for pre-resolution hook",
            data={"proto": modern_proto},
        )

    legacy_proto = f"({char_descriptor})Z"
    flags = char_class.methods.get(("attack", legacy_proto))
    if flags is not None and "static" not in flags:
        return AbiCapability(
            "char.incomingAttackHook",
            ABI_DIRECT,
            "legacy Char.attack(Char) is available for pre-resolution hook",
            data={"proto": legacy_proto},
        )

    return AbiCapability(
        "char.incomingAttackHook",
        ABI_UNSUPPORTED,
        "supported Char.attack ABI variants are missing or static: "
        "attack(Char,float,float,float), attack(Char)",
    )


ABI_PROBES: tuple[
    Callable[[dict[str, injector.SmaliClass], str], AbiCapability], ...
] = (
    _probe_item_set_current,
    _probe_wndgame_menu_hook,
    _probe_duelist_combo,
    _probe_char_attack_hook,
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
    global _current_abi_profile, _current_game_prefix, _pending_char_overlay
    game_prefix = _original_detect_target_game_prefix(target_index)
    _current_game_prefix = game_prefix
    _pending_char_overlay = None
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

    if _current_game_prefix is None:
        raise injector.InjectError("Target game package was not initialized")
    donor_total = _full_donor_payload.get(injector.MOD_PARRY_RIPOSTE)
    if donor_total is None:
        raise injector.InjectError("SMM donor is missing ModParryRiposte")
    char_descriptor = injector.game_descriptor(_current_game_prefix, "actors/Char")
    rebased_total = injector.SmaliClass.from_text(
        donor_total.path,
        injector.rebase_smali_text(donor_total.text, _current_game_prefix),
    )
    hook_flags = rebased_total.methods.get(
        ("onIncomingAttack", f"({char_descriptor}{char_descriptor})V")
    )
    if hook_flags is None or not {"public", "static"}.issubset(hook_flags):
        raise injector.InjectError(
            "SMM donor ModParryRiposte lacks public static "
            "onIncomingAttack(Char, Char); rebuild donor from current source"
        )

    return _original_build_debug_payload(donor_index, target_index)


def build_no_legacy_modankh_store_payload(
    donor_index: dict[str, injector.SmaliClass],
) -> dict[str, injector.SmaliClass]:
    """ModAnkh now uses the normal full SMM payload directly; no dedicated store family exists."""
    return {}


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
    global _pending_char_overlay
    if descriptor.endswith("/Dungeon;"):
        game_prefix = descriptor[:-len("Dungeon;")]
        char_descriptor = game_prefix + "actors/Char;"
        _, char_path = _original_find_class(root, char_descriptor)
        _pending_char_overlay = (
            char_descriptor,
            char_path.read_text(encoding="utf-8", errors="replace"),
        )
        wnd_game = game_prefix + "windows/WndGame;"
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


def _first_smali_instruction(block: str) -> tuple[int, str]:
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

        indent = line[:len(line) - len(line.lstrip())]
        return offset, indent

    raise injector.InjectError("Char.attack has no executable instruction")


def patch_char_attack(
    text: str,
    char_descriptor: str,
    proto: str | None = None,
) -> str:
    if proto is None:
        proto = f"({char_descriptor}FFF)Z"
    start, end, block = injector.method_block(text, "attack", proto)
    hook = (
        "Lcom/spd/mod/mechanics/ModParryRiposte;->onIncomingAttack("
        f"{char_descriptor}{char_descriptor})V"
    )
    if hook in block:
        raise injector.InjectError("Char.attack already contains SMM incoming-attack hook")

    insert_at, indent = _first_smali_instruction(block)
    injected = (
        f"{indent}# SMM independent Riposte incoming-attack hook\n"
        f"{indent}invoke-static/range {{p0 .. p1}}, {hook}\n\n"
    )
    patched = block[:insert_at] + injected + block[insert_at:]
    return text[:start] + patched + text[end:]


def compile_smali_with_char_hook(
    java: Path,
    smali_jar: Path,
    directory: Path,
    output: Path,
    api: int,
) -> None:
    global _pending_char_overlay
    if _pending_char_overlay is None:
        raise injector.InjectError("Char.attack overlay source was not captured")
    if _current_abi_profile is None:
        raise injector.InjectError("Target ABI profile was not initialized")

    capability = _current_abi_profile.get("char.incomingAttackHook")
    proto = capability.data.get("proto")
    if not proto:
        raise injector.InjectError("Target Char.attack ABI profile did not preserve its descriptor")

    char_descriptor, original_char = _pending_char_overlay
    patched_char = patch_char_attack(original_char, char_descriptor, proto)
    char_output = directory / Path(char_descriptor[1:-1] + ".smali")
    if char_output.exists():
        raise injector.InjectError(
            f"Overlay already contains target Char class: {char_descriptor}"
        )
    char_output.parent.mkdir(parents=True, exist_ok=True)
    char_output.write_text(patched_char, encoding="utf-8")
    injector.log(f"Char.attack incoming-attack hook ({proto}): OK")

    try:
        _original_compile_smali(java, smali_jar, directory, output, api)
    finally:
        _pending_char_overlay = None


def _host_elf_machines() -> set[int] | None:
    machine = platform.machine().lower()
    if machine in {"x86_64", "amd64"}:
        return {62}
    if machine in {"aarch64", "arm64"}:
        return {183}
    if machine in {"arm", "armv7l", "armv8l"}:
        return {40}
    if machine in {"x86", "i386", "i486", "i586", "i686"}:
        return {3}
    return None


def _elf_machine(path: Path) -> int | None:
    try:
        header = path.read_bytes()[:20]
    except OSError:
        return None
    if len(header) < 20 or header[:4] != b"\x7fELF":
        return None
    if header[5] == 1:
        endian = "<"
    elif header[5] == 2:
        endian = ">"
    else:
        return None
    return struct.unpack(endian + "H", header[18:20])[0]


def _native_tool_matches_host(path: Path) -> bool:
    machine = _elf_machine(path)
    accepted = _host_elf_machines()
    return machine is None or accepted is None or machine in accepted


def _alignment_for_entry(info: zipfile.ZipInfo) -> int:
    if info.compress_type != zipfile.ZIP_STORED:
        return 1
    if info.filename.lower().endswith(".so"):
        return PORTABLE_ZIPALIGN_SO
    return PORTABLE_ZIPALIGN_DEFAULT


def _normalize_zip_extra(extra: bytes, filename: str) -> bytes:
    """Preserve complete ZIP extra records while dropping 1-3 trailing padding bytes."""
    offset = 0
    length = len(extra)
    while offset + 4 <= length:
        _field_id, payload_size = struct.unpack_from("<HH", extra, offset)
        end = offset + 4 + payload_size
        if end > length:
            raise injector.InjectError(
                f"Malformed ZIP extra field while aligning {filename}"
            )
        offset = end
    return extra[:offset]


def _add_alignment_extra(
    info: zipfile.ZipInfo,
    header_offset: int,
    alignment: int,
) -> None:
    if alignment <= 1:
        return
    base_data_offset = header_offset + len(info.FileHeader(zip64=False))
    needed = (-base_data_offset) % alignment
    if needed == 0:
        return

    # A ZIP extra-field record needs a four-byte id/length header. If the exact
    # padding is smaller than that, add one complete alignment unit; modulo is
    # unchanged and the resulting extra record remains structurally valid.
    total = needed if needed >= 4 else needed + alignment
    payload_size = total - 4
    padding = (
        struct.pack("<HH", PORTABLE_ZIPALIGN_EXTRA_ID, payload_size)
        + (b"\0" * payload_size)
    )
    if len(info.extra) + len(padding) > 0xFFFF:
        raise injector.InjectError(
            f"Cannot align ZIP entry with oversized extra field: {info.filename}"
        )
    info.extra += padding


def _verify_portable_zip_alignment(path: Path) -> None:
    with zipfile.ZipFile(path, "r") as zf, path.open("rb") as raw:
        bad = zf.testzip()
        if bad:
            raise injector.InjectError(f"Portable zipalign produced corrupt entry: {bad}")
        for info in zf.infolist():
            alignment = _alignment_for_entry(info)
            if alignment <= 1:
                continue
            raw.seek(info.header_offset + 26)
            lengths = raw.read(4)
            if len(lengths) != 4:
                raise injector.InjectError(
                    f"Portable zipalign cannot read local header: {info.filename}"
                )
            name_len, extra_len = struct.unpack("<HH", lengths)
            data_offset = info.header_offset + 30 + name_len + extra_len
            if data_offset % alignment:
                raise injector.InjectError(
                    f"Portable zipalign failed for {info.filename}: "
                    f"offset {data_offset} is not {alignment}-byte aligned"
                )


def _portable_zipalign(source: Path, output: Path) -> None:
    output.unlink(missing_ok=True)
    with zipfile.ZipFile(source, "r") as zin, zipfile.ZipFile(
        output,
        "w",
        allowZip64=True,
    ) as zout:
        zout.comment = zin.comment
        for source_info in zin.infolist():
            info = copy.copy(source_info)
            # Some APK repackers leave 1-3 raw padding bytes at the end of a
            # central-directory extra field. Python tolerates those bytes while
            # reading the source, but appending another extra-field record turns
            # the old padding into the start of a bogus record. Preserve every
            # complete record and discard only that trailing padding before adding
            # our alignment record.
            info.extra = _normalize_zip_extra(info.extra, info.filename)
            alignment = _alignment_for_entry(info)
            if alignment > 1:
                _add_alignment_extra(info, zout.fp.tell(), alignment)
            zout.writestr(info, zin.read(source_info))
    _verify_portable_zip_alignment(output)


def _apksigner_jar(apksigner: Path) -> Path | None:
    candidate = apksigner.parent / "lib" / "apksigner.jar"
    return candidate if candidate.is_file() else None


def _run_apksigner(tools: injector.AndroidTools, args: Sequence[object]) -> None:
    try:
        injector.run([tools.apksigner, *args])
        return
    except OSError as exc:
        if exc.errno not in {errno.ENOEXEC, errno.EACCES}:
            raise

    jar = _apksigner_jar(tools.apksigner)
    java = shutil.which("java")
    if jar is None or java is None:
        raise injector.InjectError(
            "apksigner launcher cannot execute on this host and no usable "
            "lib/apksigner.jar + java fallback is available"
        )
    injector.log("apksigner launcher is not executable on this host; using its Java JAR")
    injector.run([java, "-jar", jar, *args])


def portable_sign_apk(
    tools: injector.AndroidTools,
    unsigned: Path,
    output: Path,
    keystore: Path,
    storepass: str,
    alias: str,
    keypass: str,
) -> None:
    aligned = output.with_suffix(".aligned.apk")
    aligned.unlink(missing_ok=True)

    native_zipalign = _native_tool_matches_host(tools.zipalign)
    if native_zipalign:
        try:
            injector.run([tools.zipalign, "-p", "-f", "4", unsigned, aligned])
        except OSError as exc:
            if exc.errno not in {errno.ENOEXEC, errno.EACCES}:
                raise
            native_zipalign = False
            aligned.unlink(missing_ok=True)

    if not native_zipalign:
        machine = _elf_machine(tools.zipalign)
        host = platform.machine() or "unknown"
        detail = f" ELF machine {machine}" if machine is not None else ""
        injector.log(
            f"Native zipalign{detail} cannot execute on host {host}; "
            "using built-in portable ZIP aligner"
        )
        _portable_zipalign(unsigned, aligned)

    sign_args: list[object] = [
        "sign",
        "--ks",
        keystore,
        "--ks-pass",
        f"pass:{storepass}",
        "--ks-key-alias",
        alias,
        "--key-pass",
        f"pass:{keypass}",
        "--v4-signing-enabled",
        "false",
        "--out",
        output,
        aligned,
    ]
    _run_apksigner(tools, sign_args)
    aligned.unlink(missing_ok=True)
    _run_apksigner(tools, ["verify", "--verbose", output])


def output_path(target: Path) -> Path:
    return target.with_name(target.stem + "-SMM" + (target.suffix or ".apk"))


def print_help() -> None:
    print(
        "usage: inject_apk.py TARGET.apk [--out OUTPUT.apk] [options]\n\n"
        "Inject SMM into an SPD-derived APK using smm-inject-donor.apk beside this script.\n\n"
        "options:\n"
        "  --ankh-only         inject only ModAnkh + Store + Loot + Console dependencies\n"
        "  --out PATH          output APK (default: <target>-SMM.apk, or -SMM-Ankh with --ankh-only)\n"
        "  --cache PATH        injector tool cache\n"
        "  --offline           do not download missing tools\n"
        "  --keep-work         keep temporary work files\n"
        "  --keystore PATH     signing keystore (default: smm-inject.keystore beside donor)\n"
        "  --keystore-pass S   keystore password\n"
        "  --key-alias NAME    signing key alias\n"
        "  --key-pass S        signing key password\n"
        "  -h, --help          show this help"
    )


def _translate_core_error(exc: injector.InjectError) -> injector.InjectError:
    message = str(exc)
    if message.startswith("Donor debug payload is not self-contained for this target"):
        if _ankh_only_mode:
            return injector.InjectError(
                "ModAnkh-only payload still has unresolved target API references; "
                "no reliable compatibility adapter is available."
            )
        return injector.InjectError(
            "Full SMM payload is incompatible with this target. "
            "If only ModAnkh + Store + Loot + Console are needed, retry with --ankh-only."
        )
    return exc


injector.detect_target_game_prefix = detect_target_game_prefix
injector.build_debug_payload = build_full_debug_payload
injector.build_modankh_store_payload = build_no_legacy_modankh_store_payload
injector.payload_compatibility_errors = full_payload_compatibility_errors
injector.adapt_modankh = adapt_modankh
injector.find_class = find_wndgame_instead_of_dungeon
injector.patch_dungeon = patch_wndgame
injector.compile_smali = compile_smali_with_char_hook
injector.ensure_debug_keystore = ensure_inject_keystore
injector.sign_apk = portable_sign_apk
injector.output_path = output_path


def main(argv: Sequence[str] | None = None) -> int:
    global _ankh_only_mode
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or "-h" in args or "--help" in args:
        print_help()
        return 0 if args else 2

    _ankh_only_mode = "--ankh-only" in args
    args = [arg for arg in args if arg != "--ankh-only"]
    if _ankh_only_mode:
        import _inject_apk_ankh

        _inject_apk_ankh.configure(sys.modules[__name__])
        injector.step("Injection mode")
        injector.log("ModAnkh only (Store + Loot + Console)")
    else:
        injector.step("Injection mode")
        injector.log("Full SMM")

    if not DEFAULT_DONOR.is_file():
        raise injector.InjectError(f"SMM donor APK not found beside injector: {DEFAULT_DONOR}")
    try:
        return injector.main([str(DEFAULT_DONOR), *args])
    except injector.InjectError as exc:
        translated = _translate_core_error(exc)
        if translated is exc:
            raise
        raise translated from exc


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)
