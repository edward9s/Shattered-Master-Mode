from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:140]!r}")
    return text.replace(old, new, 1)


src_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
src = src_path.read_text(encoding="utf-8")

src = replace_once(
    src,
    'help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load',
    'help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | repeat | @ | search | results | get | set | clear | save | load',
    src_path,
)

src = replace_once(
    src,
    '''            case "macro":
                macro(args);
                return;

            case "search":
''',
    '''            case "macro":
                macro(args);
                return;

            case "repeat":
                repeat(args, macroDepth);
                return;

            case "search":
''',
    src_path,
)

src = replace_once(
    src,
    '                + "macro [name]  (edit; empty body deletes; %1..%9 are arguments)\\n"\n',
    '                + "macro [name]  (edit; empty body deletes; %1..%9 are arguments)\\n"\n'
    '                + "repeat <count> <command...>  (1..1000; no interactive selectors)\\n"\n',
    src_path,
)

repeat_method = r'''    private static void repeat(
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

src = replace_once(
    src,
    '    private static boolean runMacro(\n',
    repeat_method + '    private static boolean runMacro(\n',
    src_path,
)

src = replace_once(
    src,
    '''        if ("affect".equals(command)
                || "seed".equals(command)
                || "trap".equals(command)
                || "macro".equals(command)) {
            return true;
        }

        if ("warp".equals(command)) {
''',
    '''        if ("affect".equals(command)
                || "seed".equals(command)
                || "trap".equals(command)
                || "macro".equals(command)) {
            return true;
        }

        if ("terrain".equals(command)) {
            return tokens.size() < 3;
        }

        if ("repeat".equals(command)) {
            return tokens.size() >= 3
                    && commandNeedsSelector(joinCommandTokens(tokens, 2));
        }

        if ("warp".equals(command)) {
''',
    src_path,
)

src = replace_once(
    src,
    '                "terrain", "warp", "inspect", "use",\n                "enchant", "inscribe",\n                "goto", "where", "macro",\n',
    '                "terrain", "warp", "inspect", "use",\n                "enchant", "inscribe",\n                "goto", "where", "macro", "repeat",\n',
    src_path,
)

src_path.write_text(src, encoding="utf-8")


zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
zh = replace_once(
    zh,
    '''### 產生 Blob

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

語法：

```text
seed <Blob> [amount]
```

輸入後再選地圖 cell。
''',
    '''### 產生 Blob：`seed`

`seed` 用來在指定地圖 cell 建立一個 `Blob`，例如火焰、毒氣、麻痺氣體等持續存在於格子上的區域效果。

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

語法：

```text
seed <Blob> [amount]
```

輸入後再選地圖 cell。`amount` 預設為 `1`，會傳給目標遊戲的 `Blob.seed(cell, amount, class)`；它通常代表初始 volume／強度，但實際意義仍由各 Blob class 決定，不應一律當成持續回合數。Blob class 名稱也支援 fuzzy，例如 `seed toxgas 100` 在只有一個最佳相容匹配時會採用該 Blob class。

若在前面加 handle，可以保留建立後的實際 Blob instance：

```text
@gas seed ToxicGas 100
inspect @gas
```
''',
    zh_path,
)

old_macro_zh = '''## Macro

```text
macro
macro test
```

- `macro`：列出目前保存的 macro。
- `macro name`：打開該 macro 的編輯器。
- 一行放一條 debug command。
- `%1` 到 `%9` 是位置參數。
- 儲存空內容等於刪除 macro。

例如 macro 內容：

```text
give PotionOfHealing x%1
warp %2
```

之後執行：

```text
test 10 123
```

Macro 可以呼叫其他 macro，但有遞迴上限。會打開互動式 selector 的指令必須放在 macro 最後一行，因為 selector 的完成是非同步的。

Macro 會獨立持久化，不依賴一般遊戲存檔。
'''
new_macro_zh = '''## Macro

```text
macro
macro test
```

- `macro`：列出目前保存的 macro。
- `macro name`：打開該 macro 的編輯器。
- 一行放一條 debug command。
- 空白行與 `#` 開頭的行會忽略，可拿來寫註解。
- `%1` 到 `%9` 是位置參數。
- 儲存空內容等於刪除 macro。

例如建立 `test` macro，內容填：

```text
give PotionOfHealing x%1
warp %2
```

之後執行：

```text
test 10 123
```

就等於依序執行：

```text
give PotionOfHealing x10
warp 123
```

Macro 可以呼叫其他 macro，但最多只能巢狀 8 層。會打開互動式 selector 的指令必須放在 macro 最後一行，因為 selector 的完成是非同步的。例如 `terrain CHASM` 會開 selector，所以只能放最後；`terrain CHASM @cell` 已有明確 cell，不會開 selector，因此可以放在中間。

Macro 會獨立持久化，不依賴一般遊戲存檔。

## 重複執行：`repeat`

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
'''
zh = replace_once(zh, old_macro_zh, new_macro_zh, zh_path)
zh_path.write_text(zh, encoding="utf-8")


en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
en = replace_once(
    en,
    '''### Seed a blob

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

Syntax:

```text
seed <Blob> [amount]
```

Select a tile after entering the command.
''',
    '''### Seed a blob: `seed`

`seed` creates a `Blob` on a selected map cell, such as fire, toxic gas, paralysis gas, or another area effect that persists on map cells.

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

Syntax:

```text
seed <Blob> [amount]
```

Select a tile after entering the command. `amount` defaults to `1` and is passed to the target game's `Blob.seed(cell, amount, class)`. It commonly represents initial volume/intensity, but the exact meaning is defined by each Blob class and should not be assumed to mean duration. Blob class names support fuzzy matching as well; for example, `seed toxgas 100` uses the unique best compatible Blob match when one exists.

A handle prefix stores the actual created Blob instance:

```text
@gas seed ToxicGas 100
inspect @gas
```
''',
    en_path,
)

old_macro_en = '''## Macros

```text
macro
macro test
```

- `macro` lists saved macros.
- `macro name` opens an editor for that macro.
- Put one debug command per line.
- `%1` through `%9` are positional arguments.
- Saving an empty macro deletes it.

Example macro body:

```text
give PotionOfHealing x%1
warp %2
```

Then run:

```text
test 10 123
```

Macros can call other macros, with a recursion limit. A command that opens an interactive selector must be the final command in a macro because selector completion is asynchronous.

Macros are persisted separately from normal game saves.
'''
new_macro_en = '''## Macros

```text
macro
macro test
```

- `macro` lists saved macros.
- `macro name` opens an editor for that macro.
- Put one debug command per line.
- Blank lines and lines beginning with `#` are ignored and can be used as comments.
- `%1` through `%9` are positional arguments.
- Saving an empty macro deletes it.

For example, create a macro named `test` with this body:

```text
give PotionOfHealing x%1
warp %2
```

Then run:

```text
test 10 123
```

which executes:

```text
give PotionOfHealing x10
warp 123
```

Macros can call other macros, up to 8 nested levels. A command that opens an interactive selector must be the final command in a macro because selector completion is asynchronous. For example, `terrain CHASM` opens a selector and must be last, while `terrain CHASM @cell` already has an explicit cell and may appear earlier.

Macros are persisted separately from normal game saves.

## Repeating commands: `repeat`

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
'''
en = replace_once(en, old_macro_en, new_macro_en, en_path)
en_path.write_text(en, encoding="utf-8")
