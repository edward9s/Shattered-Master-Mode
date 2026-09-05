from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    return text.replace(old, new, 1)


console_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug$Console.java")
text = console_path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '"help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load",',
    '"help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | !! | search | results | get | set | clear | save | load",',
    console_path,
)

old_execute = '''    private static void execute(String command) throws Exception {\n        if (command.contains("!!")) {\n            if (lastCommand.isEmpty()) {\n                throw new IllegalStateException("No previous debug command");\n            }\n            command = command.replace("!!", lastCommand);\n            GLog.i("> " + command);\n        }\n\n        lastCommand = command;\n        executeLine(command, 0, true);\n    }\n'''
new_execute = '''    private static void execute(String command) throws Exception {\n        Integer historyCount = historyRepeatCount(command);\n        if (historyCount != null) {\n            if (lastCommand.isEmpty()) {\n                throw new IllegalStateException("No previous debug command");\n            }\n            runHistoryCommand(lastCommand, historyCount, 0, true);\n            return;\n        }\n\n        if (command.contains("!!")) {\n            if (lastCommand.isEmpty()) {\n                throw new IllegalStateException("No previous debug command");\n            }\n            command = command.replace("!!", lastCommand);\n            GLog.i("> " + command);\n        }\n\n        lastCommand = command;\n        executeLine(command, 0, true);\n    }\n\n    private static Integer historyRepeatCount(String command) throws Exception {\n        return (Integer) invokePrivate(\n                "historyRepeatCount",\n                new Class<?>[]{String.class},\n                new Object[]{command});\n    }\n\n    private static void runHistoryCommand(\n            String command, int count, int macroDepth, boolean topLevel)\n            throws Exception {\n\n        if (count > 1\n                && commandOrMacroNeedsSelector(command, macroDepth)) {\n            throw new IllegalArgumentException(\n                    "Cannot repeat a command that opens an interactive selector more than once; "\n                            + "supply an explicit cell/handle where the command supports one");\n        }\n\n        GLog.i(\n                "> " + command\n                        + (count == 1 ? "" : "  [" + count + " times]"));\n\n        for (int i = 0; i < count; i++) {\n            executeLine(command, macroDepth, topLevel);\n        }\n    }\n'''
text = replace_once(text, old_execute, new_execute, console_path)

start = text.index("    private static boolean runMacro(\n")
end = text.index("    private static String expandMacroLine(", start)
new_macro = '''    private static boolean runMacro(\n            String name, List<String> args, int depth) throws Exception {\n\n        loadMacros();\n        String body = macros().get(name);\n        if (body == null) {\n            return false;\n        }\n        if (depth >= 8) {\n            throw new IllegalStateException("Macro recursion limit reached");\n        }\n\n        List<String> expanded = new ArrayList<String>();\n        String[] lines = body.split("\\\\r?\\\\n");\n        for (String line : lines) {\n            String trimmed = line.trim();\n            if (trimmed.isEmpty() || trimmed.startsWith("#")) {\n                continue;\n            }\n            expanded.add(expandMacroLine(trimmed, args));\n        }\n\n        String previousCommand = null;\n        for (int i = 0; i < expanded.size(); i++) {\n            String line = expanded.get(i);\n            Integer historyCount = historyRepeatCount(line);\n\n            if (historyCount != null) {\n                if (previousCommand == null) {\n                    throw new IllegalStateException(\n                            "No previous command in this macro invocation");\n                }\n                GLog.i("> " + line);\n                runHistoryCommand(\n                        previousCommand, historyCount, depth + 1, false);\n                continue;\n            }\n\n            if (i + 1 < expanded.size()\n                    && commandOrMacroNeedsSelector(line, depth + 1)) {\n                throw new IllegalArgumentException(\n                        "Selector command must be the final macro line: " + line);\n            }\n\n            GLog.i("> " + line);\n            executeLine(line, depth + 1, false);\n            previousCommand = line;\n        }\n        return true;\n    }\n\n'''
text = text[:start] + new_macro + text[end:]

old_selector = '''    private static boolean commandNeedsSelector(String line) throws Exception {\n        Object result = invokePrivate(\n                "commandNeedsSelector",\n                new Class<?>[]{String.class},\n                new Object[]{line});\n        return Boolean.TRUE.equals(result);\n    }\n'''
new_selector = '''    private static boolean commandOrMacroNeedsSelector(\n            String line, int macroDepth) throws Exception {\n        Object result = invokePrivate(\n                "commandOrMacroNeedsSelector",\n                new Class<?>[]{String.class, int.class},\n                new Object[]{line, macroDepth});\n        return Boolean.TRUE.equals(result);\n    }\n'''
text = replace_once(text, old_selector, new_selector, console_path)
console_path.write_text(text, encoding="utf-8")


core_path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
core = core_path.read_text(encoding="utf-8")
core = replace_once(
    core,
    '"help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load",',
    '"help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | !! | search | results | get | set | clear | save | load",',
    core_path,
)
core_path.write_text(core, encoding="utf-8")
