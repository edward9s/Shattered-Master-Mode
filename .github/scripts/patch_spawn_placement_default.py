from pathlib import Path

mod = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
s = mod.read_text(encoding="utf-8")

old = '''            case "spawn":
                GLog.i(
                        "spawn <Mob> [xquantity|-p|--place] [method [args...]]\\n"
                        + "Spawns mobs; -p/--place lets you choose one cell manually.\\n"
                        + "Example: @rat spawn Rat -p"
                );
                return;
'''
new = '''            case "spawn":
                GLog.i(
                        "spawn <Mob> [cell|@variable|xquantity] [method [args...]]\\n"
                        + "A single Mob opens the cell selector by default. Supply a cell/handle to place it directly.\\n"
                        + "xquantity keeps automatic placement for batch spawning.\\n"
                        + "Examples: spawn Rat | spawn Rat 123 | spawn Rat @cell | spawn Rat x10"
                );
                return;
'''
if s.count(old) != 1:
    raise SystemExit(f"spawn help anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

start = s.index('    private static void spawn(\n')
end = s.index('    private static Mob newDebugMob(\n', start)
new_spawn = '''    private static void spawn(
            List<String> args, final String storeVariable) throws Exception {

        if (args.isEmpty()) {
            throw new IllegalArgumentException(
                    "spawn <Mob> [cell|@variable|xquantity] [method [args...]]");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        final Class<?> raw = resolveClass(args.get(0), Mob.class);
        if (raw == null) {
            throw new IllegalArgumentException(
                    str("Mob class not found: ", args.get(0)));
        }

        int quantity = 1;
        boolean quantitySpecified = false;
        boolean manualPlace = false;
        Integer explicitCell = null;
        int index = 1;

        if (index < args.size()) {
            String token = args.get(index);
            if (token.matches("(?i)x\\d+")) {
                quantity = boundedCount(
                        Integer.parseInt(token.substring(1)));
                quantitySpecified = true;
                index++;

            } else if ("-p".equalsIgnoreCase(token)
                    || "--place".equalsIgnoreCase(token)) {
                // Legacy compatibility: single spawning is now manual by default.
                manualPlace = true;
                index++;

            } else if (token.matches("\\d+") || token.startsWith("@")) {
                explicitCell = integerArgument(token);
                index++;
            }
        }

        if (!quantitySpecified && explicitCell == null) {
            manualPlace = true;
        }

        final String methodName =
                index < args.size() ? args.get(index) : null;
        final List<String> methodArgs =
                index < args.size()
                        ? new ArrayList<>(
                                args.subList(index + 1, args.size()))
                        : Collections.<String>emptyList();

        if (explicitCell != null) {
            Mob probe = (Mob) newInstance(raw);
            int cell = explicitCell;
            if (!validMobCell(probe, cell)) {
                throw new IllegalArgumentException(str(
                        "You cannot place ", probe.name(),
                        " at cell ", cell, "."));
            }

            Mob mob = newDebugMob(raw, cell, probe);
            addMob(mob);
            initializeSpecialMobForDebug(mob);
            invokeGeneratedHook(mob, methodName, methodArgs);

            if (storeVariable != null) {
                putVariable(storeVariable, mob);
            }

            GLog.p(str("Spawned ", mob.name(), " at cell ", cell));
            return;
        }

        if (manualPlace) {
            final Mob probe = (Mob) newInstance(raw);

            GameScene.selectCell(new CellSelector.Listener() {
                @Override
                public String prompt() {
                    return str(
                            "Select a tile to place ", probe.name());
                }

                @Override
                public void onSelect(Integer cell) {
                    if (cell == null || cell < 0 || Dungeon.level == null) {
                        return;
                    }

                    try {
                        if (!validMobCell(probe, cell)) {
                            GLog.w(str(
                                    "You cannot place ",
                                    probe.name(), " here."));
                            return;
                        }

                        Mob mob = newDebugMob(raw, cell, probe);
                        addMob(mob);
                        initializeSpecialMobForDebug(mob);
                        invokeGeneratedHook(
                                mob, methodName, methodArgs);

                        if (storeVariable != null) {
                            putVariable(storeVariable, mob);
                        }

                        GLog.p(str("Spawned ", mob.name()));

                    } catch (Exception error) {
                        reportCommandError("Spawn failed", error);
                    }
                }
            });
            return;
        }

        int made = 0;
        Mob first = null;

        for (int i = 0; i < quantity; i++) {
            Mob probe = (Mob) newInstance(raw);
            int cell = randomRespawnCell(probe);
            if (cell < 0) {
                break;
            }

            Mob mob = newDebugMob(raw, cell, probe);
            addMob(mob);
            initializeSpecialMobForDebug(mob);
            invokeGeneratedHook(mob, methodName, methodArgs);

            if (first == null) {
                first = mob;
            }
            made++;
        }

        if (storeVariable != null && first != null) {
            putVariable(storeVariable, first);
        }

        GLog.p(str(
                "Spawned ", made, " x ", raw.getSimpleName()));
    }

'''
s = s[:start] + new_spawn + s[end:]

old = '''        if ("spawn".equals(command)) {
            for (String token : tokens) {
                if ("-p".equalsIgnoreCase(token)
                        || "--place".equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
'''
new = '''        if ("spawn".equals(command)) {
            if (tokens.size() < 3) {
                return true;
            }

            String placement = tokens.get(2);
            return !placement.matches("(?i)x\\d+")
                    && !placement.matches("\\d+")
                    && !placement.startsWith("@");
        }
'''
if s.count(old) != 1:
    raise SystemExit(f"selector anchor count: {s.count(old)}")
s = s.replace(old, new, 1)
mod.write_text(s, encoding="utf-8")

console = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug$Console.java")
c = console.read_text(encoding="utf-8")
old = '''                if (token.matches("(?i)x\\d+")
                        || "-p".equalsIgnoreCase(token)
                        || "--place".equalsIgnoreCase(token)) {
'''
new = '''                if (token.matches("(?i)x\\d+")
                        || token.matches("\\d+")
                        || token.startsWith("@")
                        || "-p".equalsIgnoreCase(token)
                        || "--place".equalsIgnoreCase(token)) {
'''
if c.count(old) != 1:
    raise SystemExit(f"console spawn option anchor count: {c.count(old)}")
c = c.replace(old, new, 1)
console.write_text(c, encoding="utf-8")

en = Path("docs/debug_console.md")
e = en.read_text(encoding="utf-8")
old = '''## Spawning mobs: `spawn`

```text
spawn Rat
spawn Rat x5
spawn Rat -p
@rat spawn Rat -p
```

Syntax:

```text
spawn <Mob> [xquantity|-p|--place] [method [args...]]
```

- Without `-p`, the game chooses normal respawn cells.
- `-p` lets you manually choose one valid mob placement cell.
- An optional method can be called on the newly spawned Mob.

Manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`.
'''
new = '''## Spawning mobs: `spawn`

```text
spawn Rat
spawn Rat 123
spawn Rat @cell
spawn Rat x5
@rat spawn Rat
```

Syntax:

```text
spawn <Mob> [cell|@variable|xquantity] [method [args...]]
```

- A single Mob opens the map selector by default; this is the normal interactive form.
- A cell number or numeric `@handle` places the Mob there immediately without opening the selector.
- `xN` is the batch form and automatically chooses normal valid respawn cells for each Mob.
- An optional method can be called on the newly spawned Mob after its normal debug initialization.

Examples:

```text
@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x10
```

Direct/manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`. The old `-p` / `--place` form remains accepted for compatibility with existing macros, but it is no longer needed or recommended.
'''
if e.count(old) != 1:
    raise SystemExit(f"English spawn docs anchor count: {e.count(old)}")
e = e.replace(old, new, 1)
en.write_text(e, encoding="utf-8")

zh = Path("docs/debug_console.zh-TW.md")
z = zh.read_text(encoding="utf-8")
old = '''## 生成 Mob：`spawn`

```text
spawn Rat
spawn Rat x5
spawn Rat -p
@rat spawn Rat -p
```

語法：

```text
spawn <Mob> [xquantity|-p|--place] [method [args...]]
```

- 不加 `-p` 時，由遊戲自己找正常 respawn cell。
- `-p` 可手動選一個合法的 Mob 落點。
- 可在生成完成後額外呼叫指定 method。

手動生成 Mob 仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。
'''
new = '''## 生成 Mob：`spawn`

```text
spawn Rat
spawn Rat 123
spawn Rat @cell
spawn Rat x5
@rat spawn Rat
```

語法：

```text
spawn <Mob> [cell|@variable|xquantity] [method [args...]]
```

- 單隻 Mob 預設直接打開地圖選格器；這是一般互動操作的主要形式。
- 指定 cell 編號或數字型 `@handle` 時，會直接在該格生成，不再打開選格器。
- `xN` 是批量形式，才會由遊戲為每隻 Mob 自動尋找合法的正常 respawn cell。
- 可在正常 debug 初始化完成後，再額外呼叫指定 method。

例如：

```text
@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x10
```

直接指定或手動選擇 Mob 落點時，仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。舊的 `-p` / `--place` 為了既有 macro 相容性仍可使用，但已不再需要，也不再是建議語法。
'''
if z.count(old) != 1:
    raise SystemExit(f"Chinese spawn docs anchor count: {z.count(old)}")
z = z.replace(old, new, 1)
zh.write_text(z, encoding="utf-8")
