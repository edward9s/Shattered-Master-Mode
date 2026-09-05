from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")
    return text.replace(old, new, 1)


zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
zh = replace_once(
    zh,
    '參數會依 method 的 Java parameter type 自動轉換，也支援引號字串與 handle：\n\n```text\nuse @object rename "test object"\nuse @object setTarget @rat\n```\n',
    '參數會依 method 的 Java parameter type 自動轉換，也支援引號字串、handle，以及明確的 `new:<Class>` 物件建立語法：\n\n```text\nuse @object rename "test object"\nuse @object setTarget @rat\nuse @weapon enchant new:Grim\n```\n\n`new:<Class>` 會先依 method 的實際參數型別限制 class 範圍，再建立相容的 instance。例如 `new:Grim` 只有在該參數可接受 `Grim` 時才會成功。\n',
    zh_path,
)
zh = replace_once(
    zh,
    '找不到相容 method 時就會報錯。`use` 不會把 method 名稱當 field 名稱來處理。\n\n## 建立物品：`give`\n',
    '''找不到相容 method 時就會報錯。`use` 不會把 method 名稱當 field 名稱來處理。\n\n## 武器附魔與防具刻印\n\n先把背包裡的實際武器或防具存成 handle：\n\n```text\n@weapon inv\n@armor inv\n```\n\n指定武器附魔：\n\n```text\nenchant @weapon Grim\nenchant @weapon Vampiric\nenchant @weapon random\nenchant @weapon none\n```\n\n指定防具刻印：\n\n```text\ninscribe @armor Brimstone\ninscribe @armor Thorns\ninscribe @armor random\ninscribe @armor none\n```\n\n`random` 會呼叫目標遊戲自己的無參數 `enchant()` / `inscribe()`；`none`（或 `null`）會清除目前效果。指定 class 時，Console 只接受 `Weapon.Enchantment` 或 `Armor.Glyph` 的相容 subclass。\n\n相同操作也能用通用 `use` + `new:<Class>` 完成：\n\n```text\nuse @weapon enchant new:Grim\nuse @armor inscribe new:Brimstone\n```\n\n## 建立物品：`give`\n''',
    zh_path,
)
zh = replace_once(
    zh,
    '輸入後再選地圖 cell。Debug 指令會建立並 reveal 該陷阱。\n\n## 移動\n',
    '''輸入後再選地圖 cell。Debug 指令會建立並 reveal 該陷阱。\n\n## 修改地形\n\n目前可直接利用 `Terrain` 的 static 常數與 `Level.set(...)` 修改某一格地形。為了跨 SPD fork／版本，建議不要硬寫 terrain 數字，而是先讀取常數：\n\n```text\n@cell cell\n@terrain get Terrain LOCKED_DOOR\nuse Level set @cell @terrain\nuse GameScene updateMap @cell\nuse Dungeon observe\n```\n\n上面會把選定 cell 改成鎖上的門。若要測試一般鐵鑰匙開門，再建立目前樓層的鑰匙：\n\n```text\ngive IronKey\n```\n\n改成 chasm：\n\n```text\n@cell cell\n@terrain get Terrain CHASM\nuse Level set @cell @terrain\nuse GameScene updateMap @cell\nuse Dungeon observe\n```\n\n`Level.set` 會同步該 cell 的 passable／solid／pit 等 terrain flags；`GameScene.updateMap` 立即刷新圖塊，`Dungeon.observe` 重新計算可視範圍。\n\n注意：並非所有「看起來像地形」的機制都只有一個 terrain 數值。陷阱還需要實際 `Trap` 物件，因此應優先使用 `trap`；樓梯／入口／出口通常還牽涉 `LevelTransition`；特殊房間、scripted gate 等也可能有額外狀態。\n\n## 移動\n''',
    zh_path,
)
zh_path.write_text(zh, encoding="utf-8")


en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
en = replace_once(
    en,
    'Arguments are converted to the method\'s Java parameter types. Quoted strings and handles are supported:\n\n```text\nuse @object rename "test object"\nuse @object setTarget @rat\n```\n',
    'Arguments are converted to the method\'s Java parameter types. Quoted strings, handles, and explicit `new:<Class>` construction are supported:\n\n```text\nuse @object rename "test object"\nuse @object setTarget @rat\nuse @weapon enchant new:Grim\n```\n\n`new:<Class>` resolves the requested class against the method\'s actual parameter type before constructing an instance. For example, `new:Grim` succeeds only where that parameter can accept a `Grim` instance.\n',
    en_path,
)
en = replace_once(
    en,
    'If no compatible method exists, the command fails. `use` does **not** silently treat the name as a field.\n\n## Creating items: `give`\n',
    '''If no compatible method exists, the command fails. `use` does **not** silently treat the name as a field.\n\n## Weapon enchantments and armor glyphs\n\nFirst store the live weapon or armor from the inventory:\n\n```text\n@weapon inv\n@armor inv\n```\n\nApply or remove a weapon enchantment:\n\n```text\nenchant @weapon Grim\nenchant @weapon Vampiric\nenchant @weapon random\nenchant @weapon none\n```\n\nApply or remove an armor glyph:\n\n```text\ninscribe @armor Brimstone\ninscribe @armor Thorns\ninscribe @armor random\ninscribe @armor none\n```\n\n`random` calls the target game's normal zero-argument `enchant()` / `inscribe()` method. `none` (or `null`) clears the current effect. Named classes are restricted to compatible `Weapon.Enchantment` or `Armor.Glyph` subclasses.\n\nThe same operations can be expressed with generic `use` plus `new:<Class>`:\n\n```text\nuse @weapon enchant new:Grim\nuse @armor inscribe new:Brimstone\n```\n\n## Creating items: `give`\n''',
    en_path,
)
en = replace_once(
    en,
    'Select a tile after entering the command. The debug command creates and reveals the trap.\n\n## Movement\n',
    '''Select a tile after entering the command. The debug command creates and reveals the trap.\n\n## Editing terrain\n\nTerrain can already be edited through `Terrain` static constants and `Level.set(...)`. For cross-fork/version compatibility, prefer reading the constant instead of hard-coding terrain IDs:\n\n```text\n@cell cell\n@terrain get Terrain LOCKED_DOOR\nuse Level set @cell @terrain\nuse GameScene updateMap @cell\nuse Dungeon observe\n```\n\nThat turns the selected cell into a locked door. To test normal iron-key unlocking on the current floor:\n\n```text\ngive IronKey\n```\n\nTo create a chasm cell:\n\n```text\n@cell cell\n@terrain get Terrain CHASM\nuse Level set @cell @terrain\nuse GameScene updateMap @cell\nuse Dungeon observe\n```\n\n`Level.set` updates the cell's passable/solid/pit and related terrain flags. `GameScene.updateMap` refreshes the rendered tile immediately, and `Dungeon.observe` recalculates visibility.\n\nNot every map feature is represented by terrain alone. Traps also require a live `Trap` object, so prefer the `trap` command for them. Entrances/exits normally involve `LevelTransition`, and scripted gates or special rooms may carry additional state.\n\n## Movement\n''',
    en_path,
)
en_path.write_text(en, encoding="utf-8")
