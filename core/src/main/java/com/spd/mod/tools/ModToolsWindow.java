package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;

import com.spd.mod.ModGame;
import com.spd.mod.ModUpdates;

public class ModToolsWindow extends WndTitledMessage {

    public static ModToolsWindow instance;

    public ModToolsWindow() {
        super(Icons.PREFS.get(), "Tools v" + ModGame.version(), "Select a function to execute.");
        instance = this;

        int w = this.width;
        if (PixelScene.landscape()) {
            w = 240; 
        }
        float width = (float) w;
        float btnHeight = 20.0f;
        float margin = 1.0f;
        float x = 0.0f;
        float y = (float) this.height + 2.0f;

        ModLevelSlider slider = new ModLevelSlider();
        slider.setSize(width, btnHeight);
        slider.setPos(x, y);
        add(slider);
        
        y += btnHeight + margin;

        float halfWidth = width / 2.0f;

        BtnUpgrade btnUpgrade = new BtnUpgrade();
        btnUpgrade.setSize(halfWidth, btnHeight);
        btnUpgrade.setPos(x, y);
        add(btnUpgrade);

        BtnDegrade btnDegrade = new BtnDegrade();
        btnDegrade.setSize(halfWidth, btnHeight);
        btnDegrade.setPos(halfWidth, y);
        add(btnDegrade);

        y += btnHeight + margin;

        BtnJournal btnJournal = new BtnJournal();
        btnJournal.setSize(halfWidth, btnHeight);
        btnJournal.setPos(x, y);
        add(btnJournal);

        BtnAlchemize btnAlchemize = new BtnAlchemize();
        btnAlchemize.setSize(halfWidth, btnHeight);
        btnAlchemize.setPos(halfWidth, y);
        add(btnAlchemize);

        y += btnHeight + margin;

        BtnIdentify btnIdentify = new BtnIdentify();
        btnIdentify.setSize(halfWidth, btnHeight);
        btnIdentify.setPos(x, y);
        add(btnIdentify);

        BtnCurse btnCurse = new BtnCurse();
        btnCurse.setSize(halfWidth, btnHeight);
        btnCurse.setPos(halfWidth, y);
        add(btnCurse);

        y += btnHeight + margin;

        if (ModGame.isAndroid()) {
            BtnExportSave btnExport = new BtnExportSave();
            btnExport.setSize(halfWidth, btnHeight);
            btnExport.setPos(x, y);
            add(btnExport);

            BtnImportSave btnImport = new BtnImportSave();
            btnImport.setSize(halfWidth, btnHeight);
            btnImport.setPos(halfWidth, y);
            add(btnImport);

            y += btnHeight;
        }

        resize(w, (int) y);
    }

    public static class OpenBtn extends RedButton {

        private boolean updateLabel;

        public OpenBtn() {
            super("Tools");
            textColor(0xffff44);
        }

        @Override
        public void update() {
            super.update();

            boolean available = ModUpdates.updateAvailable();
            if (available != updateLabel) {
                updateLabel = available;
                if (available) {
                    text("Tools (Update!)");
                    textColor(0xffaa00);
                } else {
                    text("Tools");
                    textColor(0xffff44);
                }
            }
        }

        @Override
        protected void onClick() {
            super.onClick();
            if (parent instanceof Window) {
                ((Window) parent).hide();
            }

            if (ModUpdates.updateAvailable()) {
                String latest = ModUpdates.latestVersion();
                GameScene.show(new WndOptions(
                        Icons.CHANGES.get(),
                        "SMM Update",
                        "Shattered Master Mode v" + latest + " is available.\n\n"
                                + "Installed: v" + ModGame.version(),
                        "Release Page",
                        "Continue") {
                    @Override
                    protected void onSelect(int index) {
                        if (index == 0) {
                            ModUpdates.openReleasePage();
                        } else if (index == 1) {
                            GameScene.show(new ModToolsWindow());
                        }
                    }
                });
            } else {
                GameScene.show(new ModToolsWindow());
            }
        }
    }
}
