import sys
import re

file_path = sys.argv[1]

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 插入靜態變數 instance
content = re.sub(
    r'(public class WndGame extends Window \{)',
    r'\1\n    public static WndGame instance;\n',
    content,
    count=1
)

# 插入建構子邏輯
mod_code = """
        instance = this;
        com.spd.mod.ModGame.loadSettings();
        addButton(new com.spd.mod.mechanics.ModDepthSelector.OpenBtn());
        addButton(new com.spd.mod.tools.ModToolsWindow.OpenBtn());"""

content = re.sub(
    r'(public WndGame\(\)\s*\{\s*super\(\);)',
    r'\1' + mod_code,
    content,
    count=1
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print(f"Mod code injected successfully into {file_path}")
