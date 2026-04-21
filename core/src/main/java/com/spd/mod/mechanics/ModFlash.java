package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.tweeners.AlphaTweener;

public class ModFlash {

    public static void perform(Char ch, int targetPos) {
        int oldPos = ch.pos;

        if (isDangerous(targetPos)) {
            Buff.prolong(ch, Levitation.class, 2.0f);
        }

        ch.pos = targetPos;
        Dungeon.level.occupyCell(ch);

        CharSprite sprite = ch.sprite;
        if (sprite != null) {
            sprite.place(targetPos);
        }

        Dungeon.observe();
        GameScene.updateFog();

        if (ch instanceof Hero) {
            ((Hero) ch).checkVisibleMobs();
        }

        Buff.detach(ch, Roots.class);
        Buff.detach(ch, Paralysis.class);

        if (oldPos != targetPos) {
            Sample.INSTANCE.play(Assets.Sounds.PUFF);
            CellEmitter.get(ch.pos).burst(Speck.factory(Speck.LIGHT), 6);
        }
    }

    public static boolean isDangerous(int pos) {
        Level level = Dungeon.level;
        if (level.pit[pos]) {
            return true;
        }

        Trap trap = level.traps.get(pos);
        if (trap != null && trap.active) {
            return true;
        }

        return false;
    }

    public static void teleport(Char ch, int targetPos) {
        int oldPos = ch.pos;
        ch.pos = targetPos;

        Dungeon.level.occupyCell(ch);

        CharSprite sprite = ch.sprite;
        if (sprite != null) {
            sprite.interruptMotion();
            sprite.place(targetPos);

            if (ch.invisible == 0) {
                sprite.alpha(0.0f);
                sprite.parent.add(new AlphaTweener(sprite, 1.0f, 0.4f));
            }
        }

        Dungeon.observe();
        GameScene.updateFog();

        if (ch instanceof Hero) {
            ((Hero) ch).checkVisibleMobs();
        }

        if (oldPos != targetPos) {
            Sample.INSTANCE.play(Assets.Sounds.TELEPORT);
            CellEmitter.get(targetPos).start(Speck.factory(Speck.WOOL), 0.2f, 3);
        }
    }
}
