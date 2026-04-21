package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;

public class ModJournalTabBuff extends WndTabbed.IconTab {

    private final ModJournalWindow mainWindow;

    public ModJournalTabBuff(ModJournalWindow mainWindow) {
        super(mainWindow, new ItemSprite(ItemSpriteSheet.SCROLL_HOLDER, null));
        this.mainWindow = mainWindow;
    }

    @Override
    protected void select(boolean selected) {
        super.select(selected);

        ModBuffTab tabBuff = this.mainWindow.getTabBuff();
        tabBuff.active = selected;
        tabBuff.visible = selected;

        if (selected) {
            ModJournalWindow.last_index = 3;
            tabBuff.restoreScroll();
        }
    }
}
