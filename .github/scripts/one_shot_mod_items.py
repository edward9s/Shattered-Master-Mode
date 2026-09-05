from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_jar() -> None:
    path = Path("scripts/inject_jar.py")
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "    ModDebug payload, and the supported Assassin/Parry/Enemy Surge/Loot feature families;",
        "    ModDebug payload, supported feature families, and all controlled Mod item families;",
        "JAR docstring",
    )

    marker = "LOOT_PAYLOAD_ENTRIES = tuple(prefix + \".class\" for prefix in LOOT_PAYLOAD_PREFIXES)\n"
    insertion = marker + '''MOD_ITEM_CLASS_PREFIX = "com/spd/mod/items/Mod"\nITEM_HELPER_PREFIXES = (\n    "com/spd/mod/mechanics/ModBlast",\n    "com/spd/mod/mechanics/ModSight",\n)\nITEM_HELPER_ENTRIES = tuple(prefix + ".class" for prefix in ITEM_HELPER_PREFIXES)\n'''
    text = replace_once(text, marker, insertion, "JAR item payload constants")

    old = '''def is_payload_family(name: str, prefix: str) -> bool:\n    return name == prefix + ".class" or (\n        name.startswith(prefix + "$") and name.endswith(".class")\n    )\n\n\ndef main(argv: Sequence[str] | None = None) -> int:\n'''
    new = '''def is_payload_family(name: str, prefix: str) -> bool:\n    return name == prefix + ".class" or (\n        name.startswith(prefix + "$") and name.endswith(".class")\n    )\n\n\ndef is_mod_item_payload(name: str) -> bool:\n    if not (name.startswith(MOD_ITEM_CLASS_PREFIX) and name.endswith(".class")):\n        return False\n    if name == MOD_ANKH_ENTRY:\n        return False\n    return not (\n        name == MOD_ANKH_STORE_ENTRY\n        or name.startswith(MOD_ANKH_STORE_PREFIX + "$")\n    )\n\n\ndef main(argv: Sequence[str] | None = None) -> int:\n'''
    text = replace_once(text, old, new, "JAR item payload predicate")

    old = '''            debug_names = sorted(\n                name for name in zf.namelist()\n'''
    new = '''            mod_item_entries = sorted(\n                name for name in zf.namelist()\n                if is_mod_item_payload(name)\n                and "$" not in name.rsplit("/", 1)[-1]\n            )\n            if not mod_item_entries:\n                raise InjectError("Donor JAR contains no controlled Mod item classes")\n\n            debug_names = sorted(\n                name for name in zf.namelist()\n'''
    text = replace_once(text, old, new, "JAR dynamic item roots")

    old = '''                    or any(is_payload_family(name, prefix) for prefix in LOOT_PAYLOAD_PREFIXES)\n                )\n'''
    new = '''                    or any(is_payload_family(name, prefix) for prefix in LOOT_PAYLOAD_PREFIXES)\n                    or is_mod_item_payload(name)\n                    or any(is_payload_family(name, prefix) for prefix in ITEM_HELPER_PREFIXES)\n                )\n'''
    text = replace_once(text, old, new, "JAR payload selection")

    old = '''                WND_ENEMY_SURGE_INFO_ENTRY,\n                *LOOT_PAYLOAD_ENTRIES,\n            ):\n'''
    new = '''                WND_ENEMY_SURGE_INFO_ENTRY,\n                *LOOT_PAYLOAD_ENTRIES,\n                *ITEM_HELPER_ENTRIES,\n                *mod_item_entries,\n            ):\n'''
    text = replace_once(text, old, new, "JAR donor required roots")

    # rebuild_jar() cannot see the donor-derived mod_item_entries list; helpers are static,
    # while every dynamic Mod item is verified both before repack and in validate_jar below.
    old = '''        WND_ENEMY_SURGE_INFO_ENTRY,\n        *LOOT_PAYLOAD_ENTRIES,\n    ):\n'''
    new = '''        WND_ENEMY_SURGE_INFO_ENTRY,\n        *LOOT_PAYLOAD_ENTRIES,\n        *ITEM_HELPER_ENTRIES,\n    ):\n'''
    text = replace_once(text, old, new, "JAR rebuild required helpers")

    old = '''                WND_ENEMY_SURGE_INFO_ENTRY,\n                *LOOT_PAYLOAD_ENTRIES,\n            ],\n        )\n'''
    new = '''                WND_ENEMY_SURGE_INFO_ENTRY,\n                *LOOT_PAYLOAD_ENTRIES,\n                *ITEM_HELPER_ENTRIES,\n                *mod_item_entries,\n            ],\n        )\n'''
    text = replace_once(text, old, new, "JAR output validation")

    text = replace_once(
        text,
        'f"({len(store_payload)} store classes) + debug/Assassin/Parry/Enemy Surge/Loot payload "',
        'f"({len(store_payload)} store classes) + debug/features/Mod-items payload "',
        "JAR log wording",
    )

    path.write_text(text, encoding="utf-8")


