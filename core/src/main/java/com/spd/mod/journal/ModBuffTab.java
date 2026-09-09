package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

public class ModBuffTab extends Component {

    public static ModBuffTab instance;
    public static float scrollTop;

    /** Session-only journal mode. Intentionally never serialized. */
    public static boolean heroOnly = false;

    private static final int HERO_ONLY_BUTTON_WIDTH = 62;
    private static final int HERO_ONLY_BUTTON_HEIGHT = 10;

    private ModScrollingGridPane grid;
    private RedButton heroOnlyButton;

    public ModBuffTab() {
        super();
        instance = this;

        grid = new ModScrollingGridPane();
        add(grid);

        // Master Mode-specific buffs are explicitly pinned above scanned vanilla buffs.
        grid.addHeader("Mod Buff");
        heroOnlyButton = new RedButton(heroOnlyButtonText(), 6);
        // Keep this control in the scroll content so it moves with the Mod Buff header.
        // It is intentionally not a grid item, so the vanilla grid layout remains intact.
        grid.content().add(heroOnlyButton);
        grid.setClickControl(heroOnlyButton, new Runnable() {
            @Override
            public void run() {
                heroOnly = !heroOnly;
                heroOnlyButton.text(heroOnlyButtonText());
            }
        });

        addPinned(new PinnedFactory() {
            @Override public ModGridEntry create() { return new ModGridParryRiposte(); }
        });
        addPinned(new PinnedFactory() {
            @Override public ModGridEntry create() { return new ModGridLastStand(); }
        });
        addPinned(new PinnedFactory() {
            @Override public ModGridEntry create() { return new ModGridEnemySurge(); }
        });
        addPinned(new PinnedFactory() {
            @Override public ModGridEntry create() { return new ModGridAssassinBuff(); }
        });
        addPinned(new PinnedFactory() {
            @Override public ModGridEntry create() { return new ModGridInstantKill(); }
        });

        ArrayList<ModGridBuff> positiveBuffs = new ArrayList<>();
        ArrayList<ModGridBuff> negativeBuffs = new ArrayList<>();
        ArrayList<ModGridBuff> neutralBuffs = new ArrayList<>();

        try {
            for (Class<?> buffClass : ModBuffClass.allBuffs()) {
                try {
                    Buff buff = (Buff) Reflection.newInstanceUnhandled(buffClass);
                    if (buff == null) {
                        continue;
                    }
                    ModCharSelector.smartSetDuration(buff, 1.0f);

                    if (buff.icon() != 127) {
                        String name = buff.name();
                        if (name != null && !name.contains("NO TEXT FOUND")) {
                            BuffIcon icon = new BuffIcon(buff, true);
                            ModGridBuff gridBuff = new ModGridBuff(
                                    icon,
                                    (Class<? extends Buff>) buffClass,
                                    Messages.titleCase(name),
                                    buff.desc());

                            if (buff.type == Buff.buffType.POSITIVE) {
                                positiveBuffs.add(gridBuff);
                            } else if (buff.type == Buff.buffType.NEGATIVE) {
                                negativeBuffs.add(gridBuff);
                            } else {
                                neutralBuffs.add(gridBuff);
                            }
                        }
                    }
                } catch (Throwable ignore) {
                    // Target-specific buff linkage/constructor failures are non-fatal
                    // to the presentation-only journal enumeration.
                }
            }
        } catch (Throwable ignore) {
            // Runtime class discovery is optional journal data.
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

    private static String heroOnlyButtonText() {
        return heroOnly ? "Hero Only: ON" : "Hero Only: OFF";
    }

    private interface PinnedFactory {
        ModGridEntry create();
    }

    private void addPinned(PinnedFactory factory) {
        try {
            ModGridEntry entry = factory.create();
            if (entry != null) {
                grid.addItem(entry);
            }
        } catch (Throwable ignore) {
            // A single debug-buff presentation entry must not close the journal.
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

        float buttonWidth = Math.min(HERO_ONLY_BUTTON_WIDTH, grid.width());
        heroOnlyButton.setRect(
                Math.max(0, grid.width() - buttonWidth),
                0,
                buttonWidth,
                HERO_ONLY_BUTTON_HEIGHT);
    }

    public void restoreScroll() {
        grid.scrollTo(0f, scrollTop);
    }
}
