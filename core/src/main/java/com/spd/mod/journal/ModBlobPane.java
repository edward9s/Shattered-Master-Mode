package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.utils.Reflection;

public class ModBlobPane {

    public static void populate(ScrollingGridPane pane) {
        pane.addHeader("Gases & Blobs");

        for (Class<? extends Blob> blobClass : ModBlobClass.allBlobs()) {
            String infoTitle = blobClass.getSimpleName();
            String infoDescription = null;

            try {
                Blob blob = Reflection.newInstanceUnhandled(blobClass);

                String localizedName = Messages.get(blob, "name");
                if (ModGridEntry.hasUsableText(localizedName)) {
                    infoTitle = Messages.titleCase(localizedName);
                }

                infoDescription = blob.tileDesc();
            } catch (Exception ignored) {
                // Blob effects remain available even if journal metadata cannot be resolved.
            }

            pane.addItem(new ModGridBlob(
                    blobClass,
                    infoTitle,
                    infoDescription));
        }
    }
}
