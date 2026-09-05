from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


src_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
src = src_path.read_text(encoding="utf-8")

src = replace_once(
    src,
    'help | give | spawn | affect | seed | trap | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load',
    'help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load',
    src_path,
)

src = replace_once(
    src,
    '''            case "trap":
                trap(args, storeVariable);
                return;

            case "warp":
''',
    '''            case "trap":
                trap(args, storeVariable);
                return;

            case "terrain":
                terrain(args);
                return;

            case "warp":
''',
    src_path,
)

src = replace_once(
    src,
    '                + "trap <Trap>  (select a tile; trap is revealed)\\n"\n',
    '                + "trap <Trap>  (select a tile; trap is revealed)\\n"\n'
    '                + "terrain <Terrain> [cell|@variable]  (select a tile if omitted)\\n"\n',
    src_path,
)

terrain_methods = r'''    private static void terrain(List<String> args) throws Exception {
        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalArgumentException(
                    "terrain <Terrain> [cell|@variable]");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        final Field terrainField = resolveTerrainField(args.get(0));
        if (terrainField == null) {
            throw new IllegalArgumentException(str(
                    "Terrain not found or ambiguous: ", args.get(0)));
        }

        terrainField.setAccessible(true);
        final int terrainValue = terrainField.getInt(null);
        final String terrainName = terrainField.getName();

        if (args.size() == 2) {
            applyTerrain(
                    integerArgument(args.get(1)),
                    terrainValue, terrainName);
            return;
        }

        GameScene.selectCell(new CellSelector.Listener() {
            @Override
            public String prompt() {
                return str("Select location for ", terrainName, ":");
            }

            @Override
            public void onSelect(Integer cell) {
                if (cell == null || cell < 0) {
                    return;
                }

                try {
                    applyTerrain(cell, terrainValue, terrainName);
                } catch (Exception error) {
                    reportCommandError("Terrain change failed", error);
                }
            }
        });
    }

    private static Field resolveTerrainField(String input) throws Exception {
        Class<?> terrain = loadRequired(TERRAIN_CLASS);
        String name = input.trim();
        String lower = name.toLowerCase(Locale.ROOT);

        for (Field field : terrain.getDeclaredFields()) {
            if (isTerrainConstant(field)
                    && field.getName().equalsIgnoreCase(name)) {
                field.setAccessible(true);
                return field;
            }
        }

        for (int rank = 0; rank < 3; rank++) {
            ArrayList<Field> matches = new ArrayList<>();

            for (Field field : terrain.getDeclaredFields()) {
                if (!isTerrainConstant(field)) {
                    continue;
                }

                if (fuzzyMatchRank(
                        lower,
                        field.getName().toLowerCase(Locale.ROOT)) == rank) {
                    matches.add(field);
                }
            }

            if (matches.size() == 1) {
                Field field = matches.get(0);
                field.setAccessible(true);
                GLog.i(str(
                        "Using Terrain.", field.getName(),
                        " for ", input));
                return field;
            }

            if (matches.size() > 1) {
                ArrayList<String> names = new ArrayList<>();
                for (Field field : matches) {
                    names.add(field.getName());
                }
                Collections.sort(names);
                logSimilar(names);
                return null;
            }
        }

        return null;
    }

    private static boolean isTerrainConstant(Field field) {
        if (!Modifier.isStatic(field.getModifiers())
                || !Modifier.isFinal(field.getModifiers())
                || field.getType() != int.class) {
            return false;
        }

        String name = field.getName().toUpperCase(Locale.ROOT);
        return !"PASSABLE".equals(name)
                && !"LOS_BLOCKING".equals(name)
                && !"FLAMABLE".equals(name)
                && !"FLAMMABLE".equals(name)
                && !"SECRET".equals(name)
                && !"SOLID".equals(name)
                && !"AVOID".equals(name)
                && !"LIQUID".equals(name)
                && !"PIT".equals(name)
                && !name.endsWith("_FLAG")
                && !name.endsWith("_FLAGS");
    }

    private static void applyTerrain(
            int cell, int terrainValue, String terrainName)
            throws Exception {

        boolean insideMap = false;
        InvocationResult inside = invokeCompatibleObjects(
                Dungeon.level,
                Dungeon.level.getClass(),
                "insideMap",
                new Object[]{cell},
                false, false);

        if (inside.invoked && inside.result instanceof Boolean) {
            insideMap = (Boolean) inside.result;
        } else {
            Field mapField = findField(Dungeon.level.getClass(), "map");
            if (mapField == null) {
                throw new NoSuchFieldException(
                        "Target level has no map field");
            }
            Object map = mapField.get(Dungeon.level);
            insideMap = map != null
                    && map.getClass().isArray()
                    && cell >= 0
                    && cell < Array.getLength(map);
        }

        if (!insideMap) {
            throw new IllegalArgumentException(
                    str("Cell is outside the map: ", cell));
        }

        Class<?> levelType = loadRequired(LEVEL_CLASS);
        InvocationResult setResult = invokeCompatibleObjects(
                null, levelType, "set",
                new Object[]{cell, terrainValue},
                false, false);

        if (!setResult.invoked) {
            throw new NoSuchMethodException(
                    "Target Level has no compatible static set(cell, terrain)");
        }

        refreshTerrainCell(cell);
        GLog.p(str(
                "Set cell ", cell,
                " to Terrain.", terrainName));
    }

    private static void refreshTerrainCell(int cell) throws Exception {
        InvocationResult updated = invokeCompatibleObjects(
                null, GameScene.class, "updateMap",
                new Object[]{cell},
                false, false);

        if (!updated.invoked) {
            invokeCompatibleObjects(
                    null, GameScene.class, "updateMap",
                    new Object[0],
                    false, false);
        }

        invokeCompatibleObjects(
                null, Dungeon.class, "observe",
                new Object[0],
                false, false);

        invokeCompatibleObjects(
                null, GameScene.class, "updateFog",
                new Object[0],
                false, false);
    }

'''

