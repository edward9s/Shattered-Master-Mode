package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;

public class ModBlobPane {

    public static void populate(ScrollingGridPane pane) {
        pane.addHeader("Gases & Blobs");

        for (Class<?> blobClass : ModBlobClass.allBlobs()) {
            ModGridBlob item = new ModGridBlob(blobClass);
            pane.addItem(item);
        }
    }
}
