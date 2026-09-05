from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: {label} anchor count {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# ---------- APK injector ----------
p = Path("scripts/inject_apk.py")
s = p.read_text(encoding="utf-8")

old = '''DUNGEON = "Lcom/shatteredpixel/shatteredpixeldungeon/Dungeon;"\nHERO_CLASS = "Lcom/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass;"\nHERO = "Lcom/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero;"\nITEM = "Lcom/shatteredpixel/shatteredpixeldungeon/items/Item;"\nJAVA_FRAMEWORK_PREFIXES = (\n'''
new = '''SOURCE_GAME_DESCRIPTOR_PREFIX = "Lcom/shatteredpixel/shatteredpixeldungeon/"\nSOURCE_GAME_DOTTED_PREFIX = "com.shatteredpixel.shatteredpixeldungeon"\nDUNGEON = SOURCE_GAME_DESCRIPTOR_PREFIX + "Dungeon;"\nHERO_CLASS = SOURCE_GAME_DESCRIPTOR_PREFIX + "actors/hero/HeroClass;"\nHERO = SOURCE_GAME_DESCRIPTOR_PREFIX + "actors/hero/Hero;"\nITEM = SOURCE_GAME_DESCRIPTOR_PREFIX + "items/Item;"\nJAVA_FRAMEWORK_PREFIXES = (\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk constant anchor count {s.count(old)}")
s = s.replace(old, new, 1)

# Keep this source-oriented set for donor dependency closure. Runtime target
# validation gets its own dynamically detected game prefix.
s = s.replace(
    '''TARGET_API_PREFIXES = (\n    "Lcom/shatteredpixel/", "Lcom/watabou/", "Lcom/badlogic/",\n) + JAVA_FRAMEWORK_PREFIXES\n''',
    '''TARGET_API_PREFIXES = (\n    "Lcom/shatteredpixel/", "Lcom/watabou/", "Lcom/badlogic/",\n) + JAVA_FRAMEWORK_PREFIXES\n''',
    1,
)

anchor = '''def min_sdk(decoded: Path) -> int:\n    yml = decoded / "apktool.yml"\n    if not yml.is_file():\n        return 21\n    text = yml.read_text(encoding="utf-8", errors="replace")\n    m = re.search(r"(?m)^\\s*minSdkVersion:\\s*['\\\"]?(\\d+)", text)\n    return int(m.group(1)) if m else 21\n\n\n'''
insert = '''def min_sdk(decoded: Path) -> int:\n    yml = decoded / "apktool.yml"\n    if not yml.is_file():\n        return 21\n    text = yml.read_text(encoding="utf-8", errors="replace")\n    m = re.search(r"(?m)^\\s*minSdkVersion:\\s*['\\\"]?(\\d+)", text)\n    return int(m.group(1)) if m else 21\n\n\ndef detect_target_game_prefix(index: dict[str, SmaliClass]) -> str:\n    """Locate an SPD-family package root without assuming the upstream package name."""\n\n    candidates: list[str] = []\n    required = (\n        "actors/hero/Hero;",\n        "actors/hero/HeroClass;",\n        "items/Item;",\n        "levels/Level;",\n        "scenes/GameScene;",\n    )\n    for descriptor in index:\n        if not descriptor.endswith("/Dungeon;"):\n            continue\n        prefix = descriptor[:-len("Dungeon;")]\n        if all(prefix + suffix in index for suffix in required):\n            candidates.append(prefix)\n\n    candidates = sorted(set(candidates))\n    if len(candidates) != 1:\n        raise InjectError(\n            "Expected exactly one SPD-family game package root, found "\n            + str(len(candidates))\n            + (": " + ", ".join(candidates) if candidates else "")\n        )\n    return candidates[0]\n\n\ndef dotted_game_prefix(descriptor_prefix: str) -> str:\n    if not descriptor_prefix.startswith("L") or not descriptor_prefix.endswith("/"):\n        raise InjectError(f"Invalid game descriptor prefix: {descriptor_prefix}")\n    return descriptor_prefix[1:-1].replace("/", ".")\n\n\ndef game_descriptor(prefix: str, relative: str) -> str:\n    return prefix + relative + ";"\n\n\ndef target_api_prefixes(game_prefix: str) -> tuple[str, ...]:\n    return (\n        game_prefix, "Lcom/watabou/", "Lcom/badlogic/",\n    ) + JAVA_FRAMEWORK_PREFIXES\n\n\ndef rebase_smali_text(text: str, target_game_prefix: str) -> str:\n    if target_game_prefix == SOURCE_GAME_DESCRIPTOR_PREFIX:\n        return text\n    target_dotted = dotted_game_prefix(target_game_prefix)\n    return (\n        text.replace(SOURCE_GAME_DESCRIPTOR_PREFIX, target_game_prefix)\n        .replace(SOURCE_GAME_DOTTED_PREFIX, target_dotted)\n    )\n\n\ndef rebase_smali_payload(\n    payload: dict[str, SmaliClass],\n    target_game_prefix: str,\n) -> dict[str, SmaliClass]:\n    rebased: dict[str, SmaliClass] = {}\n    for item in payload.values():\n        text = rebase_smali_text(item.text, target_game_prefix)\n        rewritten = SmaliClass.from_text(item.path, text)\n        if rewritten.descriptor in rebased:\n            raise InjectError(\n                "Duplicate injected payload class after package rebase: "\n                + rewritten.descriptor\n            )\n        rebased[rewritten.descriptor] = rewritten\n    return rebased\n\n\n'''
if s.count(anchor) != 1:
    raise SystemExit(f"inject_apk min_sdk anchor count {s.count(anchor)}")
s = s.replace(anchor, insert, 1)

old = '''def adapt_modankh(\n    text: str,\n    target_index: dict[str, SmaliClass],\n) -> tuple[str, list[str]]:\n    notes: list[str] = []\n    key = ("setCurrent", f"({HERO})V")\n    if resolve_member(target_index, ITEM, key, method=True):\n        return text, notes\n\n    item = target_index.get(ITEM)\n    if item is None:\n        raise InjectError("Target has no Item class")\n    cur_user = ("curUser", HERO)\n    cur_item = ("curItem", ITEM)\n'''
new = '''def adapt_modankh(\n    text: str,\n    target_index: dict[str, SmaliClass],\n    item_descriptor: str = ITEM,\n    hero_descriptor: str = HERO,\n) -> tuple[str, list[str]]:\n    notes: list[str] = []\n    key = ("setCurrent", f"({hero_descriptor})V")\n    if resolve_member(target_index, item_descriptor, key, method=True):\n        return text, notes\n\n    item = target_index.get(item_descriptor)\n    if item is None:\n        raise InjectError("Target has no Item class")\n    cur_user = ("curUser", hero_descriptor)\n    cur_item = ("curItem", item_descriptor)\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk adapt header anchor count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace(
    '''        + re.escape(ITEM)\n        + r"->setCurrent\\("\n        + re.escape(HERO)\n''',
    '''        + re.escape(item_descriptor)\n        + r"->setCurrent\\("\n        + re.escape(hero_descriptor)\n''',
    1,
)
s = s.replace(
    '''            f"{ind}sput-object {hero_reg}, {ITEM}->curUser:{HERO}\\n"\n            f"{ind}sput-object {this_reg}, {ITEM}->curItem:{ITEM}"\n''',
    '''            f"{ind}sput-object {hero_reg}, {item_descriptor}->curUser:{hero_descriptor}\\n"\n            f"{ind}sput-object {this_reg}, {item_descriptor}->curItem:{item_descriptor}"\n''',
    1,
)

old = '''def payload_compatibility_errors(\n    payload: dict[str, SmaliClass],\n    target_index: dict[str, SmaliClass],\n) -> list[str]:\n'''
new = '''def payload_compatibility_errors(\n    payload: dict[str, SmaliClass],\n    target_index: dict[str, SmaliClass],\n    allowed_target_prefixes: tuple[str, ...] = TARGET_API_PREFIXES,\n) -> list[str]:\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk payload validator header count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace('''            if not dep.startswith(TARGET_API_PREFIXES):\n''', '''            if not dep.startswith(allowed_target_prefixes):\n''', 1)
s = s.replace('''            if not owner.startswith(TARGET_API_PREFIXES):\n''', '''            if not owner.startswith(allowed_target_prefixes):\n''', 2)

old = '''def patch_dungeon(text: str) -> str:\n'''
new = '''def patch_dungeon(\n    text: str,\n    hero_class_descriptor: str = HERO_CLASS,\n    hero_descriptor: str = HERO,\n) -> str:\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk patch_dungeon header count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace(
    '''        + re.escape(HERO_CLASS)\n        + r"->initHero\\("\n        + re.escape(HERO)\n''',
    '''        + re.escape(hero_class_descriptor)\n        + r"->initHero\\("\n        + re.escape(hero_descriptor)\n''',
    1,
)

old = '''        target_index = index_smali(tgt)\n        donor_index = index_smali(donor)\n        _, donor_ankh = find_class(donor, MOD_ANKH)\n\n        store_payload = build_modankh_store_payload(donor_index)\n        debug_payload, helper_relocations = build_debug_payload(\n            donor_index,\n            target_index,\n        )\n'''
new = '''        target_index = index_smali(tgt)\n        donor_index = index_smali(donor)\n        _, donor_ankh = find_class(donor, MOD_ANKH)\n\n        target_game_prefix = detect_target_game_prefix(target_index)\n        target_game_dotted = dotted_game_prefix(target_game_prefix)\n        log(f"Target SPD-family package: {target_game_dotted}")\n        target_dungeon = game_descriptor(target_game_prefix, "Dungeon")\n        target_hero_class = game_descriptor(\n            target_game_prefix, "actors/hero/HeroClass"\n        )\n        target_hero = game_descriptor(target_game_prefix, "actors/hero/Hero")\n        target_item = game_descriptor(target_game_prefix, "items/Item")\n        allowed_target_prefixes = target_api_prefixes(target_game_prefix)\n\n        store_payload = rebase_smali_payload(\n            build_modankh_store_payload(donor_index),\n            target_game_prefix,\n        )\n        debug_payload, helper_relocations = build_debug_payload(\n            donor_index,\n            target_index,\n        )\n        debug_payload = rebase_smali_payload(\n            debug_payload,\n            target_game_prefix,\n        )\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk main payload anchor count {s.count(old)}")
s = s.replace(old, new, 1)

s = s.replace(
    '''        dungeon_dir, dungeon_path = find_class(tgt, DUNGEON)\n''',
    '''        dungeon_dir, dungeon_path = find_class(tgt, target_dungeon)\n''',
    1,
)
old = '''        mod_text = donor_ankh.read_text(\n            encoding="utf-8",\n            errors="replace",\n        )\n        mod_text, notes = adapt_modankh(mod_text, target_index)\n'''
new = '''        mod_text = donor_ankh.read_text(\n            encoding="utf-8",\n            errors="replace",\n        )\n        mod_text = rebase_smali_text(mod_text, target_game_prefix)\n        mod_text, notes = adapt_modankh(\n            mod_text,\n            target_index,\n            target_item,\n            target_hero,\n        )\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_apk mod_text anchor count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace(
    '''        debug_errors = payload_compatibility_errors(\n            debug_payload,\n            target_index,\n        )\n''',
    '''        debug_errors = payload_compatibility_errors(\n            debug_payload,\n            target_index,\n            allowed_target_prefixes,\n        )\n''',
    1,
)
s = s.replace(
    '''        store_errors = payload_compatibility_errors(\n            store_payload,\n            target_index,\n        )\n''',
    '''        store_errors = payload_compatibility_errors(\n            store_payload,\n            target_index,\n            allowed_target_prefixes,\n        )\n''',
    1,
)
s = s.replace(
    '''        patched_dungeon = patch_dungeon(original_dungeon)\n''',
    '''        patched_dungeon = patch_dungeon(\n            original_dungeon,\n            target_hero_class,\n            target_hero,\n        )\n''',
    1,
)
s = s.replace(
    '''        overlay_dungeon = overlay_root / Path(DUNGEON[1:-1] + ".smali")\n''',
    '''        overlay_dungeon = overlay_root / Path(target_dungeon[1:-1] + ".smali")\n''',
    1,
)
s = s.replace(
    '''        log("Injected: ModAnkh + ModAnkhStore + debug console")\n''',
    '''        log("Injected: ModAnkh + ModAnkhStore + debug/Assassin payload")\n''',
    1,
)
p.write_text(s, encoding="utf-8")


# ---------- JAR injector ----------
p = Path("scripts/inject_jar.py")
s = p.read_text(encoding="utf-8")
old = '''DUNGEON_ENTRY = "com/shatteredpixel/shatteredpixeldungeon/Dungeon.class"\nCLASS_MAGIC = b"\\xca\\xfe\\xba\\xbe"\n'''
new = '''SOURCE_GAME_INTERNAL_ROOT = "com/shatteredpixel/shatteredpixeldungeon"\nSOURCE_GAME_DOTTED_ROOT = "com.shatteredpixel.shatteredpixeldungeon"\nDUNGEON_ENTRY = SOURCE_GAME_INTERNAL_ROOT + "/Dungeon.class"\nCLASS_MAGIC = b"\\xca\\xfe\\xba\\xbe"\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar constants anchor count {s.count(old)}")
s = s.replace(old, new, 1)

anchor = '''def validate_jar(path: Path, required: Sequence[str] = ()) -> None:\n    if not zipfile.is_zipfile(path):\n        raise InjectError(f"Not a valid JAR/ZIP file: {path}")\n    with zipfile.ZipFile(path) as zf:\n        bad = zf.testzip()\n        if bad:\n            raise InjectError(f"Corrupt JAR entry: {bad}")\n        names = set(zf.namelist())\n        for entry in required:\n            if entry not in names:\n                raise InjectError(f"JAR is missing required class: {entry}")\n            if not zf.read(entry).startswith(CLASS_MAGIC):\n                raise InjectError(f"Invalid class file: {entry}")\n\n\n'''
insert = anchor + '''def detect_target_game_root(names: Sequence[str]) -> str:\n    name_set = set(names)\n    candidates: list[str] = []\n    required = (\n        "actors/hero/Hero.class",\n        "actors/hero/HeroClass.class",\n        "items/Item.class",\n        "levels/Level.class",\n        "scenes/GameScene.class",\n    )\n    suffix = "/Dungeon.class"\n    for name in name_set:\n        if not name.endswith(suffix):\n            continue\n        root = name[:-len(suffix)]\n        if all(root + "/" + relative in name_set for relative in required):\n            candidates.append(root)\n    candidates = sorted(set(candidates))\n    if len(candidates) != 1:\n        raise InjectError(\n            "Expected exactly one SPD-family game package root, found "\n            + str(len(candidates))\n            + (": " + ", ".join(candidates) if candidates else "")\n        )\n    return candidates[0]\n\n\ndef rebase_class_bytes(data: bytes, target_game_root: str) -> bytes:\n    """Rebase package names in CONSTANT_Utf8 entries without decompiling the class."""\n\n    if target_game_root == SOURCE_GAME_INTERNAL_ROOT:\n        return data\n    if not data.startswith(CLASS_MAGIC) or len(data) < 10:\n        raise InjectError("Invalid class file while rebasing target package")\n\n    source_internal = SOURCE_GAME_INTERNAL_ROOT.encode("ascii")\n    target_internal = target_game_root.encode("ascii")\n    source_dotted = SOURCE_GAME_DOTTED_ROOT.encode("ascii")\n    target_dotted = target_game_root.replace("/", ".").encode("ascii")\n\n    cp_count = int.from_bytes(data[8:10], "big")\n    out = bytearray(data[:10])\n    offset = 10\n    index = 1\n\n    fixed_sizes = {\n        3: 4, 4: 4,\n        7: 2, 8: 2, 16: 2, 19: 2, 20: 2,\n        9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4,\n        15: 3,\n    }\n\n    while index < cp_count:\n        if offset >= len(data):\n            raise InjectError("Truncated class constant pool")\n        tag = data[offset]\n        out.append(tag)\n        offset += 1\n\n        if tag == 1:\n            if offset + 2 > len(data):\n                raise InjectError("Truncated CONSTANT_Utf8 length")\n            length = int.from_bytes(data[offset:offset + 2], "big")\n            offset += 2\n            raw = data[offset:offset + length]\n            if len(raw) != length:\n                raise InjectError("Truncated CONSTANT_Utf8 value")\n            offset += length\n            rewritten = (\n                raw.replace(source_internal, target_internal)\n                .replace(source_dotted, target_dotted)\n            )\n            if len(rewritten) > 0xFFFF:\n                raise InjectError("Rebased class UTF-8 constant is too long")\n            out.extend(len(rewritten).to_bytes(2, "big"))\n            out.extend(rewritten)\n        elif tag in (5, 6):\n            size = 8\n            out.extend(data[offset:offset + size])\n            offset += size\n            index += 1\n        else:\n            size = fixed_sizes.get(tag)\n            if size is None:\n                raise InjectError(f"Unsupported class constant-pool tag: {tag}")\n            out.extend(data[offset:offset + size])\n            offset += size\n\n        index += 1\n\n    out.extend(data[offset:])\n    return bytes(out)\n\n\ndef write_helper_payload_jar(path: Path, payload: dict[str, bytes]) -> None:\n    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as zf:\n        for name in sorted(payload):\n            zf.writestr(name, payload[name])\n\n\n'''
if s.count(anchor) != 1:
    raise SystemExit(f"inject_jar validate anchor count {s.count(anchor)}")
s = s.replace(anchor, insert, 1)

# Dynamic Dungeon entry in rebuild_jar.
old = '''def rebuild_jar(\n    target: Path,\n    patched_dungeon: Path,\n    patched_modankh: Path,\n    store_payload: dict[str, bytes],\n    debug_payload: dict[str, bytes],\n    output: Path,\n) -> None:\n'''
new = '''def rebuild_jar(\n    target: Path,\n    patched_dungeon: Path,\n    patched_modankh: Path,\n    store_payload: dict[str, bytes],\n    debug_payload: dict[str, bytes],\n    output: Path,\n    dungeon_entry: str = DUNGEON_ENTRY,\n) -> None:\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar rebuild header count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace('''        if DUNGEON_ENTRY not in names:\n            raise InjectError(f"Target JAR has no {DUNGEON_ENTRY}")\n''', '''        if dungeon_entry not in names:\n            raise InjectError(f"Target JAR has no {dungeon_entry}")\n''', 1)
s = s.replace('''        dungeon_count = sum(1 for name in names if name == DUNGEON_ENTRY)\n''', '''        dungeon_count = sum(1 for name in names if name == dungeon_entry)\n''', 1)
s = s.replace('''            if info.filename == DUNGEON_ENTRY\n''', '''            if info.filename == dungeon_entry\n''', 1)
s = s.replace('''                data = dungeon_bytes if name == DUNGEON_ENTRY else zin.read(name)\n''', '''                data = dungeon_bytes if name == dungeon_entry else zin.read(name)\n''', 1)

# Java helper source is specialized by replacing the upstream package root
# before source-file execution; keep helper logic otherwise unchanged.
old = '''def patch_classes(\n    java: Path,\n    target: Path,\n    source: Path,\n    donor_modankh: Path,\n    work: Path,\n) -> tuple[Path, Path]:\n    helper = work / "JarInjectorHelper.java"\n    helper.write_text(JAVA_HELPER, encoding="utf-8")\n'''
new = '''def patch_classes(\n    java: Path,\n    target: Path,\n    helper_payload: Path,\n    donor_modankh: Path,\n    work: Path,\n    target_game_root: str = SOURCE_GAME_INTERNAL_ROOT,\n) -> tuple[Path, Path]:\n    helper = work / "JarInjectorHelper.java"\n    helper_source = JAVA_HELPER.replace(\n        SOURCE_GAME_INTERNAL_ROOT,\n        target_game_root,\n    )\n    helper.write_text(helper_source, encoding="utf-8")\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar patch_classes header count {s.count(old)}")
s = s.replace(old, new, 1)
s = s.replace('''        source,\n        donor_modankh,\n''', '''        helper_payload,\n        donor_modankh,\n''', 1)

old = '''    validate_jar(source, [MOD_ANKH_ENTRY, MOD_ANKH_STORE_ENTRY])\n    validate_jar(target, [DUNGEON_ENTRY])\n    java = ensure_java()\n'''
new = '''    validate_jar(source, [MOD_ANKH_ENTRY, MOD_ANKH_STORE_ENTRY])\n    validate_jar(target)\n    with zipfile.ZipFile(target) as target_zip:\n        target_game_root = detect_target_game_root(target_zip.namelist())\n    dungeon_entry = target_game_root + "/Dungeon.class"\n    log("Target SPD-family package: " + target_game_root.replace("/", "."))\n    java = ensure_java()\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar main validation anchor count {s.count(old)}")
s = s.replace(old, new, 1)

# Rebase donor bytes as they are loaded.
s = s.replace(
    '''            donor_modankh.write_bytes(zf.read(MOD_ANKH_ENTRY))\n''',
    '''            donor_modankh.write_bytes(\n                rebase_class_bytes(zf.read(MOD_ANKH_ENTRY), target_game_root)\n            )\n''',
    1,
)
s = s.replace(
    '''            store_payload = {\n                name: zf.read(name) for name in store_names\n            }\n''',
    '''            store_payload = {\n                name: rebase_class_bytes(zf.read(name), target_game_root)\n                for name in store_names\n            }\n''',
    1,
)
s = s.replace(
    '''            debug_payload = {\n                name: zf.read(name) for name in debug_names\n            }\n''',
    '''            debug_payload = {\n                name: rebase_class_bytes(zf.read(name), target_game_root)\n                for name in debug_names\n            }\n''',
    1,
)

old = '''        step("Adapting and validating donor ModAnkh against target JAR")\n        patched_modankh, patched_dungeon = patch_classes(\n            java,\n            target,\n            source,\n            donor_modankh,\n            work,\n        )\n'''
new = '''        helper_payload = work / "rebased-helper-payload.jar"\n        write_helper_payload_jar(helper_payload, store_payload)\n\n        step("Adapting and validating donor ModAnkh against target JAR")\n        patched_modankh, patched_dungeon = patch_classes(\n            java,\n            target,\n            helper_payload,\n            donor_modankh,\n            work,\n            target_game_root,\n        )\n'''
if s.count(old) != 1:
    raise SystemExit(f"inject_jar patch call anchor count {s.count(old)}")
s = s.replace(old, new, 1)

s = s.replace(
    '''            unsigned_tmp,\n        )\n''',
    '''            unsigned_tmp,\n            dungeon_entry,\n        )\n''',
    1,
)
s = s.replace(
    '''                DUNGEON_ENTRY,\n                MOD_ANKH_ENTRY,\n''',
    '''                dungeon_entry,\n                MOD_ANKH_ENTRY,\n''',
    1,
)
p.write_text(s, encoding="utf-8")


# ---------- Documentation ----------
for path, marker, addition in (
    (
        "docs/modankh_payload_rules.md",
        "The injector must not use a generic rule such as 'copy every `com.spd.mod` class'.",
        "The APK and JAR injectors also detect the target SPD-family package root from the `Dungeon` / `Hero` / `HeroClass` / `Item` / `Level` / `GameScene` class structure. If a fork has renamed the upstream package (for example Rat King Adventure), donor references and reflection strings are rebased to that target root before compatibility validation. `com.spd.mod.*` payload names themselves are never renamed.",
    ),
    (
        "docs/modankh_payload_rules.zh-TW.md",
        "Injector 不應採用「把所有 `com.spd.mod` class 都複製進去」這種泛化規則。",
        "APK／JAR injector 也會利用 `Dungeon`／`Hero`／`HeroClass`／`Item`／`Level`／`GameScene` 的 class 結構辨識 target 的 SPD-family package root。若 fork 改過 upstream package 名稱（例如 Rat King Adventure），donor 對遊戲 class 的引用與 reflection 字串會在 compatibility validation 前 rebase 到 target root；`com.spd.mod.*` payload 自己的名稱則永遠不改。",
    ),
):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if text.count(marker) != 1:
        raise SystemExit(f"{path}: package-rebase docs marker count {text.count(marker)}")
    text = text.replace(marker, marker + "\n\n" + addition, 1)
    p.write_text(text, encoding="utf-8")
