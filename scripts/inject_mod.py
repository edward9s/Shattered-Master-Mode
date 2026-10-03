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

_DEFENSE_VERB_CALL_RE = re.compile(
    r'(?<![\w$])'
    r'(?P<receiver>(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)'
    r'\s*\.\s*defenseVerb\s*\(\s*\)'
)


_CLASS_RE = re.compile(
    r'\bclass\s+(?P<name>[A-Za-z_$][\w$]*)'
    r'(?:\s*<[^;{}]+>)?'
    r'(?:\s+extends\s+(?P<parent>(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*))?'
    r'[^;{}]*\{'
)
_DAMAGE_METHOD_RE = re.compile(
    r'(?P<head>(?:(?:public|protected|private|final|synchronized|strictfp)\s+)*'
    r'void\s+damage\s*\(\s*(?:final\s+)?int\s+(?P<damage>[A-Za-z_$][\w$]*)'
    r'\s*,\s*(?:final\s+)?(?:java\.lang\.)?Object\s+(?P<src>[A-Za-z_$][\w$]*)'
    r'\s*\)\s*\{)'
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
    """Find the preferred supported Char.hit ABI and its parameter names."""
    masked = _mask_non_code(content)
    modern = []
    legacy = []

    for match in _HIT_METHOD_RE.finditer(masked):
        if not re.search(r'\bstatic\b', match.group('head')):
            continue

        params = content[match.start('params'):match.end('params')]
        parts = [part.strip() for part in params.split(',')]
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
        open_brace = match.end('head') - 1
        if types == ['Char', 'Char', 'float', 'boolean']:
            modern.append((parsed[0][1], parsed[1][1], open_brace))
        elif types == ['Char', 'Char', 'boolean']:
            legacy.append((parsed[0][1], parsed[1][1], open_brace))

    matches = modern if modern else legacy
    if len(matches) != 1:
        label = 'modern' if modern else 'legacy'
        raise RuntimeError(
            f'Expected exactly one supported {label} static Char.hit overload, '
            f'found {len(matches)}'
        )
    return matches[0]


def patch_char_hit(file_path: Path) -> None:
    content = file_path.read_text(encoding='utf-8')
    force_marker = '// MASTER_MODE_FORCE_HIT'
    parry_marker = '// MASTER_MODE_PARRY_RIPOSTE_HIT'

    if force_marker in content or parry_marker in content:
        if force_marker in content and parry_marker in content:
            print(f"Force Hit + Parry/Riposte hit hook already injected into {file_path}")
            return
        raise RuntimeError(
            f"Partial Char.hit hook set found in {file_path}; refusing an ambiguous patch"
        )

    attacker, defender, open_brace = _char_hit_method(content)
    injected = (
        "\n\t\t// MASTER_MODE_PARRY_RIPOSTE_HIT\n"
        f"\t\tboolean _smmParryRiposte = com.spd.mod.mechanics.ModParryRiposte.onHitCheck({attacker}, {defender});\n"
        "\t\t// MASTER_MODE_FORCE_HIT\n"
        f"\t\tif (com.spd.mod.mechanics.ModForceHit.forceHitCheck({attacker}, {defender})) return true;\n"
        "\t\tif (_smmParryRiposte) return false;\n"
    )
    content = content[:open_brace + 1] + injected + content[open_brace + 1:]
    file_path.write_text(content, encoding='utf-8')
    print(f"Unified Char.hit Force Hit + Parry/Riposte hook injected into {file_path}")



def _class_declarations(package_root: Path) -> tuple[list[dict], dict[str, list[dict]]]:
    """Parse Java class ranges and build a simple-name inheritance index."""
    declarations: list[dict] = []
    by_name: dict[str, list[dict]] = {}

    for file_path in package_root.rglob('*.java'):
        content = file_path.read_text(encoding='utf-8')
        masked = _mask_non_code(content)
        file_decls: list[dict] = []

        for match in _CLASS_RE.finditer(masked):
            open_brace = match.end() - 1
            close_brace = _find_matching(masked, open_brace, '{', '}')
            decl = {
                'file': file_path,
                'name': match.group('name'),
                'parent': match.group('parent'),
                'start': match.start(),
                'open': open_brace,
                'end': close_brace,
            }
            file_decls.append(decl)
            declarations.append(decl)
            by_name.setdefault(decl['name'], []).append(decl)

        for decl in file_decls:
            enclosing = [
                other for other in file_decls
                if other['start'] < decl['start'] < other['end']
            ]
            decl['enclosing'] = min(
                enclosing,
                key=lambda item: item['end'] - item['start'],
                default=None,
            )

    return declarations, by_name


def _resolve_parent(decl: dict, by_name: dict[str, list[dict]]) -> dict | None:
    parent = decl['parent']
    if parent is None:
        return None
    simple = parent.rsplit('.', 1)[-1]
    candidates = by_name.get(simple, [])
    if len(candidates) == 1:
        return candidates[0]

    enclosing = decl.get('enclosing')
    same_file = [candidate for candidate in candidates if candidate['file'] == decl['file']]
    if enclosing is not None:
        nested = [
            candidate for candidate in same_file
            if candidate['start'] < decl['start'] < candidate['end']
        ]
        if len(nested) == 1:
            return nested[0]
    if len(same_file) == 1:
        return same_file[0]
    if not candidates:
        return None
    raise RuntimeError(
        f"Ambiguous parent class {parent} for {decl['file']}::{decl['name']}"
    )


def _is_char_subclass(
    decl: dict,
    char_decl: dict,
    by_name: dict[str, list[dict]],
    memo: dict[int, bool],
    active: set[int],
) -> bool:
    key = id(decl)
    if key in memo:
        return memo[key]
    if decl is char_decl:
        memo[key] = True
        return True
    if key in active:
        raise RuntimeError(f"Cyclic class hierarchy at {decl['file']}::{decl['name']}")

    active.add(key)
    parent_name = decl['parent']
    if parent_name is None:
        result = False
    elif parent_name.rsplit('.', 1)[-1] == 'Char':
        result = True
    else:
        parent = _resolve_parent(decl, by_name)
        result = parent is not None and _is_char_subclass(
            parent, char_decl, by_name, memo, active
        )
    active.remove(key)
    memo[key] = result
    return result


def patch_direct_damage_overrides(package_root: Path, char_path: Path) -> None:
    """Patch Char-subclass damage(int,Object) overrides without using Focus."""
    declarations, by_name = _class_declarations(package_root)
    char_candidates = [
        decl for decl in declarations
        if decl['file'] == char_path and decl['name'] == 'Char'
    ]
    if len(char_candidates) != 1:
        raise RuntimeError(
            f"Expected exactly one Char declaration in {char_path}, "
            f"found {len(char_candidates)}"
        )
    char_decl = char_candidates[0]
    memo: dict[int, bool] = {}
    marker = '// MASTER_MODE_PARRY_RIPOSTE_DIRECT_DAMAGE'
    patched_methods = 0
    patched_files = 0

    declarations_by_file: dict[Path, list[dict]] = {}
    for decl in declarations:
        declarations_by_file.setdefault(decl['file'], []).append(decl)

    for file_path, file_decls in declarations_by_file.items():
        content = file_path.read_text(encoding='utf-8')
        masked = _mask_non_code(content)
        insertions: list[tuple[int, str]] = []

        for match in _DAMAGE_METHOD_RE.finditer(masked):
            containing = [
                decl for decl in file_decls
                if decl['open'] < match.start() < decl['end']
            ]
            if not containing:
                continue
            owner = min(
                containing,
                key=lambda item: item['end'] - item['start'],
            )
            if owner is char_decl or not _is_char_subclass(
                owner, char_decl, by_name, memo, set()
            ):
                continue

            open_brace = match.end('head') - 1
            close_brace = _find_matching(masked, open_brace, '{', '}')
            body = content[open_brace + 1:close_brace]
            if marker in body:
                continue
            if 'ModParryRiposte.resolveDirectDamage' in body:
                raise RuntimeError(
                    f"Unmarked direct-damage Parry hook already exists in "
                    f"{file_path}::{owner['name']}.damage"
                )

            line_start = content.rfind('\n', 0, match.start()) + 1
            indent_match = re.match(r'[ \t]*', content[line_start:match.start()])
            indent = indent_match.group(0) if indent_match else ''
            hook = (
                f"\n{indent}\t\t{marker}\n"
                f"{indent}\t\tif (com.spd.mod.mechanics.ModParryRiposte."
                f"resolveDirectDamage(this, {match.group('damage')}, "
                f"{match.group('src')}) == null) return;\n"
            )
            insertions.append((open_brace + 1, hook))

        if not insertions:
            continue
        for position, hook in reversed(insertions):
            content = content[:position] + hook + content[position:]
        file_path.write_text(content, encoding='utf-8')
        patched_methods += len(insertions)
        patched_files += 1

    print(
        f"Parry/Riposte direct-damage hook injected into {patched_methods} "
        f"Char-subclass damage override(s) across {patched_files} source file(s)"
    )


def patch_defense_feedback(package_root: Path) -> None:
    """Route every Char defenseVerb display call through ModParryRiposte.

    The helper preserves normal virtual dispatch for non-SMM misses. Calls to
    super.defenseVerb() are deliberately left alone so class overrides keep
    their native fallback behavior.
    """
    total = 0
    files = 0

    for file_path in package_root.rglob('*.java'):
        content = file_path.read_text(encoding='utf-8')
        masked = _mask_non_code(content)
        replacements: list[tuple[int, int, str]] = []

        for match in _DEFENSE_VERB_CALL_RE.finditer(masked):
            receiver = content[
                match.start('receiver'):match.end('receiver')
            ]
            if receiver == 'super' or receiver.endswith('.super'):
                continue
            if receiver.endswith('ModParryRiposte'):
                continue

            replacements.append((
                match.start(),
                match.end(),
                'com.spd.mod.mechanics.ModParryRiposte.defenseVerb('
                + receiver
                + ')',
            ))

        if not replacements:
            continue

        for begin, finish, replacement in reversed(replacements):
            content = content[:begin] + replacement + content[finish:]

        file_path.write_text(content, encoding='utf-8')
        files += 1
        total += len(replacements)

    if total == 0:
        raise RuntimeError(
            f'No Char defenseVerb() display calls found under {package_root}'
        )

    print(
        f'Parry defense feedback routed through {total} call(s) '
        f'across {files} source file(s)'
    )

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
    instant_marker = '// MASTER_MODE_INSTANT_KILL'

    if instant_marker in content:
        print(f"Char.attack Instant Kill hook already injected into {file_path}")
        return

    if ('// MASTER_MODE_INCOMING_ATTACK' in content
            or 'ModParryRiposte.onIncomingAttack' in content
            or 'ModParryRiposte.onIncomingAttackComplete' in content):
        raise RuntimeError(
            f"Obsolete Char.attack Parry/Riposte hook found in {file_path}; "
            "refusing to mix old and unified hit-check architectures"
        )

    defender, open_brace, close_brace = _terminal_attack_method(content)
    body = content[open_brace + 1:close_brace]

    # Instant Kill remains an attack-layer concern. Parry/Riposte is handled only
    # by the selected Char.hit() hook.
    hit_success = re.compile(
        r'(?P<head>(?:}\s*else\s+)?if\s*\(\s*hit\s*\(\s*this\s*,\s*'
        + re.escape(defender)
        + r'\s*,[^{};]*?\)\s*\)\s*{)'
    )
    instant_code = (
        "\n\t\t\t// MASTER_MODE_INSTANT_KILL\n"
        f"\t\t\tif (com.spd.mod.mechanics.ModInstantKill.resolveSuccessfulAttack(this, {defender})) return true;"
    )
    patched_body, instant_count = hit_success.subn(
        lambda m: m.group('head') + instant_code,
        body,
    )
    if instant_count != 1:
        raise RuntimeError(
            f"Expected exactly one successful Char.hit branch in terminal Char.attack, "
            f"found {instant_count}: {file_path}"
        )

    content = content[:open_brace + 1] + patched_body + content[close_brace:]
    file_path.write_text(content, encoding='utf-8')
    print(f"Char.attack Instant Kill hook injected successfully into {file_path}")


patch_wndgame(wnd_path)

# WndGame and Char live under the detected SPD-family package root.
# Instant Kill remains on Char.attack. Parry/Riposte uses the same two generic
# combat layers as APK/JAR injection: selected Char.hit plus Char-subclass
# damage(int,Object) overrides. No Focus bridge participates in combat.
package_root = wnd_path.parent.parent
char_path = package_root / 'actors' / 'Char.java'
if not char_path.is_file():
    raise RuntimeError(f"Char.java not found beside WndGame package root: {char_path}")
patch_char(char_path)
patch_char_hit(char_path)
patch_direct_damage_overrides(package_root, char_path)
patch_defense_feedback(package_root)

buff_indicator_path = package_root / 'ui' / 'BuffIndicator.java'
if not buff_indicator_path.is_file():
    raise RuntimeError(
        f"BuffIndicator.java not found beside game package root: {buff_indicator_path}"
    )
patch_buff_indicator(buff_indicator_path)
