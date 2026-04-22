package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Callback;
import com.spd.mod.tools.ModToolsWindow;

public class ModBlobSelector extends CellSelector.Listener implements Callback {

    private Class<?> blobClass;
    private boolean reselecting;
    private boolean isClosing;

    public ModBlobSelector(Class<?> blobClass) {
        super();
        this.blobClass = blobClass;
    }

    public static void start(Class<?> blobClass) {
        if (ModJournalWindow.instance != null) {
            ModJournalWindow.instance.hide();
        }
        if (ModToolsWindow.instance != null) {
            ModToolsWindow.instance.hide();
        }
        if (WndGame.instance != null) {
            WndGame.instance.hide();
        }
        
        GameScene.selectCell(new ModBlobSelector(blobClass));
    }

    @Override
    public String prompt() {
        return "Toggle " + this.blobClass.getSimpleName();
    }

    @Override
    public void call() {
        GameScene.selectCell(this);
    }

    @Override
    public void onSelect(Integer pos) {
        if (pos == null) {
            if (this.reselecting) {
                this.reselecting = false;
            } else {
                if (!this.isClosing) {
                    this.isClosing = true;
                    GameScene.show(new ModJournalWindow());
                }
            }
            return;
        }

        this.reselecting = true;
        int cell = pos;

        Blob blob = Dungeon.level.blobs.get(this.blobClass);
        String logMsg;

        if (blob != null && blob.cur != null && blob.cur[cell] > 0) {
            blob.clear(cell);
            logMsg = "Removed %s";
        } else {
            String simpleName = this.blobClass.getSimpleName();
            int amount = ModEmitterHelper.bind(null, simpleName);
            
            @SuppressWarnings("unchecked")
            Class<? extends Blob> clazz = (Class<? extends Blob>) this.blobClass;
            Blob newBlob = Blob.seed(cell, amount, clazz);
            GameScene.add(newBlob);
            
            logMsg = "Placed %s";
        }

        Dungeon.observe();
        GameScene.updateFog();

        GLog.p(logMsg, new Object[]{ this.blobClass.getSimpleName() });

        ShatteredPixelDungeon.runOnRenderThread(this);
    }
}
