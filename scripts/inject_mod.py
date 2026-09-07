import sys
import re
from pathlib import Path

wnd_path = Path(sys.argv[1])


def patch_wndgame(file_path: Path) -> None:
    content = file_path.read_text(encoding='utf-8')
    marker = '// MASTER_MODE_MENU'
    if marker not in content:
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
        file_path.write_text(content, encoding='utf-8')
        print(f"Mod menu injected successfully into {file_path}")
    else:
        print(f"Mod menu already injected into {file_path}")


def patch_char(file_path: Path) -> None:
    content = file_path.read_text(encoding='utf-8')

    attack_marker = '// MASTER_MODE_INCOMING_ATTACK'
    if attack_marker not in content:
        attack_pattern = re.compile(
            r'(public\s+boolean\s+attack\s*\(\s*Char\s+enemy\s*,\s*float\s+dmgMulti\s*,\s*'
            r'float\s+dmgBonus\s*,\s*float\s+accMulti\s*\)\s*\{\s*'
            r'if\s*\(\s*enemy\s*==\s*null\s*\)\s*return\s+false\s*;)'
        )
        attack_hook = """

        // MASTER_MODE_INCOMING_ATTACK
        com.spd.mod.mechanics.ModParryRiposte.onIncomingAttack(this, enemy);"""
        content, count = attack_pattern.subn(r'\1' + attack_hook, content, count=1)
        if count != 1:
            raise RuntimeError(f"Char.attack injection point not found in {file_path}")
        print(f"Incoming Char.attack hook injected successfully into {file_path}")
    else:
        print(f"Incoming Char.attack hook already injected into {file_path}")

    hit_marker = '// MASTER_MODE_INCOMING_HIT'
    if hit_marker not in content:
        hit_pattern = re.compile(
            r'(public\s+static\s+boolean\s+hit\s*\(\s*Char\s+attacker\s*,\s*'
            r'Char\s+defender\s*,\s*float\s+accMulti\s*,\s*boolean\s+magic\s*\)\s*\{)'
        )
        hit_hook = """

        // MASTER_MODE_INCOMING_HIT
        com.spd.mod.mechanics.ModParryRiposte.onIncomingAttack(attacker, defender);"""
        content, count = hit_pattern.subn(r'\1' + hit_hook, content, count=1)
        if count != 1:
            raise RuntimeError(f"Char.hit injection point not found in {file_path}")
        print(f"Incoming Char.hit hook injected successfully into {file_path}")
    else:
        print(f"Incoming Char.hit hook already injected into {file_path}")

    file_path.write_text(content, encoding='utf-8')


patch_wndgame(wnd_path)

# All current source-build entry points already invoke this script with WndGame.java.
# Derive Char.java from that stable package location so source builds receive both
# Riposte pre-resolution hooks without adding another build-system patch step.
package_root = wnd_path.parent.parent
char_path = package_root / 'actors' / 'Char.java'
if not char_path.is_file():
    raise RuntimeError(f"Char.java not found beside WndGame package root: {char_path}")
patch_char(char_path)
