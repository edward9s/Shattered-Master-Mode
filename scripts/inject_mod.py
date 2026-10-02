import sys
import re
from pathlib import Path

from _attack_hook_common import select_unique_terminal
from spd_source import wndgame_path

if len(sys.argv) != 2:
    raise SystemExit("usage: inject_mod.py <SPD_ROOT>")

source_root = Path(sys.argv[1])
if not source_root.is_dir():
    raise RuntimeError(f"SPD source root not found: {source_root}")
wnd_path = wndgame_path(source_root)

_ATTACK_METHOD_RE = re.compile(
    r'(?P<head>(?:(?:public|protected|final|synchronized|native|strictfp)\s+)*'
    r'boolean\s+attack\s*\((?P<params>[^)]*)\)\s*\{)'
)
_SELF_ATTACK_RE = re.compile(r'(?<![\w.])(?:this\s*\.\s*)?attack\s*\(')
_HIT_METHOD_RE = re.compile(
    r'(?P<head>(?:(?:public|protected|private|final|static|synchronized|native|strictfp)\s+)*'
    r'boolean\s+hit\s*\((?P<params>[^)]*)\)\s*\{)'
)


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


def _find_matching(text: str, open_index: int, opener: str, closer: str) -> int:
    depth = 0
    for index in range(open_index, len(text)):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError(f'Unmatched {opener}{closer} while parsing Char.attack')


def _argument_count(masked: str) -> int:
    if not masked.strip():
        return 0

    depth = 0
    count = 1
    pairs = {'(': ')', '[': ']', '{': '}'}
    closers = set(pairs.values())
    for ch in masked:
        if ch in pairs:
            depth += 1
        elif ch in closers:
            depth -= 1
            if depth < 0:
                raise RuntimeError('Unbalanced expression while parsing Char.attack')
        elif ch == ',' and depth == 0:
            count += 1
    if depth != 0:
        raise RuntimeError('Unbalanced expression while parsing Char.attack')
    return count


def _self_attack_arities(masked_body: str) -> list[int]:
    arities = []
    for match in _SELF_ATTACK_RE.finditer(masked_body):
        open_paren = masked_body.find('(', match.start(), match.end())
        if open_paren < 0:
            raise RuntimeError('Malformed Char.attack delegation call')
        close_paren = _find_matching(masked_body, open_paren, '(', ')')
        arities.append(_argument_count(masked_body[open_paren + 1:close_paren]))
    return arities


def _terminal_attack_method(content: str) -> tuple[str, int, int]:
    """Find the unique terminal Char.attack() using the shared graph semantics."""
    masked = _mask_non_code(content)
    candidates: dict[str, tuple[str, int, int, int]] = {}
    by_arity: dict[int, list[str]] = {}

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

        signature = params.strip()
        if signature in candidates:
            raise RuntimeError(f'Duplicate Char.attack signature: {signature}')

        open_brace = match.end('head') - 1
        close_brace = _find_matching(masked, open_brace, '{', '}')
        arity = _argument_count(_mask_non_code(params))
        candidates[signature] = (
            defender_match.group(1), open_brace, close_brace, arity
        )
        by_arity.setdefault(arity, []).append(signature)

    edges: dict[str, set[str]] = {signature: set() for signature in candidates}
    for signature, (_, open_brace, close_brace, _) in candidates.items():
        body_masked = masked[open_brace + 1:close_brace]
        for arity in _self_attack_arities(body_masked):
            targets = by_arity.get(arity, [])
            if len(targets) != 1:
                found = ', '.join(targets) if targets else 'none'
                raise RuntimeError(
                    f'Unable to resolve Char.attack delegation with {arity} argument(s); '
                    f'candidates: {found}'
                )
            edges[signature].add(targets[0])

    terminal, detail = select_unique_terminal(candidates, edges)
    if terminal is None:
        raise RuntimeError('Unable to identify terminal Char.attack overload: ' + detail)

    defender, open_brace, close_brace, _ = candidates[terminal]
    return defender, open_brace, close_brace