def patch_apk() -> None:
    path = Path("scripts/inject_apk.py")
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "  * copies the controlled ModDebug payload plus the supported Assassin/Parry/Enemy Surge/Loot feature families;",
        "  * copies the controlled ModDebug payload, supported feature families, and all controlled Mod item families;",
        "APK docstring",
    )

    marker = "LOOT_REQUIRED_ROOTS = tuple(root for root, _ in LOOT_PAYLOAD_FAMILIES)\n"
    insertion = marker + '''MOD_ITEM_DESCRIPTOR_PREFIX = "Lcom/spd/mod/items/Mod"\nITEM_HELPER_FAMILIES = (\n    ("Lcom/spd/mod/mechanics/ModBlast;", "Lcom/spd/mod/mechanics/ModBlast$"),\n    ("Lcom/spd/mod/mechanics/ModSight;", "Lcom/spd/mod/mechanics/ModSight$"),\n)\nITEM_HELPER_REQUIRED_ROOTS = tuple(root for root, _ in ITEM_HELPER_FAMILIES)\n'''
    text = replace_once(text, marker, insertion, "APK item payload constants")

    old = '''def is_debug_root(descriptor: str) -> bool:\n    return (\n'''
    new = '''def is_mod_item_payload(descriptor: str) -> bool:\n    if not descriptor.startswith(MOD_ITEM_DESCRIPTOR_PREFIX):\n        return False\n    if descriptor == MOD_ANKH:\n        return False\n    return not is_modankh_store_root(descriptor)\n\n\ndef is_debug_root(descriptor: str) -> bool:\n    return (\n'''
    text = replace_once(text, old, new, "APK item payload predicate")

    old = '''        or descriptor.startswith(WND_ENEMY_SURGE_INFO_INNER_PREFIX)\n        or any(descriptor == root or descriptor.startswith(inner) for root, inner in LOOT_PAYLOAD_FAMILIES)\n    )\n'''
    new = '''        or descriptor.startswith(WND_ENEMY_SURGE_INFO_INNER_PREFIX)\n        or any(descriptor == root or descriptor.startswith(inner) for root, inner in LOOT_PAYLOAD_FAMILIES)\n        or is_mod_item_payload(descriptor)\n        or any(descriptor == root or descriptor.startswith(inner) for root, inner in ITEM_HELPER_FAMILIES)\n    )\n'''
    text = replace_once(text, old, new, "APK payload selection")

    old = '''        WND_ENEMY_SURGE_INFO,\n        *LOOT_REQUIRED_ROOTS,\n    )\n'''
    new = '''        WND_ENEMY_SURGE_INFO,\n        *LOOT_REQUIRED_ROOTS,\n        *ITEM_HELPER_REQUIRED_ROOTS,\n    )\n'''
    text = replace_once(text, old, new, "APK helper required roots")

    old = '''    missing_roots = [desc for desc in required_roots if desc not in roots]\n    if missing_roots:\n'''
    new = '''    mod_item_roots = sorted(\n        desc for desc in roots\n        if is_mod_item_payload(desc) and "$" not in desc\n    )\n    if not mod_item_roots:\n        raise InjectError(\n            "Donor APK contains no controlled Mod item classes. "\n            "Rebuild the SMM donor from current source before injecting."\n        )\n\n    missing_roots = [desc for desc in required_roots if desc not in roots]\n    if missing_roots:\n'''
    text = replace_once(text, old, new, "APK dynamic item roots")

    text = replace_once(
        text,
        'log(f"Debug/Assassin/Parry/Enemy Surge/Loot payload classes: {len(debug_payload)}")',
        'log(f"Debug/features/Mod-items payload classes: {len(debug_payload)}")',
        "APK payload log",
    )
    text = replace_once(
        text,
        'log("Injected: ModAnkh + ModAnkhStore + debug/Assassin/Parry/Enemy Surge/Loot payload")',
        'log("Injected: ModAnkh + ModAnkhStore + debug/features/Mod-items payload")',
        "APK final log",
    )

    path.write_text(text, encoding="utf-8")


