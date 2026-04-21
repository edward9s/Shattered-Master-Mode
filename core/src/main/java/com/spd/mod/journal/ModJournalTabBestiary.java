package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;

import java.lang.reflect.Field;

public class ModJournalTabBestiary extends WndTabbed.IconTab {

    private final ModJournalWindow mainWindow;

    public ModJournalTabBestiary(ModJournalWindow mainWindow) {
        super(mainWindow, new ItemSprite(getIconId(), null));
        this.mainWindow = mainWindow;
    }

    private static int getIconId() {
        try {
            Field field = ItemSpriteSheet.class.getField("MOB_HOLDER");
            return field.getInt(null);
        } catch (Exception e) {
            return ItemSpriteSheet.SOMETHING;
        }
    }

    @Override
    protected void select(boolean selected) {
        super.select(selected);

        if (selected) {
            this.mainWindow.showBestiary();
        } else {
            this.mainWindow.hideBestiary();
        }
    }
}
