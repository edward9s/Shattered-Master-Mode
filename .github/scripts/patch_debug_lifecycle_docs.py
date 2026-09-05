from pathlib import Path

mod = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
s = mod.read_text(encoding="utf-8")

old = '''            case "macro":
                GLog.i(
                        "macro [name]\\n"
                        + "Lists macros, or opens the editor for one macro. %1..%9 are arguments.\\n"
                        + "Standalone !! inside a macro repeats that invocation's previous command.\\n"
                        + "Commands that open a selector must be the final macro line."
                );
                return;
'''
new = '''            case "macro":
                GLog.i(
                        "macro [name]\\n"
                        + "Lists macros, or opens the editor for one macro. %1..%9 are arguments.\\n"
                        + "Macros are persisted across app/process restarts, separately from normal game saves.\\n"
                        + "Standalone !! inside a macro repeats that invocation's previous command.\\n"
                        + "Commands that open a selector must be the final macro line."
                );
                return;
'''
if s.count(old) != 1:
    raise SystemExit(f"macro help anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

old = '''            case "@":
            case "handle":
            case "handles":
                GLog.i(
                        "@handle operations\\n"
                        + "@                 list handles\\n"
                        + "@x inv            store an inventory Item\\n"
                        + "@x cell           store a selected cell\\n"
                        + "@x char           store a selected character\\n"
                        + "@x obj            store an object/cell\\n"
                        + "@x hero|level     store current hero/level\\n"
                        + "@x                show a handle\\n"
                        + "@x clear          delete a handle\\n"
                        + "Prefix a returning command with @x to capture its result."
                );
                return;
'''
new = '''            case "@":
            case "handle":
            case "handles":
                GLog.i(
                        "@handle operations\\n"
                        + "@                 list handles\\n"
                        + "@x inv            store an inventory Item\\n"
                        + "@x cell           store a selected cell\\n"
                        + "@x char           store a selected character\\n"
                        + "@x obj            store an object/cell\\n"
                        + "@x hero|level     store current hero/level\\n"
                        + "@x                show a handle\\n"
                        + "@x clear          delete a handle\\n"
                        + "Prefix a returning command with @x to capture its result.\\n"
                        + "Handles are process-local only: floor changes do not clear them, but a process restart does."
                );
                return;
'''
if s.count(old) != 1:
    raise SystemExit(f"handle help anchor count: {s.count(old)}")
s = s.replace(old, new, 1)
mod.write_text(s, encoding="utf-8")

en = Path("docs/debug_console.md")
e = en.read_text(encoding="utf-8")
old = '''A handle gives a temporary name to a live Java object or value. Handles exist only for the current game process; they are not stored in the save file.
'''
new = '''A handle gives a temporary name to a live Java object or value. Handles are **process-local only**: they live in an in-memory static map and are not written to a save file or another persistent store. Changing floors does not automatically clear them, but terminating/restarting the app process clears all handles. Simply putting the app in the background may leave them intact if Android keeps the process alive.

Because object handles are strong references, a floor transition can leave a handle pointing at an object from the old floor (for example an old `Level` or Mob). The handle still exists, but that object may no longer belong to the active game state. Numeric handles such as a stored cell also remain in memory, although the same cell number can mean a different location on another floor.
'''
if e.count(old) != 1:
    raise SystemExit(f"English handle lifecycle anchor count: {e.count(old)}")
e = e.replace(old, new, 1)
e = e.replace('@rat spawn Rat -p', '@rat spawn Rat')
old = '''Macros are persisted separately from normal game saves.
'''
new = '''Macros are **persistent across game/process restarts** and are stored separately from normal game saves. On Android they are written to the app-private file `filesDir/smm-debug-macros.properties`; on desktop the fallback location is `~/.smm-debug-macros.properties`. They remain until the macro is deleted (save an empty body), or the corresponding app/private data is removed. Clearing app data or uninstalling the Android app removes the Android macro file.
'''
if e.count(old) != 1:
    raise SystemExit(f"English macro lifecycle anchor count: {e.count(old)}")
e = e.replace(old, new, 1)
en.write_text(e, encoding="utf-8")

zh = Path("docs/debug_console.zh-TW.md")
z = zh.read_text(encoding="utf-8")
old = '''`@` handle 可以替目前遊戲中的實際 Java 物件或值取一個暫時名稱。Handle 只存在於目前程序記憶體，不會寫進遊戲存檔。
'''
new = '''`@` handle 可以替目前遊戲中的實際 Java 物件或值取一個暫時名稱。Handle **只存在於目前 app process 的記憶體**：實作上只是 static map，不會寫進遊戲存檔或其他持久化儲存。換樓層不會自動清除 handle；但 app process 被終止或遊戲真正重新啟動後，所有 handle 都會消失。單純把遊戲切到背景時，如果 Android 沒有殺掉 process，handle 可能仍然存在。

Object handle 保存的是強引用，所以換樓層後，某些 handle 仍可能指向上一層已不屬於目前遊戲狀態的物件，例如舊 `Level` 或 Mob。Handle 本身還在，但該物件可能已不適合繼續操作。像 `@cell` 這種整數值也會保留，但換樓層後同一個 cell 編號當然可能代表完全不同的位置。
'''
if z.count(old) != 1:
    raise SystemExit(f"Chinese handle lifecycle anchor count: {z.count(old)}")
z = z.replace(old, new, 1)
z = z.replace('@rat spawn Rat -p', '@rat spawn Rat')
old = '''Macro 會獨立持久化，不依賴一般遊戲存檔。
'''
new = '''Macro **會跨遊戲／process 重啟持久保存**，而且與一般遊戲存檔分開。Android 會寫到 app 私有的 `filesDir/smm-debug-macros.properties`；desktop fallback 則是 `~/.smm-debug-macros.properties`。Macro 會一直保留，直到把該 macro 儲存成空內容來刪除，或對應的 app/private data 被移除。Android 清除應用程式資料或解除安裝時，也會刪除這個 macro 檔案。
'''
if z.count(old) != 1:
    raise SystemExit(f"Chinese macro lifecycle anchor count: {z.count(old)}")
z = z.replace(old, new, 1)
zh.write_text(z, encoding="utf-8")
