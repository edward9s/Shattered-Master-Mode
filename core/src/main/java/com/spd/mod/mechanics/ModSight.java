package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Awareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Foresight;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MindVision;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.AttackIndicator;

public class ModSight {

    public ModSight() {
    }

    public static void onSight(Hero hero) {
        float duration = 2.0f;

        // 掛載並延長 Foresight 與 MindVision 效果
        Buff.prolong(hero, Foresight.class, duration);
        Buff.prolong(hero, MindVision.class, duration);

        Level level = Dungeon.level;
        int pos = hero.pos;

        // 強制重置當前格子的鑑定狀態並重新搜索，以刷新周遭視野
        if (level.mapped != null) {
            level.mapped[pos] = false;
        }
        hero.search(false);

        // 揭露物品與出口
        revealItems();
        revealExit();

        Dungeon.observe();

        // 強制刷新敵人列表與攻擊指示器介面
        hero.checkVisibleMobs();
        AttackIndicator.updateState();
    }

    public static void revealItems() {
        Buff.affect(Dungeon.hero, Awareness.class, 2.0f);
        Dungeon.observe();
    }

    public static void revealExit() {
        Level level = Dungeon.level;
        int exitPos = level.exit();

        if (exitPos != -1) {
            // 在小地圖上標記出口
            if (level.mapped != null) {
                level.mapped[exitPos] = true;
            }
            // 在主畫面去除出口位置的迷霧
            if (level.visited != null) {
                level.visited[exitPos] = true;
            }

            level.discover(exitPos);
            GameScene.updateFog(exitPos, 2);
        }
    }
}