src = replace_once(
    src,
    '    private static void trap(\n',
    terrain_methods + '    private static void trap(\n',
    src_path,
)

src = replace_once(
    src,
    '''                    if (!tileSet.invoked) {
                        throw new NoSuchMethodException(
                                "Target Level has no compatible static set(cell, terrain)");
                    }

                    if (storeVariable != null) {
''',
    '''                    if (!tileSet.invoked) {
                        throw new NoSuchMethodException(
                                "Target Level has no compatible static set(cell, terrain)");
                    }

                    refreshTerrainCell(cell);

                    if (storeVariable != null) {
''',
    src_path,
)

resolve_anchor = '''        for (String candidate : matches) {
            Class<?> loaded =
                    tryLoad(candidate, parent);

            if (loaded != null) {
                return loaded;
            }
        }

        return null;
    }

    private static Class<?> loadRequired(
'''

fuzzy_helpers = r'''        for (String candidate : matches) {
            Class<?> loaded =
                    tryLoad(candidate, parent);

            if (loaded != null) {
                return loaded;
            }
        }

        Class<?> fuzzy = resolveFuzzyClass(name, parent);
        if (fuzzy != null) {
            return fuzzy;
        }

        return null;
    }

    private static Class<?> resolveFuzzyClass(
            String input, Class<?> parent) {

        ensureClassIndex();
        String lower = input.toLowerCase(Locale.ROOT);

        for (int rank = 0; rank < 3; rank++) {
            ArrayList<Class<?>> matches = new ArrayList<>();

            for (String className : CLASS_NAMES) {
                String simple = simpleClassName(className);
                if (fuzzyMatchRank(
                        lower,
                        simple.toLowerCase(Locale.ROOT)) != rank) {
                    continue;
                }

                Class<?> loaded = tryLoad(className, parent);
                if (loaded != null) {
                    matches.add(loaded);
                }
            }

            if (matches.size() == 1) {
                Class<?> loaded = matches.get(0);
                GLog.i(str(
                        "Using ", loaded.getSimpleName(),
                        " for ", input));
                return loaded;
            }

            if (matches.size() > 1) {
                ArrayList<String> names = new ArrayList<>();
                for (Class<?> match : matches) {
                    names.add(match.getSimpleName());
                }
                Collections.sort(names);
                logSimilar(names);
                return null;
            }
        }

        return null;
    }

    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        int dollar = className.lastIndexOf('$');
        return className.substring(Math.max(dot, dollar) + 1);
    }

    private static int fuzzyMatchRank(
            String query, String candidate) {

        if (query == null || query.isEmpty()) {
            return -1;
        }
        if (candidate.startsWith(query)) {
            return 0;
        }
        if (candidate.contains(query)) {
            return 1;
        }
        return isSubsequence(query, candidate) ? 2 : -1;
    }

    private static boolean isSubsequence(
            String query, String candidate) {

        int index = 0;
        for (int i = 0;
                i < candidate.length() && index < query.length();
                i++) {
            if (candidate.charAt(i) == query.charAt(index)) {
                index++;
            }
        }
        return index == query.length();
    }

    private static void logSimilar(List<String> names) {
        StringBuilder out = new StringBuilder("Similar:");
        int limit = Math.min(10, names.size());
        for (int i = 0; i < limit; i++) {
            out.append(i == 0 ? " " : ", ")
                    .append(names.get(i));
        }
        if (names.size() > limit) {
            out.append(", ...");
        }
        GLog.w(out.toString());
    }

    private static Class<?> loadRequired(
'''

