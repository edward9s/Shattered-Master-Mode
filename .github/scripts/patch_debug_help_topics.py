from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    return text.replace(old, new, 1)


core_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
core = core_path.read_text(encoding="utf-8")

core = replace_once(
    core,
    '''            case "help":\n                help();\n                break;\n''',
    '''            case "help":\n                help(args);\n                return;\n''',
    core_path,
)

start = core.index("    private static void help() {\n")
end = core.index("    private static Object give(List<String> args) throws Exception {\n", start)
help_block = r'''    private static void help(List<String> args) {
        if (args.size() > 1) {
            throw new IllegalArgumentException("help [topic]");
        }

        if (args.isEmpty()) {
            GLog.i(
                    "Debug Console help\n"
                    + "Use help <topic> for focused help.\n\n"
                    + "Create / place:\n"
                    + "  give  spawn  affect  seed  trap  terrain\n"
                    + "Objects / reflection:\n"
                    + "  inspect  use  get  set  enchant  inscribe\n"
                    + "Movement / dungeon:\n"
                    + "  warp  goto  where\n"
                    + "Handles / automation:\n"
                    + "  @  !!  macro\n"
                    + "Value search:\n"
                    + "  search  results  clear\n"
                    + "Files:\n"
                    + "  save  load\n\n"
                    + "Examples: help give | help terrain | help @ | help !!\n"
                    + "Use help fuzzy for identifier matching rules."
            );
            return;
        }

        String topic = args.get(0).toLowerCase(Locale.ROOT);
        switch (topic) {
            case "help":
                GLog.i(
                        "help [topic]\n"
                        + "Without a topic, shows the categorized command index.\n"
                        + "With a topic, shows only that command/topic."
                );
                return;

            case "give":
                GLog.i(
                        "give <Item> [+level] [xquantity] [-f|--force] [method [args...]]\n"
                        + "Creates items and normally uses pickup logic. --force collects directly.\n"
                        + "Examples: give PotionOfHealing x10 | give Longsword +5"
                );
                return;

            case "spawn":
                GLog.i(
                        "spawn <Mob> [xquantity|-p|--place] [method [args...]]\n"
                        + "Spawns mobs; -p/--place lets you choose one cell manually.\n"
                        + "Example: @rat spawn Rat -p"
                );
                return;

            case "affect":
                GLog.i(
                        "affect <Buff> [duration] [method [args...]]\n"
                        + "Select a character, then apply the Buff. Duration is used when supported.\n"
                        + "Example: @buff affect Haste"
                );
                return;

            case "seed":
                GLog.i(
                        "seed <Blob> [amount]\n"
                        + "Select a cell and seed the Blob there. amount defaults to 1.\n"
                        + "Example: @gas seed ToxicGas 100"
                );
                return;

            case "trap":
                GLog.i(
                        "trap <Trap>\n"
                        + "Select a cell; creates and registers a real revealed Trap object.\n"
                        + "Example: @trap trap AlarmTrap"
                );
                return;

            case "terrain":
                GLog.i(
                        "terrain <Terrain|id> [cell|@variable]\n"
                        + "Changes terrain and refreshes the map immediately. Names support fuzzy matching.\n"
                        + "A raw numeric ID bypasses name lookup for minified/fork-specific terrain.\n"
                        + "Examples: terrain CHASM | terrain chsm | terrain 0 | terrain 123 @cell"
                );
                return;

            case "inspect":
                GLog.i(
                        "inspect <Class|hero|level|@variable> [query]\n"
                        + "Lists fields and methods; query filters member names.\n"
                        + "Examples: inspect @item | inspect @item quan"
                );
                return;

            case "use":
                GLog.i(
                        "use <Class|hero|level|@variable> <method> [args...]\n"
                        + "Calls a compatible method. Quoted strings, @handles, and new:<Class> are supported.\n"
                        + "Example: @result use @object someMethod"
                );
                return;

            case "enchant":
                GLog.i(
                        "enchant @weapon <Enchantment|random|none>\n"
                        + "Applies, randomizes, or clears a weapon enchantment.\n"
                        + "Example: enchant @weapon Grim"
                );
                return;

            case "inscribe":
                GLog.i(
                        "inscribe @armor <Glyph|random|none>\n"
                        + "Applies, randomizes, or clears an armor glyph.\n"
                        + "Example: inscribe @armor Brimstone"
                );
                return;

            case "warp":
                GLog.i(
                        "warp [cell|@variable]\n"
                        + "Teleports within the current level. Without a cell, opens the selector.\n"
                        + "Example: warp @cell"
                );
                return;

            case "goto":
                GLog.i(
                        "goto <depth> [branch]\n"
                        + "Changes dungeon depth/branch; branch defaults to 0.\n"
                        + "Example: goto 7"
                );
                return;

            case "where":
                GLog.i("where\nShows the current depth and branch.");
                return;

            case "macro":
                GLog.i(
                        "macro [name]\n"
                        + "Lists macros, or opens the editor for one macro. %1..%9 are arguments.\n"
                        + "Standalone !! inside a macro repeats that invocation's previous command.\n"
                        + "Commands that open a selector must be the final macro line."
                );
                return;

            case "@":
            case "handle":
            case "handles":
                GLog.i(
                        "@handle operations\n"
                        + "@                 list handles\n"
                        + "@x inv            store an inventory Item\n"
                        + "@x cell           store a selected cell\n"
                        + "@x char           store a selected character\n"
                        + "@x obj            store an object/cell\n"
                        + "@x hero|level     store current hero/level\n"
                        + "@x                show a handle\n"
                        + "@x clear          delete a handle\n"
                        + "Prefix a returning command with @x to capture its result."
                );
                return;

            case "!!":
            case "history":
                GLog.i(
                        "History replay\n"
                        + "!!        repeat the previous command once\n"
                        + "!! N      repeat it N additional times (1..1000)\n"
                        + "Inline !! keeps ScrollOfDebug text expansion, e.g. !! x10 or !! +10.\n"
                        + "History directives do not replace the remembered command."
                );
                return;

            case "search":
            case "results":
            case "clear":
                GLog.i(
                        "Value search\n"
                        + "search <number|changed|unchanged|increased|decreased>\n"
                        + "results [#id]\n"
                        + "get #id\n"
                        + "set #id <number>\n"
                        + "clear\n"
                        + "Search/refine numeric fields, inspect a result, edit it, or clear the session."
                );
                return;

            case "get":
                GLog.i(
                        "get @object <field> | get <Class> <staticField> | get #id\n"
                        + "Reads an object/static field, or a Value Search result.\n"
                        + "Prefix with @x to capture a non-null field value."
                );
                return;

            case "set":
                GLog.i(
                        "set @object <field> <value> | set <Class> <staticField> <value> | set #id <number>\n"
                        + "Writes an object/static field, or a Value Search result."
                );
                return;

            case "save":
                GLog.i(
                        "save\n"
                        + "Android: exports this app's save files to Download/<package>."
                );
                return;

            case "load":
                GLog.i(
                        "load\n"
                        + "Android: imports save files from Download/<package>, then restarts the app."
                );
                return;

            case "fuzzy":
            case "identifiers":
                GLog.i(
                        "Identifier matching\n"
                        + "Class and terrain names are case-insensitive and support fuzzy matching.\n"
                        + "The user-facing Console also resolves field, method, Class-argument, and enum identifiers.\n"
                        + "Exact matches win; ambiguous matches show Similar suggestions.\n"
                        + "Command names, @handles, numbers, and ordinary strings remain exact."
                );
                return;

            default:
                GLog.w(str(
                        "Unknown help topic: ", args.get(0),
                        ". Use help to list topics."));
        }
    }

'''
core = core[:start] + help_block + core[end:]
core_path.write_text(core, encoding="utf-8")


