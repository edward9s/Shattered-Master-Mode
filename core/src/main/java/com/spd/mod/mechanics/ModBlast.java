package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DemonSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.ImpShopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.audio.Sample;

import java.util.ArrayList;

public class ModBlast {

    public static void castBlast(Char ch) {
        if (Dungeon.level == null) {
            return;
        }

        GameScene.flash(0x80FFFFFF, true);

        if (Sample.INSTANCE != null) {
            Sample.INSTANCE.play(Assets.Sounds.BLAST);
        }

        ArrayList<Mob> targets = new ArrayList<>();
        boolean[] heroFOV = Dungeon.level.heroFOV;

        for (Mob mob : Dungeon.level.mobs) {
            if (mob == null) {
                continue;
            }
            if (mob.pos == -1) {
                continue;
            }
            if (!heroFOV[mob.pos]) {
                continue;
            }
            if (mob.alignment == Char.Alignment.ALLY) {
                continue;
            }
            if (mob == ch) {
                continue;
            }
            if (mob instanceof DemonSpawner) {
                continue;
            }
            if (mob instanceof Shopkeeper) {
                continue;
            }
            if (mob instanceof ImpShopkeeper) {
                continue;
            }

            targets.add(mob);
        }

        for (Mob mob : targets) {
            if (mob.sprite != null) {
                mob.sprite.flash();
            }

            int damage = (mob.HT + mob.shielding()) * 100;
            mob.damage(damage, ch);

            if (mob.isAlive()) {
                Buff.prolong(mob, Blindness.class, 5.0f);
                Buff.prolong(mob, Paralysis.class, 5.0f);
            }
        }

        Dungeon.observe();

        if (ch instanceof Hero) {
            ((Hero) ch).checkVisibleMobs();
        }
    }
}
