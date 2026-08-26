package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.watabou.noosa.Image;
import com.watabou.noosa.particles.Emitter;

public class ModGridBlob extends ModGridEntry {

    public Class<? extends Blob> blobClass;
    public Emitter emitter;

    public ModGridBlob(Class<? extends Blob> blobClass,
                       String infoTitle, String infoDescription) {
        this(blobClass, DungeonTerrainTilemap.tile(0, 1), infoTitle, infoDescription);
    }

    private ModGridBlob(Class<? extends Blob> blobClass, Image bg,
                        String infoTitle, String infoDescription) {
        super(bg, infoTitle, infoDescription);
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
