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

        // Every tab is presentation/inspection UI. A target-specific runtime
        // failure in one tab must not prevent the journal itself from opening.
        try {
            this.tabEquip = new ModCatalogTab(Catalog.equipmentCatalogs, 0);
            add(this.tabEquip);
            this.tabEquip.setRect(0.0f, 0.0f, w, h);
        } catch (Throwable ignore) {
            this.tabEquip = null;
        }

        try {
            this.tabConsumable = new ModCatalogTab(Catalog.consumableCatalogs, 1);
            add(this.tabConsumable);
            this.tabConsumable.setRect(0.0f, 0.0f, w, h);
        } catch (Throwable ignore) {
            this.tabConsumable = null;
        }

        try {
            this.tabBestiary = new ModBestiaryTab();
            add(this.tabBestiary);
            this.tabBestiary.setRect(0.0f, 0.0f, w, h);
        } catch (Throwable ignore) {
            this.tabBestiary = null;
        }

        try {
            this.tabBuff = new ModBuffTab();
            add(this.tabBuff);
            this.tabBuff.setRect(0.0f, 0.0f, w, h);
        } catch (Throwable ignore) {
            this.tabBuff = null;
        }

        try {
            this.tabEnvironment = new ModEnvironmentTab();
            add(this.tabEnvironment);
            this.tabEnvironment.setRect(0.0f, 0.0f, w, h);
        } catch (Throwable ignore) {
            this.tabEnvironment = null;
        }

        add(new IconTab(ModJournalCompat.holderIcon(
                "WEAPON_HOLDER", "SCROLL_HOLDER", "POTION_HOLDER", "SPELL_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (tabEquip != null) {
                    tabEquip.active = selected;
                    tabEquip.visible = selected;
                    if (selected) {
                        last_index = 0;
                        tabEquip.restoreScroll();
                    }
                }
            }
        });

        add(new IconTab(ModJournalCompat.holderIcon(
                "POTION_HOLDER", "SCROLL_HOLDER", "WEAPON_HOLDER", "SPELL_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (tabConsumable != null) {
                    tabConsumable.active = selected;
                    tabConsumable.visible = selected;
                    if (selected) {
                        last_index = 1;
                        tabConsumable.restoreScroll();
                    }
                }
            }
        });

        add(new IconTab(ModJournalCompat.holderIcon(
                "MOB_HOLDER", "WEAPON_HOLDER", "SCROLL_HOLDER", "POTION_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (selected) {
                    last_index = 2;
                    showBestiary();
                } else {
                    hideBestiary();
                }
            }
        });

        add(new IconTab(ModJournalCompat.holderIcon(
                "SCROLL_HOLDER", "POTION_HOLDER", "WEAPON_HOLDER", "SPELL_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (tabBuff != null) {
                    tabBuff.active = selected;
                    tabBuff.visible = selected;
                    if (selected) {
                        last_index = 3;
                        tabBuff.restoreScroll();
                    }
                }
            }
        });

        add(new IconTab(ModJournalCompat.holderIcon(
                "SPELL_HOLDER", "SCROLL_HOLDER", "POTION_HOLDER", "WEAPON_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (tabEnvironment != null) {
                    tabEnvironment.active = selected;
                    tabEnvironment.visible = selected;
                    if (selected) {
                        last_index = 4;
                        tabEnvironment.restoreScroll();
                    }
                }
            }
        });

        layoutTabs();
        int index = last_index;
        if (index < 0 || index > 4) {
            index = 0;
            last_index = 0;
        }
        select(index);
    }

    public ModCatalogTab getTabEquip() { return this.tabEquip; }
    public ModCatalogTab getTabConsumable() { return this.tabConsumable; }
    public ModBestiaryTab getTabBestiary() { return this.tabBestiary; }
    public ModBuffTab getTabBuff() { return this.tabBuff; }
    public ModEnvironmentTab getTabEnvironment() { return this.tabEnvironment; }

    public void showBestiary() {
        if (tabBestiary != null) {
            tabBestiary.active = true;
            tabBestiary.visible = true;
            tabBestiary.restoreScroll();
        }
    }

    public void hideBestiary() {
        if (tabBestiary != null) {
            tabBestiary.active = false;
            tabBestiary.visible = false;
        }
    }

    @Override
    public void offset(int x, int y) {
        super.offset(x, y);
        if (this.tabEquip != null) this.tabEquip.layout();
        if (this.tabConsumable != null) this.tabConsumable.layout();
        if (this.tabBestiary != null) this.tabBestiary.layout();
        if (this.tabBuff != null) this.tabBuff.layout();
        if (this.tabEnvironment != null) this.tabEnvironment.layout();
    }
}
