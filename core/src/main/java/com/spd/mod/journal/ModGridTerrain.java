package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;

public class ModGridTerrain extends ScrollingGridPane.GridItem {

    public int terrainId;

    public ModGridTerrain(Image image, int terrainId) {
        super(image);
        this.terrainId = terrainId;
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModTerrainSelector.start(this.terrainId);
        return true;
    }
}
