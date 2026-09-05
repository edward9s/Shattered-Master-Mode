package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModEnemySurge;

/** Enemy Surge's live buff information window. */
public class WndEnemySurgeInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndEnemySurgeInfo(final ModEnemySurge buff) {
        super(buff);

        final RenderedTextBlock currentMultiplier = PixelScene.renderTextBlock(currentText(buff), 10);
        currentMultiplier.hardlight(TITLE_COLOR);
        currentMultiplier.setPos((width - currentMultiplier.width()) / 2f, height + GAP);
        add(currentMultiplier);

        float halfWidth = (width - GAP) / 2f;

        final RedButton downButton = new RedButton(downText(buff), 8) {
            @Override
            protected void onClick() {
                if (!buff.isAttached()) {
                    WndEnemySurgeInfo.this.hide();
                    return;
                }

                buff.setSpawnMultiplier(buff.spawnMultiplier() - 1);
                rebuild(buff);
            }
        };
        downButton.setRect(0, currentMultiplier.bottom() + GAP, halfWidth, BUTTON_HEIGHT);
        add(downButton);

        final RedButton upButton = new RedButton(upText(buff), 8) {
            @Override
            protected void onClick() {
                if (!buff.isAttached()) {
                    WndEnemySurgeInfo.this.hide();
                    return;
                }

                buff.setSpawnMultiplier(buff.spawnMultiplier() + 1);
                rebuild(buff);
            }
        };
        upButton.setRect(downButton.right() + GAP, downButton.top(), halfWidth, BUTTON_HEIGHT);
        add(upButton);

        final RedButton attractButton = new RedButton(attractText(buff), 8) {
            @Override
            protected void onClick() {
                if (!buff.isAttached()) {
                    WndEnemySurgeInfo.this.hide();
                    return;
                }

                buff.toggleAttractEnemies();
                rebuild(buff);
            }
        };
        attractButton.setRect(0, downButton.bottom() + GAP, width, BUTTON_HEIGHT);
        add(attractButton);

        resize(width, (int) attractButton.bottom() + 2);
    }

    private void rebuild(ModEnemySurge buff) {
        ModEnemySurgeInfoOverlay.refreshIndicators();
        hide();
        GameScene.show(new WndEnemySurgeInfo(buff));
    }

    private static String currentText(ModEnemySurge buff) {
        return "SPAWN MULTIPLIER: " + buff.spawnMultiplier() + "x";
    }

    private static String downText(ModEnemySurge buff) {
        return "Down: " + Math.max(1, buff.spawnMultiplier() - 1) + "x";
    }

    private static String upText(ModEnemySurge buff) {
        return "Up: " + Math.min(10, buff.spawnMultiplier() + 1) + "x";
    }

    private static String attractText(ModEnemySurge buff) {
        return buff.attractEnemies() ? "Attract Enemies: ON" : "Attract Enemies: OFF";
    }
}
