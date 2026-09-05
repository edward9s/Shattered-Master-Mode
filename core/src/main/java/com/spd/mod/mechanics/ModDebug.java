package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Small in-game reflection console for SMM experiments.
 *
 * This intentionally lives entirely in com.spd.mod: target forks do not need
 * WndUseItem, PlatformSupport, or GameScene patches. ModAnkh exposes it through
 * a normal Item action.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ModDebug {

    private static final String[] ROOTS = {
            "com.shatteredpixel.shatteredpixeldungeon",
            "com.spd.mod"
    };

    private static final String INTERLEVEL_SCENE =
            "com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene";
    private static final String LEVEL_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.levels.Level";
    private static final String TERRAIN_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.levels.Terrain";
    private static final String BLOB_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob";
    private static final String TRAP_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap";
    private static final String GAME_CLASS = "com.watabou.noosa.Game";
    private static final String KEY_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.keys.Key";
    private static final String MIMIC_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic";
    private static final String GHOST_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost";
    private static final String WANDMAKER_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker";
    private static final String BLACKSMITH_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith";
    private static final String IMP_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp";
    private static final String DEMON_SPAWNER_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DemonSpawner";
    private static final String STATISTICS_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.Statistics";
    private static final String GENERATOR_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.Generator";
    private static final String GENERATOR_CATEGORY_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.Generator$Category";
    private static final String EMBERS_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.quest.Embers";
    private static final String DWARF_TOKEN_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken";
    private static final String WEAPON_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon";
    private static final String WEAPON_ENCHANTMENT_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon$Enchantment";
    private static final String ARMOR_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor";
    private static final String ARMOR_GLYPH_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor$Glyph";

    private static final Object BAD_ARG = new Object();
    private static final Pattern HISTORY_COMMAND =
            Pattern.compile("^!!(?:\\s+(\\d+))?$");
    private static final List<String> CLASS_NAMES = new ArrayList<>();
    private static final Map<String, StoredValue> VARIABLES = new HashMap<>();
    private static final Map<String, String> MACROS = new HashMap<>();

    private static boolean indexed;
    private static boolean macrosLoaded;
    private static String lastCommand = "";

    private ModDebug() {
    }

    public static void open() {
        GameScene.show(new WndTextInput(
                "Debug command",
                "help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | search | results | get | set | clear | save | load",
                "",
                400,
                false,
                "Execute",
                "Cancel") {
            @Override
            public void onSelect(boolean positive, String text) {
                if (!positive) {
                    return;
                }

                String command = text == null ? "" : text.trim();
                if (command.isEmpty()) {
                    return;
                }

                try {
                    execute(command);
                } catch (Throwable error) {
                    reportCommandError("Debug command failed", error);
                }
            }
        });
    }

    public static void execute(String commandLine) throws Exception {
        String text = commandLine == null ? "" : commandLine.trim();
        if (text.isEmpty()) {
            return;
        }

        Integer historyCount = historyRepeatCount(text);
        if (historyCount != null) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            runHistoryCommand(lastCommand, historyCount, 0);
            return;
        }

        if (text.contains("!!")) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            text = text.replace("!!", lastCommand);
            GLog.i(str("> ", text));
        }

        lastCommand = text;
        executeExpanded(text, 0);
    }

    private static Integer historyRepeatCount(String text) {
        Matcher matcher = HISTORY_COMMAND.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }

        int count = matcher.group(1) == null
                ? 1
                : Integer.parseInt(matcher.group(1));

        if (count < 1 || count > 1000) {
            throw new IllegalArgumentException(
                    "!! count must be between 1 and 1000");
        }

        return count;
    }

    private static void runHistoryCommand(
            String command, int count, int macroDepth) throws Exception {

        if (count > 1
                && commandOrMacroNeedsSelector(command, macroDepth)) {
            throw new IllegalArgumentException(
                    "Cannot repeat a command that opens an interactive selector more than once; "
                            + "supply an explicit cell/handle where the command supports one");
        }

        GLog.i(str(
                "> ", command,
                count == 1 ? "" : str("  [", count, " times]")));

        for (int i = 0; i < count; i++) {
            executeExpanded(command, macroDepth);
        }
    }

    private static void executeExpanded(String commandLine, int macroDepth)
            throws Exception {

        List<String> args = tokenize(commandLine);
        if (args.isEmpty()) {
            return;
        }

        String storeVariable = handleVariablePrefix(args);
        if (args.isEmpty()) {
            return;
        }

        String command = args.remove(0).toLowerCase(Locale.ROOT);
        Object stored = null;
        boolean hasStoredResult = false;

        switch (command) {
            case "help":
                help();
                break;

            case "give":
                stored = give(args);
                hasStoredResult = stored != null;
                break;

            case "spawn":
                spawn(args, storeVariable);
                return;

            case "affect":
                affect(args, storeVariable);
                return;

            case "seed":
                seed(args, storeVariable);
                return;

            case "trap":
                trap(args, storeVariable);
                return;

            case "terrain":
                terrain(args);
                return;

            case "warp":
                warp(args);
                return;

            case "inspect":
                inspect(args);
                break;

            case "use":
                InvocationResult result = use(args);
                if (result.invoked) {
                    stored = result.result;
                    hasStoredResult = result.result != null;
                }
                break;

            case "enchant":
                stored = applyEquipmentEffect(args, true);
                hasStoredResult = stored != null;
                break;

            case "inscribe":
                stored = applyEquipmentEffect(args, false);
                hasStoredResult = stored != null;
                break;

            case "goto":
                gotoLevel(args);
                break;

            case "where":
                where(args);
                break;

            case "macro":
                macro(args);
                return;

            case "search":
                ModValueSearch.search(args);
                break;

            case "results":
                ModValueSearch.results(args);
                break;

            case "get":
                if (!args.isEmpty() && args.get(0).startsWith("@")) {
                    stored = getObjectField(args);
                    hasStoredResult = stored != null;
                } else if (!args.isEmpty() && args.get(0).startsWith("#")) {
                    ModValueSearch.get(args);
                } else {
                    stored = getStaticField(args);
                    hasStoredResult = stored != null;
                }
                break;

            case "set":
                if (!args.isEmpty() && args.get(0).startsWith("@")) {
                    setObjectField(args);
                } else if (!args.isEmpty() && args.get(0).startsWith("#")) {
                    ModValueSearch.set(args);
                } else {
                    setStaticField(args);
                }
                break;

            case "clear":
                ModValueSearch.clear(args);
                break;

            case "save":
                save(args);
                break;

            case "load":
                load(args);
                break;

            default:
                if (runMacro(command, args, macroDepth)) {
                    return;
                }
                GLog.w(str(
                        "Unknown debug command: ", command,
                        ". Type 'help'."));
                return;
        }

        if (storeVariable != null) {
            if (hasStoredResult) {
                putVariable(storeVariable, stored);
            } else {
                GLog.w(str(
                        storeVariable,
                        " was not changed because the command returned no object."));
            }
        }
    }

    private static String handleVariablePrefix(List<String> args) {
        if (args.isEmpty() || !args.get(0).startsWith("@")) {
            return null;
        }

        String token = args.get(0);
        if ("@".equals(token)) {
            listVariables();
            args.clear();
            return null;
        }

        String key = variableKey(token);
        if (key == null) {
            throw new IllegalArgumentException(
                    "Variable names use @name and must start with a letter or underscore.");
        }

        if (args.size() == 1) {
            showVariable(token);
            args.clear();
            return null;
        }

        String action = args.get(1).toLowerCase(Locale.ROOT);
        if ("inv".equals(action) || "inventory".equals(action)) {
            selectInventoryVariable(token);
            args.clear();
            return null;
        }

        if ("cell".equals(action)) {
            selectCellVariable(token, false, false);
            args.clear();
            return null;
        }

        if ("char".equals(action) || "character".equals(action)) {
            selectCellVariable(token, true, false);
            args.clear();
            return null;
        }

        if ("obj".equals(action) || "object".equals(action)) {
            selectCellVariable(token, false, true);
            args.clear();
            return null;
        }

        if ("hero".equals(action)) {
            if (Dungeon.hero == null) {
                throw new IllegalStateException("No active hero");
            }
            putVariable(token, Dungeon.hero);
            args.clear();
            return null;
        }

        if ("level".equals(action)) {
            if (Dungeon.level == null) {
                throw new IllegalStateException("No active level");
            }
            putVariable(token, Dungeon.level);
            args.clear();
            return null;
        }

        if ("clear".equals(action) || "delete".equals(action)) {
            VARIABLES.remove(key);
            GLog.i(str(token, " cleared"));
            args.clear();
            return null;
        }

        args.remove(0);
        return token;
    }

    private static void help() {
        GLog.i(
                "Debug commands:\n"
                + "give <Item> [+level] [xquantity] [-f|--force] [method [args...]]\n"
                + "spawn <Mob> [xquantity|-p|--place] [method [args...]]\n"
                + "affect <Buff> [duration] [method [args...]]  (select a character)\n"
                + "seed <Blob> [amount]  (select a tile)\n"
                + "trap <Trap>  (select a tile; trap is revealed)\n"
                + "terrain <Terrain> [cell|@variable]  (select a tile if omitted)\n"
                + "warp [cell|@variable]  (same-floor teleport)\n"
                + "inspect <Class|hero|level|@variable>\n"
                + "use <Class|hero|level|@variable> <method> [args...]\n"
                + "enchant @weapon <Enchantment|random|none>\n"
                + "inscribe @armor <Glyph|random|none>\n"
                + "goto <depth> [branch]  (branch defaults to 0)\n"
                + "where  (show current depth and branch)\n"
                + "macro [name]  (edit; empty body deletes; %1..%9 are arguments)\n"
                + "@  (list variables)\n"
                + "@x inv|cell|char|obj|hero|level|clear\n"
                + "@x use ...  (store a returned object; also works with give/spawn/affect/seed/trap)\n"
                + "!! [count]  (repeat the previous command; count is 1..1000)\n"
                + "In a macro, standalone !! uses that macro invocation's previous command.\n"
                + "search <number|changed|unchanged|increased|decreased>\n"
                + "results [#id] | get #id | set #id <number> | clear\n"
                + "get @object <field> | set @object <field> <value>\n"
                + "get <Class> <staticField> | set <Class> <staticField> <value>\n"
                + "@x get @object <field>  (store a non-null field value)\n"
                + "@x get <Class> <staticField>  (store a non-null static value)\n"
                + "save  (Android: export app save files to Download/<package>)\n"
                + "load  (Android: import them, then restart the app)\n"
                + "Class names may be simple (RingOfEnergy) or fully qualified.\n"
                + "Quoted strings, @variables, and new:<Class> are supported as method arguments.\n"
                + "Commands that open a selector should be the final line of a macro."
        );
    }

    private static Object give(List<String> args) throws Exception {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(
                    "give <Item> [+level] [xquantity] [-f|--force] [method [args...]]");
        }

        Class<?> raw = resolveClass(args.get(0), Item.class);
        if (raw == null) {
            throw new IllegalArgumentException(
                    str("Item class not found: ", args.get(0)));
        }

        Integer level = null;
        int quantity = 1;
        boolean force = false;
        String methodName = null;
        List<String> methodArgs = Collections.emptyList();

        for (int i = 1; i < args.size(); i++) {
            String token = args.get(i);

            if (token.matches("[+-]\\d+")) {
                level = Integer.parseInt(token);

            } else if (token.matches("(?i)x\\d+")) {
                quantity = boundedCount(
                        Integer.parseInt(token.substring(1)));

            } else if ("-f".equalsIgnoreCase(token)
                    || "--force".equalsIgnoreCase(token)) {
                force = true;

            } else {
                methodName = token;
                methodArgs = new ArrayList<>(
                        args.subList(i + 1, args.size()));
                break;
            }
        }

        int made = 0;
        Item firstCreated = null;

        for (int i = 0; i < quantity; i++) {
            Item item = newDebugItem(raw);

            invokeCompatibleObjects(
                    item, item.getClass(), "identify",
                    new Object[0], false, false);

            if (level != null) {
                setItemLevel(item, level);
            }

            if (methodName != null) {
                InvocationResult hook = invokeCompatibleRaw(
                        item, item.getClass(), methodName,
                        methodArgs, false, false);
                if (!hook.invoked) {
                    throw new NoSuchMethodException(str(
                            "No compatible ", raw.getSimpleName(), ".",
                            methodName, " with ",
                            methodArgs.size(), " argument(s)"));
                }
            }

            boolean collected = force
                    ? collectItem(item)
                    : debugPickUp(item);

            if (!collected) {
                GLog.w(str(
                        "Backpack full or pickup rejected; stopped after ",
                        made, " item(s)."));
                break;
            }

            if (firstCreated == null) {
                firstCreated = item;
            }
            made++;
        }

        if (level == null) {
            GLog.p(str("Created ", made, " x ", raw.getSimpleName()));
        } else {
            GLog.p(str(
                    "Created ", made, " x ", raw.getSimpleName(),
                    " (level ", level, ")"));
        }

        return firstCreated;
    }

    private static Item newDebugItem(Class<?> raw) throws Exception {
        if (findTypeInHierarchy(raw, KEY_CLASS) != null) {
            try {
                Constructor<?> constructor =
                        raw.getDeclaredConstructor(int.class);
                constructor.setAccessible(true);
                return (Item) constructor.newInstance(Dungeon.depth);
            } catch (NoSuchMethodException ignored) {
            }
        }

        return (Item) newInstance(raw);
    }

    private static void setItemLevel(Item item, int level) throws Exception {
        InvocationResult result = invokeCompatibleObjects(
                item, item.getClass(), "level",
                new Object[]{level}, false, false);

        if (!result.invoked) {
            throw new NoSuchMethodException(
                    "Target item has no compatible level(int) setter");
        }
    }

    private static boolean collectItem(Item item) throws Exception {
        InvocationResult result = invokeCompatibleObjects(
                item, item.getClass(), "collect",
                new Object[0], false, false);

        if (!result.invoked) {
            throw new NoSuchMethodException(
                    "Target item has no compatible collect()");
        }

        return !(result.result instanceof Boolean)
                || (Boolean) result.result;
    }

    private static void refundPickupTime() {
        if (Dungeon.hero == null) {
            return;
        }

        try {
            InvocationResult cooldown = invokeCompatibleObjects(
                    Dungeon.hero, Dungeon.hero.getClass(), "cooldown",
                    new Object[0], false, false);

            if (!cooldown.invoked
                    || !(cooldown.result instanceof Number)) {
                return;
            }

            invokeCompatibleObjects(
                    Dungeon.hero, Dungeon.hero.getClass(), "spend",
                    new Object[]{-((Number) cooldown.result).floatValue()},
                    false, false);
        } catch (Throwable ignored) {
        }
    }

    private static boolean debugPickUp(Item item) throws Exception {
        if (Dungeon.hero == null) {
            return collectItem(item);
        }

        InvocationResult picked = invokeCompatibleObjects(
                item, item.getClass(), "doPickUp",
                new Object[]{Dungeon.hero}, false, false);

        if (!picked.invoked) {
            return collectItem(item);
        }

        boolean success = !(picked.result instanceof Boolean)
                || (Boolean) picked.result;

        if (success) {
            refundPickupTime();
        }

        return success;
    }

    private static void spawn(
            List<String> args, final String storeVariable) throws Exception {

        if (args.isEmpty()) {
            throw new IllegalArgumentException(
                    "spawn <Mob> [xquantity|-p|--place] [method [args...]]");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        final Class<?> raw = resolveClass(args.get(0), Mob.class);
        if (raw == null) {
            throw new IllegalArgumentException(
                    str("Mob class not found: ", args.get(0)));
        }

        int quantity = 1;
        boolean manualPlace = false;
        int index = 1;

        if (index < args.size()) {
            String token = args.get(index);
            if (token.matches("(?i)x\\d+")) {
                quantity = boundedCount(
                        Integer.parseInt(token.substring(1)));
                index++;

            } else if ("-p".equalsIgnoreCase(token)
                    || "--place".equalsIgnoreCase(token)) {
                manualPlace = true;
                index++;
            }
        }

        final String methodName =
                index < args.size() ? args.get(index) : null;
        final List<String> methodArgs =
                index < args.size()
                        ? new ArrayList<>(
                                args.subList(index + 1, args.size()))
                        : Collections.<String>emptyList();

        if (manualPlace && quantity != 1) {
            throw new IllegalArgumentException(
                    "Manual placement cannot be combined with quantity");
        }

        if (manualPlace) {
            final Mob probe = (Mob) newInstance(raw);

            GameScene.selectCell(new CellSelector.Listener() {
                @Override
                public String prompt() {
                    return str(
                            "Select a tile to place ", probe.name());
                }

                @Override
                public void onSelect(Integer cell) {
                    if (cell == null || cell < 0 || Dungeon.level == null) {
                        return;
                    }

                    try {
                        if (!validMobCell(probe, cell)) {
                            GLog.w(str(
                                    "You cannot place ",
                                    probe.name(), " here."));
                            return;
                        }

                        Mob mob = newDebugMob(raw, cell, probe);
                        addMob(mob);
                        initializeSpecialMobForDebug(mob);
                        invokeGeneratedHook(
                                mob, methodName, methodArgs);

                        if (storeVariable != null) {
                            putVariable(storeVariable, mob);
                        }

                        GLog.p(str("Spawned ", mob.name()));

                    } catch (Exception error) {
                        reportCommandError("Spawn failed", error);
                    }
                }
            });
            return;
        }

        int made = 0;
        Mob first = null;

        for (int i = 0; i < quantity; i++) {
            Mob probe = (Mob) newInstance(raw);
            int cell = randomRespawnCell(probe);
            if (cell < 0) {
                break;
            }

            Mob mob = newDebugMob(raw, cell, probe);
            addMob(mob);
            initializeSpecialMobForDebug(mob);
            invokeGeneratedHook(mob, methodName, methodArgs);

            if (first == null) {
                first = mob;
            }
            made++;
        }

        if (storeVariable != null && first != null) {
            putVariable(storeVariable, first);
        }

        GLog.p(str(
                "Spawned ", made, " x ", raw.getSimpleName()));
    }

    private static Mob newDebugMob(
            Class<?> raw, int cell, Mob fallback) throws Exception {

        Class<?> mimicBase = findTypeInHierarchy(raw, MIMIC_CLASS);
        if (mimicBase != null) {
            Object emptyItems = Array.newInstance(Item.class, 0);

            InvocationResult spawned = invokeCompatibleObjects(
                    null, mimicBase, "spawnAt",
                    new Object[]{cell, raw, emptyItems},
                    false, false);

            if (!spawned.invoked) {
                spawned = invokeCompatibleObjects(
                        null, mimicBase, "spawnAt",
                        new Object[]{cell, raw, true, emptyItems},
                        false, false);
            }

            if (spawned.invoked && spawned.result instanceof Mob) {
                return (Mob) spawned.result;
            }

            throw new NoSuchMethodException(
                    "Target Mimic has no compatible spawnAt factory");
        }

        Mob mob = fallback != null
                ? fallback
                : (Mob) newInstance(raw);
        mob.pos = cell;
        return mob;
    }

    private static void initializeSpecialMobForDebug(Mob mob) {
        if (mob == null) {
            return;
        }

        try {
            Class<?> type = mob.getClass();

            if (findTypeInHierarchy(type, GHOST_CLASS) != null) {
                initializeGhostQuestForDebug();
            } else if (findTypeInHierarchy(type, WANDMAKER_CLASS) != null) {
                initializeWandmakerQuestForDebug();
            } else if (findTypeInHierarchy(type, BLACKSMITH_CLASS) != null) {
                initializeBlacksmithQuestForDebug();
            } else if (findTypeInHierarchy(type, IMP_CLASS) != null) {
                initializeImpQuestForDebug();
            } else if (findTypeInHierarchy(type, DEMON_SPAWNER_CLASS) != null) {
                registerDebugDemonSpawner(mob);
            }
        } catch (Throwable error) {
            GLog.w(str(
                    "Special debug initialization failed for ",
                    mob.getClass().getSimpleName(), ": ",
                    error.getClass().getSimpleName(),
                    error.getMessage() == null ? "" : str(": ", error.getMessage())));
            error.printStackTrace();
        }
    }

    private static void initializeGhostQuestForDebug() throws Exception {
        Class<?> quest = loadRequired(str(GHOST_CLASS, "$Quest"));

        boolean usable = staticBoolean(quest, "spawned")
                && staticFieldValue(quest, "weapon") != null
                && staticFieldValue(quest, "armor") != null;
        if (usable) {
            return;
        }

        invokeCompatibleObjects(
                null, quest, "reset", new Object[0], false, false);

        Item weapon = generateDebugItem("WEAPON");
        Item armor = generateDebugItem("ARMOR");
        prepareDebugGeneratedItem(weapon, 0, false);
        prepareDebugGeneratedItem(armor, 0, false);

        int type = Math.max(1, Math.min(3, Dungeon.depth - 1));
        setStaticFieldValue(quest, "spawned", true);
        setStaticFieldValue(quest, "type", type);
        setStaticFieldValue(quest, "given", false);
        setStaticFieldValue(quest, "processed", false);
        setStaticFieldValue(quest, "depth", Dungeon.depth);
        setStaticFieldValue(quest, "weapon", weapon);
        setStaticFieldValue(quest, "armor", armor);
        setStaticFieldValue(quest, "enchant", null);
        setStaticFieldValue(quest, "glyph", null);

        GLog.i(str("Initialized debug Ghost quest (type ", type, ")."));
    }

    private static void initializeWandmakerQuestForDebug() throws Exception {
        Class<?> quest = loadRequired(str(WANDMAKER_CLASS, "$Quest"));

        boolean usable = staticBoolean(quest, "spawned")
                && staticFieldValue(quest, "wand1") != null
                && staticFieldValue(quest, "wand2") != null;
        if (usable) {
            return;
        }

        invokeCompatibleObjects(
                null, quest, "reset", new Object[0], false, false);

        Item wand1 = generateDebugItem("WAND");
        Item wand2 = generateDebugItem("WAND");
        for (int tries = 0;
                tries < 20 && wand2.getClass() == wand1.getClass();
                tries++) {
            wand2 = generateDebugItem("WAND");
        }

        prepareDebugGeneratedItem(wand1, 1, false);
        prepareDebugGeneratedItem(wand2, 1, false);

        setStaticFieldValue(quest, "type", 2);
        setStaticFieldValue(quest, "spawned", true);
        setStaticFieldValue(quest, "given", false);
        setStaticFieldValue(quest, "wand1", wand1);
        setStaticFieldValue(quest, "wand2", wand2);

        giveDebugQuestItem(EMBERS_CLASS, 1);
        GLog.i("Initialized debug Wandmaker quest with an Embers turn-in item.");
    }

    private static void initializeBlacksmithQuestForDebug() throws Exception {
        Class<?> quest = loadRequired(str(BLACKSMITH_CLASS, "$Quest"));

        boolean spawned = staticBoolean(quest, "spawned");
        boolean completed = staticBoolean(quest, "completed");
        InvocationResult available = invokeCompatibleObjects(
                null, quest, "rewardsAvailable",
                new Object[0], false, false);
        boolean rewardsAvailable = available.invoked
                && Boolean.TRUE.equals(available.result);

        if (spawned && (!completed || rewardsAvailable)) {
            return;
        }

        invokeCompatibleObjects(
                null, quest, "reset", new Object[0], false, false);
        InvocationResult generated = invokeCompatibleObjects(
                null, quest, "generateRewards",
                new Object[]{false}, false, false);
        if (!generated.invoked) {
            throw new NoSuchMethodException(
                    "Blacksmith.Quest.generateRewards(boolean)");
        }

        setStaticFieldValue(quest, "type", 1);
        setStaticFieldValue(quest, "spawned", true);
        setStaticFieldValue(quest, "given", true);
        setStaticFieldValue(quest, "started", true);
        setStaticFieldValue(quest, "bossBeaten", true);
        setStaticFieldValue(quest, "completed", true);
        setStaticFieldValue(quest, "favor", 3000);
        setStaticFieldValue(quest, "freePickaxe", true);
        setStaticFieldValue(quest, "reforges", 0);
        setStaticFieldValue(quest, "hardens", 0);
        setStaticFieldValue(quest, "upgrades", 0);
        setStaticFieldValue(quest, "smiths", 0);

        GLog.i("Initialized debug Blacksmith rewards with 3000 favor.");
    }

    private static void initializeImpQuestForDebug() throws Exception {
        Class<?> quest = loadRequired(str(IMP_CLASS, "$Quest"));

        boolean usable = staticBoolean(quest, "spawned")
                && !staticBoolean(quest, "completed")
                && staticFieldValue(quest, "reward") != null;
        if (usable) {
            return;
        }

        invokeCompatibleObjects(
                null, quest, "reset", new Object[0], false, false);

        Item reward = generateDebugItem("RING");
        prepareDebugGeneratedItem(reward, 2, true);

        boolean alternative = Dungeon.depth <= 18;
        setStaticFieldValue(quest, "alternative", alternative);
        setStaticFieldValue(quest, "spawned", true);
        setStaticFieldValue(quest, "given", false);
        setStaticFieldValue(quest, "completed", false);
        setStaticFieldValue(quest, "reward", reward);

        giveDebugQuestItem(DWARF_TOKEN_CLASS, 5);
        GLog.i("Initialized debug Imp quest with 5 dwarf tokens.");
    }

    private static void registerDebugDemonSpawner(Mob mob) throws Exception {
        Field recorded = findField(mob.getClass(), "spawnRecorded");
        if (recorded == null || recorded.getBoolean(mob)) {
            return;
        }

        Class<?> statistics = loadRequired(STATISTICS_CLASS);
        Field alive = requireField(statistics, "spawnersAlive");
        alive.setInt(null, alive.getInt(null) + 1);
        recorded.setBoolean(mob, true);
        GLog.i("Registered debug DemonSpawner in Statistics.spawnersAlive.");
    }

    private static Item generateDebugItem(String categoryName) throws Exception {
        Class<?> generator = loadRequired(GENERATOR_CLASS);
        Class<?> categoryType = loadRequired(GENERATOR_CATEGORY_CLASS);
        Object category = null;

        Object[] constants = categoryType.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(categoryName)) {
                    category = constant;
                    break;
                }
            }
        }
        if (category == null) {
            throw new IllegalArgumentException(
                    str("Generator category not found: ", categoryName));
        }

        InvocationResult generated = invokeCompatibleObjects(
                null, generator, "random",
                new Object[]{category}, false, false);
        if (!generated.invoked || !(generated.result instanceof Item)) {
            generated = invokeCompatibleObjects(
                    null, generator, "randomUsingDefaults",
                    new Object[]{category}, false, false);
        }
        if (!generated.invoked || !(generated.result instanceof Item)) {
            throw new NoSuchMethodException(str(
                    "No compatible Generator item factory for ", categoryName));
        }
        return (Item) generated.result;
    }

    private static void prepareDebugGeneratedItem(
            Item item, int upgrades, boolean cursed) throws Exception {
        if (item == null) {
            return;
        }

        if (upgrades > 0) {
            InvocationResult upgraded = invokeCompatibleObjects(
                    item, item.getClass(), "upgrade",
                    new Object[]{upgrades}, false, false);
            if (!upgraded.invoked) {
                for (int i = 0; i < upgrades; i++) {
                    InvocationResult one = invokeCompatibleObjects(
                            item, item.getClass(), "upgrade",
                            new Object[0], false, false);
                    if (!one.invoked) {
                        throw new NoSuchMethodException(
                                "Target item has no compatible upgrade method");
                    }
                }
            }
        }

        Field cursedField = findField(item.getClass(), "cursed");
        if (cursedField != null) {
            cursedField.setBoolean(item, cursed);
        }
    }

    private static void giveDebugQuestItem(
            String className, int quantity) throws Exception {
        if (Dungeon.hero == null) {
            return;
        }

        Class<?> type = loadRequired(className);
        Object created = newInstance(type);
        if (!(created instanceof Item)) {
            throw new IllegalArgumentException(str(
                    className, " is not an Item"));
        }

        Item item = (Item) created;
        if (quantity > 1) {
            InvocationResult quantitySet = invokeCompatibleObjects(
                    item, item.getClass(), "quantity",
                    new Object[]{quantity}, false, false);
            if (!quantitySet.invoked) {
                throw new NoSuchMethodException(
                        "Target quest item has no quantity(int) setter");
            }
        }

        if (!debugPickUp(item)) {
            GLog.w(str(
                    "Could not add debug quest item ",
                    type.getSimpleName(), " to the hero."));
        }
    }

    private static Object staticFieldValue(
            Class<?> type, String name) throws Exception {
        Field field = requireField(type, name);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(str(
                    "Field is not static: ", type.getName(), ".", name));
        }
        return field.get(null);
    }

    private static boolean staticBoolean(
            Class<?> type, String name) throws Exception {
        Object value = staticFieldValue(type, name);
        return value instanceof Boolean && (Boolean) value;
    }

    private static void setStaticFieldValue(
            Class<?> type, String name, Object value) throws Exception {
        Field field = requireField(type, name);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(str(
                    "Field is not static: ", type.getName(), ".", name));
        }
        field.set(null, value);
    }

    private static int randomRespawnCell(Mob mob) throws Exception {
        InvocationResult result = invokeCompatibleObjects(
                Dungeon.level, Dungeon.level.getClass(), "randomRespawnCell",
                new Object[]{mob}, false, false);

        if (!result.invoked || !(result.result instanceof Number)) {
            throw new NoSuchMethodException(
                    "Target level has no compatible randomRespawnCell(Mob)");
        }

        return ((Number) result.result).intValue();
    }

    private static void addMob(Mob mob) throws Exception {
        InvocationResult result = invokeCompatibleObjects(
                null, GameScene.class, "add",
                new Object[]{mob}, false, false);

        if (!result.invoked) {
            throw new NoSuchMethodException(
                    "Target GameScene has no compatible add(mob)");
        }
    }

    private static boolean validMobCell(Mob mob, int cell) {
        boolean invalid =
                cell < 0
                || cell >= Dungeon.level.passable.length
                || Actor.findChar(cell) != null
                || !Dungeon.level.passable[cell]
                || Dungeon.level.solid[cell];

        if (!invalid
                && mob.properties().contains(Char.Property.LARGE)
                && cell < Dungeon.level.openSpace.length
                && !Dungeon.level.openSpace[cell]) {
            invalid = true;
        }

        return !invalid;
    }

    private static void invokeGeneratedHook(
            Object object,
            String methodName,
            List<String> methodArgs) throws Exception {

        if (methodName == null) {
            return;
        }

        InvocationResult result = invokeCompatibleRaw(
                object, object.getClass(), methodName,
                methodArgs, false, false);

        if (!result.invoked) {
            throw new NoSuchMethodException(str(
                    "No compatible ",
                    object.getClass().getSimpleName(),
                    ".", methodName, " with ",
                    methodArgs.size(), " argument(s)"));
        }
    }

    private static void affect(
            List<String> args, final String storeVariable)
            throws Exception {

        if (args.isEmpty()) {
            throw new IllegalArgumentException(
                    "affect <Buff> [duration] [method [args...]]");
        }
        if (Dungeon.hero == null || Dungeon.level == null) {
            throw new IllegalStateException("No active dungeon");
        }

        final Class<?> raw = resolveClass(args.get(0), Buff.class);
        if (raw == null) {
            throw new IllegalArgumentException(
                    str("Buff class not found: ", args.get(0)));
        }

        final List<String> options =
                new ArrayList<>(args.subList(1, args.size()));

        GameScene.selectCell(new CellSelector.Listener() {
            @Override
            public String prompt() {
                return "Select the character to apply the buff to:";
            }

            @Override
            public void onSelect(Integer cell) {
                if (cell == null || cell < 0) {
                    return;
                }

                Char target = Actor.findChar(cell);
                if (target == null) {
                    return;
                }

                try {
                    Buff buff = applyAffect(target, raw, options);
                    if (storeVariable != null && buff != null) {
                        putVariable(storeVariable, buff);
                    }
                } catch (Exception error) {
                    reportCommandError("Affect failed", error);
                }
            }
        });
    }

    private static Buff applyAffect(
            Char target, Class<?> raw, List<String> options)
            throws Exception {

        int index = 0;
        Buff buff;

        if (FlavourBuff.class.isAssignableFrom(raw)
                && !options.isEmpty()) {

            Float duration = tryParseFloat(options.get(0));
            if (duration != null) {
                buff = attachBuff(target, raw, duration);
                index = 1;
            } else {
                buff = attachBuff(target, raw, null);
            }

        } else {
            buff = attachBuff(target, raw, null);
        }

        if (buff == null) {
            throw new IllegalStateException(str(
                    "Buff could not be attached: ",
                    raw.getSimpleName()));
        }

        boolean invoked = false;
        List<String> remaining =
                options.subList(index, options.size());

        if (!FlavourBuff.class.isAssignableFrom(raw)
                && !remaining.isEmpty()) {

            String[] commonMethods = {
                    "set", "reset", "prolong", "extend"
            };

            for (String methodName : commonMethods) {
                InvocationResult result = invokeCompatibleRaw(
                        buff, buff.getClass(),
                        methodName, remaining,
                        false, false);

                if (result.invoked) {
                    invoked = true;
                    break;
                }
            }
        }

        if (!invoked && index < options.size()) {
            String methodName = options.get(index);
            List<String> methodArgs =
                    options.subList(
                            index + 1, options.size());

            InvocationResult result = invokeCompatibleRaw(
                    buff, buff.getClass(),
                    methodName, methodArgs,
                    false, false);

            if (!result.invoked) {
                GLog.w(str(
                        "No supported method matching ",
                        methodName, " was found on ",
                        buff.getClass().getSimpleName(),
                        "."));
            }
        }

        GLog.p(str(
                "Affected ",
                target.getClass().getSimpleName(),
                " with ",
                buff.getClass().getSimpleName()));

        return buff;
    }

    private static Buff attachBuff(
            Char target, Class<?> raw, Float duration) throws Exception {

        Object[] args = duration == null
                ? new Object[]{target, raw}
                : new Object[]{target, raw, duration};

        InvocationResult result = invokeCompatibleObjects(
                null, Buff.class, "affect",
                args, false, false);

        if (!result.invoked || !(result.result instanceof Buff)) {
            throw new NoSuchMethodException(
                    "Target Buff has no compatible affect(Char,Class[,duration])");
        }

        return (Buff) result.result;
    }

    private static Float tryParseFloat(String raw) {
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void seed(
            List<String> args, final String storeVariable)
            throws Exception {

        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalArgumentException(
                    "seed <Blob> [amount]");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        final Class<?> blobBase = loadRequired(BLOB_CLASS);
        final Class<?> raw =
                resolveClass(args.get(0), blobBase);

        if (raw == null) {
            throw new IllegalArgumentException(
                    str("Blob class not found: ", args.get(0)));
        }

        final int amount =
                args.size() == 2
                        ? integerArgument(args.get(1))
                        : 1;

        GameScene.selectCell(new CellSelector.Listener() {
            @Override
            public String prompt() {
                return "Select the tile to seed the blob:";
            }

            @Override
            public void onSelect(Integer cell) {
                if (cell == null || cell < 0) {
                    return;
                }

                try {
                    InvocationResult seeded =
                            invokeCompatibleObjects(
                                    null, blobBase, "seed",
                                    new Object[]{
                                            cell, amount, raw
                                    },
                                    false, false);

                    if (!seeded.invoked
                            || seeded.result == null) {
                        throw new NoSuchMethodException(
                                "No compatible Blob.seed(int,int,Class)");
                    }

                    InvocationResult added =
                            invokeCompatibleObjects(
                                    null, GameScene.class, "add",
                                    new Object[]{seeded.result},
                                    false, false);

                    if (!added.invoked) {
                        throw new NoSuchMethodException(
                                "No compatible GameScene.add(blob)");
                    }

                    if (storeVariable != null) {
                        putVariable(
                                storeVariable, seeded.result);
                    }

                    GLog.p(str(
                            "Seeded ",
                            raw.getSimpleName(),
                            " x", amount));

                } catch (Exception error) {
                    reportCommandError("Seed failed", error);
                }
            }
        });
    }

    private static void terrain(List<String> args) throws Exception {
        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalArgumentException(
                    "terrain <Terrain> [cell|@variable]");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        final Field terrainField = resolveTerrainField(args.get(0));
        if (terrainField == null) {
            throw new IllegalArgumentException(str(
                    "Terrain not found or ambiguous: ", args.get(0)));
        }

        terrainField.setAccessible(true);
        final int terrainValue = terrainField.getInt(null);
        final String terrainName = terrainField.getName();

        if (args.size() == 2) {
            applyTerrain(
                    integerArgument(args.get(1)),
                    terrainValue, terrainName);
            return;
        }

        GameScene.selectCell(new CellSelector.Listener() {
            @Override
            public String prompt() {
                return str("Select location for ", terrainName, ":");
            }

            @Override
            public void onSelect(Integer cell) {
                if (cell == null || cell < 0) {
                    return;
                }

                try {
                    applyTerrain(cell, terrainValue, terrainName);
                } catch (Exception error) {
                    reportCommandError("Terrain change failed", error);
                }
            }
        });
    }

    private static Field resolveTerrainField(String input) throws Exception {
        Class<?> terrain = loadRequired(TERRAIN_CLASS);
        String name = input.trim();
        String lower = name.toLowerCase(Locale.ROOT);

        for (Field field : terrain.getDeclaredFields()) {
            if (isTerrainConstant(field)
                    && field.getName().equalsIgnoreCase(name)) {
                field.setAccessible(true);
                return field;
            }
        }

        for (int rank = 0; rank < 3; rank++) {
            ArrayList<Field> matches = new ArrayList<>();

            for (Field field : terrain.getDeclaredFields()) {
                if (!isTerrainConstant(field)) {
                    continue;
                }

                if (fuzzyMatchRank(
                        lower,
                        field.getName().toLowerCase(Locale.ROOT)) == rank) {
                    matches.add(field);
                }
            }

            if (matches.size() == 1) {
                Field field = matches.get(0);
                field.setAccessible(true);
                GLog.i(str(
                        "Using Terrain.", field.getName(),
                        " for ", input));
                return field;
            }

            if (matches.size() > 1) {
                ArrayList<String> names = new ArrayList<>();
                for (Field field : matches) {
                    names.add(field.getName());
                }
                Collections.sort(names);
                logSimilar(names);
                return null;
            }
        }

        return null;
    }

    private static boolean isTerrainConstant(Field field) {
        if (!Modifier.isStatic(field.getModifiers())
                || !Modifier.isFinal(field.getModifiers())
                || field.getType() != int.class) {
            return false;
        }

        String name = field.getName().toUpperCase(Locale.ROOT);
        return !"PASSABLE".equals(name)
                && !"LOS_BLOCKING".equals(name)
                && !"FLAMABLE".equals(name)
                && !"FLAMMABLE".equals(name)
                && !"SECRET".equals(name)
                && !"SOLID".equals(name)
                && !"AVOID".equals(name)
                && !"LIQUID".equals(name)
                && !"PIT".equals(name)
                && !name.endsWith("_FLAG")
                && !name.endsWith("_FLAGS");
    }

    private static void applyTerrain(
            int cell, int terrainValue, String terrainName)
            throws Exception {

        boolean insideMap = false;
        InvocationResult inside = invokeCompatibleObjects(
                Dungeon.level,
                Dungeon.level.getClass(),
                "insideMap",
                new Object[]{cell},
                false, false);

        if (inside.invoked && inside.result instanceof Boolean) {
            insideMap = (Boolean) inside.result;
        } else {
            Field mapField = findField(Dungeon.level.getClass(), "map");
            if (mapField == null) {
                throw new NoSuchFieldException(
                        "Target level has no map field");
            }
            Object map = mapField.get(Dungeon.level);
            insideMap = map != null
                    && map.getClass().isArray()
                    && cell >= 0
                    && cell < Array.getLength(map);
        }

        if (!insideMap) {
            throw new IllegalArgumentException(
                    str("Cell is outside the map: ", cell));
        }

        Class<?> levelType = loadRequired(LEVEL_CLASS);
        InvocationResult setResult = invokeCompatibleObjects(
                null, levelType, "set",
                new Object[]{cell, terrainValue},
                false, false);

        if (!setResult.invoked) {
            throw new NoSuchMethodException(
                    "Target Level has no compatible static set(cell, terrain)");
        }

        refreshTerrainCell(cell);
        GLog.p(str(
                "Set cell ", cell,
                " to Terrain.", terrainName));
    }

    private static void refreshTerrainCell(int cell) throws Exception {
        InvocationResult updated = invokeCompatibleObjects(
                null, GameScene.class, "updateMap",
                new Object[]{cell},
                false, false);

        if (!updated.invoked) {
            invokeCompatibleObjects(
                    null, GameScene.class, "updateMap",
                    new Object[0],
                    false, false);
        }

        invokeCompatibleObjects(
                null, Dungeon.class, "observe",
                new Object[0],
                false, false);

        invokeCompatibleObjects(
                null, GameScene.class, "updateFog",
                new Object[0],
                false, false);
    }

    private static void trap(
            List<String> args, final String storeVariable)
            throws Exception {

        if (args.size() != 1) {
            throw new IllegalArgumentException(
                    "trap <Trap>");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        final Class<?> trapBase = loadRequired(TRAP_CLASS);
        final Class<?> raw =
                resolveClass(args.get(0), trapBase);

        if (raw == null) {
            throw new IllegalArgumentException(
                    str("Trap class not found: ", args.get(0)));
        }

        final Object trap = newInstance(raw);

        GameScene.selectCell(new CellSelector.Listener() {
            @Override
            public String prompt() {
                return "Select location of trap:";
            }

            @Override
            public void onSelect(Integer cell) {
                if (cell == null || cell < 0) {
                    return;
                }

                try {
                    Object placed = trap;

                    InvocationResult setResult =
                            invokeCompatibleObjects(
                                    trap, trap.getClass(), "set",
                                    new Object[]{cell},
                                    false, false);
                    if (setResult.invoked
                            && setResult.result != null) {
                        placed = setResult.result;
                    }

                    InvocationResult revealResult =
                            invokeCompatibleObjects(
                                    placed, placed.getClass(),
                                    "reveal", new Object[0],
                                    false, false);
                    if (revealResult.invoked
                            && revealResult.result != null) {
                        placed = revealResult.result;
                    }

                    InvocationResult levelSet =
                            invokeCompatibleObjects(
                                    Dungeon.level,
                                    Dungeon.level.getClass(),
                                    "setTrap",
                                    new Object[]{placed, cell},
                                    false, false);

                    if (!levelSet.invoked) {
                        throw new NoSuchMethodException(
                                "Target level has no compatible setTrap");
                    }

                    Class<?> terrain =
                            loadRequired(TERRAIN_CLASS);
                    Field trapTerrain =
                            requireField(terrain, "TRAP");
                    int terrainValue =
                            trapTerrain.getInt(null);

                    Class<?> levelType =
                            loadRequired(LEVEL_CLASS);
                    InvocationResult tileSet =
                            invokeCompatibleObjects(
                                    null, levelType, "set",
                                    new Object[]{
                                            cell, terrainValue
                                    },
                                    false, false);

                    if (!tileSet.invoked) {
                        throw new NoSuchMethodException(
                                "Target Level has no compatible static set(cell, terrain)");
                    }

                    refreshTerrainCell(cell);

                    if (storeVariable != null) {
                        putVariable(
                                storeVariable, placed);
                    }

                    GLog.p(str(
                            "Placed ",
                            raw.getSimpleName()));

                } catch (Exception error) {
                    reportCommandError("Trap placement failed", error);
                }
            }
        });
    }

    private static void warp(List<String> args) throws Exception {
        if (args.size() > 1) {
            throw new IllegalArgumentException(
                    "warp [cell|@variable]");
        }
        if (Dungeon.hero == null || Dungeon.level == null) {
            throw new IllegalStateException("No active dungeon");
        }

        if (args.size() == 1) {
            warpTo(integerArgument(args.get(0)));
            return;
        }

        GameScene.selectCell(new CellSelector.Listener() {
            @Override
            public String prompt() {
                return "Choose a location to teleport";
            }

            @Override
            public void onSelect(Integer cell) {
                if (cell == null || cell < 0) {
                    return;
                }

                try {
                    warpTo(cell);
                } catch (Exception error) {
                    reportCommandError("Warp failed", error);
                }
            }
        });
    }

    private static void warpTo(int cell) throws Exception {
        if (Dungeon.hero == null || Dungeon.level == null) {
            throw new IllegalStateException("No active dungeon");
        }

        boolean insideMap = false;
        InvocationResult inside =
                invokeCompatibleObjects(
                        Dungeon.level,
                        Dungeon.level.getClass(),
                        "insideMap",
                        new Object[]{cell},
                        false, false);

        if (inside.invoked && inside.result instanceof Boolean) {
            insideMap = (Boolean) inside.result;
        } else {
            Field mapField =
                    findField(Dungeon.level.getClass(), "map");
            if (mapField == null) {
                throw new NoSuchFieldException(
                        "Target level has no map field");
            }
            Object map = mapField.get(Dungeon.level);
            insideMap = map != null
                    && map.getClass().isArray()
                    && cell >= 0
                    && cell < Array.getLength(map);
        }

        if (!insideMap) {
            throw new IllegalArgumentException(
                    str("Cell is outside the map: ", cell));
        }

        Char occupant = Actor.findChar(cell);
        if (occupant != null && occupant != Dungeon.hero) {
            throw new IllegalArgumentException(
                    str("Cell is occupied by ", occupant.name()));
        }

        Dungeon.hero.pos = cell;

        InvocationResult occupied =
                invokeCompatibleObjects(
                        Dungeon.level,
                        Dungeon.level.getClass(),
                        "occupyCell",
                        new Object[]{Dungeon.hero},
                        false, false);
        if (!occupied.invoked) {
            throw new NoSuchMethodException(
                    "Target level has no compatible occupyCell(Char)");
        }

        Field spriteField =
                findField(Dungeon.hero.getClass(), "sprite");
        if (spriteField != null) {
            Object sprite = spriteField.get(Dungeon.hero);
            if (sprite != null) {
                invokeCompatibleObjects(
                        sprite, sprite.getClass(),
                        "interruptMotion",
                        new Object[0],
                        false, false);
                invokeCompatibleObjects(
                        sprite, sprite.getClass(),
                        "place",
                        new Object[]{cell},
                        false, false);
            }
        }

        invokeCompatibleObjects(
                null, Dungeon.class,
                "observe", new Object[0],
                false, false);
        invokeCompatibleObjects(
                null, GameScene.class,
                "updateFog", new Object[0],
                false, false);
        invokeCompatibleObjects(
                Dungeon.hero, Dungeon.hero.getClass(),
                "checkVisibleMobs", new Object[0],
                false, false);

        GLog.p(str("Warped to cell ", cell));
    }

    private static Object getObjectField(List<String> args)
            throws Exception {

        if (args.size() != 2
                || !args.get(0).startsWith("@")) {
            throw new IllegalArgumentException(
                    "get @object <field>");
        }

        Object object = getVariable(args.get(0));
        if (object == null) {
            throw new IllegalArgumentException(str(
                    "Variable is undefined or inactive: ",
                    args.get(0)));
        }

        Field field = findField(
                object.getClass(), args.get(1));
        if (field == null) {
            throw new NoSuchFieldException(str(
                    object.getClass().getName(),
                    ".", args.get(1)));
        }

        Object value = field.get(object);
        GLog.p(str(
                field.getName(), " -> ",
                valueString(value)));
        return value;
    }

    private static void setObjectField(List<String> args)
            throws Exception {

        if (args.size() != 3
                || !args.get(0).startsWith("@")) {
            throw new IllegalArgumentException(
                    "set @object <field> <value>");
        }

        Object object = getVariable(args.get(0));
        if (object == null) {
            throw new IllegalArgumentException(str(
                    "Variable is undefined or inactive: ",
                    args.get(0)));
        }

        Field field = findField(
                object.getClass(), args.get(1));
        if (field == null) {
            throw new NoSuchFieldException(str(
                    object.getClass().getName(),
                    ".", args.get(1)));
        }

        Object value = convertArg(
                field.getType(), args.get(2));
        if (value == BAD_ARG) {
            throw new IllegalArgumentException(str(
                    "Cannot assign ", args.get(2),
                    " to ", field.getType().getName(),
                    " field ", field.getName()));
        }

        field.set(object, value);
        GLog.p(str(
                field.getName(), " = ",
                valueString(field.get(object))));
    }

    private static Object getStaticField(List<String> args)
            throws Exception {

        if (args.size() != 2) {
            throw new IllegalArgumentException(
                    "get <Class> <staticField>");
        }

        Class<?> type = resolveClass(args.get(0), Object.class);
        if (type == null) {
            throw new ClassNotFoundException(args.get(0));
        }

        Field field = findField(type, args.get(1));
        if (field == null) {
            throw new NoSuchFieldException(str(
                    type.getName(), ".", args.get(1)));
        }
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(str(
                    "Field is not static: ",
                    type.getName(), ".", field.getName()));
        }

        Object value = field.get(null);
        GLog.p(str(
                type.getSimpleName(), ".", field.getName(),
                " -> ", valueString(value)));
        return value;
    }

    private static void setStaticField(List<String> args)
            throws Exception {

        if (args.size() != 3) {
            throw new IllegalArgumentException(
                    "set <Class> <staticField> <value>");
        }

        Class<?> type = resolveClass(args.get(0), Object.class);
        if (type == null) {
            throw new ClassNotFoundException(args.get(0));
        }

        Field field = findField(type, args.get(1));
        if (field == null) {
            throw new NoSuchFieldException(str(
                    type.getName(), ".", args.get(1)));
        }
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(str(
                    "Field is not static: ",
                    type.getName(), ".", field.getName()));
        }
        if (Modifier.isFinal(field.getModifiers())) {
            throw new IllegalArgumentException(str(
                    "Static final field is read-only: ",
                    type.getName(), ".", field.getName()));
        }

        Object value = convertArg(field.getType(), args.get(2));
        if (value == BAD_ARG) {
            throw new IllegalArgumentException(str(
                    "Cannot assign ", args.get(2),
                    " to ", field.getType().getName(),
                    " field ", field.getName()));
        }

        field.set(null, value);
        GLog.p(str(
                type.getSimpleName(), ".", field.getName(),
                " = ", valueString(field.get(null))));
    }

    private static void inspect(List<String> args)
            throws Exception {

        if (args.size() != 1) {
            throw new IllegalArgumentException(
                    "inspect <Class|hero|level|@variable>");
        }

        TargetRef target = target(args.get(0));
        Class<?> type = target.type;

        List<Field> fields = allFields(type);
        List<Method> methods = allMethods(type);

        Collections.sort(
                fields, new FieldNameComparator());
        Collections.sort(
                methods, new MethodKeyComparator());

        StringBuilder out =
                new StringBuilder(type.getName());

        if (target.instance != null) {
            out.append("\nObject: ")
                    .append(debugName(target.instance));
        }

        if (!fields.isEmpty()) {
            out.append("\nFields:");
            int count = 0;

            for (Field field : fields) {
                if (count++ >= 40) {
                    out.append("\n  ...");
                    break;
                }

                out.append("\n  ")
                        .append(Modifier.toString(
                                field.getModifiers()))
                        .append(' ')
                        .append(field.getType()
                                .getSimpleName())
                        .append(' ')
                        .append(field.getName());
            }
        }

        if (!methods.isEmpty()) {
            out.append("\nMethods:");
            int count = 0;

            for (Method method : methods) {
                if (count++ >= 60) {
                    out.append("\n  ...");
                    break;
                }

                out.append("\n  ")
                        .append(Modifier.toString(
                                method.getModifiers()))
                        .append(' ')
                        .append(method.getReturnType()
                                .getSimpleName())
                        .append(' ')
                        .append(method.getName())
                        .append('(');

                Class<?>[] params =
                        method.getParameterTypes();

                for (int i = 0;
                        i < params.length; i++) {

                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(
                            params[i].getSimpleName());
                }

                out.append(')');
            }
        }

        GLog.i(out.toString());
    }

    private static InvocationResult use(
            List<String> args) throws Exception {

        if (args.size() < 2) {
            throw new IllegalArgumentException(
                    "use <Class|hero|level|@variable> <method> [args...]");
        }

        TargetRef ref = target(args.get(0));
        String name = args.get(1);
        List<String> rawArgs =
                args.subList(2, args.size());

        InvocationResult result =
                invokeCompatibleRaw(
                        ref.instance, ref.type,
                        name, rawArgs,
                        true, true);

        if (!result.invoked) {
            throw new NoSuchMethodException(str(
                    "No compatible ",
                    ref.type.getSimpleName(),
                    ".", name, " with ",
                    rawArgs.size(),
                    " argument(s)"));
        }

        return result;
    }

    private static Object applyEquipmentEffect(
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

    private static void gotoLevel(List<String> args)
            throws Exception {

        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalArgumentException(
                    "goto <depth> [branch]");
        }
        if (Dungeon.hero == null
                || Dungeon.level == null) {
            throw new IllegalStateException(
                    "No active dungeon");
        }

        int depth = integerArgument(args.get(0));
        int branch =
                args.size() == 2
                        ? integerArgument(args.get(1))
                        : 0;

        ClassLoader loader =
                ModDebug.class.getClassLoader();

        Class<?> interlevel =
                Class.forName(
                        INTERLEVEL_SCENE,
                        true, loader);

        Class<?> modeType =
                Class.forName(
                        str(INTERLEVEL_SCENE, "$Mode"),
                        true, loader);

        Object returnMode = null;
        Object[] modes = modeType.getEnumConstants();

        if (modes != null) {
            for (Object mode : modes) {
                if (mode instanceof Enum
                        && "RETURN".equals(
                                ((Enum<?>) mode)
                                        .name())) {
                    returnMode = mode;
                    break;
                }
            }
        }

        if (returnMode == null) {
            throw new IllegalStateException(
                    "Target has no InterlevelScene RETURN mode");
        }

        Field returnBranch =
                findField(interlevel, "returnBranch");

        if (returnBranch == null && branch != 0) {
            throw new IllegalArgumentException(
                    "Target does not support branch floor selection");
        }

        invokeBeforeTransition(loader);

        requireField(interlevel, "mode")
                .set(null, returnMode);

        requireField(interlevel, "returnDepth")
                .setInt(null, depth);

        if (returnBranch != null) {
            returnBranch.setInt(null, branch);
        }

        requireField(interlevel, "returnPos")
                .setInt(null, -1);

        Class<?> game =
                Class.forName(
                        GAME_CLASS,
                        true, loader);

        Method switchScene =
                game.getMethod(
                        "switchScene", Class.class);

        switchScene.invoke(null, interlevel);
    }

    private static void where(List<String> args)
            throws Exception {

        if (!args.isEmpty()) {
            throw new IllegalArgumentException("where");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException(
                    "No active level");
        }

        int branch = 0;
        Field branchField =
                findField(Dungeon.class, "branch");

        if (branchField != null) {
            branch = branchField.getInt(null);
        }

        GLog.i(str(
                "Depth ", Dungeon.depth,
                ", branch ", branch));
    }

    private static void invokeBeforeTransition(
            ClassLoader loader) throws Exception {

        Class<?> level =
                Class.forName(
                        LEVEL_CLASS, false, loader);

        try {
            Method beforeTransition =
                    level.getDeclaredMethod(
                            "beforeTransition");

            beforeTransition.setAccessible(true);
            beforeTransition.invoke(null);

        } catch (NoSuchMethodException ignored) {
        }
    }

    private static void macro(List<String> args)
            throws Exception {

        loadMacros();

        if (args.isEmpty()) {
            if (MACROS.isEmpty()) {
                GLog.i("No debug macros defined.");
                return;
            }

            ArrayList<String> names =
                    new ArrayList<>(MACROS.keySet());
            Collections.sort(names);

            StringBuilder out =
                    new StringBuilder("Debug macros:");

            for (String name : names) {
                out.append("\n  ").append(name);
            }

            GLog.i(out.toString());
            return;
        }

        if (args.size() != 1) {
            throw new IllegalArgumentException(
                    "macro [name]");
        }

        final String name = args.get(0);

        if (!name.matches(
                "[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException(
                    "Macro name must be a valid identifier");
        }

        if (isBuiltInCommand(name)) {
            throw new IllegalArgumentException(
                    "Macro name conflicts with a debug command");
        }

        final String existing =
                MACROS.containsKey(name)
                        ? MACROS.get(name)
                        : "";

        GameScene.show(new WndTextInput(
                str("Macro ", name),
                "Enter one debug command per line. "
                        + "%1..%9 are macro arguments. "
                        + "Selector commands must be last. "
                        + "An empty body deletes the macro.",
                existing,
                4000,
                true,
                "Confirm",
                "Cancel") {
            @Override
            public void onSelect(
                    boolean positive, String text) {

                if (!positive) {
                    return;
                }

                try {
                    setMacro(
                            name,
                            text == null
                                    ? ""
                                    : text);
                } catch (Exception error) {
                    reportCommandError(
                            "Macro save failed",
                            error);
                }
            }
        });
    }

    private static boolean runMacro(
            String name,
            List<String> args,
            int depth) throws Exception {

        loadMacros();
        String body = MACROS.get(name);
        if (body == null) {
            return false;
        }

        if (depth >= 8) {
            throw new IllegalStateException(
                    "Macro recursion limit reached");
        }

        String[] lines = body.split("\\r?\\n");
        ArrayList<String> expanded =
                new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith("#")) {
                continue;
            }
            expanded.add(
                    expandMacroLine(
                            trimmed, args));
        }

        String previousCommand = null;

        for (int i = 0;
                i < expanded.size(); i++) {

            String line = expanded.get(i);
            Integer historyCount = historyRepeatCount(line);

            if (historyCount != null) {
                if (previousCommand == null) {
                    throw new IllegalStateException(
                            "No previous command in this macro invocation");
                }

                GLog.i(str("> ", line));
                runHistoryCommand(
                        previousCommand, historyCount, depth + 1);
                continue;
            }

            if (i + 1 < expanded.size()
                    && commandOrMacroNeedsSelector(line, depth + 1)) {
                throw new IllegalArgumentException(str(
                        "Selector command must be the final macro line: ",
                        line));
            }

            GLog.i(str("> ", line));
            executeExpanded(line, depth + 1);
            previousCommand = line;
        }

        return true;
    }

    private static String expandMacroLine(
            String line,
            List<String> args) {

        String expanded = line;

        for (int i = 9; i >= 1; i--) {
            String marker = str("%", i);

            if (!expanded.contains(marker)) {
                continue;
            }

            if (i > args.size()) {
                throw new IllegalArgumentException(str(
                        "Macro argument ", marker,
                        " was not provided"));
            }

            expanded = expanded.replace(
                    marker,
                    quoteToken(args.get(i - 1)));
        }

        Matcher unresolved =
                Pattern.compile("%[1-9]")
                        .matcher(expanded);

        if (unresolved.find()) {
            throw new IllegalArgumentException(str(
                    "Macro argument ",
                    unresolved.group(),
                    " was not provided"));
        }

        return expanded;
    }

    private static boolean commandNeedsSelector(
            String commandLine) {

        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            return false;
        }

        if (tokens.get(0).startsWith("@")) {
            if (tokens.size() >= 2) {
                String variableAction =
                        tokens.get(1)
                                .toLowerCase(Locale.ROOT);

                if ("inv".equals(variableAction)
                        || "inventory".equals(variableAction)
                        || "cell".equals(variableAction)
                        || "char".equals(variableAction)
                        || "character".equals(variableAction)
                        || "obj".equals(variableAction)
                        || "object".equals(variableAction)) {
                    return true;
                }
            }

            tokens.remove(0);
            if (tokens.isEmpty()) {
                return false;
            }
        }

        String command =
                tokens.get(0)
                        .toLowerCase(Locale.ROOT);

        if ("affect".equals(command)
                || "seed".equals(command)
                || "trap".equals(command)
                || "macro".equals(command)) {
            return true;
        }

        if ("terrain".equals(command)) {
            return tokens.size() < 3;
        }

        if ("warp".equals(command)) {
            return tokens.size() == 1;
        }

        if ("spawn".equals(command)) {
            for (String token : tokens) {
                if ("-p".equalsIgnoreCase(token)
                        || "--place".equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }

        return false;
    }


    private static boolean commandOrMacroNeedsSelector(
            String commandLine, int macroDepth) {

        if (commandNeedsSelector(commandLine)) {
            return true;
        }

        if (macroDepth >= 8) {
            return false;
        }

        try {
            loadMacros();
            List<String> tokens = tokenize(commandLine);
            if (tokens.isEmpty() || tokens.get(0).startsWith("@")) {
                return false;
            }

            String macroName = tokens.get(0).toLowerCase(Locale.ROOT);
            String body = MACROS.get(macroName);
            if (body == null) {
                return false;
            }

            List<String> macroArgs = new ArrayList<>(
                    tokens.subList(1, tokens.size()));
            String previous = null;

            for (String rawLine : body.split("\\r?\\n")) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                String line = expandMacroLine(trimmed, macroArgs);
                Integer historyCount = historyRepeatCount(line);
                if (historyCount != null) {
                    if (previous != null
                            && historyCount > 1
                            && commandOrMacroNeedsSelector(
                                    previous, macroDepth + 1)) {
                        return true;
                    }
                    continue;
                }

                if (commandOrMacroNeedsSelector(
                        line, macroDepth + 1)) {
                    return true;
                }
                previous = line;
            }

        } catch (Exception ignored) {
            // If the macro cannot be expanded here, normal execution will
            // report the actual error. Do not invent a selector dependency.
        }

        return false;
    }

    private static void setMacro(
            String name, String body) throws Exception {

        loadMacros();
        String trimmed = body.trim();

        if (trimmed.isEmpty()) {
            MACROS.remove(name);
            saveMacros();
            GLog.i(str("Macro ", name, " deleted"));
            return;
        }

        MACROS.put(name, body);
        saveMacros();
        GLog.p(str("Macro ", name, " saved"));
    }

    private static synchronized void loadMacros()
            throws Exception {

        if (macrosLoaded) {
            return;
        }
        macrosLoaded = true;

        File file = macroFile();
        if (!file.isFile()) {
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream in =
                new FileInputStream(file)) {
            properties.load(in);
        }

        for (String name :
                properties.stringPropertyNames()) {
            MACROS.put(
                    name,
                    properties.getProperty(name, ""));
        }
    }

    private static synchronized void saveMacros()
            throws Exception {

        File file = macroFile();
        File parent = file.getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Properties properties = new Properties();
        properties.putAll(MACROS);

        try (FileOutputStream out =
                new FileOutputStream(file)) {
            properties.store(
                    out, "SMM ModDebug macros");
        }
    }

    private static File macroFile() {
        try {
            Class<?> activityThread =
                    Class.forName(
                            "android.app.ActivityThread");

            Object application =
                    activityThread
                            .getMethod(
                                    "currentApplication")
                            .invoke(null);

            if (application != null) {
                Method getFilesDir =
                        application.getClass()
                                .getMethod(
                                        "getFilesDir");

                Object directory =
                        getFilesDir.invoke(application);

                if (directory instanceof File) {
                    return new File(
                            (File) directory,
                            "smm-debug-macros.properties");
                }
            }
        } catch (Throwable ignored) {
        }

        String home =
                System.getProperty(
                        "user.home", ".");

        return new File(
                home,
                ".smm-debug-macros.properties");
    }

    private static void save(List<String> args)
            throws Exception {

        if (!args.isEmpty()) {
            throw new IllegalArgumentException("save");
        }

        ModSaveTransfer.exportSave();
    }

    private static void load(List<String> args)
            throws Exception {

        if (!args.isEmpty()) {
            throw new IllegalArgumentException("load");
        }

        ModSaveTransfer.importSave();
    }

    private static void listVariables() {
        if (VARIABLES.isEmpty()) {
            GLog.i("No debug variables defined.");
            return;
        }

        ArrayList<String> names =
                new ArrayList<>(VARIABLES.keySet());
        Collections.sort(names);

        StringBuilder out =
                new StringBuilder("Debug variables:");

        for (String name : names) {
            Object value =
                    getVariable(
                            str("@", name));

            out.append("\n  @")
                    .append(name)
                    .append(" = ")
                    .append(
                            value == null
                                    ? "<inactive>"
                                    : debugName(value));
        }

        GLog.i(out.toString());
    }

    private static void showVariable(String token) {
        Object value = getVariable(token);

        if (value == null) {
            GLog.w(str(
                    token,
                    " is undefined or inactive"));
        } else {
            GLog.i(str(
                    token, " = ",
                    debugName(value)));
        }
    }

    private static void selectInventoryVariable(
            final String token) {

        GameScene.selectItem(
                new WndBag.ItemSelector() {
                    @Override
                    public String textPrompt() {
                        return "Select an item";
                    }

                    @Override
                    public boolean itemSelectable(
                            Item item) {
                        return item != null;
                    }

                    @Override
                    public void onSelect(Item item) {
                        if (item != null) {
                            putVariable(token, item);
                        }
                    }
                });
    }

    private static void selectCellVariable(
            final String token,
            final boolean charOnly,
            final boolean objectMode) {

        GameScene.selectCell(
                new CellSelector.Listener() {
                    @Override
                    public String prompt() {
                        if (charOnly) {
                            return "Select a character";
                        }
                        if (objectMode) {
                            return "Select a tile/object";
                        }
                        return "Select a cell";
                    }

                    @Override
                    public void onSelect(Integer cell) {
                        if (cell == null || cell < 0) {
                            return;
                        }

                        if (charOnly) {
                            Char target =
                                    Actor.findChar(cell);

                            if (target == null) {
                                GLog.w(
                                        "No character on that cell.");
                                return;
                            }

                            putVariable(
                                    token, target);
                            return;
                        }

                        if (objectMode) {
                            Object object =
                                    objectAtCell(cell);
                            putVariable(
                                    token,
                                    object != null
                                            ? object
                                            : cell);
                            return;
                        }

                        putVariable(token, cell);
                    }
                });
    }

    private static Object objectAtCell(int cell) {
        Char target = Actor.findChar(cell);
        if (target != null) {
            return target;
        }

        try {
            Method method =
                    GameScene.class
                            .getDeclaredMethod(
                                    "getObjectsAtCell",
                                    int.class);

            method.setAccessible(true);
            Object result =
                    method.invoke(null, cell);

            if (result instanceof Iterable) {
                for (Object object :
                        (Iterable<?>) result) {
                    if (object != null) {
                        return object;
                    }
                }
            }

            if (result != null
                    && result.getClass().isArray()
                    && Array.getLength(result) > 0) {
                return Array.get(result, 0);
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    private static void putVariable(
            String token, Object value) {

        if (value == null) {
            return;
        }

        String key = variableKey(token);
        if (key == null) {
            throw new IllegalArgumentException(
                    str("Invalid variable name: ", token));
        }

        VARIABLES.put(
                key, new StoredValue(value));

        GLog.p(str(
                "@", key, " = ",
                debugName(value)));
    }

    private static Object getVariable(String token) {
        String key = variableKey(token);
        if (key == null) {
            return null;
        }

        StoredValue stored =
                VARIABLES.get(key);

        return stored == null
                ? null
                : stored.get();
    }

    private static boolean hasVariable(String token) {
        String key = variableKey(token);
        return key != null
                && VARIABLES.containsKey(key);
    }

    private static String variableKey(String token) {
        if (token == null
                || !token.startsWith("@")
                || token.length() < 2) {
            return null;
        }

        String key = token.substring(1);

        return key.matches(
                "[A-Za-z_][A-Za-z0-9_$]*")
                ? key
                : null;
    }

    private static TargetRef target(String token)
            throws Exception {

        if (token.startsWith("@")) {
            Object value = getVariable(token);

            if (value == null) {
                throw new IllegalArgumentException(
                        str(
                                "Variable is undefined or inactive: ",
                                token));
            }

            return new TargetRef(
                    value.getClass(), value);
        }

        if ("hero".equalsIgnoreCase(token)) {
            if (Dungeon.hero == null) {
                throw new IllegalStateException(
                        "No active hero");
            }

            return new TargetRef(
                    Dungeon.hero.getClass(),
                    Dungeon.hero);
        }

        if ("level".equalsIgnoreCase(token)) {
            if (Dungeon.level == null) {
                throw new IllegalStateException(
                        "No active level");
            }

            return new TargetRef(
                    Dungeon.level.getClass(),
                    Dungeon.level);
        }

        Class<?> type =
                resolveClass(token, Object.class);

        if (type == null) {
            throw new ClassNotFoundException(token);
        }

        return new TargetRef(type, null);
    }

    private static InvocationResult invokeCompatibleRaw(
            Object receiver,
            Class<?> type,
            String name,
            List<String> rawArgs,
            boolean instantiateReceiver,
            boolean logResult)
            throws Exception {

        List<Method> candidates =
                allMethods(type);
        Collections.sort(
                candidates,
                new MethodKeyComparator());

        Exception lastError = null;

        for (Method method : candidates) {
            if (!method.getName()
                    .equalsIgnoreCase(name)
                    || method.getParameterTypes().length
                    != rawArgs.size()) {
                continue;
            }

            Object[] converted =
                    convertArgs(
                            method.getParameterTypes(),
                            rawArgs);

            if (converted == null) {
                continue;
            }

            Object actualReceiver =
                    methodReceiver(
                            receiver, type,
                            method,
                            instantiateReceiver);

            if (!Modifier.isStatic(
                    method.getModifiers())
                    && actualReceiver == BAD_ARG) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object result =
                        method.invoke(
                                actualReceiver,
                                converted);

                if (logResult) {
                    GLog.p(str(
                            method.getName(),
                            " -> ",
                            valueString(result)));
                }

                return new InvocationResult(
                        true, result);

            } catch (Exception error) {
                lastError = error;
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        return InvocationResult.NOT_INVOKED;
    }

    private static InvocationResult invokeCompatibleObjects(
            Object receiver,
            Class<?> type,
            String name,
            Object[] args,
            boolean instantiateReceiver,
            boolean logResult)
            throws Exception {

        List<Method> candidates =
                allMethods(type);
        Collections.sort(
                candidates,
                new MethodKeyComparator());

        Exception lastError = null;

        for (Method method : candidates) {
            if (!method.getName()
                    .equalsIgnoreCase(name)
                    || method.getParameterTypes().length
                    != args.length) {
                continue;
            }

            Object[] converted =
                    convertObjectArgs(
                            method.getParameterTypes(),
                            args);

            if (converted == null) {
                continue;
            }

            Object actualReceiver =
                    methodReceiver(
                            receiver, type,
                            method,
                            instantiateReceiver);

            if (!Modifier.isStatic(
                    method.getModifiers())
                    && actualReceiver == BAD_ARG) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object result =
                        method.invoke(
                                actualReceiver,
                                converted);

                if (logResult) {
                    GLog.p(str(
                            method.getName(),
                            " -> ",
                            valueString(result)));
                }

                return new InvocationResult(
                        true, result);

            } catch (Exception error) {
                lastError = error;
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        return InvocationResult.NOT_INVOKED;
    }

    private static Object methodReceiver(
            Object receiver,
            Class<?> type,
            Method method,
            boolean instantiateReceiver)
            throws Exception {

        if (Modifier.isStatic(
                method.getModifiers())) {
            return null;
        }

        if (receiver != null) {
            return receiver;
        }

        if (!instantiateReceiver) {
            return BAD_ARG;
        }

        return newInstance(type);
    }

    private static Object[] convertArgs(
            Class<?>[] types,
            List<String> raw) throws Exception {

        Object[] result =
                new Object[types.length];

        for (int i = 0;
                i < types.length; i++) {

            Object value;

            try {
                value =
                        convertArg(
                                types[i],
                                raw.get(i));
            } catch (RuntimeException parseError) {
                return null;
            }

            if (value == BAD_ARG) {
                return null;
            }

            result[i] = value;
        }

        return result;
    }

    private static Object convertArg(
            Class<?> type, String raw)
            throws Exception {

        if (raw.startsWith("@")
                && hasVariable(raw)) {

            Object variable =
                    getVariable(raw);

            if (variable == null) {
                return BAD_ARG;
            }

            return convertObjectArg(
                    type, variable);
        }

        if ("null".equalsIgnoreCase(raw)) {
            return type.isPrimitive()
                    ? BAD_ARG
                    : null;
        }

        if (type == String.class
                || type == CharSequence.class) {
            return raw;
        }

        if (raw.length() > 4
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

        if (type == boolean.class
                || type == Boolean.class) {

            if ("true".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return false;
            }
            return BAD_ARG;
        }

        if (type == byte.class
                || type == Byte.class) {
            return Byte.parseByte(raw);
        }

        if (type == short.class
                || type == Short.class) {
            return Short.parseShort(raw);
        }

        if (type == int.class
                || type == Integer.class) {
            return Integer.parseInt(raw);
        }

        if (type == long.class
                || type == Long.class) {
            return Long.parseLong(raw);
        }

        if (type == float.class
                || type == Float.class) {
            return Float.parseFloat(raw);
        }

        if (type == double.class
                || type == Double.class) {
            return Double.parseDouble(raw);
        }

        if (type == char.class
                || type == Character.class) {
            return raw.length() == 1
                    ? raw.charAt(0)
                    : BAD_ARG;
        }

        if (type == Class.class) {
            Class<?> cls =
                    resolveClass(
                            raw, Object.class);

            return cls == null
                    ? BAD_ARG
                    : cls;
        }

        if (type.isEnum()) {
            for (Object constant :
                    type.getEnumConstants()) {

                if (((Enum<?>) constant)
                        .name()
                        .equalsIgnoreCase(raw)) {
                    return constant;
                }
            }

            return BAD_ARG;
        }

        if (Dungeon.hero != null
                && "hero".equalsIgnoreCase(raw)
                && type.isInstance(
                        Dungeon.hero)) {
            return Dungeon.hero;
        }

        if (Dungeon.level != null
                && "level".equalsIgnoreCase(raw)
                && type.isInstance(
                        Dungeon.level)) {
            return Dungeon.level;
        }

        Class<?> cls =
                resolveClass(raw, type);

        if (cls != null
                && type.isAssignableFrom(cls)) {

            try {
                return newInstance(cls);
            } catch (Exception ignored) {
                return BAD_ARG;
            }
        }

        return BAD_ARG;
    }

    private static Object[] convertObjectArgs(
            Class<?>[] types, Object[] args) {

        Object[] result =
                new Object[types.length];

        for (int i = 0;
                i < types.length; i++) {

            Object converted =
                    convertObjectArg(
                            types[i], args[i]);

            if (converted == BAD_ARG) {
                return null;
            }

            result[i] = converted;
        }

        return result;
    }

    private static Object convertObjectArg(
            Class<?> type, Object value) {

        if (value == null) {
            return type.isPrimitive()
                    ? BAD_ARG
                    : null;
        }

        Class<?> boxed = boxedType(type);

        if (boxed.isInstance(value)) {
            return value;
        }

        if (value instanceof Number
                && Number.class
                        .isAssignableFrom(boxed)) {

            Number number = (Number) value;

            if (boxed == Byte.class) {
                return number.byteValue();
            }
            if (boxed == Short.class) {
                return number.shortValue();
            }
            if (boxed == Integer.class) {
                return number.intValue();
            }
            if (boxed == Long.class) {
                return number.longValue();
            }
            if (boxed == Float.class) {
                return number.floatValue();
            }
            if (boxed == Double.class) {
                return number.doubleValue();
            }
        }

        return BAD_ARG;
    }

    private static Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }

        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }

        return type;
    }

    private static int integerArgument(
            String token) {

        if (token.startsWith("@")
                && hasVariable(token)) {

            Object value = getVariable(token);

            if (value instanceof Number) {
                return ((Number) value)
                        .intValue();
            }

            throw new IllegalArgumentException(str(
                    token,
                    " does not contain a number"));
        }

        return Integer.parseInt(token);
    }

    private static Object newInstance(
            Class<?> type) throws Exception {

        if (type.isInterface()
                || Modifier.isAbstract(
                        type.getModifiers())) {

            throw new InstantiationException(str(
                    "Cannot instantiate ",
                    type.getName()));
        }

        Constructor<?> constructor =
                type.getDeclaredConstructor();

        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static int boundedCount(int count) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException(
                    "quantity must be between 1 and 100");
        }

        return count;
    }

    private static Class<?> resolveClass(
            String input, Class<?> parent) {

        String name = input.trim();

        Class<?> direct =
                tryLoad(name, parent);

        if (direct != null) {
            return direct;
        }

        for (String root : ROOTS) {
            direct =
                    tryLoad(
                            str(root, ".", name),
                            parent);

            if (direct != null) {
                return direct;
            }
        }

        ensureClassIndex();

        String lower =
                name.toLowerCase(Locale.ROOT);

        List<String> matches =
                new ArrayList<>();

        for (String className :
                CLASS_NAMES) {

            String fullLower =
                    className.toLowerCase(
                            Locale.ROOT);

            int dot =
                    className.lastIndexOf('.');
            int dollar =
                    className.lastIndexOf('$');
            int split =
                    Math.max(dot, dollar);

            String simple =
                    className.substring(split + 1);

            if (className.equalsIgnoreCase(name)
                    || fullLower.endsWith(
                            str(".", lower))
                    || fullLower.endsWith(
                            str("$", lower))
                    || simple.equalsIgnoreCase(name)) {

                matches.add(className);
            }
        }

        Collections.sort(
                matches,
                new ClassNameComparator());

        for (String candidate : matches) {
            Class<?> loaded =
                    tryLoad(candidate, parent);

            if (loaded != null) {
                return loaded;
            }
        }

        Class<?> fuzzy = resolveFuzzyClass(name, parent);
        if (fuzzy != null) {
            return fuzzy;
        }

        return null;
    }

    private static Class<?> resolveFuzzyClass(
            String input, Class<?> parent) {

        ensureClassIndex();
        String lower = input.toLowerCase(Locale.ROOT);

        for (int rank = 0; rank < 3; rank++) {
            ArrayList<Class<?>> matches = new ArrayList<>();

            for (String className : CLASS_NAMES) {
                String simple = simpleClassName(className);
                if (fuzzyMatchRank(
                        lower,
                        simple.toLowerCase(Locale.ROOT)) != rank) {
                    continue;
                }

                Class<?> loaded = tryLoad(className, parent);
                if (loaded != null) {
                    matches.add(loaded);
                }
            }

            if (matches.size() == 1) {
                Class<?> loaded = matches.get(0);
                GLog.i(str(
                        "Using ", loaded.getSimpleName(),
                        " for ", input));
                return loaded;
            }

            if (matches.size() > 1) {
                ArrayList<String> names = new ArrayList<>();
                for (Class<?> match : matches) {
                    names.add(match.getSimpleName());
                }
                Collections.sort(names);
                logSimilar(names);
                return null;
            }
        }

        return null;
    }

    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        int dollar = className.lastIndexOf('$');
        return className.substring(Math.max(dot, dollar) + 1);
    }

    private static int fuzzyMatchRank(
            String query, String candidate) {

        if (query == null || query.isEmpty()) {
            return -1;
        }
        if (candidate.startsWith(query)) {
            return 0;
        }
        if (candidate.contains(query)) {
            return 1;
        }
        return isSubsequence(query, candidate) ? 2 : -1;
    }

    private static boolean isSubsequence(
            String query, String candidate) {

        int index = 0;
        for (int i = 0;
                i < candidate.length() && index < query.length();
                i++) {
            if (candidate.charAt(i) == query.charAt(index)) {
                index++;
            }
        }
        return index == query.length();
    }

    private static void logSimilar(List<String> names) {
        StringBuilder out = new StringBuilder("Similar:");
        int limit = Math.min(10, names.size());
        for (int i = 0; i < limit; i++) {
            out.append(i == 0 ? " " : ", ")
                    .append(names.get(i));
        }
        if (names.size() > limit) {
            out.append(", ...");
        }
        GLog.w(out.toString());
    }

    private static Class<?> loadRequired(
            String name) throws ClassNotFoundException {

        Class<?> type =
                tryLoad(name, null);

        if (type == null) {
            throw new ClassNotFoundException(name);
        }

        return type;
    }

    private static Class<?> tryLoad(
            String name, Class<?> parent) {

        try {
            ClassLoader loader =
                    ModDebug.class
                            .getClassLoader();

            Class<?> type =
                    Class.forName(
                            name, false, loader);

            return parent == null
                    || parent.isAssignableFrom(type)
                    ? type
                    : null;

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized void ensureClassIndex() {
        if (indexed) {
            return;
        }
        indexed = true;

        Set<String> names =
                new HashSet<>();

        boolean android =
                indexAndroid(names);

        if (!android) {
            indexDesktop(names);
        }

        CLASS_NAMES.addAll(names);
        Collections.sort(CLASS_NAMES);

        if (CLASS_NAMES.isEmpty()) {
            GLog.w(
                    "Debug class index is empty; use fully-qualified class names.");
        }
    }

    private static boolean indexAndroid(
            Set<String> names) {

        try {
            Class<?> activityThread =
                    Class.forName(
                            "android.app.ActivityThread");

            Object application =
                    activityThread
                            .getMethod(
                                    "currentApplication")
                            .invoke(null);

            if (application == null) {
                return false;
            }

            Method getPackageCodePath =
                    application.getClass()
                            .getMethod(
                                    "getPackageCodePath");

            String path =
                    (String)
                            getPackageCodePath
                                    .invoke(application);

            Class<?> dexFileClass =
                    Class.forName(
                            "dalvik.system.DexFile");

            Object dexFile =
                    dexFileClass
                            .getConstructor(
                                    String.class)
                            .newInstance(path);

            Enumeration<?> entries =
                    (Enumeration<?>)
                            dexFileClass
                                    .getMethod(
                                            "entries")
                                    .invoke(dexFile);

            while (entries.hasMoreElements()) {
                Object next =
                        entries.nextElement();

                if (next instanceof String) {
                    addIndexedName(
                            names,
                            (String) next);
                }
            }

            try {
                dexFileClass
                        .getMethod("close")
                        .invoke(dexFile);
            } catch (Throwable ignored) {
            }

            return true;

        } catch (ClassNotFoundException notAndroid) {
            return false;

        } catch (Throwable error) {
            GLog.w(
                    "Android class scan failed; fully-qualified names still work.");
            error.printStackTrace();
            return true;
        }
    }

    private static void indexDesktop(
            Set<String> names) {

        try {
            URL location =
                    ModDebug.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation();

            if (location == null
                    || !"file".equalsIgnoreCase(
                            location.getProtocol())) {
                return;
            }

            File path =
                    new File(
                            URLDecoder.decode(
                                    location.getPath(),
                                    "UTF-8"));

            if (path.isFile()) {
                try (JarFile jar =
                        new JarFile(path)) {

                    Enumeration<JarEntry> entries =
                            jar.entries();

                    while (entries.hasMoreElements()) {
                        String entry =
                                entries.nextElement()
                                        .getName();

                        if (entry.endsWith(
                                ".class")) {

                            addIndexedName(
                                    names,
                                    entry.substring(
                                                    0,
                                                    entry.length()
                                                            - 6)
                                            .replace(
                                                    '/',
                                                    '.'));
                        }
                    }
                }

            } else if (path.isDirectory()) {
                indexDirectory(
                        names, path, path);
            }

        } catch (Throwable error) {
            GLog.w(
                    "Desktop class scan failed; fully-qualified names still work.");
            error.printStackTrace();
        }
    }

    private static void indexDirectory(
            Set<String> names,
            File root,
            File directory) {

        File[] files =
                directory.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                indexDirectory(
                        names, root, file);

            } else if (file.getName()
                    .endsWith(".class")) {

                String relative =
                        root.toURI()
                                .relativize(
                                        file.toURI())
                                .getPath();

                if (relative.endsWith(
                        ".class")) {

                    addIndexedName(
                            names,
                            relative.substring(
                                            0,
                                            relative.length()
                                                    - 6)
                                    .replace(
                                            '/', '.')
                                    .replace(
                                            '\\', '.'));
                }
            }
        }
    }

    private static void addIndexedName(
            Set<String> names,
            String className) {

        for (String root : ROOTS) {
            if (className.equals(root)
                    || className.startsWith(
                            str(root, "."))) {

                names.add(className);
                return;
            }
        }
    }

    private static List<Field> allFields(
            Class<?> type) {

        ArrayList<Field> result =
                new ArrayList<>();
        Set<String> seen =
                new HashSet<>();

        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {

            for (Field field :
                    current.getDeclaredFields()) {

                String key =
                        str(
                                field.getName(),
                                ":",
                                field.getType()
                                        .getName());

                if (seen.add(key)) {
                    result.add(field);
                }
            }
        }

        return result;
    }

    private static List<Method> allMethods(
            Class<?> type) {

        ArrayList<Method> result =
                new ArrayList<>();
        Set<String> seen =
                new HashSet<>();

        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {

            for (Method method :
                    current.getDeclaredMethods()) {

                String key =
                        methodKey(method);

                if (seen.add(key)) {
                    result.add(method);
                }
            }
        }

        return result;
    }

    private static String methodKey(Method method) {
        StringBuilder key =
                new StringBuilder(
                        method.getName())
                        .append('(');

        for (Class<?> type :
                method.getParameterTypes()) {

            key.append(type.getName())
                    .append(';');
        }

        return key.append(')')
                .toString();
    }

    private static Class<?> findTypeInHierarchy(
            Class<?> type, String className) {

        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {

            if (className.equals(current.getName())) {
                return current;
            }
        }

        return null;
    }

    private static Field findField(
            Class<?> type, String name) {

        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {

            try {
                Field field =
                        current.getDeclaredField(name);

                field.setAccessible(true);
                return field;

            } catch (NoSuchFieldException ignored) {
            }
        }

        return null;
    }

    private static Field requireField(
            Class<?> type, String name)
            throws NoSuchFieldException {

        Field field =
                findField(type, name);

        if (field == null) {
            throw new NoSuchFieldException(
                    str(type.getName(), ".", name));
        }

        return field;
    }

    private static String debugName(Object value) {
        if (value == null) {
            return "null";
        }

        try {
            Method name =
                    value.getClass()
                            .getMethod("name");

            if (name.getParameterTypes().length == 0
                    && name.getReturnType()
                    == String.class) {

                Object result =
                        name.invoke(value);

                if (result != null) {
                    return str(
                            value.getClass()
                                    .getSimpleName(),
                            "(", result, ")");
                }
            }
        } catch (Throwable ignored) {
        }

        return str(
                value.getClass().getSimpleName(),
                "(", valueString(value), ")");
    }

    private static String valueString(Object value) {
        if (value == null) {
            return "null";
        }

        Class<?> type = value.getClass();

        if (!type.isArray()) {
            return String.valueOf(value);
        }

        int length =
                Array.getLength(value);

        StringBuilder result =
                new StringBuilder("[");

        for (int i = 0; i < length; i++) {
            if (i > 0) {
                result.append(", ");
            }

            result.append(
                    String.valueOf(
                            Array.get(value, i)));
        }

        return result.append(']')
                .toString();
    }

    private static void reportCommandError(
            String prefix, Throwable error) {

        error.printStackTrace();

        String message =
                error.getMessage();

        if (message == null
                || message.isEmpty()) {
            GLog.n(str(prefix, "."));
        } else {
            GLog.n(str(
                    prefix, ": ",
                    error.getClass()
                            .getSimpleName(),
                    ": ", message));
        }
    }

    private static String quoteToken(String token) {
        if (token == null) {
            return "\"\"";
        }

        boolean needsQuotes =
                token.isEmpty();

        for (int i = 0;
                i < token.length()
                && !needsQuotes; i++) {

            if (Character.isWhitespace(
                    token.charAt(i))) {
                needsQuotes = true;
            }
        }

        if (!needsQuotes
                && token.indexOf('"') < 0
                && token.indexOf('\\') < 0) {
            return token;
        }

        return str(
                "\"",
                token.replace("\\", "\\\\")
                        .replace("\"", "\\\""),
                "\"");
    }

    private static String str(Object... parts) {
        StringBuilder result =
                new StringBuilder();

        for (Object part : parts) {
            result.append(
                    String.valueOf(part));
        }

        return result.toString();
    }

    private static List<String> tokenize(String text) {
        ArrayList<String> tokens =
                new ArrayList<>();
        StringBuilder current =
                new StringBuilder();

        char quote = 0;
        boolean escaped = false;

        for (int i = 0;
                i < text.length(); i++) {

            char c = text.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\''
                    || c == '"') {
                quote = c;

            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(
                            current.toString());
                    current.setLength(0);
                }

            } else {
                current.append(c);
            }
        }

        if (escaped) {
            current.append('\\');
        }

        if (quote != 0) {
            throw new IllegalArgumentException(
                    "Unclosed quote");
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static boolean isBuiltInCommand(
            String name) {

        String lower =
                name.toLowerCase(Locale.ROOT);

        String[] commands = {
                "help", "give", "spawn",
                "affect", "seed", "trap",
                "terrain", "warp", "inspect", "use",
                "enchant", "inscribe",
                "goto", "where", "macro",
                "search", "results", "get",
                "set", "clear", "save", "load"
        };

        for (String command : commands) {
            if (command.equals(lower)) {
                return true;
            }
        }

        return false;
    }

    private static final class StoredValue {
        private final Object value;

        StoredValue(Object value) {
            this.value = value;
        }

        Object get() {
            return value;
        }
    }

    private static final class InvocationResult {
        static final InvocationResult NOT_INVOKED =
                new InvocationResult(false, null);

        final boolean invoked;
        final Object result;

        InvocationResult(
                boolean invoked, Object result) {
            this.invoked = invoked;
            this.result = result;
        }
    }

    private static final class FieldNameComparator
            implements Comparator<Field> {

        @Override
        public int compare(
                Field left, Field right) {
            return left.getName()
                    .compareTo(right.getName());
        }
    }

    private static final class MethodKeyComparator
            implements Comparator<Method> {

        @Override
        public int compare(
                Method left, Method right) {
            return methodKey(left)
                    .compareTo(methodKey(right));
        }
    }

    private static final class ClassNameComparator
            implements Comparator<String> {

        @Override
        public int compare(
                String left, String right) {

            int byLength =
                    Integer.compare(
                            left.length(),
                            right.length());

            if (byLength != 0) {
                return byLength;
            }

            return left.compareTo(right);
        }
    }

    private static final class TargetRef {
        final Class<?> type;
        final Object instance;

        TargetRef(
                Class<?> type, Object instance) {
            this.type = type;
            this.instance = instance;
        }
    }
}
