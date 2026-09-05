from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:160]!r}")
    return text.replace(old, new, 1)


src_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
src = src_path.read_text(encoding="utf-8")

src = replace_once(
    src,
    '    private static final Object BAD_ARG = new Object();\n',
    '    private static final Object BAD_ARG = new Object();\n'
    '    private static final Pattern HISTORY_COMMAND =\n'
    '            Pattern.compile("^!!(?:\\\\s+(\\\\d+))?$");\n',
    src_path,
)

src = replace_once(
    src,
    'help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | repeat | @ | search | results | get | set | clear | save | load',
    'help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load',
    src_path,
)

old_execute = '''    public static void execute(String commandLine) throws Exception {
        String text = commandLine == null ? "" : commandLine.trim();
        if (text.isEmpty()) {
            return;
        }

        if (text.contains("!!")) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            text = text.replace("!!", lastCommand);
            GLog.i(str("> ", text));
        }

        lastCommand = text;
        executeExpanded(text, 0);
    }
'''
new_execute = '''    public static void execute(String commandLine) throws Exception {
        String text = commandLine == null ? "" : commandLine.trim();
        if (text.isEmpty()) {
            return;
        }

        Integer historyCount = historyRepeatCount(text);
        if (historyCount != null) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            runHistoryCommand(lastCommand, historyCount, 0);
            return;
        }

        if (text.contains("!!")) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            text = text.replace("!!", lastCommand);
            GLog.i(str("> ", text));
        }

        lastCommand = text;
        executeExpanded(text, 0);
    }

    private static Integer historyRepeatCount(String text) {
        Matcher matcher = HISTORY_COMMAND.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }

        int count = matcher.group(1) == null
                ? 1
                : Integer.parseInt(matcher.group(1));

        if (count < 1 || count > 1000) {
            throw new IllegalArgumentException(
                    "!! count must be between 1 and 1000");
        }

        return count;
    }

    private static void runHistoryCommand(
            String command, int count, int macroDepth) throws Exception {

        if (count > 1
                && commandOrMacroNeedsSelector(command, macroDepth)) {
            throw new IllegalArgumentException(
                    "Cannot repeat a command that opens an interactive selector more than once; "
                            + "supply an explicit cell/handle where the command supports one");
        }

        GLog.i(str(
                "> ", command,
                count == 1 ? "" : str("  [", count, " times]")));

        for (int i = 0; i < count; i++) {
            executeExpanded(command, macroDepth);
        }
    }
'''
src = replace_once(src, old_execute, new_execute, src_path)

src = replace_once(
    src,
    '''            case "macro":
                macro(args);
                return;

            case "repeat":
                repeat(args, macroDepth);
                return;

            case "search":
''',
    '''            case "macro":
                macro(args);
                return;

            case "search":
''',
    src_path,
)

src = replace_once(
    src,
    '                + "macro [name]  (edit; empty body deletes; %1..%9 are arguments)\\n"\n'
    '                + "repeat <count> <command...>  (1..1000; no interactive selectors)\\n"\n',
    '                + "macro [name]  (edit; empty body deletes; %1..%9 are arguments)\\n"\n',
    src_path,
)

src = replace_once(
    src,
    '                + "!!  (repeat the previous command)\\n"\n',
    '                + "!! [count]  (repeat the previous command; count is 1..1000)\\n"\n'
    '                + "In a macro, standalone !! uses that macro invocation\'s previous command.\\n"\n',
    src_path,
)

