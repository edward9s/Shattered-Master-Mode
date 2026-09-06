package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;

public class ModTerrainPane {

    public static void populate(ScrollingGridPane pane) {
        pane.addHeader("Terrain Tiles");

        Level level = Dungeon.level;
        if (level == null || Dungeon.hero == null) {
            return;
        }

        String defaultName;
        try {
            defaultName = level.tileName(-1);
        } catch (Throwable ignore) {
            defaultName = null;
        }

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

            try {
                String tileName = level.tileName(i);
                if (tileName == null || (defaultName != null && tileName.equals(defaultName))) {
                    continue;
                }

                int heroPos = Dungeon.hero.pos;
                Image tileImage = DungeonTerrainTilemap.tile(heroPos, i);
                if (tileImage == null) {
                    continue;
                }
                pane.addItem(new ModGridTerrain(
                        tileImage,
                        i,
                        Messages.titleCase(tileName),
                        level.tileDesc(i)));
            } catch (Throwable ignore) {
                // A target-specific terrain id/rendering path is optional journal data.
            }
        }
    }
}
