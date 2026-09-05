from pathlib import Path

pairs = [
    (Path("docs/debug_console.md"), False),
    (Path("docs/debug_console.zh-TW.md"), True),
]

for path, zh in pairs:
    text = path.read_text(encoding="utf-8")

    if zh:
        old_spawn = """直接指定或手動選擇 Mob 落點時，仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。\n"""
        new_spawn = """直接指定或手動選擇 Mob 落點時，仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。`spawn` 也會對不能只靠裸 constructor 正常建立的特殊 Mob 做必要的 debug 初始化；目前包含使用正常 factory 建立 Mimic，以及替 Bee 補上等級、HP/HT 與脫離 Honeypot 綁定所需的初始化。\n"""
        old_terrain = """內部實作上，`terrain` 會呼叫目標遊戲的 `Level.set(cell, terrain)`，因此 passable／solid／pit 等相關 flag 會跟著更新。接著會刷新 map、重新計算視野並更新 fog。\n"""
        new_terrain = """內部實作上，`terrain` 會呼叫目標遊戲的 `Level.set(cell, terrain)`，因此 passable／solid／pit 等相關 flag 會跟著更新。接著會刷新 map、重新計算視野並更新 fog。對一般 terrain tile 而言，選格或指定 cell 成功後，地圖上的 tile 圖像應立即改變，不需要換樓層或重新載入場景；需要額外物件或狀態的特殊機制仍屬例外。\n"""
    else:
        old_spawn = """Direct/manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`.\n"""
        new_spawn = """Direct/manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`. `spawn` also applies required debug initialization for special Mob types that cannot be safely created with a bare constructor; this currently includes using the normal factory path for Mimics and initializing Bee level, HP/HT, and detached Honeypot state.\n"""
        old_terrain = """Internally, `terrain` calls the target game's `Level.set(cell, terrain)`, so passable/solid/pit and related flags are updated. It then refreshes the map, recalculates observation, and updates fog.\n"""
        new_terrain = """Internally, `terrain` calls the target game's `Level.set(cell, terrain)`, so passable/solid/pit and related flags are updated. It then refreshes the map, recalculates observation, and updates fog. For ordinary terrain tiles, the visible tile graphic should change immediately after the cell is applied; changing floors or reloading the scene should not be necessary. Features backed by additional objects or state remain exceptions.\n"""

    if text.count(old_spawn) != 1:
        raise SystemExit(f"{path}: spawn anchor count {text.count(old_spawn)}")
    if text.count(old_terrain) != 1:
        raise SystemExit(f"{path}: terrain anchor count {text.count(old_terrain)}")

    text = text.replace(old_spawn, new_spawn, 1)
    text = text.replace(old_terrain, new_terrain, 1)
    path.write_text(text, encoding="utf-8")
