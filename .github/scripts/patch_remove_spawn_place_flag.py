from pathlib import Path

mod = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
s = mod.read_text(encoding="utf-8")

old = '''            case "spawn":
                GLog.i(
                        "spawn <Mob> [cell|@variable|xquantity] [method [args...]]\\n"
                        + "A single Mob opens the cell selector by default. Supply a cell/handle to place it directly.\\n"
                        + "xquantity keeps automatic placement for batch spawning.\\n"
                        + "Examples: spawn Rat | spawn Rat 123 | spawn Rat @cell | spawn Rat x10"
                );
                return;
'''
new = '''            case "spawn":
                GLog.i(
                        "spawn <Mob> [cell|@variable|xquantity] [method [args...]]\\n"
                        + "A single Mob opens the cell selector by default. Supply a cell/handle to place it directly.\\n"
                        + "xquantity, including x1, uses non-interactive automatic placement.\\n"
                        + "Examples: spawn Rat | spawn Rat 123 | spawn Rat @cell | spawn Rat x1 | spawn Rat x10"
                );
                return;
'''
if s.count(old) != 1:
    raise SystemExit(f"spawn help anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

old = '''        int quantity = 1;
        boolean quantitySpecified = false;
        boolean manualPlace = false;
        Integer explicitCell = null;
'''
new = '''        int quantity = 1;
        boolean quantitySpecified = false;
        Integer explicitCell = null;
'''
if s.count(old) != 1:
    raise SystemExit(f"spawn state anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

old = '''            } else if ("-p".equalsIgnoreCase(token)
                    || "--place".equalsIgnoreCase(token)) {
                // Legacy compatibility: single spawning is now manual by default.
                manualPlace = true;
                index++;

            } else if (token.matches("[0-9]+") || token.startsWith("@")) {
'''
new = '''            } else if (token.matches("[0-9]+") || token.startsWith("@")) {
'''
if s.count(old) != 1:
    raise SystemExit(f"legacy spawn flag anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

old = '''        if (!quantitySpecified && explicitCell == null) {
            manualPlace = true;
        }

'''
if s.count(old) != 1:
    raise SystemExit(f"manualPlace assignment anchor count: {s.count(old)}")
s = s.replace(old, "", 1)

old = '''        if (manualPlace) {
            final Mob probe = (Mob) newInstance(raw);
'''
new = '''        if (!quantitySpecified) {
            final Mob probe = (Mob) newInstance(raw);
'''
if s.count(old) != 1:
    raise SystemExit(f"manualPlace branch anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

mod.write_text(s, encoding="utf-8")

console = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug$Console.java")
c = console.read_text(encoding="utf-8")
old = '''                if (token.matches("(?i)x\\\\d+")
                        || token.matches("[0-9]+")
                        || token.startsWith("@")
                        || "-p".equalsIgnoreCase(token)
                        || "--place".equalsIgnoreCase(token)) {
'''
new = '''                if (token.matches("(?i)x\\\\d+")
                        || token.matches("[0-9]+")
                        || token.startsWith("@")) {
'''
if c.count(old) != 1:
    raise SystemExit(f"console legacy spawn flag anchor count: {c.count(old)}")
c = c.replace(old, new, 1)
console.write_text(c, encoding="utf-8")

en = Path("docs/debug_console.md")
e = en.read_text(encoding="utf-8")
old = '''- `xN` is the batch form and automatically chooses normal valid respawn cells for each Mob.
- An optional method can be called on the newly spawned Mob after its normal debug initialization.
'''
new = '''- `xN` uses automatic placement and chooses normal valid respawn cells for each Mob. `x1` is the non-interactive single-Mob form, which is useful in macros.
- An optional method can be called on the newly spawned Mob after its normal debug initialization.
'''
if e.count(old) != 1:
    raise SystemExit(f"English spawn bullet anchor count: {e.count(old)}")
e = e.replace(old, new, 1)
old = '''Direct/manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`. The old `-p` / `--place` form remains accepted for compatibility with existing macros, but it is no longer needed or recommended.
'''
new = '''Direct/manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`.
'''
if e.count(old) != 1:
    raise SystemExit(f"English legacy note anchor count: {e.count(old)}")
e = e.replace(old, new, 1)
old = '''spawn Rat x10
'''
new = '''spawn Rat x1
spawn Rat x10
'''
# Only alter the spawn examples block occurrence following @bee line.
needle = '''@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x10
'''
replacement = '''@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x1
spawn Rat x10
'''
if e.count(needle) != 1:
    raise SystemExit(f"English spawn examples anchor count: {e.count(needle)}")
e = e.replace(needle, replacement, 1)
en.write_text(e, encoding="utf-8")

zh = Path("docs/debug_console.zh-TW.md")
z = zh.read_text(encoding="utf-8")
old = '''- `xN` 是批量形式，才會由遊戲為每隻 Mob 自動尋找合法的正常 respawn cell。
- 可在正常 debug 初始化完成後，再額外呼叫指定 method。
'''
new = '''- `xN` 會使用自動落點，由遊戲為每隻 Mob 尋找合法的正常 respawn cell；`x1` 就是非互動式的單隻生成形式，特別適合 macro。
- 可在正常 debug 初始化完成後，再額外呼叫指定 method。
'''
if z.count(old) != 1:
    raise SystemExit(f"Chinese spawn bullet anchor count: {z.count(old)}")
z = z.replace(old, new, 1)
old = '''直接指定或手動選擇 Mob 落點時，仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。舊的 `-p` / `--place` 為了既有 macro 相容性仍可使用，但已不再需要，也不再是建議語法。
'''
new = '''直接指定或手動選擇 Mob 落點時，仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。
'''
if z.count(old) != 1:
    raise SystemExit(f"Chinese legacy note anchor count: {z.count(old)}")
z = z.replace(old, new, 1)
needle = '''@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x10
'''
replacement = '''@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x1
spawn Rat x10
'''
if z.count(needle) != 1:
    raise SystemExit(f"Chinese spawn examples anchor count: {z.count(needle)}")
z = z.replace(needle, replacement, 1)
zh.write_text(z, encoding="utf-8")
