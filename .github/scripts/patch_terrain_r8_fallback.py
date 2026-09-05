from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    return text.replace(old, new, 1)


java_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
text = java_path.read_text(encoding="utf-8")

old = '''        final Field terrainField = resolveTerrainField(args.get(0));
        if (terrainField == null) {
            throw new IllegalArgumentException(str(
                    "Terrain not found or ambiguous: ", args.get(0)));
        }

        terrainField.setAccessible(true);
        final int terrainValue = terrainField.getInt(null);
        final String terrainName = terrainField.getName();
'''
new = '''        final TerrainValue terrain = resolveTerrain(args.get(0));
        if (terrain == null) {
            throw new IllegalArgumentException(str(
                    "Terrain not found or ambiguous: ", args.get(0)));
        }

        final int terrainValue = terrain.value;
        final String terrainName = terrain.name;
'''
text = replace_once(text, old, new, java_path)

start = text.index("    private static Field resolveTerrainField(String input) throws Exception {")
end = text.index("    private static boolean isTerrainConstant(Field field) {", start)
resolver = '''    private static TerrainValue resolveTerrain(String input) throws Exception {
        Map<String, Integer> terrains = terrainConstants();
        String name = input.trim();
        String exact = name.toUpperCase(Locale.ROOT);

        if (terrains.containsKey(exact)) {
            return new TerrainValue(exact, terrains.get(exact));
        }

        String lower = name.toLowerCase(Locale.ROOT);
        for (int rank = 0; rank < 3; rank++) {
            ArrayList<String> matches = new ArrayList<>();

            for (String candidate : terrains.keySet()) {
                if (fuzzyMatchRank(
                        lower,
                        candidate.toLowerCase(Locale.ROOT)) == rank) {
                    matches.add(candidate);
                }
            }

            if (matches.size() == 1) {
                String match = matches.get(0);
                GLog.i(str(
                        "Using Terrain.", match,
                        " for ", input));
                return new TerrainValue(match, terrains.get(match));
            }

            if (matches.size() > 1) {
                Collections.sort(matches);
                logSimilar(matches);
                return null;
            }
        }

        return null;
    }

    private static Map<String, Integer> terrainConstants() throws Exception {
        Class<?> terrain = loadRequired(TERRAIN_CLASS);
        HashMap<String, Integer> values = new HashMap<>();

        for (Field field : terrain.getDeclaredFields()) {
            if (!isTerrainConstant(field)) {
                continue;
            }

            field.setAccessible(true);
            values.put(
                    field.getName().toUpperCase(Locale.ROOT),
                    field.getInt(null));
        }

        addStandardTerrainFallbacks(values);
        return values;
    }

    private static void addStandardTerrainFallbacks(
            Map<String, Integer> values) {

        // Android R8 may remove public static final int Terrain fields because
        // their values are compile-time constants. Runtime reflection is still
        // preferred; these canonical SPD values are only a fallback for names
        // whose fields no longer exist in a minified target APK.
        putTerrainFallback(values, "CHASM", 0);
        putTerrainFallback(values, "EMPTY", 1);
        putTerrainFallback(values, "GRASS", 2);
        putTerrainFallback(values, "EMPTY_WELL", 3);
        putTerrainFallback(values, "WALL", 4);
        putTerrainFallback(values, "DOOR", 5);
        putTerrainFallback(values, "OPEN_DOOR", 6);
        putTerrainFallback(values, "ENTRANCE", 7);
        putTerrainFallback(values, "EXIT", 8);
        putTerrainFallback(values, "EMBERS", 9);
        putTerrainFallback(values, "LOCKED_DOOR", 10);
        putTerrainFallback(values, "PEDESTAL", 11);
        putTerrainFallback(values, "WALL_DECO", 12);
        putTerrainFallback(values, "BARRICADE", 13);
        putTerrainFallback(values, "EMPTY_SP", 14);
        putTerrainFallback(values, "HIGH_GRASS", 15);
        putTerrainFallback(values, "SECRET_DOOR", 16);
        putTerrainFallback(values, "SECRET_TRAP", 17);
        putTerrainFallback(values, "TRAP", 18);
        putTerrainFallback(values, "INACTIVE_TRAP", 19);
        putTerrainFallback(values, "EMPTY_DECO", 20);
        putTerrainFallback(values, "LOCKED_EXIT", 21);
        putTerrainFallback(values, "UNLOCKED_EXIT", 22);
        putTerrainFallback(values, "CUSTOM_DECO", 23);
        putTerrainFallback(values, "WELL", 24);
        putTerrainFallback(values, "STATUE", 25);
        putTerrainFallback(values, "STATUE_SP", 26);
        putTerrainFallback(values, "BOOKSHELF", 27);
        putTerrainFallback(values, "ALCHEMY", 28);
        putTerrainFallback(values, "WATER", 29);
        putTerrainFallback(values, "FURROWED_GRASS", 30);
        putTerrainFallback(values, "CRYSTAL_DOOR", 31);
        putTerrainFallback(values, "CUSTOM_DECO_EMPTY", 32);
        putTerrainFallback(values, "REGION_DECO", 33);
        putTerrainFallback(values, "REGION_DECO_ALT", 34);
        putTerrainFallback(values, "MINE_CRYSTAL", 35);
        putTerrainFallback(values, "MINE_BOULDER", 36);
        putTerrainFallback(values, "ENTRANCE_SP", 37);
        putTerrainFallback(values, "HERO_LKD_DR", 38);
    }

    private static void putTerrainFallback(
            Map<String, Integer> values, String name, int value) {
        if (!values.containsKey(name)) {
            values.put(name, value);
        }
    }

'''
text = text[:start] + resolver + text[end:]