old_repeat = '''    private static void repeat(
            List<String> args, int macroDepth) throws Exception {

        if (args.size() < 2) {
            throw new IllegalArgumentException(
                    "repeat <count> <command...>");
        }

        int count = integerArgument(args.get(0));
        if (count < 1 || count > 1000) {
            throw new IllegalArgumentException(
                    "repeat count must be between 1 and 1000");
        }

        String command = joinCommandTokens(args, 1);
        List<String> commandTokens = tokenize(command);
        if (!commandTokens.isEmpty()
                && "repeat".equalsIgnoreCase(commandTokens.get(0))) {
            throw new IllegalArgumentException(
                    "Nested repeat commands are not supported");
        }

        if (commandNeedsSelector(command)) {
            throw new IllegalArgumentException(
                    "repeat cannot run a command that opens an interactive selector; "
                            + "supply an explicit cell/handle where the command supports one");
        }

        for (int i = 0; i < count; i++) {
            executeExpanded(command, macroDepth);
        }
    }

    private static String joinCommandTokens(
            List<String> tokens, int start) {

        StringBuilder out = new StringBuilder();
        for (int i = start; i < tokens.size(); i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(quoteToken(tokens.get(i)));
        }
        return out.toString();
    }

'''
src = replace_once(src, old_repeat, '', src_path)

old_run_macro = '''        for (int i = 0;
                i < expanded.size(); i++) {

            String line = expanded.get(i);

            if (i + 1 < expanded.size()
                    && commandNeedsSelector(line)) {
                throw new IllegalArgumentException(str(
                        "Selector command must be the final macro line: ",
                        line));
            }

            GLog.i(str("> ", line));
            executeExpanded(line, depth + 1);
        }

        return true;
    }
'''
new_run_macro = '''        String previousCommand = null;

        for (int i = 0;
                i < expanded.size(); i++) {

            String line = expanded.get(i);
            Integer historyCount = historyRepeatCount(line);

            if (historyCount != null) {
                if (previousCommand == null) {
                    throw new IllegalStateException(
                            "No previous command in this macro invocation");
                }

                GLog.i(str("> ", line));
                runHistoryCommand(
                        previousCommand, historyCount, depth + 1);
                continue;
            }

            if (i + 1 < expanded.size()
                    && commandOrMacroNeedsSelector(line, depth + 1)) {
                throw new IllegalArgumentException(str(
                        "Selector command must be the final macro line: ",
                        line));
            }

            GLog.i(str("> ", line));
            executeExpanded(line, depth + 1);
            previousCommand = line;
        }

        return true;
    }
'''
src = replace_once(src, old_run_macro, new_run_macro, src_path)

src = replace_once(
    src,
    '''        if ("repeat".equals(command)) {
            return tokens.size() >= 3
                    && commandNeedsSelector(joinCommandTokens(tokens, 2));
        }

''',
    '',
    src_path,
)

selector_helpers = '''
    private static boolean commandOrMacroNeedsSelector(
            String commandLine, int macroDepth) {

        if (commandNeedsSelector(commandLine)) {
            return true;
        }

        if (macroDepth >= 8) {
            return false;
        }

        try {
            loadMacros();
            List<String> tokens = tokenize(commandLine);
            if (tokens.isEmpty() || tokens.get(0).startsWith("@")) {
                return false;
            }

            String macroName = tokens.get(0).toLowerCase(Locale.ROOT);
            String body = MACROS.get(macroName);
            if (body == null) {
                return false;
            }

            List<String> macroArgs = new ArrayList<>(
                    tokens.subList(1, tokens.size()));
            String previous = null;

            for (String rawLine : body.split("\\\\r?\\\\n")) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                String line = expandMacroLine(trimmed, macroArgs);
                Integer historyCount = historyRepeatCount(line);
                if (historyCount != null) {
                    if (previous != null
                            && historyCount > 1
                            && commandOrMacroNeedsSelector(
                                    previous, macroDepth + 1)) {
                        return true;
                    }
                    continue;
                }

                if (commandOrMacroNeedsSelector(
                        line, macroDepth + 1)) {
                    return true;
                }
                previous = line;
            }

        } catch (Exception ignored) {
            // If the macro cannot be expanded here, normal execution will
            // report the actual error. Do not invent a selector dependency.
        }

        return false;
    }

'''
src = replace_once(
    src,
    '    private static void setMacro(\n',
    selector_helpers + '    private static void setMacro(\n',
    src_path,
)

src = replace_once(
    src,
    '                "goto", "where", "macro", "repeat",\n',
    '                "goto", "where", "macro",\n',
    src_path,
)

