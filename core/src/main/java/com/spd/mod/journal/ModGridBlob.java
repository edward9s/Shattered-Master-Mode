package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;
import com.watabou.noosa.particles.Emitter;

public class ModGridBlob extends ScrollingGridPane.GridItem {

    public Class<?> blobClass;
    public Emitter emitter;

    public ModGridBlob(Class<?> blobClass) {
        this(blobClass, DungeonTerrainTilemap.tile(0, 1));
    }

    private ModGridBlob(Class<?> blobClass, Image bg) {
        super(bg);
        this.blobClass = blobClass;

        this.emitter = new Emitter();
        this.emitter.pos(bg);
        add(this.emitter);

        ModEmitterHelper.bind(this.emitter, blobClass.getSimpleName());
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModBlobSelector.start(this.blobClass);
        return true;
    }

    @Override
    public void layout() {
        super.layout();

        if (this.emitter != null) {
            this.emitter.pos(this.x, this.y, this.width, this.height);
        }
    }
}
