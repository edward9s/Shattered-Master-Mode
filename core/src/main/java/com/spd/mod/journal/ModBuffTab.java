package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

public class ModBuffTab extends Component {

    public static ModBuffTab instance;
    public static float scrollTop;

    private ScrollingGridPane grid;

    public ModBuffTab() {
        super();
        instance = this;

        grid = new ScrollingGridPane();
        add(grid);

        ArrayList<ModGridBuff> positiveBuffs = new ArrayList<>();
        ArrayList<ModGridBuff> negativeBuffs = new ArrayList<>();
        ArrayList<ModGridBuff> neutralBuffs = new ArrayList<>();

        for (Class<?> buffClass : ModBuffClass.allBuffs()) {
            try {
                Buff buff = (Buff) Reflection.newInstance(buffClass);
                ModCharSelector.smartSetDuration(buff, 1.0f);

                if (buff.icon() != 127) {
                    if (!buff.name().contains("NO TEXT FOUND")) {
                        BuffIcon icon = new BuffIcon(buff, true);
                        ModGridBuff gridBuff = new ModGridBuff(icon, buffClass);

                        if (buff.type == Buff.buffType.POSITIVE) {
                            positiveBuffs.add(gridBuff);
                        } else if (buff.type == Buff.buffType.NEGATIVE) {
                            negativeBuffs.add(gridBuff);
                        } else {
                            neutralBuffs.add(gridBuff);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore exception and continue to the next entity
            }
        }

        if (!positiveBuffs.isEmpty()) {
            grid.addHeader("Positive Buff");
            for (ModGridBuff gridBuff : positiveBuffs) {
                grid.addItem(gridBuff);
            }
        }

        if (!negativeBuffs.isEmpty()) {
            grid.addHeader("Negative Buff");
            for (ModGridBuff gridBuff : negativeBuffs) {
                grid.addItem(gridBuff);
            }
        }

        if (!neutralBuffs.isEmpty()) {
            grid.addHeader("Neutral Buff");
            for (ModGridBuff gridBuff : neutralBuffs) {
                grid.addItem(gridBuff);
            }
        }
    }

    @Override
    public void update() {
        super.update();
        scrollTop = grid.content().camera.scroll.y;
    }

    @Override
    public void layout() {
        super.layout();
        grid.setRect(this.x, this.y, this.width, this.height);
    }

    public void restoreScroll() {
        grid.scrollTo(0f, scrollTop);
    }
}
