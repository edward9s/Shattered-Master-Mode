package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModEnemySurge;
import com.spd.mod.tools.ModLevelSlider;

/** Enemy Surge's live buff information window. */
public class WndEnemySurgeInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndEnemySurgeInfo(final ModEnemySurge buff) {
        super(buff);

        final RedButton multiplierButton = new RedButton(multiplierText(buff), 8) {
            @Override
            protected void onClick() {
                if (buff.target == null || buff.target.buff(ModEnemySurge.class) != buff) {
                    WndEnemySurgeInfo.this.hide();
                    return;
                }

                buff.setSpawnMultiplier(ModLevelSlider.level);
                rebuild(buff);
            }
        };
        multiplierButton.setRect(0, height + GAP, width, BUTTON_HEIGHT);
        add(multiplierButton);

        final RedButton attractButton = new RedButton(attractText(buff), 8) {
            @Override
            protected void onClick() {
                if (buff.target == null || buff.target.buff(ModEnemySurge.class) != buff) {
                    WndEnemySurgeInfo.this.hide();
                    return;
                }

                buff.toggleAttractEnemies();
                rebuild(buff);
            }
        };
        attractButton.setRect(0, multiplierButton.bottom() + GAP, width, BUTTON_HEIGHT);
        add(attractButton);

        resize(width, (int) attractButton.bottom() + 2);
    }

    private void rebuild(ModEnemySurge buff) {
        ModEnemySurgeInfoOverlay.refreshIndicators();
        hide();
        GameScene.show(new WndEnemySurgeInfo(buff));
    }

    private static String multiplierText(ModEnemySurge buff) {
        return "Apply " + ModLevelSlider.level + "x Rate & Limit (Current " + buff.spawnMultiplier() + "x)";
    }

    private static String attractText(ModEnemySurge buff) {
        return buff.attractEnemies() ? "Attract Enemies: ON" : "Attract Enemies: OFF";
    }
}
