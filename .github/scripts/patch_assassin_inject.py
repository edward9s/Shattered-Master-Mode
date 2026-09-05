from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: {label} anchor count {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Android APK injector: make the Assassin feature family part of the controlled
# debug payload so its donor-only dependencies and target API references receive
# the same closure/relocation/compatibility validation as ModDebug.
p = Path("scripts/inject_apk.py")
s = p.read_text(encoding="utf-8")
s = s.replace(
    '"""Inject SMM\'s ModAnkh, ModAnkhStore, and debug console into an SPD-derived APK.',
    '"""Inject SMM\'s ModAnkh, debug console, and supported debug feature payloads into an SPD-derived APK.',
    1,
)
s = s.replace(
    '  * copies the controlled ModDebug payload;\n',
    '  * copies the controlled ModDebug payload plus the ModAssassinBuff support family;\n',
    1,
)
old = '''MOD_DEBUG = "Lcom/spd/mod/mechanics/ModDebug;"\nMOD_DEBUG_INNER_PREFIX = "Lcom/spd/mod/mechanics/ModDebug$"\n'''
new = '''MOD_DEBUG = "Lcom/spd/mod/mechanics/ModDebug;"\nMOD_DEBUG_INNER_PREFIX = "Lcom/spd/mod/mechanics/ModDebug$"\nMOD_ASSASSIN_BUFF = "Lcom/spd/mod/mechanics/ModAssassinBuff;"\nMOD_ASSASSIN_BUFF_INNER_PREFIX = "Lcom/spd/mod/mechanics/ModAssassinBuff$"\nMOD_ASSASSIN = "Lcom/spd/mod/mechanics/ModAssassin;"\nMOD_ASSASSIN_INNER_PREFIX = "Lcom/spd/mod/mechanics/ModAssassin$"\nMOD_FLASH = "Lcom/spd/mod/mechanics/ModFlash;"\nMOD_FLASH_INNER_PREFIX = "Lcom/spd/mod/mechanics/ModFlash$"\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk constants anchor count {s.count(old)}")
s = s.replace(old, new, 1)
old = '''def is_debug_root(descriptor: str) -> bool:\n    return (\n        descriptor == MOD_DEBUG\n        or descriptor.startswith(MOD_DEBUG_INNER_PREFIX)\n    )\n'''
new = '''def is_debug_root(descriptor: str) -> bool:\n    return (\n        descriptor == MOD_DEBUG\n        or descriptor.startswith(MOD_DEBUG_INNER_PREFIX)\n        or descriptor == MOD_ASSASSIN_BUFF\n        or descriptor.startswith(MOD_ASSASSIN_BUFF_INNER_PREFIX)\n        or descriptor == MOD_ASSASSIN\n        or descriptor.startswith(MOD_ASSASSIN_INNER_PREFIX)\n        or descriptor == MOD_FLASH\n        or descriptor.startswith(MOD_FLASH_INNER_PREFIX)\n    )\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk root anchor count {s.count(old)}")
s = s.replace(old, new, 1)
old = '''    if MOD_DEBUG not in roots:\n        raise InjectError(\n            "Donor APK is missing com.spd.mod.mechanics.ModDebug"\n        )\n'''
new = '''    required_roots = (\n        MOD_DEBUG, MOD_ASSASSIN_BUFF, MOD_ASSASSIN, MOD_FLASH,\n    )\n    missing_roots = [desc for desc in required_roots if desc not in roots]\n    if missing_roots:\n        raise InjectError(\n            "Donor APK is missing required debug payload classes: "\n            + ", ".join(missing_roots)\n            + ". Rebuild the SMM donor from current source before injecting."\n        )\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk required roots anchor count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace(
    '    """Build and relocate the donor-only dependency closure for ModDebug.\n',
    '    """Build and relocate the donor-only dependency closure for ModDebug and supported debug features.\n',
    1,
)
s = s.replace(
    '        log(f"Debug payload classes: {len(debug_payload)}")\n',
    '        log(f"Debug/Assassin payload classes: {len(debug_payload)}")\n',
    1,
)
p.write_text(s, encoding="utf-8")


# Android donor must preserve stable binary payload class names/members through R8.
p = Path("scripts/patch_android.py")
s = p.read_text(encoding="utf-8")
old = '''        '-keep class com.spd.mod.mechanics.ModDebug { *; }',\n        '-keep class com.spd.mod.mechanics.ModDebug$* { *; }',\n'''
new = '''        '-keep class com.spd.mod.mechanics.ModDebug { *; }',\n        '-keep class com.spd.mod.mechanics.ModDebug$* { *; }',\n        '-keep class com.spd.mod.mechanics.ModAssassinBuff { *; }',\n        '-keep class com.spd.mod.mechanics.ModAssassinBuff$* { *; }',\n        '-keep class com.spd.mod.mechanics.ModAssassin { *; }',\n        '-keep class com.spd.mod.mechanics.ModAssassin$* { *; }',\n        '-keep class com.spd.mod.mechanics.ModFlash { *; }',\n        '-keep class com.spd.mod.mechanics.ModFlash$* { *; }',\n'''
if s.count(old) != 1:
    raise SystemExit(f"patch_android keep anchor count {s.count(old)}")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")


# Desktop JAR injector: explicitly copy the same stable Assassin class families.
p = Path("scripts/inject_jar.py")
s = p.read_text(encoding="utf-8")
s = s.replace(
    '  * copies ModAnkh, the dedicated ModAnkhStore payload, and the controlled\n    ModDebug payload from the donor;\n',
    '  * copies ModAnkh, the dedicated ModAnkhStore payload, the controlled\n    ModDebug payload, and the ModAssassinBuff support family from the donor;\n',
    1,
)
old = '''MOD_DEBUG_PREFIX = "com/spd/mod/mechanics/ModDebug"\nMOD_DEBUG_ENTRY = "com/spd/mod/mechanics/ModDebug.class"\n'''
new = '''MOD_DEBUG_PREFIX = "com/spd/mod/mechanics/ModDebug"\nMOD_DEBUG_ENTRY = "com/spd/mod/mechanics/ModDebug.class"\nMOD_ASSASSIN_BUFF_PREFIX = "com/spd/mod/mechanics/ModAssassinBuff"\nMOD_ASSASSIN_BUFF_ENTRY = "com/spd/mod/mechanics/ModAssassinBuff.class"\nMOD_ASSASSIN_PREFIX = "com/spd/mod/mechanics/ModAssassin"\nMOD_ASSASSIN_ENTRY = "com/spd/mod/mechanics/ModAssassin.class"\nMOD_FLASH_PREFIX = "com/spd/mod/mechanics/ModFlash"\nMOD_FLASH_ENTRY = "com/spd/mod/mechanics/ModFlash.class"\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar constants anchor count {s.count(old)}")
s = s.replace(old, new, 1)
old = '''    if MOD_VALUE_SEARCH_ENTRY not in debug_payload:\n        raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModValueSearch")\n'''
new = '''    if MOD_VALUE_SEARCH_ENTRY not in debug_payload:\n        raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModValueSearch")\n    for required in (MOD_ASSASSIN_BUFF_ENTRY, MOD_ASSASSIN_ENTRY, MOD_FLASH_ENTRY):\n        if required not in debug_payload:\n            raise InjectError(f"Donor JAR is missing required debug payload class: {required}")\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar rebuild requirements anchor count {s.count(old)}")
s = s.replace(old, new, 1)
old = '''                    or name == MOD_SAVE_TRANSFER_ENTRY\n                    or (\n                        name.startswith(MOD_SAVE_TRANSFER_PREFIX + "$")\n                        and name.endswith(".class")\n                    )\n'''
new = '''                    or name == MOD_SAVE_TRANSFER_ENTRY\n                    or (\n                        name.startswith(MOD_SAVE_TRANSFER_PREFIX + "$")\n                        and name.endswith(".class")\n                    )\n                    or name == MOD_ASSASSIN_BUFF_ENTRY\n                    or (\n                        name.startswith(MOD_ASSASSIN_BUFF_PREFIX + "$")\n                        and name.endswith(".class")\n                    )\n                    or name == MOD_ASSASSIN_ENTRY\n                    or (\n                        name.startswith(MOD_ASSASSIN_PREFIX + "$")\n                        and name.endswith(".class")\n                    )\n                    or name == MOD_FLASH_ENTRY\n                    or (\n                        name.startswith(MOD_FLASH_PREFIX + "$")\n                        and name.endswith(".class")\n                    )\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar selection anchor count {s.count(old)}")
s = s.replace(old, new, 1)
old = '''            if MOD_SAVE_TRANSFER_ENTRY not in debug_names:\n                raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModSaveTransfer")\n            debug_payload = {\n'''
new = '''            if MOD_SAVE_TRANSFER_ENTRY not in debug_names:\n                raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModSaveTransfer")\n            for required in (MOD_ASSASSIN_BUFF_ENTRY, MOD_ASSASSIN_ENTRY, MOD_FLASH_ENTRY):\n                if required not in debug_names:\n                    raise InjectError(f"Donor JAR is missing required debug payload class: {required}")\n            debug_payload = {\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar donor requirements anchor count {s.count(old)}")
s = s.replace(old, new, 1)
old = '''                MOD_DEBUG_ENTRY,\n                MOD_VALUE_SEARCH_ENTRY,\n                MOD_SAVE_TRANSFER_ENTRY,\n'''
new = '''                MOD_DEBUG_ENTRY,\n                MOD_VALUE_SEARCH_ENTRY,\n                MOD_SAVE_TRANSFER_ENTRY,\n                MOD_ASSASSIN_BUFF_ENTRY,\n                MOD_ASSASSIN_ENTRY,\n                MOD_FLASH_ENTRY,\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar validate list anchor count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace(
    '            f"({len(store_payload)} store classes) + debug console "\n            f"({len(debug_payload)} debug classes)"\n',
    '            f"({len(store_payload)} store classes) + debug/Assassin payload "\n            f"({len(debug_payload)} classes)"\n',
    1,
)
p.write_text(s, encoding="utf-8")


# In-game help: make the injected feature discoverable through the existing affect command.
p = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
s = p.read_text(encoding="utf-8")
old = '''                        "affect <Buff> [duration] [method [args...]]\\n"\n                        + "Select a character, then apply the Buff. Duration is used when supported.\\n"\n                        + "Example: @buff affect Haste"\n'''
new = '''                        "affect <Buff> [duration] [method [args...]]\\n"\n                        + "Select a character, then apply the Buff. Duration is used when supported.\\n"\n                        + "Examples: @buff affect Haste | affect ModAssassinBuff (select the Hero)"\n'''
if s.count(old) != 1:
    raise SystemExit(f"ModDebug affect help anchor count {s.count(old)}")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")


# User documentation.
p = Path("docs/debug_console.md")
s = p.read_text(encoding="utf-8")
old = '''After the command, select the character that should receive the buff. Duration is especially useful for `FlavourBuff`-style temporary effects. The command can also invoke a compatible setup method on the created buff.\n'''
new = '''After the command, select the character that should receive the buff. Duration is especially useful for `FlavourBuff`-style temporary effects. The command can also invoke a compatible setup method on the created buff.\n\nThe injectable debug payload also includes `ModAssassinBuff` and its required `ModAssassin` / `ModFlash` support classes. To enable the permanent Assassin Instinct control on the Hero in an injected build:\n\n```text\naffect ModAssassinBuff\n# then select the Hero\n```\n\n`ModAssassinBuff` refuses non-Hero targets. The Android/JAR injectors copy this family together with the debug payload; on Android the donor build keeps these classes through R8 so the class remains discoverable by `affect`.\n'''
if s.count(old) != 1:
    raise SystemExit(f"English affect docs anchor count {s.count(old)}")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")

p = Path("docs/debug_console.zh-TW.md")
s = p.read_text(encoding="utf-8")
old = '''輸入指令後，再從地圖選擇要套用 Buff 的角色。`duration` 對 `FlavourBuff` 類型的暫時效果特別有用，也可在建立後再呼叫相容的初始化 method。\n'''
new = '''輸入指令後，再從地圖選擇要套用 Buff 的角色。`duration` 對 `FlavourBuff` 類型的暫時效果特別有用，也可在建立後再呼叫相容的初始化 method。\n\n可注入的 debug payload 也包含 `ModAssassinBuff`，以及它所需的 `ModAssassin`／`ModFlash` 支援 class。在注入版中要替英雄啟用永久的 Assassin Instinct 操作，可輸入：\n\n```text\naffect ModAssassinBuff\n# 接著選擇 Hero\n```\n\n`ModAssassinBuff` 會拒絕非 Hero target。Android／JAR injector 會把這個 family 與 debug payload 一起複製；Android donor build 也會用 R8 keep rules 保留這些 class，讓 `affect` 的 class index 能找到它。\n'''
if s.count(old) != 1:
    raise SystemExit(f"Chinese affect docs anchor count {s.count(old)}")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")

# Payload design docs: explicitly record the new family boundary.
for path, marker, line in (
    (
        "docs/modankh_payload_rules.md",
        "- explicitly supported helpers such as `ModValueSearch` and `ModSaveTransfer`",
        "- `com.spd.mod.mechanics.ModAssassinBuff` plus its required `ModAssassin` / `ModFlash` class families, for Debug Console `affect` support",
    ),
    (
        "docs/modankh_payload_rules.zh-TW.md",
        "- 明確支援的 helper，例如 `ModValueSearch`、`ModSaveTransfer`",
        "- `com.spd.mod.mechanics.ModAssassinBuff`，以及 Debug Console `affect` 所需的 `ModAssassin`／`ModFlash` class family",
    ),
):
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    if s.count(marker) != 1:
        raise SystemExit(f"{path}: payload list marker count {s.count(marker)}")
    s = s.replace(marker, marker + "\n" + line, 1)
    p.write_text(s, encoding="utf-8")