src_path.write_text(src, encoding="utf-8")


zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")

old_zh = '''## 重複執行：`repeat`

若要把同一條非互動式指令執行很多次，不必在 macro 裡貼 100 行：

```text
repeat 100 use @weapon upgrade
repeat 100 give Gold
```

語法：

```text
repeat <count> <command...>
```

`count` 可為 1 到 1000。`repeat` 不允許巢狀 `repeat`，也不會重複會開互動式 selector 的指令，避免一次打開大量 selector。例如以下會被拒絕：

```text
repeat 100 terrain CHASM
repeat 100 trap AlarmTrap
```

若指令本身支援明確 cell／handle，就可以避免 selector，例如：

```text
@cell cell
repeat 100 terrain CHASM @cell
```

部分指令本身已有更直接的數量語法，這時不必使用 `repeat`：

```text
give PotionOfHealing x100
spawn Rat x100
```

## 重複上一條指令：`!!`

```text
!!
```

`!!` 會展開成上一條 Debug command。它也可以出現在一行指令中，只要展開後的內容語法合理即可。
'''
new_zh = '''## 重複上一條指令：`!!`

`!!` 是 history 語法，不另外引入 `repeat` command：

```text
use @weapon upgrade
!!
!! 100
```

- `!!`：把上一條指令再執行 1 次。
- `!! N`：把上一條指令額外再執行 N 次；N 可為 1 到 1000，因此 `!! 1` 與 `!!` 等價。
- `!! N` 本身不會取代「上一條指令」，所以連續輸入 `!! 100`、`!! 10` 仍然都是重複同一條實際指令。

若上一條頂層指令是一個 macro，例如：

```text
prepareBoss
!! 10
```

則會把整個 `prepareBoss` macro 再執行 10 次，而不是只重複 macro 內最後一行。

在 macro 裡，獨立成行的 `!!` / `!! N` 使用的是**該次 macro 執行自己的上一條有效指令**，不會引用 Console 外層 history。例如 macro 內容：

```text
use @weapon upgrade
!! 100
```

會把 `use @weapon upgrade` 額外執行 100 次。空白行與 `#` 註解不影響這個 macro-local history；若 `!!` 是 macro 第一條有效指令，會直接報錯。若上一條是另一個 macro，則重複的是那整個子 macro。

批次重複不會一次開出大量互動式 selector。因此 `!! N` 在 N > 1 時，如果上一條指令（或整個 macro）會打開 selector，就會拒絕執行。例如：

```text
terrain CHASM
!! 100
```

不允許；但已指定 cell 的版本可以批次重複：

```text
@cell cell
terrain CHASM @cell
!! 100
```

單次 `!!` / `!! 1` 仍可重跑一個會開 selector 的上一條指令。

為了向後相容，若 `!!` 不是完整的一行 history 指令，而是出現在其他頂層命令文字中，仍保留原本的 inline 文字展開行為。Macro-local history 則只認獨立成行的 `!!` / `!! N`。

部分指令本身已有更直接的數量語法，仍應優先使用：

```text
give PotionOfHealing x100
spawn Rat x100
```
'''
zh = replace_once(zh, old_zh, new_zh, zh_path)

zh = replace_once(
    zh,
    'Macro 可以呼叫其他 macro，但最多只能巢狀 8 層。會打開互動式 selector 的指令必須放在 macro 最後一行，因為 selector 的完成是非同步的。例如 `terrain CHASM` 會開 selector，所以只能放最後；`terrain CHASM @cell` 已有明確 cell，不會開 selector，因此可以放在中間。\n\nMacro 會獨立持久化，不依賴一般遊戲存檔。\n',
    'Macro 可以呼叫其他 macro，但最多只能巢狀 8 層。會打開互動式 selector 的指令必須放在 macro 最後一行，因為 selector 的完成是非同步的。例如 `terrain CHASM` 會開 selector，所以只能放最後；`terrain CHASM @cell` 已有明確 cell，不會開 selector，因此可以放在中間。\n\nMacro 內也支援獨立成行的 `!!` / `!! N`，而且使用 macro-local history；完整規則見下一節。\n\nMacro 會獨立持久化，不依賴一般遊戲存檔。\n',
    zh_path,
)
zh_path.write_text(zh, encoding="utf-8")


