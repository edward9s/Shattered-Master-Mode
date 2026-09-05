from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    return text.replace(old, new, 1)


java_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
text = java_path.read_text(encoding="utf-8")
text = text.replace(
    'terrain <Terrain> [cell|@variable]',
    'terrain <Terrain|id> [cell|@variable]',
)

old = '''    private static TerrainValue resolveTerrain(String input) throws Exception {
        Map<String, Integer> terrains = terrainConstants();
        String name = input.trim();
        String exact = name.toUpperCase(Locale.ROOT);
'''
new = '''    private static TerrainValue resolveTerrain(String input) throws Exception {
        String name = input.trim();

        if (name.matches("\\\\d+")) {
            final int value;
            try {
                value = Integer.parseInt(name);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        str("Terrain ID is outside the integer range: ", name));
            }

            int limit = terrainIdLimit();
            if (value < 0 || (limit > 0 && value >= limit)) {
                throw new IllegalArgumentException(
                        limit > 0
                                ? str("Terrain ID must be between 0 and ",
                                        limit - 1, ": ", value)
                                : str("Terrain ID must be non-negative: ", value));
            }

            GLog.i(str("Using raw terrain ID ", value));
            return new TerrainValue(Integer.toString(value), value);
        }

        Map<String, Integer> terrains = terrainConstants();
        String exact = name.toUpperCase(Locale.ROOT);
'''
text = replace_once(text, old, new, java_path)

marker = '''    private static Map<String, Integer> terrainConstants() throws Exception {
'''
helper = '''    private static int terrainIdLimit() throws Exception {
        Class<?> terrain = loadRequired(TERRAIN_CLASS);
        Field flags = findField(terrain, "flags");
        if (flags == null || !Modifier.isStatic(flags.getModifiers())) {
            return -1;
        }

        flags.setAccessible(true);
        Object value = flags.get(null);
        if (value == null || !value.getClass().isArray()) {
            return -1;
        }

        return Array.getLength(value);
    }

'''
text = replace_once(text, marker, helper + marker, java_path)
java_path.write_text(text, encoding="utf-8")

zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
zh = replace_once(
    zh,
    '''terrain <Terrain> [cell|@variable]\n''',
    '''terrain <Terrain|id> [cell|@variable]\n''',
    zh_path,
)
zh = replace_once(
    zh,
    '''Terrain 名稱不分大小寫，並支援 prefix、包含字串與 subsequence fuzzy。例如：\n\n```text\nterrain chsm\nterrain lockdoor\n```\n\n其中 `chsm` 可匹配 `CHASM`，`lockdoor` 可匹配 `LOCKED_DOOR`。若最佳匹配不唯一，Console 會列出 `Similar:` 候選而不修改地圖。不要硬寫 terrain 數字，名稱在不同 SPD fork／版本間較安全。\n''',
    '''Terrain 名稱不分大小寫，並支援 prefix、包含字串與 subsequence fuzzy。例如：\n\n```text\nterrain chsm\nterrain lockdoor\n```\n\n其中 `chsm` 可匹配 `CHASM`，`lockdoor` 可匹配 `LOCKED_DOOR`。若最佳匹配不唯一，Console 會列出 `Similar:` 候選而不修改地圖。一般仍應優先使用名稱，因為名稱比數字 ID 更容易跨版本閱讀與維護。\n\n若名稱在目標 fork 中不存在、或已被 R8 完全移除，也可以直接輸入該 fork 的 raw terrain ID：\n\n```text\nterrain 0\nterrain 123 @cell\n```\n\n純數字第一參數會直接當成 terrain ID，不做名稱解析或 fuzzy matching。Console 會以目標 `Terrain.flags` 陣列長度檢查可用範圍；例如標準 SPD 的 `flags` 長度是 256，因此有效 ID 為 `0..255`。這使 fork 自訂 terrain 即使只剩數值、沒有可反射的常數名稱時，仍可用 ID 操作。raw ID 必須以目標 APK／fork 的實際定義為準，不能假設不同 fork 的同一數字具有相同意義。\n''',
    zh_path,
)
zh = replace_once(
    zh,
    '''若某個 fork 自訂 terrain 的欄位名稱已被 R8 完全移除，APK 本身已沒有名稱可供反射還原，該自訂名稱仍可能無法解析。\n''',
    '''若某個 fork 自訂 terrain 的欄位名稱已被 R8 完全移除，APK 本身已沒有名稱可供反射還原；此時若知道該 fork 的實際 terrain ID，就可直接用數字形式操作。\n''',
    zh_path,
)
zh_path.write_text(zh, encoding="utf-8")

en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
en = replace_once(
    en,
    '''terrain <Terrain> [cell|@variable]\n''',
    '''terrain <Terrain|id> [cell|@variable]\n''',
    en_path,
)
en = replace_once(
    en,
    '''Terrain names are case-insensitive and support prefix, substring, and subsequence fuzzy matching. For example:\n\n```text\nterrain chsm\nterrain lockdoor\n```\n\n`chsm` can resolve to `CHASM`, while `lockdoor` can resolve to `LOCKED_DOOR`. If the best match is ambiguous, the console prints `Similar:` candidates and leaves the map unchanged. Prefer names over hard-coded numeric terrain IDs for cross-fork/version compatibility.\n''',
    '''Terrain names are case-insensitive and support prefix, substring, and subsequence fuzzy matching. For example:\n\n```text\nterrain chsm\nterrain lockdoor\n```\n\n`chsm` can resolve to `CHASM`, while `lockdoor` can resolve to `LOCKED_DOOR`. If the best match is ambiguous, the console prints `Similar:` candidates and leaves the map unchanged. Names should normally be preferred because they are easier to read and maintain across versions.\n\nIf a name does not exist in the target fork, or R8 has removed the field name completely, the target fork's raw terrain ID can be supplied directly:\n\n```text\nterrain 0\nterrain 123 @cell\n```\n\nA purely numeric first argument is treated directly as a terrain ID and bypasses name/fuzzy resolution. The console validates it against the target `Terrain.flags` array length; standard SPD currently has 256 entries, so valid IDs are `0..255`. This lets fork-specific terrain remain usable when only its numeric value is known. Raw IDs are target/fork-specific and must not be assumed to have the same meaning across different forks.\n''',
    en_path,
)
en = replace_once(
    en,
    '''A fork-specific custom terrain name whose field name was completely removed by R8 cannot be reconstructed from the APK and may still be unavailable by name.\n''',
    '''A fork-specific custom terrain name whose field name was completely removed by R8 cannot be reconstructed from the APK; if that fork's actual terrain ID is known, use the numeric form instead.\n''',
    en_path,
)
en_path.write_text(en, encoding="utf-8")
