package com.spd.mod.journal;

import com.watabou.noosa.Image;

public class ModGridTerrain extends ModGridEntry {

    public int terrainId;

    public ModGridTerrain(Image image, int terrainId,
                          String infoTitle, String infoDescription) {
        super(image, infoTitle, infoDescription);
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