en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
old_en = '''## Repeating commands: `repeat`

To execute the same non-interactive command many times, you do not need to paste 100 lines into a macro:

```text
repeat 100 use @weapon upgrade
repeat 100 give Gold
```

Syntax:

```text
repeat <count> <command...>
```

`count` may be from 1 to 1000. Nested `repeat` commands are rejected, and `repeat` will not execute a command that opens an interactive selector, preventing a large number of selectors from being opened at once. These are therefore rejected:

```text
repeat 100 terrain CHASM
repeat 100 trap AlarmTrap
```

When a command supports an explicit cell/handle, supply it to avoid the selector:

```text
@cell cell
repeat 100 terrain CHASM @cell
```

Some commands already have a direct quantity form, which is preferable to `repeat`:

```text
give PotionOfHealing x100
spawn Rat x100
```

## Repeat the previous command: `!!`

```text
!!
```

`!!` expands to the previous debug command. It can also appear inside a command line where repeating the previous text makes sense.
'''
new_en = '''## Repeat the previous command: `!!`

`!!` is history syntax; there is no separate `repeat` command:

```text
use @weapon upgrade
!!
!! 100
```

- `!!` executes the previous command one additional time.
- `!! N` executes the previous command N additional times; N may be from 1 to 1000, so `!! 1` is equivalent to `!!`.
- `!! N` does not itself replace the previous-command entry. Consecutive `!! 100` and `!! 10` therefore still repeat the same real command.

If the previous top-level command is a macro, for example:

```text
prepareBoss
!! 10
```

then the whole `prepareBoss` macro is executed 10 additional times, not merely its final line.

Inside a macro, a standalone `!!` / `!! N` uses **that macro invocation's own previous effective command**, never the Console's outer history. For example, this macro body:

```text
use @weapon upgrade
!! 100
```

executes `use @weapon upgrade` 100 additional times. Blank lines and `#` comments do not affect macro-local history. A macro whose first effective line is `!!` fails because it has no local previous command. If the previous macro line invokes another macro, the whole nested macro is repeated.

Batch history replay will not open a large number of interactive selectors. When N > 1, `!! N` rejects a previous command (or macro) that opens a selector. For example:

```text
terrain CHASM
!! 100
```

is rejected, while an explicit-cell form can be replayed in a batch:

```text
@cell cell
terrain CHASM @cell
!! 100
```

A single `!!` / `!! 1` may still rerun a previous command that opens a selector.

For backward compatibility, when `!!` is not a complete history-command line and instead appears inside other top-level command text, the original inline textual expansion remains available. Macro-local history recognizes only standalone `!!` / `!! N` lines.

Some commands already have a direct quantity form and should still prefer it:

```text
give PotionOfHealing x100
spawn Rat x100
```
'''
en = replace_once(en, old_en, new_en, en_path)

en = replace_once(
    en,
    'Macros can call other macros, up to 8 nested levels. A command that opens an interactive selector must be the final command in a macro because selector completion is asynchronous. For example, `terrain CHASM` opens a selector and must be last, while `terrain CHASM @cell` already has an explicit cell and may appear earlier.\n\nMacros are persisted separately from normal game saves.\n',
    'Macros can call other macros, up to 8 nested levels. A command that opens an interactive selector must be the final command in a macro because selector completion is asynchronous. For example, `terrain CHASM` opens a selector and must be last, while `terrain CHASM @cell` already has an explicit cell and may appear earlier.\n\nMacros also support standalone `!!` / `!! N` with macro-local history; see the next section for the exact rules.\n\nMacros are persisted separately from normal game saves.\n',
    en_path,
)
en_path.write_text(en, encoding="utf-8")
