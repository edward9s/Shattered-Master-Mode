from pathlib import Path


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    return text.replace(old, new, 1)


en_path = Path("docs/debug_console.md")
en = en_path.read_text(encoding="utf-8")
en = replace_once(
    en,
    "For backward compatibility, when `!!` is not a complete history-command line and instead appears inside other top-level command text, the original inline textual expansion remains available. Macro-local history recognizes only standalone `!!` / `!! N` lines.\n",
    """For backward compatibility, when `!!` is not a complete history-command line and instead appears inside other top-level command text, the original ScrollOfDebug-style inline textual expansion remains available. The `!!` token is replaced by the previous top-level command text before the resulting command is parsed. For example:\n\n```text\ngive PotionOfHealing\n!! x10\n# expands to: give PotionOfHealing x10\n\ngive Longsword\n!! +10\n# expands to: give Longsword +10\n```\n\nThis is deliberately different from batch replay. `!! 10` is a complete history command and means \"execute the previous command 10 additional times\"; `!! x10` and `!! +10` do not match the batch form, so they perform textual expansion and append `x10` / `+10` to the previous command. Inline expansion is only a top-level Console feature; macro-local history recognizes only standalone `!!` / `!! N` lines.\n""",
    en_path,
)
en_path.write_text(en, encoding="utf-8")

zh_path = Path("docs/debug_console.zh-TW.md")
zh = zh_path.read_text(encoding="utf-8")
zh = replace_once(
    zh,
    "為了向後相容，若 `!!` 不是完整的一行 history 指令，而是出現在其他頂層命令文字中，仍保留原本的 inline 文字展開行為。Macro-local history 則只認獨立成行的 `!!` / `!! N`。\n",
    """為了向後相容，若 `!!` 不是完整的一行 history 指令，而是出現在其他頂層命令文字中，仍保留原本 ScrollOfDebug 的 inline 文字展開行為。Console 會先把 `!!` 替換成上一條頂層指令的完整文字，再解析展開後的命令。例如：\n\n```text\ngive PotionOfHealing\n!! x10\n# 展開為：give PotionOfHealing x10\n\ngive Longsword\n!! +10\n# 展開為：give Longsword +10\n```\n\n這和批次重跑刻意採用不同語意。`!! 10` 本身完整符合 history 指令，因此表示「把上一條指令額外執行 10 次」；`!! x10` 與 `!! +10` 不符合批次格式，所以會先做文字展開，等於在上一條命令後追加 `x10` 或 `+10`。Inline 展開只屬於頂層 Console；macro-local history 只認獨立成行的 `!!` / `!! N`。\n""",
    zh_path,
)
zh_path.write_text(zh, encoding="utf-8")
