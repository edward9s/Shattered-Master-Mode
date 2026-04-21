package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.journal.ModJournalWindow;

public class BtnJournal extends RedButton {

    public BtnJournal() {
        super("Journal");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        ModJournalWindow window = new ModJournalWindow();
        GameScene.show(window);
    }
}