def _char_hit_method(content: str) -> tuple[str, str, int]:
    """Find static Char.hit(Char, Char, float, boolean) and its parameter names."""
    masked = _mask_non_code(content)
    matches = []

    for match in _HIT_METHOD_RE.finditer(masked):
        if not re.search(r'\bstatic\b', match.group('head')):
            continue

        params = content[match.start('params'):match.end('params')]
        parts = [part.strip() for part in params.split(',')]
        if len(parts) != 4:
            continue

        parsed = []
        valid = True
        for part in parts:
            tokens = [token for token in re.split(r'\s+', part) if token and token != 'final']
            if len(tokens) < 2:
                valid = False
                break
            parsed.append((tokens[-2], tokens[-1]))

        if not valid:
            continue

        types = [typ for typ, _ in parsed]
        if types != ['Char', 'Char', 'float', 'boolean']:
            continue

        open_brace = match.end('head') - 1
        matches.append((parsed[0][1], parsed[1][1], open_brace))

    if len(matches) != 1:
        raise RuntimeError(
            'Expected exactly one static Char.hit(Char, Char, float, boolean), '
            f'found {len(matches)}'
        )
    return matches[0]


def patch_char_hit(file_path: Path) -> None:
    content = file_path.read_text(encoding='utf-8')
    force_marker = '// MASTER_MODE_FORCE_HIT'
    parry_marker = '// MASTER_MODE_PARRY'

    if force_marker in content or parry_marker in content:
        if force_marker in content and parry_marker in content:
            print(f"Force Hit + Parry hooks already injected into {file_path}")
            return
        raise RuntimeError(
            f"Partial Char.hit hook set found in {file_path}; refusing an ambiguous patch"
        )

    attacker, defender, open_brace = _char_hit_method(content)
    injected = (
        "\n\t\t// MASTER_MODE_FORCE_HIT\n"
        f"\t\tif (com.spd.mod.mechanics.ModForceHit.forceHitCheck({attacker}, {defender})) return true;\n"
        "\t\t// MASTER_MODE_PARRY\n"
        f"\t\tif (com.spd.mod.mechanics.ModParryRiposte.shouldParry({attacker}, {defender})) return false;\n"
    )
    content = content[:open_brace + 1] + injected + content[open_brace + 1:]
    file_path.write_text(content, encoding='utf-8')
    print(f"Force Hit + Parry hooks injected successfully into {file_path}")



