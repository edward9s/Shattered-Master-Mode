package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;

public class ModTerrainPane {

    public static void populate(ScrollingGridPane pane) {
        pane.addHeader("Terrain Tiles");

        Level level = Dungeon.level;
        String defaultName = level.tileName(-1);

        for (int i = 0; i < 48; i++) {
            // 使用 Terrain 常數替換硬編碼 ID
            if (i == Terrain.SECRET_TRAP ||
                i == Terrain.INACTIVE_TRAP ||
                i == Terrain.ENTRANCE ||
                i == Terrain.ENTRANCE_SP ||
                i == Terrain.EXIT ||
                i == Terrain.LOCKED_EXIT ||
                i == Terrain.UNLOCKED_EXIT) {
                continue;
            }

            String tileName = level.tileName(i);
            if (tileName.equals(defaultName)) {
                continue;
            }

            try {
                int heroPos = Dungeon.hero.pos;
                Image tileImage = DungeonTerrainTilemap.tile(heroPos, i);
                pane.addItem(new ModGridTerrain(tileImage, i));
            } catch (Exception e) {
                // Ignore generation failure
            }
        }
    }
}