def patch_docs() -> None:
    en = Path("docs/modankh_payload_rules.md")
    en_text = en.read_text(encoding="utf-8")
    en_marker = "## Controlled Mod item payload surface"
    if en_marker not in en_text:
        en_text = en_text.rstrip() + '''\n\n## Controlled Mod item payload surface\n\nThe injectors treat compiled `com.spd.mod.items.Mod*` classes (including inner classes) as the controlled Mod-item surface. `ModAnkh` and `ModAnkhStore` remain on their dedicated injection path; non-`Mod*` helpers such as `WndModLoot`, and cross-package mechanics helpers such as `ModBlast` and `ModSight`, remain explicit payload families.\n\nThis keeps future self-contained Mod items automatic without opening all of `com.spd.mod.items` or `com.spd.mod.mechanics` to injection. A new Mod item may still fail target-API validation if it introduces an incompatible game/fork dependency; the directory rule does not bypass compatibility checks.\n'''
        en.write_text(en_text, encoding="utf-8")

    zh = Path("docs/modankh_payload_rules.zh-TW.md")
    zh_text = zh.read_text(encoding="utf-8")
    zh_marker = "## 受控的 Mod item payload 範圍"
    if zh_marker not in zh_text:
        zh_text = zh_text.rstrip() + '''\n\n## 受控的 Mod item payload 範圍\n\ninjector 現在會把編譯後的 `com.spd.mod.items.Mod*` class（包含 inner class）視為受控的 Mod item 注入範圍。`ModAnkh` 與 `ModAnkhStore` 仍走原本的專用注入路徑；不是 `Mod*` 的 helper（例如 `WndModLoot`），以及跨 package 的 mechanics helper（例如 `ModBlast`、`ModSight`），仍維持 explicit payload family。\n\n這讓之後新增的 self-contained Mod item 通常不必再修改 injector，同時不會把整個 `com.spd.mod.items` 或 `com.spd.mod.mechanics` 無限制開放給 payload。若新 item 引入了與目標 fork 不相容的 API，target-API validator 仍會拒絕注入；目錄規則不會繞過相容性檢查。\n'''
        zh.write_text(zh_text, encoding="utf-8")


def verify() -> None:
    jar = Path("scripts/inject_jar.py").read_text(encoding="utf-8")
    apk = Path("scripts/inject_apk.py").read_text(encoding="utf-8")
    android = Path("scripts/patch_android.py").read_text(encoding="utf-8")

    expected = (
        "ModElixirBrew", "ModPotionOfResetTier", "ModPotionOfWeakness",
        "ModScrollOfAssassin", "ModScrollOfBlast", "ModScrollOfDisplacement",
        "ModScrollOfLoot", "ModScrollOfSight", "ModReusable",
    )
    # The injectors intentionally match these by package prefix rather than spelling
    # every class name; verify source currently contains every expected item.
    items_dir = Path("core/src/main/java/com/spd/mod/items")
    missing_source = [name for name in expected if not (items_dir / f"{name}.java").is_file()]
    if missing_source:
        raise RuntimeError("Missing expected Mod item source: " + ", ".join(missing_source))

    for needle in (
        'MOD_ITEM_CLASS_PREFIX = "com/spd/mod/items/Mod"',
        '"com/spd/mod/mechanics/ModBlast"',
        '"com/spd/mod/mechanics/ModSight"',
        "or is_mod_item_payload(name)",
    ):
        if needle not in jar:
            raise RuntimeError(f"JAR invariant missing: {needle}")

    for needle in (
        'MOD_ITEM_DESCRIPTOR_PREFIX = "Lcom/spd/mod/items/Mod"',
        '"Lcom/spd/mod/mechanics/ModBlast;"',
        '"Lcom/spd/mod/mechanics/ModSight;"',
        "or is_mod_item_payload(descriptor)",
    ):
        if needle not in apk:
            raise RuntimeError(f"APK invariant missing: {needle}")

    for needle in (
        '-keep class com.spd.mod.items.Mod** { *; }',
        '-keep class com.spd.mod.mechanics.ModBlast { *; }',
        '-keep class com.spd.mod.mechanics.ModSight { *; }',
        "maven-metadata.xml",
    ):
        if needle not in android:
            raise RuntimeError(f"Android patch invariant missing: {needle}")


if __name__ == "__main__":
    patch_jar()
    patch_apk()
    patch_docs()
    verify()
