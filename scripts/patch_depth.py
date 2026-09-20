import sys
import re

if len(sys.argv) > 3:
    raise SystemExit("usage: patch_depth.py [DEPTH] [SPD_ROOT]")

depth = sys.argv[1] if len(sys.argv) > 1 else "26"
root = sys.argv[2] if len(sys.argv) > 2 else "spd_src"
file_path = f"{root}/core/src/main/java/com/spd/mod/ModGame.java"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 精準匹配 maxDepth 方法並替換數值
new_content = re.sub(
    r'(public static int maxDepth\(\)\s*\{\s*return\s+)\d+(;\s*\})',
    rf'\g<1>{depth}\g<2>',
    content
)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(new_content)

print(f"ModGame.maxDepth() patched to {depth}")
