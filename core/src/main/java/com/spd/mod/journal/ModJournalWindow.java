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

        // Content tabs are deliberately lazy. Runtime class scanning and
        // presentation helpers can touch a large amount of target-specific
        // code, so opening the journal should only construct the selected tab.
        add(new IconTab(ModJournalCompat.holderIcon(
                "WEAPON_HOLDER", "SCROLL_HOLDER", "POTION_HOLDER", "SPELL_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (selected) {
                    ModCatalogTab tab = ensureEquipTab();
                    if (tab != null) {
                        tab.active = true;
                        tab.visible = true;
                        tab.restoreScroll();
                    }
                    last_index = 0;
                } else if (tabEquip != null) {
                    tabEquip.active = false;
                    tabEquip.visible = false;
                }
            }
        });

        add(new IconTab(ModJournalCompat.holderIcon(
                "POTION_HOLDER", "SCROLL_HOLDER", "WEAPON_HOLDER", "SPELL_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (selected) {
                    ModCatalogTab tab = ensureConsumableTab();
                    if (tab != null) {
                        tab.active = true;
                        tab.visible = true;
                        tab.restoreScroll();
                    }
                    last_index = 1;
                } else if (tabConsumable != null) {
                    tabConsumable.active = false;
                    tabConsumable.visible = false;
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
                if (selected) {
                    ModBuffTab tab = ensureBuffTab();
                    if (tab != null) {
                        tab.active = true;
                        tab.visible = true;
                        tab.restoreScroll();
                    }
                    last_index = 3;
                } else if (tabBuff != null) {
                    tabBuff.active = false;
                    tabBuff.visible = false;
                }
            }
        });

        add(new IconTab(ModJournalCompat.holderIcon(
                "SPELL_HOLDER", "SCROLL_HOLDER", "POTION_HOLDER", "WEAPON_HOLDER")) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                if (selected) {
                    ModEnvironmentTab tab = ensureEnvironmentTab();
                    if (tab != null) {
                        tab.active = true;
                        tab.visible = true;
                        tab.restoreScroll();
                    }
                    last_index = 4;
                } else if (tabEnvironment != null) {
                    tabEnvironment.active = false;
                    tabEnvironment.visible = false;
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

    private ModCatalogTab ensureEquipTab() {
        if (tabEquip == null) {
            try {
                tabEquip = new ModCatalogTab(Catalog.equipmentCatalogs, 0);
                addContent(tabEquip);
            } catch (Throwable ignore) {
                tabEquip = null;
            }
        }
        return tabEquip;
    }

    private ModCatalogTab ensureConsumableTab() {
        if (tabConsumable == null) {
            try {
                tabConsumable = new ModCatalogTab(Catalog.consumableCatalogs, 1);
                addContent(tabConsumable);
            } catch (Throwable ignore) {
                tabConsumable = null;
            }
        }
        return tabConsumable;
    }

    private ModBestiaryTab ensureBestiaryTab() {
        if (tabBestiary == null) {
            try {
                tabBestiary = new ModBestiaryTab();
                addContent(tabBestiary);
            } catch (Throwable ignore) {
                tabBestiary = null;
            }
        }
        return tabBestiary;
    }

    private ModBuffTab ensureBuffTab() {
        if (tabBuff == null) {
            try {
                tabBuff = new ModBuffTab();
                addContent(tabBuff);
            } catch (Throwable ignore) {
                tabBuff = null;
            }
        }
        return tabBuff;
    }

    private ModEnvironmentTab ensureEnvironmentTab() {
        if (tabEnvironment == null) {
            try {
                tabEnvironment = new ModEnvironmentTab();
                addContent(tabEnvironment);
            } catch (Throwable ignore) {
                tabEnvironment = null;
            }
        }
        return tabEnvironment;
    }

    private void addContent(com.watabou.noosa.ui.Component content) {
        add(content);
        content.setRect(0.0f, 0.0f, (float) width, (float) height);
    }

    public ModCatalogTab getTabEquip() { return this.tabEquip; }
    public ModCatalogTab getTabConsumable() { return this.tabConsumable; }
    public ModBestiaryTab getTabBestiary() { return this.tabBestiary; }
    public ModBuffTab getTabBuff() { return this.tabBuff; }
    public ModEnvironmentTab getTabEnvironment() { return this.tabEnvironment; }

    public void showBestiary() {
        ModBestiaryTab tab = ensureBestiaryTab();
        if (tab != null) {
            tab.active = true;
            tab.visible = true;
            tab.restoreScroll();
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
