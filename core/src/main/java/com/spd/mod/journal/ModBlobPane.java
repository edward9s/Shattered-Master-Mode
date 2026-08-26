package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.utils.Reflection;

public class ModBlobPane {

    public static void populate(ScrollingGridPane pane) {
        pane.addHeader("Gases & Blobs");

        for (Class<? extends Blob> blobClass : ModBlobClass.allBlobs()) {
            try {
                Blob blob = Reflection.newInstanceUnhandled(blobClass);
                ModGridBlob item = new ModGridBlob(
                        blobClass,
                        Messages.titleCase(Messages.get(blob, "name")),
                        blob.tileDesc());
                pane.addItem(item);
            } catch (Exception ignored) {
                // Internal blob classes without authoritative journal text are not entries.
            }
        }
    }
}