src = replace_once(src, resolve_anchor, fuzzy_helpers, src_path)

src = replace_once(
    src,
    '                "affect", "seed", "trap",\n                "warp", "inspect", "use",\n',
    '                "affect", "seed", "trap",\n                "terrain", "warp", "inspect", "use",\n',
    src_path,
)

src_path.write_text(src, encoding="utf-8")


# Update both docs to describe the dedicated terrain command and real fuzzy class behavior.
zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
zh = replace_once(
    zh,
    '''### 放置 Trap

```text
trap AlarmTrap
@trap trap AlarmTrap
```

語法：

```text
trap <Trap>
```

輸入後再選地圖 cell。Debug 指令會建立並 reveal 該陷阱。
''',
    '''### 放置 Trap

```text
trap AlarmTrap
trap alarm
@trap trap RockfallTrap
```

語法：

```text
trap <Trap>
```

`trap` 本身就是完整的專用放置指令：它會建立 Trap instance、設定 cell、reveal、加入目前 Level，並把該格設成 `Terrain.TRAP`。Trap class 名稱支援和其他 class 指令相同的 fuzzy 規則；若最佳匹配有歧義，會列出 `Similar:` 候選而不執行。放置完成後也會刷新地圖與視野。
''',
    zh_path,
)
old_zh_terrain = '''## 修改地形

目前可直接利用 `Terrain` 的 static 常數與 `Level.set(...)` 修改某一格地形。為了跨 SPD fork／版本，建議不要硬寫 terrain 數字，而是先讀取常數：

```text
@cell cell
@terrain get Terrain LOCKED_DOOR
use Level set @cell @terrain
use GameScene updateMap @cell
use Dungeon observe
```

上面會把選定 cell 改成鎖上的門。若要測試一般鐵鑰匙開門，再建立目前樓層的鑰匙：

```text
give IronKey
```

改成 chasm：

```text
@cell cell
@terrain get Terrain CHASM
use Level set @cell @terrain
use GameScene updateMap @cell
use Dungeon observe
```

`Level.set` 會同步該 cell 的 passable／solid／pit 等 terrain flags；`GameScene.updateMap` 立即刷新圖塊，`Dungeon.observe` 重新計算可視範圍。

注意：並非所有「看起來像地形」的機制都只有一個 terrain 數值。陷阱還需要實際 `Trap` 物件，因此應優先使用 `trap`；樓梯／入口／出口通常還牽涉 `LevelTransition`；特殊房間、scripted gate 等也可能有額外狀態。
'''
new_zh_terrain = '''## 修改地形：`terrain`

最方便的方式是直接輸入 terrain 名稱，然後點選地圖 cell：

```text
terrain LOCKED_DOOR
terrain CHASM
terrain WATER
terrain WALL
```

語法：

```text
terrain <Terrain> [cell|@variable]
```

省略 cell 時會打開地圖選擇器；也可以直接指定 cell 或先保存的 handle：

```text
@cell cell
terrain CHASM @cell
terrain WALL 123
```

Terrain 名稱不分大小寫，並支援 prefix、包含字串與 subsequence fuzzy。例如：

```text
terrain chsm
tterrain lockdoor
```

其中 `chsm` 可匹配 `CHASM`，`lockdoor` 可匹配 `LOCKED_DOOR`。若最佳匹配不唯一，Console 會列出 `Similar:` 候選而不修改地圖。不要硬寫 terrain 數字，名稱在不同 SPD fork／版本間較安全。

`terrain` 內部會呼叫目標遊戲的 `Level.set(cell, terrain)`，因此 passable／solid／pit 等 terrain flags 會同步更新；之後還會刷新 map、重新 observe 與更新 fog。

例如建立鎖上的門後，可直接產生目前樓層的鐵鑰匙測試：

```text
terrain LOCKED_DOOR
give IronKey
```

注意：並非所有「看起來像地形」的機制都只有一個 terrain 數值。陷阱還需要實際 `Trap` 物件，因此應使用 `trap`；樓梯／入口／出口通常還牽涉 `LevelTransition`；特殊房間、scripted gate 等也可能有額外狀態。
'''.replace('tterrain lockdoor', 'terrain lockdoor')
zh = replace_once(zh, old_zh_terrain, new_zh_terrain, zh_path)
zh_path.write_text(zh, encoding="utf-8")


