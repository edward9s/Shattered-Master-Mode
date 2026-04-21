package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.watabou.utils.Callback;

import java.util.Iterator;

import com.spd.mod.tools.ModToolsWindow;

public class ModTerrainSelector extends CellSelector.Listener implements Callback {

    private int terrainId;
    private boolean reselecting = false;
    private boolean isClosing = false;

    public ModTerrainSelector(int terrainId) {
        this.terrainId = terrainId;
    }

    public static void start(int terrainId) {
        if (ModJournalWindow.instance != null) {
            ModJournalWindow.instance.hide();
        }
        if (ModToolsWindow.instance != null) {
            ModToolsWindow.instance.hide();
        }
        if (WndGame.instance != null) {
            WndGame.instance.hide();
        }

        GameScene.selectCell(new ModTerrainSelector(terrainId));
    }

    @Override
    public String prompt() {
        String tileName = Dungeon.level.tileName(this.terrainId);
        return "Place " + tileName;
    }

    @Override
    public void call() {
        GameScene.selectCell(this);
    }

    @Override
    public void onSelect(Integer cell) {
        if (cell == null) {
            if (reselecting) {
                reselecting = false;
                return;
            }
            if (!isClosing) {
                isClosing = true;
                GameScene.show(new ModJournalWindow());
            }
            return;
        }

        reselecting = true;
        int pos = cell;
        Level level = Dungeon.level;

        // 1. 檢查並清除舊的跳轉點 (包含防呆機制)
        LevelTransition transition = level.getTransition(pos);
        if (transition != null) {
            LevelTransition.Type type = transition.type;
            int count = 0;
            
            for (LevelTransition t : level.transitions) {
                if (t.type == type) {
                    count++;
                }
            }

            if (count <= 1) {
                GLog.w("Cancelled: Cannot remove the last %s!", type.name());
                ShatteredPixelDungeon.runOnRenderThread(this);
                return;
            } else {
                level.transitions.remove(transition);
            }
        }

        // 2. 修改地形資料庫
        Level.set(pos, this.terrainId);

        // 3. 動態判定並生成新的出入口跳轉點
        LevelTransition.Type newType = null;
        if (this.terrainId == Terrain.ENTRANCE || this.terrainId == Terrain.ENTRANCE_SP) {
            newType = LevelTransition.Type.REGULAR_ENTRANCE;
        } else if (this.terrainId == Terrain.EXIT || this.terrainId == Terrain.UNLOCKED_EXIT) {
            newType = LevelTransition.Type.REGULAR_EXIT;
        }

        if (newType != null) {
            level.transitions.add(new LevelTransition(level, pos, newType));
        }

        // 4. 更新引擎狀態與畫面
        level.cleanWalls();
        GameScene.updateMap(pos);
        Dungeon.observe();
        GameScene.updateFog();

        Char ch = Actor.findChar(pos);
        if (ch != null) {
            level.occupyCell(ch);
        }

        GLog.p("Placed %s", level.tileName(this.terrainId));

        ShatteredPixelDungeon.runOnRenderThread(this);
    }
}
