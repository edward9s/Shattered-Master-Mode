from pathlib import Path

path = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)


# Reflection-only class names preserve ModDebug's cross-fork compatibility.
token_line = '            "com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken";\n'
replace_once(
    token_line,
    token_line
    + '    private static final String WEAPON_CLASS =\n'
      '            "com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon";\n'
      '    private static final String WEAPON_ENCHANTMENT_CLASS =\n'
      '            "com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon$Enchantment";\n'
      '    private static final String ARMOR_CLASS =\n'
      '            "com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor";\n'
      '    private static final String ARMOR_GLYPH_CLASS =\n'
      '            "com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor$Glyph";\n'
)

# Add dedicated commands immediately after use.
start = text.index('            case "use":\n')
end_marker = '                break;\n'
end = text.index(end_marker, start) + len(end_marker)
text = (
    text[:end]
    + '\n'
      '            case "enchant":\n'
      '                stored = applyEquipmentEffect(args, true);\n'
      '                hasStoredResult = stored != null;\n'
      '                break;\n'
      '\n'
      '            case "inscribe":\n'
      '                stored = applyEquipmentEffect(args, false);\n'
      '                hasStoredResult = stored != null;\n'
      '                break;\n'
    + text[end:]
)

replace_once(
    '| inspect | use | goto |',
    '| inspect | use | enchant | inscribe | goto |',
)

help_use = '                + "use <Class|hero|level|@variable> <method> [args...]\\n"\n'
replace_once(
    help_use,
    help_use
    + '                + "enchant @weapon <Enchantment|random|none>\\n"\n'
      '                + "inscribe @armor <Glyph|random|none>\\n"\n',
)
replace_once(
    'Quoted strings and @variables are supported as method arguments.',
    'Quoted strings, @variables, and new:<Class> are supported as method arguments.',
)

marker = '    private static void gotoLevel(List<String> args)\n'
insert = '''    private static Object applyEquipmentEffect(
            List<String> args, boolean weapon) throws Exception {

        String usage = weapon
                ? "enchant @weapon <Enchantment|random|none>"
                : "inscribe @armor <Glyph|random|none>";

        if (args.size() != 2 || !args.get(0).startsWith("@")) {
            throw new IllegalArgumentException(usage);
        }

        Object item = getVariable(args.get(0));
        if (item == null) {
            throw new IllegalArgumentException(str(
                    "Variable is undefined or inactive: ", args.get(0)));
        }

        String itemClassName = weapon ? WEAPON_CLASS : ARMOR_CLASS;
        String effectClassName = weapon
                ? WEAPON_ENCHANTMENT_CLASS
                : ARMOR_GLYPH_CLASS;
        String methodName = weapon ? "enchant" : "inscribe";
        String fieldName = weapon ? "enchantment" : "glyph";

        Class<?> itemBase = loadRequired(itemClassName);
        if (!itemBase.isInstance(item)) {
            throw new IllegalArgumentException(str(
                    args.get(0), " contains ", item.getClass().getSimpleName(),
                    ", not a ", itemBase.getSimpleName()));
        }

        String effectToken = args.get(1);
        InvocationResult applied;

        if ("random".equalsIgnoreCase(effectToken)) {
            applied = invokeCompatibleObjects(
                    item, item.getClass(), methodName,
                    new Object[0], false, false);
        } else {
            Object effect = null;

            if (!"none".equalsIgnoreCase(effectToken)
                    && !"null".equalsIgnoreCase(effectToken)) {
                String className = effectToken;
                if (className.length() > 4
                        && className.regionMatches(
                                true, 0, "new:", 0, 4)) {
                    className = className.substring(4);
                }

                Class<?> effectBase = loadRequired(effectClassName);
                Class<?> effectType = resolveClass(className, effectBase);
                if (effectType == null) {
                    throw new IllegalArgumentException(str(
                            weapon ? "Enchantment" : "Glyph",
                            " class not found: ", className));
                }
                effect = newInstance(effectType);
            }

            applied = invokeCompatibleObjects(
                    item, item.getClass(), methodName,
                    new Object[]{effect}, false, false);
        }

        if (!applied.invoked) {
            throw new NoSuchMethodException(str(
                    "No compatible ", item.getClass().getSimpleName(),
                    ".", methodName));
        }

        Field effectField = findField(item.getClass(), fieldName);
        Object actual = effectField == null ? null : effectField.get(item);
        GLog.p(str(
                item.getClass().getSimpleName(), ".", fieldName,
                " = ", actual == null
                        ? "none"
                        : actual.getClass().getSimpleName()));
        return item;
    }

'''
replace_once(marker, insert + marker)

# Explicit object construction syntax for any reflective method argument.
boolean_marker = (
    '        if (type == boolean.class\n'
    '                || type == Boolean.class) {\n'
)
explicit_new = '''        if (raw.length() > 4
                && raw.regionMatches(true, 0, "new:", 0, 4)) {

            String className = raw.substring(4);
            Class<?> cls = resolveClass(className, type);

            if (cls == null || !type.isAssignableFrom(cls)) {
                return BAD_ARG;
            }

            try {
                return newInstance(cls);
            } catch (Exception ignored) {
                return BAD_ARG;
            }
        }

'''
replace_once(boolean_marker, explicit_new + boolean_marker)

built_in = '                "warp", "inspect", "use",\n'
replace_once(
    built_in,
    built_in + '                "enchant", "inscribe",\n',
)

path.write_text(text, encoding="utf-8")
