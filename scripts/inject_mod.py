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
    marker = '// MASTER_MODE_INCOMING_ATTACK'
    if marker in content:
        print(f"Incoming-attack hook already injected into {file_path}")
        return

    # Forks commonly add their own setup logic at the start of Char.attack(), so
    # do not depend on any particular first statement (such as a null guard).
    # Match the stable four-argument attack entry point itself and preserve the
    # fork's actual defender parameter name.
    pattern = re.compile(
        r'(public\s+boolean\s+attack\s*\(\s*Char\s+([A-Za-z_$][\w$]*)\s*,\s*'
        r'float\s+[A-Za-z_$][\w$]*\s*,\s*float\s+[A-Za-z_$][\w$]*\s*,\s*'
        r'float\s+[A-Za-z_$][\w$]*\s*\)\s*\{)'
    )

    def inject_hook(match: re.Match) -> str:
        defender = match.group(2)
        hook = f"""

        // MASTER_MODE_INCOMING_ATTACK
        com.spd.mod.mechanics.ModParryRiposte.onIncomingAttack(this, {defender});"""
        return match.group(1) + hook

    content, count = pattern.subn(inject_hook, content, count=1)
    if count != 1:
        raise RuntimeError(f"Char.attack injection point not found in {file_path}")
    file_path.write_text(content, encoding='utf-8')
    print(f"Incoming-attack hook injected successfully into {file_path}")


patch_wndgame(wnd_path)

# All current source-build entry points already invoke this script with WndGame.java.
# Derive Char.java from that stable package location so source builds receive the
# Riposte pre-resolution hook without adding another build-system patch step.
package_root = wnd_path.parent.parent
char_path = package_root / 'actors' / 'Char.java'
if not char_path.is_file():
    raise RuntimeError(f"Char.java not found beside WndGame package root: {char_path}")
patch_char(char_path)