marker = "    private static final class StoredValue {\n"
terrain_value = '''    private static final class TerrainValue {
        final String name;
        final int value;

        TerrainValue(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

'''
text = replace_once(text, marker, terrain_value + marker, java_path)
java_path.write_text(text, encoding="utf-8")

zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
needle = "`terrain` 內部會呼叫目標遊戲的 `Level.set(cell, terrain)`，因此 passable／solid／pit 等 terrain flags 會同步更新；之後還會刷新 map、重新 observe 與更新 fog。\n"
insert = needle + "\nAndroid release 的 R8 可能把沒有被直接引用的 `Terrain` `public static final int` 常數欄位移除。ModDebug 會優先使用目標 APK 執行時仍存在的 Terrain 欄位；若標準 SPD terrain 欄位已被 shrink，則退回 SMM 所對應官方 Terrain 的 canonical ID，因此像 `terrain chasm` 在 minified／注入版 APK 也能解析。若某個 fork 自訂 terrain 的欄位名稱已被 R8 完全移除，APK 本身已沒有名稱可供反射還原，該自訂名稱仍可能無法解析。\n"
zh = replace_once(zh, needle, insert, zh_path)
zh_path.write_text(zh, encoding="utf-8")

en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
needle = "Internally, `terrain` calls the target game's `Level.set(cell, terrain)`, so passable/solid/pit and related flags are updated. It then refreshes the map, recalculates observation, and updates fog.\n"
insert = needle + "\nAndroid release R8 can remove `Terrain` `public static final int` fields that are only compile-time constants. ModDebug first uses Terrain fields that still exist in the target APK at runtime; if a standard SPD terrain field was shrunk away, it falls back to the canonical Terrain ID from SMM's upstream baseline. This keeps commands such as `terrain chasm` working in minified/injected APKs. A fork-specific custom terrain name whose field name was completely removed by R8 cannot be reconstructed from the APK and may still be unavailable by name.\n"
en = replace_once(en, needle, insert, en_path)
en_path.write_text(en, encoding="utf-8")