en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
en = replace_once(
    en,
    '''### Place a trap

```text
trap AlarmTrap
@trap trap AlarmTrap
```

Syntax:

```text
trap <Trap>
```

Select a tile after entering the command. The debug command creates and reveals the trap.
''',
    '''### Place a trap

```text
trap AlarmTrap
trap alarm
@trap trap RockfallTrap
```

Syntax:

```text
trap <Trap>
```

`trap` is already a complete dedicated placement command: it constructs the Trap instance, assigns its cell, reveals it, installs it into the current Level, and changes the tile to `Terrain.TRAP`. Trap class names use the same fuzzy resolution as other class-based commands. If the best fuzzy match is ambiguous, the console prints `Similar:` candidates and does not place anything. The map and visibility are refreshed after placement.
''',
    en_path,
)
old_en_terrain = '''## Editing terrain

Terrain can already be edited through `Terrain` static constants and `Level.set(...)`. For cross-fork/version compatibility, prefer reading the constant instead of hard-coding terrain IDs:

```text
@cell cell
@terrain get Terrain LOCKED_DOOR
use Level set @cell @terrain
use GameScene updateMap @cell
use Dungeon observe
```

That turns the selected cell into a locked door. To test normal iron-key unlocking on the current floor:

```text
give IronKey
```

To create a chasm cell:

```text
@cell cell
@terrain get Terrain CHASM
use Level set @cell @terrain
use GameScene updateMap @cell
use Dungeon observe
```

`Level.set` updates the cell's passable/solid/pit and related terrain flags. `GameScene.updateMap` refreshes the rendered tile immediately, and `Dungeon.observe` recalculates visibility.

Not every map feature is represented by terrain alone. Traps also require a live `Trap` object, so prefer the `trap` command for them. Entrances/exits normally involve `LevelTransition`, and scripted gates or special rooms may carry additional state.
'''
new_en_terrain = '''## Editing terrain: `terrain`

The convenient form is to name a terrain and then select a map cell:

```text
terrain LOCKED_DOOR
terrain CHASM
terrain WATER
terrain WALL
```

Syntax:

```text
terrain <Terrain> [cell|@variable]
```

Omit the cell to open the map selector, or provide a cell number / saved handle directly:

```text
@cell cell
terrain CHASM @cell
terrain WALL 123
```

Terrain names are case-insensitive and support prefix, substring, and subsequence fuzzy matching. For example:

```text
terrain chsm
terrain lockdoor
```

`chsm` can resolve to `CHASM`, while `lockdoor` can resolve to `LOCKED_DOOR`. If the best match is ambiguous, the console prints `Similar:` candidates and leaves the map unchanged. Prefer names over hard-coded numeric terrain IDs for cross-fork/version compatibility.

Internally, `terrain` calls the target game's `Level.set(cell, terrain)`, so passable/solid/pit and related flags are updated. It then refreshes the map, recalculates observation, and updates fog.

For a locked-door test:

```text
terrain LOCKED_DOOR
give IronKey
```

Not every map feature is represented by terrain alone. Traps also require a live `Trap` object, so use `trap` for them. Entrances/exits normally involve `LevelTransition`, and scripted gates or special rooms may carry additional state.
'''
en = replace_once(en, old_en_terrain, new_en_terrain, en_path)
en_path.write_text(en, encoding="utf-8")
