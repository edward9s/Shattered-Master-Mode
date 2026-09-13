import sys
import re
from pathlib import Path

wnd_path = Path(sys.argv[1])

_ATTACK_METHOD_RE = re.compile(
    r'(?P<head>(?:(?:public|protected|final|synchronized|native|strictfp)\s+)*'
    r'boolean\s+attack\s*\((?P<params>[^)]*)\)\s*\{)'
)
_SELF_ATTACK_RE = re.compile(r'(?<![\w.])(?:this\s*\.\s*)?attack\s*\(')


def _mask_non_code(text: str) -> str:
    """Mask comments and string/char literals while preserving positions/newlines."""
    chars = list(text)
    i = 0
    state = 'code'
    quote = ''

    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ''

        if state == 'code':
            if ch == '/' and nxt == '/':
                chars[i] = chars[i + 1] = ' '
                state = 'line'
                i += 1
            elif ch == '/' and nxt == '*':
                chars[i] = chars[i + 1] = ' '
                state = 'block'
                i += 1
            elif ch in ('"', "'"):
                chars[i] = ' '
                state = 'string'
                quote = ch

        elif state == 'line':
            if ch == '\n':
                state = 'code'
            else:
                chars[i] = ' '

        elif state == 'block':
            if ch == '*' and nxt == '/':
                chars[i] = chars[i + 1] = ' '
                state = 'code'
                i += 1
            elif ch != '\n':
                chars[i] = ' '

        elif state == 'string':
            if ch == '\\':
                chars[i] = ' '
                if i + 1 < len(text):
                    if text[i + 1] != '\n':
                        chars[i + 1] = ' '
                    i += 1
            elif ch == quote:
                chars[i] = ' '
                state = 'code'
            elif ch != '\n':
                chars[i] = ' '

        i += 1

    return ''.join(chars)


def _find_matching_brace(masked: str, open_index: int) -> int:
    depth = 0
    for index in range(open_index, len(masked)):
        if masked[index] == '{':
            depth += 1
        elif masked[index] == '}':
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError('Unmatched Char.attack method brace')


def _terminal_attack_method(content: str) -> tuple[str, int, int]:
    """
    Find the one Char.attack overload that does not delegate to another attack()
    overload in the same class. Source builds mirror the APK injector's
    fail-closed terminal-overload rule instead of assuming a fixed signature.
    """
    masked = _mask_non_code(content)
    candidates = []

    for match in _ATTACK_METHOD_RE.finditer(masked):
        if re.search(r'\bstatic\b', match.group('head')):
            continue

        params = content[match.start('params'):match.end('params')]
        first_param = params.split(',', 1)[0].strip()
        defender_match = re.search(
            r'\bChar\s+([A-Za-z_$][\w$]*)\s*$',
            first_param,
        )
        if defender_match is None:
            continue

        open_brace = match.end('head') - 1
        close_brace = _find_matching_brace(masked, open_brace)
        body_masked = masked[open_brace + 1:close_brace]
        delegates = _SELF_ATTACK_RE.search(body_masked) is not None

        candidates.append(
            (defender_match.group(1), open_brace, close_brace, delegates, params.strip())
        )

    terminals = [candidate for candidate in candidates if not candidate[3]]
    if len(terminals) != 1:
        signatures = ', '.join(candidate[4] for candidate in candidates) or 'none'
        raise RuntimeError(
            'Unable to identify one terminal Char.attack overload '
            f'(found {len(terminals)} terminals among: {signatures})'
        )

    defender, open_brace, close_brace, _, _ = terminals[0]
    return defender, open_brace, close_brace


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
    pre_marker = '// MASTER_MODE_INCOMING_ATTACK'
    completion_marker = '// MASTER_MODE_INCOMING_ATTACK_COMPLETE'

    if completion_marker in content:
        print(f"Incoming-attack entry/completion hooks already injected into {file_path}")
        return

    defender, open_brace, close_brace = _terminal_attack_method(content)
    body = content[open_brace + 1:close_brace]

    # Upgrade source trees that were already patched by the old pre-hook-only
    # implementation instead of adding a duplicate onIncomingAttack() call.
    old_pre_hook = re.compile(
        r'\s*// MASTER_MODE_INCOMING_ATTACK\s*\n'
        r'\s*com\.spd\.mod\.mechanics\.ModParryRiposte\.onIncomingAttack'
        r'\(\s*this\s*,\s*' + re.escape(defender) + r'\s*\);\s*'
    )
    clean_body, old_hook_count = old_pre_hook.subn('\n', body, count=1)

    if pre_marker in body and old_hook_count != 1:
        raise RuntimeError(
            f"Unrecognized existing incoming-attack hook in terminal Char.attack: {file_path}"
        )
    if pre_marker in content and pre_marker not in body:
        raise RuntimeError(
            f"Existing incoming-attack hook is not in terminal Char.attack: {file_path}"
        )

    # try/finally preserves Java return-expression evaluation order: completion
    # runs after the terminal attack result has been computed, while still
    # guaranteeing every normal return path reaches onIncomingAttackComplete().
    indented_body = re.sub(r'(?m)^', '\t', clean_body)
    wrapped_body = (
        "\n\t\t// MASTER_MODE_INCOMING_ATTACK\n"
        f"\t\tcom.spd.mod.mechanics.ModParryRiposte.onIncomingAttack(this, {defender});\n"
        "\t\ttry {"
        f"{indented_body}"
        "\n\t\t} finally {\n"
        "\t\t\t// MASTER_MODE_INCOMING_ATTACK_COMPLETE\n"
        f"\t\t\tcom.spd.mod.mechanics.ModParryRiposte.onIncomingAttackComplete(this, {defender});\n"
        "\t\t}\n\t"
    )

    content = (
        content[:open_brace + 1]
        + wrapped_body
        + content[close_brace:]
    )
    file_path.write_text(content, encoding='utf-8')
    print(f"Incoming-attack entry/completion hooks injected successfully into {file_path}")


patch_wndgame(wnd_path)

# All current source-build entry points already invoke this script with WndGame.java.
# Derive Char.java from that stable package location so source builds receive the
# same terminal-attack Riposte lifecycle as APK injection.
package_root = wnd_path.parent.parent
char_path = package_root / 'actors' / 'Char.java'
if not char_path.is_file():
    raise RuntimeError(f"Char.java not found beside WndGame package root: {char_path}")
patch_char(char_path)
