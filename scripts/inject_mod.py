import sys
import re

file_path = sys.argv[1]

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

marker = '// MASTER_MODE_MENU'
if marker in content:
    print(f"Mod code already injected into {file_path}")
    sys.exit(0)

mod_code = """
        // MASTER_MODE_MENU
        com.spd.mod.ModGame.installMenu(this::addButton);"""

content, count = re.subn(
    r'(public WndGame\(\)\s*\{\s*super\(\);)',
    r'\1' + mod_code,
    content,
    count=1
)

if count != 1:
    raise RuntimeError(f"WndGame injection point not found in {file_path}")

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print(f"Mod code injected successfully into {file_path}")
