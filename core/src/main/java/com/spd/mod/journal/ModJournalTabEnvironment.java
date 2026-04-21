package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;

public class ModJournalTabEnvironment extends WndTabbed.IconTab {

    private final ModJournalWindow mainWindow;

    public ModJournalTabEnvironment(ModJournalWindow mainWindow) {
        super(mainWindow, new ItemSprite(ItemSpriteSheet.WAND_HOLDER, null));
        this.mainWindow = mainWindow;
    }

    @Override
    protected void select(boolean selected) {
        super.select(selected);

        ModEnvironmentTab tab = this.mainWindow.getTabEnvironment();
        tab.active = selected;
        tab.visible = selected;

        if (selected) {
            ModJournalWindow.last_index = 4;
            tab.restoreScroll();
        }
    }
}
