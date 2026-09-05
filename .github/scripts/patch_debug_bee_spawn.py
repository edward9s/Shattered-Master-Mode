from pathlib import Path

p = Path("core/src/main/java/com/spd/mod/mechanics/ModDebug.java")
s = p.read_text(encoding="utf-8")

old = '''    private static final String MIMIC_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic";
'''
new = '''    private static final String MIMIC_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic";
    private static final String BEE_CLASS =
            "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bee";
'''
if s.count(old) != 1:
    raise SystemExit(f"MIMIC_CLASS anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

old = '''        Mob mob = fallback != null
                ? fallback
                : (Mob) newInstance(raw);
        mob.pos = cell;
        return mob;
    }

    private static void initializeSpecialMobForDebug(Mob mob) {
'''
new = '''        Mob mob = fallback != null
                ? fallback
                : (Mob) newInstance(raw);
        mob.pos = cell;
        initializeConstructedMobForDebug(mob);
        return mob;
    }

    private static void initializeConstructedMobForDebug(Mob mob) throws Exception {
        if (mob == null) {
            return;
        }

        if (findTypeInHierarchy(mob.getClass(), BEE_CLASS) != null) {
            int level = Dungeon.depth;
            InvocationResult scalingDepth = invokeCompatibleObjects(
                    null, Dungeon.class, "scalingDepth",
                    new Object[0], false, false);
            if (scalingDepth.invoked && scalingDepth.result instanceof Number) {
                level = ((Number) scalingDepth.result).intValue();
            }

            InvocationResult spawned = invokeCompatibleObjects(
                    mob, mob.getClass(), "spawn",
                    new Object[]{level}, false, false);
            if (!spawned.invoked) {
                throw new NoSuchMethodException(
                        "Target Bee has no compatible spawn(int) initializer");
            }

            InvocationResult detached = invokeCompatibleObjects(
                    mob, mob.getClass(), "setPotInfo",
                    new Object[]{-1, null}, false, false);
            if (!detached.invoked) {
                throw new NoSuchMethodException(
                        "Target Bee has no compatible setPotInfo(int,Char) initializer");
            }

            mob.HP = mob.HT;
        }
    }

    private static void initializeSpecialMobForDebug(Mob mob) {
'''
if s.count(old) != 1:
    raise SystemExit(f"newDebugMob anchor count: {s.count(old)}")
s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")
