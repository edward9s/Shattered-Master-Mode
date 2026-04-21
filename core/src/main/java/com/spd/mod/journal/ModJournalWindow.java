package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;

public class ModJournalWindow extends WndTabbed {

    public static int last_index = 0;
    public static ModJournalWindow instance;

    private ModCatalogTab tabEquip;
    private ModCatalogTab tabConsumable;
    private ModBestiaryTab tabBestiary;
    private ModBuffTab tabBuff;
    private ModEnvironmentTab tabEnvironment;

    public ModJournalWindow() {
        super();
        instance = this;

        if (PixelScene.landscape()) {
            resize(216, 130);
        } else {
            resize(126, 180);
        }

        float w = (float) this.width;
        float h = (float) this.height;

        this.tabEquip = new ModCatalogTab(Catalog.equipmentCatalogs);
        add(this.tabEquip);
        this.tabEquip.setRect(0.0f, 0.0f, w, h);

        this.tabConsumable = new ModCatalogTab(Catalog.consumableCatalogs);
        add(this.tabConsumable);
        this.tabConsumable.setRect(0.0f, 0.0f, w, h);

        this.tabBestiary = new ModBestiaryTab();
        add(this.tabBestiary);
        this.tabBestiary.setRect(0.0f, 0.0f, w, h);

        this.tabBuff = new ModBuffTab();
        add(this.tabBuff);
        this.tabBuff.setRect(0.0f, 0.0f, w, h);

        this.tabEnvironment = new ModEnvironmentTab();
        add(this.tabEnvironment);
        this.tabEnvironment.setRect(0.0f, 0.0f, w, h);

        add(new ModJournalTabEquip(this));
        add(new ModJournalTabConsumable(this));
        add(new ModJournalTabBestiary(this));
        add(new ModJournalTabBuff(this));
        add(new ModJournalTabEnvironment(this));

        layoutTabs();
        select(last_index);
    }

    public ModCatalogTab getTabEquip() {
        return this.tabEquip;
    }

    public ModCatalogTab getTabConsumable() {
        return this.tabConsumable;
    }

    public ModBestiaryTab getTabBestiary() {
        return this.tabBestiary;
    }

    public ModBuffTab getTabBuff() {
        return this.tabBuff;
    }

    public ModEnvironmentTab getTabEnvironment() {
        return this.tabEnvironment;
    }

    @Override
    public void offset(int x, int y) {
        super.offset(x, y);
        this.tabEquip.layout();
        this.tabConsumable.layout();
        this.tabBestiary.layout();
        this.tabBuff.layout();
        this.tabEnvironment.layout();
    }
}
