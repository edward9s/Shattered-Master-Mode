package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

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

        // 介面尺寸適應
        if (PixelScene.landscape()) {
            resize(216, 130);
        } else {
            resize(126, 180);
        }

        float w = (float) this.width;
        float h = (float) this.height;

        // 1. 初始化內容面板
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

        // 2. 建立分頁標籤按鈕 (匿名內部類實作)
        
        // 裝備標籤
        add(new IconTab(new ItemSprite(ItemSpriteSheet.WEAPON_HOLDER, null)) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                tabEquip.active = selected;
                tabEquip.visible = selected;
                if (selected) {
                    last_index = 0;
                    tabEquip.restoreScroll();
                }
            }
        });

        // 消耗品標籤
        add(new IconTab(new ItemSprite(ItemSpriteSheet.POTION_HOLDER, null)) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                tabConsumable.active = selected;
                tabConsumable.visible = selected;
                if (selected) {
                    last_index = 1;
                    tabConsumable.restoreScroll();
                }
            }
        });

        // 圖鑑標籤
        add(new IconTab(new ItemSprite(ItemSpriteSheet.ARTIFACT_HOLDER, null)) {
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

        // Buff 標籤
        add(new IconTab(new ItemSprite(ItemSpriteSheet.SCROLL_HOLDER, null)) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                tabBuff.active = selected;
                tabBuff.visible = selected;
                if (selected) {
                    last_index = 3;
                    tabBuff.restoreScroll();
                }
            }
        });

        // 環境標籤
        add(new IconTab(new ItemSprite(ItemSpriteSheet.SEED_HOLDER, null)) {
            @Override
            protected void select(boolean selected) {
                super.select(selected);
                tabEnvironment.active = selected;
                tabEnvironment.visible = selected;
                if (selected) {
                    last_index = 4;
                    tabEnvironment.restoreScroll();
                }
            }
        });

        layoutTabs();
        select(last_index);
    }

    // --- 以下為 Getter 方法 (供其他類別存取面板) ---
    public ModCatalogTab getTabEquip() { return this.tabEquip; }
    public ModCatalogTab getTabConsumable() { return this.tabConsumable; }
    public ModBestiaryTab getTabBestiary() { return this.tabBestiary; }
    public ModBuffTab getTabBuff() { return this.tabBuff; }
    public ModEnvironmentTab getTabEnvironment() { return this.tabEnvironment; }

    // --- 圖鑑切換邏輯 (對應原有 Smali 需求) ---
    public void showBestiary() {
        tabBestiary.active = true;
        tabBestiary.visible = true;
        tabBestiary.restoreScroll();
    }

    public void hideBestiary() {
        tabBestiary.active = false;
        tabBestiary.visible = false;
    }

    // --- 介面位移處理 ---
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
