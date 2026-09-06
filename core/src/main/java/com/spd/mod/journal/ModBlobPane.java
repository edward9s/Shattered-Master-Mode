package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.utils.Reflection;

public class ModBlobPane {

    public static void populate(ScrollingGridPane pane) {
        pane.addHeader("Gases & Blobs");

        try {
            for (Class<? extends Blob> blobClass : ModBlobClass.allBlobs()) {
                try {
                    String infoTitle = blobClass.getSimpleName();
                    String infoDescription = null;

                    Blob blob = Reflection.newInstanceUnhandled(blobClass);
                    if (blob != null) {
                        String localizedName = Messages.get(blob, "name");
                        if (ModGridEntry.hasUsableText(localizedName)) {
                            infoTitle = Messages.titleCase(localizedName);
                        }
                        infoDescription = blob.tileDesc();
                    }

                    pane.addItem(new ModGridBlob(
                            blobClass,
                            infoTitle,
                            infoDescription));
                } catch (Throwable ignore) {
                    // One target-specific blob class must not make the whole
                    // presentation-only journal unusable.
                }
            }
        } catch (Throwable ignore) {
            // Runtime blob discovery is optional journal data.
        }
    }
}
