package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Door;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.tweeners.AlphaTweener;

public class ModFlash {

    /**
     * 崩潰防護底線：在地圖內、且無其他角色佔據。刻意不檢查牆壁或任何障礙物——
     * 閃現/位移類功能依設計可落在任何合法地圖點位。
     * 需要落點「合理性」(可站立) 的功能請另外呼叫 isStandable()，
     * 目前只有暗殺 (ModAssassin) 需要。
     */
    public static boolean isValidLanding(Char ch, int pos) {
        Level level = Dungeon.level;

        if (pos < 0 || pos >= level.length() || !level.insideMap(pos)) {
            return false;
        }

        Char occupant = Actor.findChar(pos);
        return occupant == null || occupant == ch;
    }

    /**
     * 可站立性 (牆壁/障礙物檢查)：passable 或 avoid。
     * avoid (陷阱/裂隙) 放行——perform() 既有的危險檢查會加上漂浮處理。
     * 只有需要落點合理性的功能 (暗殺) 才呼叫；一般閃現不受此限。
     */
    public static boolean isStandable(int pos) {
        Level level = Dungeon.level;
        return level.passable[pos] || level.avoid[pos];
    }

    /**
     * 閃現。回傳是否成功移動；落點不合法 (出界或被其他角色佔據) 時
     * 不移動、不做任何改址，印出警告並回傳 false。
     * 本方法不限制地形：牆壁與障礙物皆為合法落點。
     */
    public static boolean perform(Char ch, int targetPos) {
        int oldPos = ch.pos;

        if (!isValidLanding(ch, targetPos)) {
            GLog.w("Cannot travel there.", new Object[0]);
            return false;
        }

        if (isDangerous(targetPos)) {
            Buff.prolong(ch, Levitation.class, 2.0f);
        }

        // 比照 Char.move()：離開開著的門時關門。
        // 若少了這步，導致用卷軸離開門口後，門永遠保持敞開 (看似門被破壞)
        if (Dungeon.level.map[oldPos] == Terrain.OPEN_DOOR) {
            Door.leave(oldPos);
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
            CellEmitter.get(ch.pos).burst(Speck.factory(Speck.WOOL), 6);
        }

        return true;
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

        // 比照 Char.move()：離開開著的門時關門
        if (Dungeon.level.map[oldPos] == Terrain.OPEN_DOOR) {
            Door.leave(oldPos);
        }

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
            CellEmitter.get(targetPos).start(Speck.factory(Speck.LIGHT), 0.2f, 3);
        }
    }
}
