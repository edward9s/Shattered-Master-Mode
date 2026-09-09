package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModEnemySurge;
import com.watabou.noosa.ui.Component;

/** Enemy Surge's live buff information window. */
public class WndEnemySurgeInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndEnemySurgeInfo(final ModEnemySurge buff) {
        super(buff);

        final RenderedTextBlock currentMultiplier = PixelScene.renderTextBlock(currentText(buff), 10);
        currentMultiplier.hardlight(TITLE_COLOR);

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

        final Component controls = new Component() {
            @Override
            protected void layout() {
                currentMultiplier.setPos(x + (width - currentMultiplier.width()) / 2f, y);

                float halfWidth = (width - GAP) / 2f;
                downButton.setRect(x, currentMultiplier.bottom() + GAP, halfWidth, BUTTON_HEIGHT);
                upButton.setRect(downButton.right() + GAP, downButton.top(), halfWidth, BUTTON_HEIGHT);
                attractButton.setRect(x, downButton.bottom() + GAP, width, BUTTON_HEIGHT);
            }
        };
        controls.add(currentMultiplier);
        controls.add(downButton);
        controls.add(upButton);
        controls.add(attractButton);
        controls.setSize(
                width,
                currentMultiplier.height() + GAP + BUTTON_HEIGHT + GAP + BUTTON_HEIGHT);

        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
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
