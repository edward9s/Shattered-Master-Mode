package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;

public class ModJournalTabEquip extends WndTabbed.IconTab {

    private final ModJournalWindow mainWindow;

    public ModJournalTabEquip(ModJournalWindow mainWindow) {
        super(mainWindow, new ItemSprite(ItemSpriteSheet.WEAPON_HOLDER, null));
        this.mainWindow = mainWindow;
    }

    @Override
    protected void select(boolean selected) {
        super.select(selected);

        ModCatalogTab tab = this.mainWindow.getTabEquip();
        tab.active = selected;
        tab.visible = selected;

        if (selected) {
            ModJournalWindow.last_index = 0;
            ModCatalogTab.currentTab = 0;
            tab.restoreScroll();
        }
    }
}
