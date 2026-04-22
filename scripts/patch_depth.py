import sys
import re

# 取得傳入的深度數值，未傳入則預設為 26
depth = sys.argv[1] if len(sys.argv) > 1 else "26"
file_path = "spd_src/core/src/main/java/com/spd/mod/ModGame.java"

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
