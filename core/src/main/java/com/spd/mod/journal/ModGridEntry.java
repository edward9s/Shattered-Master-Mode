package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndJournalItem;
import com.watabou.noosa.Image;

abstract class ModGridEntry extends ScrollingGridPane.GridItem {

    private final String infoTitle;
    private final String infoDescription;

    ModGridEntry(Image image, String infoTitle, String infoDescription) {
        super(image);

        if (!hasUsableText(infoTitle)) {
            throw new IllegalArgumentException("Journal entries require a title");
        }

        this.infoTitle = infoTitle;
        this.infoDescription = normalizeDescription(infoDescription);
    }

    public final boolean onLongClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ShatteredPixelDungeon.scene().addToFront(
                new WndJournalItem(new Image(icon), infoTitle, infoDescription));
        return true;
    }

    static boolean hasUsableText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        String upper = text.toUpperCase();
        return !upper.contains(Messages.NO_TEXT_FOUND)
                && !upper.contains("TEXT NOT FOUND");
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return Messages.NO_TEXT_FOUND;
        }
        return description;
    }
}
