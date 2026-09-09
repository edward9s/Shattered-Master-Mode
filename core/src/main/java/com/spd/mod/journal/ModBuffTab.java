package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

public class ModBuffTab extends Component {

    public static ModBuffTab instance;
    public static float scrollTop;

    /** Session-only journal mode. Intentionally never serialized. */
    public static boolean heroOnly = false;

    private static final int HERO_ONLY_CHECK_WIDTH = 62;
    private static final int HERO_ONLY_CHECK_HEIGHT = 9;

    private ModScrollingGridPane grid;
    private CheckBox heroOnlyCheckBox;

    public ModBuffTab() {
        super();
        instance = this;

        grid = new ModScrollingGridPane();
        add(grid);

        // Keep the Mod Buff header identical to the other buff section headers.
        grid.addHeader("Mod Buff");

        heroOnlyCheckBox = new CheckBox("Hero Only") {
            {
                // Reuse the target fork's own GridHeader text so this checkbox
                // follows whatever font size/style that fork defines for headers.
                remove(text);
                text = headerText("Hero Only");
                add(text);
            }

            @Override
            protected void onClick() {
                super.onClick();
                heroOnly = checked();
            }
        };
        heroOnlyCheckBox.checked(heroOnly);
        add(heroOnlyCheckBox);
        // The grid owns a full-size PointerController; keep the native checkbox
        // ahead of it in pointer dispatch while the Mod Buff header is visible.
        heroOnlyCheckBox.givePointerPriority();

        // Master Mode-specific buffs are explicitly pinned above scanned vanilla buffs.
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

    private static RenderedTextBlock headerText(String label) {
        HeaderTextSource source = new HeaderTextSource(label);
        RenderedTextBlock result = source.takeText();
        source.destroy();
        return result;
    }

    private static class HeaderTextSource extends ScrollingGridPane.GridHeader {
        HeaderTextSource(String label) {
            super(label);
        }

        RenderedTextBlock takeText() {
            remove(text);
            return text;
        }
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

    private void layoutHeroOnlyCheckBox() {
        float checkWidth = Math.min(HERO_ONLY_CHECK_WIDTH, width);
        float checkY = y - grid.content().camera.scroll.y;
        heroOnlyCheckBox.setRect(
                x + Math.max(0, width - checkWidth),
                checkY,
                checkWidth,
                HERO_ONLY_CHECK_HEIGHT);

        // The checkbox is a sibling of the ScrollPane so it receives native button
        // events. Hide it as soon as its header has scrolled out of the viewport.
        heroOnlyCheckBox.visible = checkY + HERO_ONLY_CHECK_HEIGHT > y
                && checkY < y + height;
    }

    @Override
    public void update() {
        super.update();
        scrollTop = grid.content().camera.scroll.y;
        layoutHeroOnlyCheckBox();
    }

    @Override
    public void layout() {
        super.layout();
        grid.setRect(this.x, this.y, this.width, this.height);
        layoutHeroOnlyCheckBox();
    }

    public void restoreScroll() {
        grid.scrollTo(0f, scrollTop);
        layoutHeroOnlyCheckBox();
    }
}