def patch_buff_indicator(file_path: Path) -> None:
    content = file_path.read_text(encoding='utf-8')
    marker = '// MASTER_MODE_BUFF_INFO'

    if marker in content:
        print(f"BuffIndicator click bridge already injected into {file_path}")
        return

    pattern = re.compile(
        r'(?P<indent>^[ \t]*)@Override\s*\n'
        r'(?P=indent)protected void onClick\(\)\s*\{\s*\n'
        r'(?P=indent)[ \t]+if\s*\(\s*buff\.icon\(\)\s*!=\s*NONE\s*\)\s*'
        r'GameScene\.show\(new WndInfoBuff\(buff\)\);\s*\n'
        r'(?P=indent)\}',
        re.MULTILINE,
    )
    match = pattern.search(content)
    if match is None:
        raise RuntimeError(
            f"Expected native BuffIndicator BuffButton.onClick() info handler: {file_path}"
        )

    indent = match.group('indent')
    body = f"""{indent}@Override
{indent}protected void onClick() {{
{indent}\t// MASTER_MODE_BUFF_INFO
{indent}\tif (buff instanceof com.spd.mod.mechanics.ModLastStand) {{
{indent}\t\t((com.spd.mod.mechanics.ModLastStand) buff).open();
{indent}\t}} else if (buff instanceof com.spd.mod.mechanics.ModParryRiposte) {{
{indent}\t\t((com.spd.mod.mechanics.ModParryRiposte) buff).openInfo();
{indent}\t}} else if (buff instanceof com.spd.mod.mechanics.ModInstantKill) {{
{indent}\t\t((com.spd.mod.mechanics.ModInstantKill) buff).openInfo();
{indent}\t}} else if (buff instanceof com.spd.mod.mechanics.ModForceHit) {{
{indent}\t\t((com.spd.mod.mechanics.ModForceHit) buff).openInfo();
{indent}\t}} else if (buff instanceof com.spd.mod.mechanics.ModAssassinate) {{
{indent}\t\t((com.spd.mod.mechanics.ModAssassinate) buff).openInfo();
{indent}\t}} else if (buff.icon() != NONE) {{
{indent}\t\tGameScene.show(new WndInfoBuff(buff));
{indent}\t}}
{indent}}}

{indent}@Override
{indent}protected boolean onLongClick() {{
{indent}\tif (buff instanceof com.spd.mod.mechanics.ModLastStand
{indent}\t\t\t|| buff instanceof com.spd.mod.mechanics.ModParryRiposte
{indent}\t\t\t|| buff instanceof com.spd.mod.mechanics.ModInstantKill
{indent}\t\t\t|| buff instanceof com.spd.mod.mechanics.ModForceHit
{indent}\t\t\t|| buff instanceof com.spd.mod.mechanics.ModAssassinate) {{
{indent}\t\tif (buff.icon() != NONE) GameScene.show(new WndInfoBuff(buff));
{indent}\t\treturn true;
{indent}\t}}
{indent}\treturn super.onLongClick();
{indent}}}"""

    content = content[:match.start()] + body + content[match.end():]
    file_path.write_text(content, encoding='utf-8')
    print(f"SMM BuffIndicator click bridge injected successfully into {file_path}")

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
    instant_marker = '// MASTER_MODE_INSTANT_KILL'

    if completion_marker in content or instant_marker in content:
        if completion_marker in content and instant_marker in content:
            print(f"Char.attack hooks already injected into {file_path}")
            return
        raise RuntimeError(
            f"Partial Char.attack hook set found in {file_path}; refusing an ambiguous patch"
        )

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

    # Instant Kill owns successful physical hits before any defenseProc() side
    # effects. Match the terminal attack's single native hit(this, defender, ...)
    # success branch and fail if that structural anchor is ambiguous.
    hit_success = re.compile(
        r'(?P<head>(?:}\s*else\s+)?if\s*\(\s*hit\s*\(\s*this\s*,\s*'
        + re.escape(defender)
        + r'\s*,[^{};]*?\)\s*\)\s*{)'
    )
    instant_code = (
        "\n\t\t\t// MASTER_MODE_INSTANT_KILL\n"
        f"\t\t\tif (com.spd.mod.mechanics.ModInstantKill.resolveSuccessfulAttack(this, {defender})) return true;"
    )
    clean_body, instant_count = hit_success.subn(
        lambda m: m.group('head') + instant_code,
        clean_body,
    )
    if instant_count != 1:
        raise RuntimeError(
            f"Expected exactly one successful Char.hit branch in terminal Char.attack, "
            f"found {instant_count}: {file_path}"
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
    print(f"Char.attack Riposte + Instant Kill hooks injected successfully into {file_path}")


def _patch_enemy_surge_body(body: str, label: str) -> str:
    marker = '// MASTER_MODE_ENEMY_SURGE'
    if marker in body:
        return body

    limit_re = re.compile(
        r'Dungeon\.level\.(?P<method>mobLimit|nMobs)\(\)'
    )
    limit_matches = list(limit_re.finditer(body))
    if len(limit_matches) != 1:
        raise RuntimeError(
            f'Expected exactly one native population-limit call in {label}, '
            f'found {len(limit_matches)}'
        )

    body = limit_re.sub(
        lambda m: (
            'com.spd.mod.mechanics.ModEnemySurge.scaleMobLimit('
            + m.group(0)
            + ')'
        ),
        body,
        count=1,
    )

    cooldown_re = re.compile(
        r'(?P<indent>^[ \t]*)spend\(\s*'
        r'Dungeon\.level\.respawnCooldown\(\)\s*\);',
        re.MULTILINE,
    )
    cooldown_matches = list(cooldown_re.finditer(body))
    if not cooldown_matches:
        raise RuntimeError(
            f'Native respawn cooldown call not found in {label}'
        )

    body = cooldown_re.sub(
        lambda m: (
            m.group('indent')
            + 'spend(com.spd.mod.mechanics.ModEnemySurge.'
            + 'scaleRespawnCooldown(Dungeon.level.respawnCooldown()));'
        ),
        body,
    )

    first_line_end = body.find('\n')
    if first_line_end < 0:
        raise RuntimeError(f'Malformed respawner body in {label}')
    return (
        body[:first_line_end + 1]
        + '\t\t// MASTER_MODE_ENEMY_SURGE\n'
        + body[first_line_end + 1:]
    )


def patch_enemy_surge_respawner(package_root: Path) -> None:
    level_path = package_root / 'levels' / 'Level.java'
    if not level_path.is_file():
        raise RuntimeError(f'Level.java not found: {level_path}')

    level_text = level_path.read_text(encoding='utf-8')

    if re.search(r'\bMobSpawner\s+respawner\s*;', level_text):
        spawner_path = package_root / 'actors' / 'mobs' / 'MobSpawner.java'
        if not spawner_path.is_file():
            raise RuntimeError(
                'Level uses MobSpawner but MobSpawner.java was not found: '
                + str(spawner_path)
            )
        source = spawner_path.read_text(encoding='utf-8')
        patched = _patch_enemy_surge_body(source, str(spawner_path))
        if patched != source:
            spawner_path.write_text(patched, encoding='utf-8')
            print(f'Enemy Surge native respawner hook injected into {spawner_path}')
        else:
            print(f'Enemy Surge native respawner hook already injected into {spawner_path}')
        return

    if not re.search(r'\bRespawner\s+respawner\s*;', level_text):
        raise RuntimeError(
            'Unsupported native respawner shape: expected MobSpawner or Level.Respawner'
        )

    class_match = re.search(
        r'\bclass\s+Respawner\s+extends\s+Actor\s*\{',
        _mask_non_code(level_text),
    )
    if class_match is None:
        raise RuntimeError(
            'Level declares a Respawner field but Level.Respawner class was not found'
        )

    masked = _mask_non_code(level_text)
    open_brace = masked.find('{', class_match.start(), class_match.end())
    close_brace = _find_matching(masked, open_brace, '{', '}')
    body = level_text[class_match.start():close_brace + 1]
    patched_body = _patch_enemy_surge_body(body, str(level_path) + '::Respawner')
    if patched_body == body:
        print(f'Enemy Surge native respawner hook already injected into {level_path}')
        return

    level_text = (
        level_text[:class_match.start()]
        + patched_body
        + level_text[close_brace + 1:]
    )
    level_path.write_text(level_text, encoding='utf-8')
    print(f'Enemy Surge native respawner hook injected into {level_path}')


patch_wndgame(wnd_path)

# WndGame and Char live under the detected SPD-family package root.
# Derive Char.java from that root so source builds receive the same
# terminal-attack Riposte lifecycle as APK injection.
package_root = wnd_path.parent.parent
patch_enemy_surge_respawner(package_root)
char_path = package_root / 'actors' / 'Char.java'
if not char_path.is_file():
    raise RuntimeError(f"Char.java not found beside WndGame package root: {char_path}")
patch_char(char_path)
patch_char_hit(char_path)

buff_indicator_path = package_root / 'ui' / 'BuffIndicator.java'
if not buff_indicator_path.is_file():
    raise RuntimeError(
        f"BuffIndicator.java not found beside game package root: {buff_indicator_path}"
    )
patch_buff_indicator(buff_indicator_path)
