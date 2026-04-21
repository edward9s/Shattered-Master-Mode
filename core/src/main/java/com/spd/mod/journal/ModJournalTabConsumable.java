package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;

public class ModJournalTabConsumable extends WndTabbed.IconTab {

    private final ModJournalWindow mainWindow;

    public ModJournalTabConsumable(ModJournalWindow mainWindow) {
        super(mainWindow, new ItemSprite(ItemSpriteSheet.POTION_HOLDER, null));
        this.mainWindow = mainWindow;
    }

    @Override
    protected void select(boolean selected) {
        super.select(selected);

        ModCatalogTab tab = this.mainWindow.getTabConsumable();
        tab.active = selected;
        tab.visible = selected;

        if (selected) {
            ModJournalWindow.last_index = 1;
            ModCatalogTab.currentTab = 1;
            tab.restoreScroll();
        }
    }
}
