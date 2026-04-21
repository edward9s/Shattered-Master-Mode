package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Challenges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.SmokeBomb;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bee;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Sheep;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.watabou.utils.Callback;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.lang.reflect.Method;

import com.spd.mod.tools.ModToolsWindow;

public class ModCellSelector extends CellSelector.Listener implements Callback {

    private Class<?> entityClass;
    private boolean reselecting;
    private boolean isClosing;

    public ModCellSelector(Class<?> entityClass) {
        super();
        this.entityClass = entityClass;
    }

    public static void start(Class<?> clazz) {
        if (ModJournalWindow.instance != null) {
            ModJournalWindow.instance.hide();
        }
        if (ModToolsWindow.instance != null) {
            ModToolsWindow.instance.hide();
        }
        if (WndGame.instance != null) {
            WndGame.instance.hide();
        }
        GameScene.selectCell(new ModCellSelector(clazz));
    }

    @Override
    public String prompt() {
        String name;
        try {
            Object instance = Reflection.newInstance(this.entityClass);
            Method method = instance.getClass().getMethod("name");
            name = (String) method.invoke(instance);
        } catch (Exception e) {
            name = this.entityClass.getSimpleName();
        }
        return "Spawn " + name;
    }

    @Override
    public void call() {
        GameScene.selectCell(this);
    }

    @Override
    public void onSelect(Integer pos) {
        if (pos == null) {
            if (this.reselecting) {
                this.reselecting = false;
            } else {
                if (!this.isClosing) {
                    this.isClosing = true;
                    GameScene.show(new ModJournalWindow());
                }
            }
            return;
        }

        this.reselecting = true;
        int cell = pos;

        try {
            Object instance = Reflection.newInstance(this.entityClass);

            if (instance instanceof Mob) {
                spawnMob(cell, (Mob) instance);
            } else if (instance instanceof Trap) {
                spawnTrap(cell, (Trap) instance);
            } else if (instance instanceof Plant) {
                spawnPlant(cell, (Plant) instance);
            }
        } catch (Exception ignore) {
        } finally {
            ShatteredPixelDungeon.runOnRenderThread(this);
        }
    }

    private void spawnMob(int pos, Mob mob) {
        Mob existing = Dungeon.level.findMob(pos);
        if (existing != null) {
            if (existing.sprite != null) {
                existing.sprite.visible = false;
                existing.sprite.kill();
            }
            Actor.remove(existing);
            Dungeon.level.mobs.remove(existing);
            logEntity("Remove %s", existing);
            return;
        }

        mob.pos = pos;

        if (mob instanceof Mimic) {
            Item[] loot;
            if (mob instanceof CrystalMimic) {
                loot = createCrystalLoot();
            } else {
                loot = new Item[0];
            }
            mob = Mimic.spawnAt(pos, mob.getClass(), true, loot);
        }

        initMobStats(mob);

        mob.state = mob.WANDERING;
        GameScene.add(mob);
        Dungeon.level.occupyCell(mob);
        
        logEntity("Spawn %s", mob);
    }

    private void initMobStats(Mob mob) {
        if (mob instanceof WandOfWarding.Ward.WardSentry) {
            WandOfWarding.Ward ward = (WandOfWarding.Ward) mob;
            ward.upgrade(3);
            ward.upgrade(3);
            ward.upgrade(3);
            ward.upgrade(3);
        } else if (mob instanceof Statue) {
            ((Statue) mob).createWeapon(true);
        } else if (mob instanceof Wandmaker) {
            initWandmaker();
        } else if (mob instanceof Bee) {
            Bee bee = (Bee) mob;
            bee.spawn(Dungeon.scalingDepth());
            bee.setPotInfo(-1, null);
        } else if (mob instanceof SmokeBomb.NinjaLog) {
            mob.HT = 20 + Dungeon.scalingDepth() * 5;
        } else if (mob instanceof DriedRose.GhostHero) {
            mob.HT = 20 + Dungeon.scalingDepth() * 8;
        } else if (mob instanceof Sheep) {
            ((Sheep) mob).initialize(8.0f);
        }

        if (mob.HT <= 0) {
            mob.HT = 100;
        }
        mob.HP = mob.HT;
    }

    private void initWandmaker() {
        Wand wand1 = (Wand) Generator.random(Generator.Category.WAND);
        wand1.cursed = false;
        wand1.upgrade();
        Wandmaker.Quest.wand1 = wand1;

        Wand wand2;
        while (true) {
            wand2 = (Wand) Generator.random(Generator.Category.WAND);
            if (wand1.getClass() != wand2.getClass()) {
                break;
            }
        }
        wand2.cursed = false;
        wand2.upgrade();
        Wandmaker.Quest.wand2 = wand2;
    }

    private Item[] createCrystalLoot() {
        int r = Random.Int(3);
        Generator.Category category = Generator.Category.ARTIFACT;
        
        if (r == 0) {
            category = Generator.Category.WAND;
        } else if (r == 1) {
            category = Generator.Category.RING;
        }

        Item item;
        while (true) {
            item = Generator.random(category);
            if (item != null && !Challenges.isItemBlocked(item)) {
                break;
            }
        }

        return new Item[]{item};
    }

    private void spawnTrap(int pos, Trap trap) {
        Trap existing = Dungeon.level.traps.get(pos);
        if (existing == null) {
            Level.set(pos, Terrain.TRAP); // 18 (0x12)
            Dungeon.level.setTrap(trap, pos).reveal();
            logEntity("Spawn %s", trap);

            Char ch = Actor.findChar(pos);
            if (ch != null && !ch.flying) {
                Dungeon.level.pressCell(pos);
            }
        } else {
            logEntity("Remove %s", existing);
            Level.set(pos, Terrain.EMPTY); // 1
            GameScene.updateMap(pos);
        }
    }

    private void spawnPlant(int pos, Plant plant) {
        Plant existing = Dungeon.level.plants.get(pos);
        if (existing == null) {
            plant.pos = pos;
            Dungeon.level.plants.put(pos, plant);
            GameScene.updateMap(pos);
            logEntity("Spawn %s", plant);

            Char ch = Actor.findChar(pos);
            if (ch != null && !ch.flying) {
                Dungeon.level.pressCell(pos);
            }
        } else {
            logEntity("Remove %s", existing);
            existing.wither();
        }
    }

    private void logEntity(String format, Object entity) {
        String name;
        try {
            Method method = entity.getClass().getMethod("name");
            name = (String) method.invoke(entity);
        } catch (Exception e) {
            name = entity.getClass().getSimpleName();
        }
        GLog.p(format, new Object[]{name});
    }
}