console_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug$Console.java")
console = console_path.read_text(encoding="utf-8")
console = replace_once(
    console,
    '"help | give | spawn | affect | seed | trap | warp | inspect | use | goto | where | macro | @ | search | results | get | set | clear | save | load",',
    '"help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load",',
    console_path,
)
console = replace_once(
    console,
    '''                || "trap".equals(command)\n                || "warp".equals(command)\n                || "inspect".equals(command)\n                || "use".equals(command)\n                || "goto".equals(command)\n''',
    '''                || "trap".equals(command)\n                || "terrain".equals(command)\n                || "warp".equals(command)\n                || "inspect".equals(command)\n                || "use".equals(command)\n                || "enchant".equals(command)\n                || "inscribe".equals(command)\n                || "goto".equals(command)\n''',
    console_path,
)
extra_start = console.index(
    '''        if (topLevel\n                && commandIndex == 0\n                && tokens.size() == 1\n                && "help".equalsIgnoreCase(tokens.get(0))) {\n'''
)
extra_end = console.index("        }\n    }\n\n    private static String preprocess(", extra_start) + len("        }\n")
console = console[:extra_start] + console[extra_end:]
console_path.write_text(console, encoding="utf-8")


zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
marker = "> 可用的 class、field 與 method 會隨目標 SPD fork 與版本不同。碰到目標版本沒有的 API 時，對應指令可能失敗。\n\n"
section = '''## 遊戲內 `help`\n\n只輸入 `help` 會顯示依用途分類的完整指令索引；要查看某一項時，用 `help <topic>`，只會顯示該主題的說明，不會把其他不相關指令一起列出。\n\n```text\nhelp\nhelp give\nhelp terrain\nhelp @\nhelp !!\nhelp search\nhelp fuzzy\n```\n\n`help @` 會集中說明 handle 建立、查看、清除與回傳值捕捉；`help !!` 說明 history replay 與 inline `!!`；`help search`／`help results`／`help clear` 會集中說明 Value Search。輸入不存在的 topic 時只會提示 topic 不存在，再請你使用 `help` 查看索引。\n\n'''
zh = replace_once(zh, marker, marker + section, zh_path)
zh_path.write_text(zh, encoding="utf-8")


en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
marker = "> The exact classes, fields, and methods available depend on the target SPD fork and version. Commands that refer to game internals can fail when a target uses a different API.\n\n"
section = '''## In-game `help`\n\nBare `help` shows a complete command index grouped by purpose. Use `help <topic>` for focused help; only that command/topic is shown instead of mixing in unrelated commands.\n\n```text\nhelp\nhelp give\nhelp terrain\nhelp @\nhelp !!\nhelp search\nhelp fuzzy\n```\n\n`help @` covers handle creation, inspection, deletion, and result capture. `help !!` covers history replay and inline `!!`. `help search` / `help results` / `help clear` show the Value Search group. An unknown topic only reports that it is unknown and points back to the main `help` index.\n\n'''
en = replace_once(en, marker, marker + section, en_path)
en_path.write_text(en, encoding="utf-8")
