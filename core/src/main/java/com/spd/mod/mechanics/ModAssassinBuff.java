package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;
import com.watabou.utils.Signal;

import java.lang.reflect.Field;

/** Permanent Hero buff that exposes Mod Assassin through a map long press. */
public class ModAssassinBuff extends Buff {

    private static LongPressLayer inputLayer;
    private static boolean installPending;

    private static Field cellSelectorField;
    private static Field defaultCellListenerField;
    private static Field selectorEventField;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        return super.attachTo(target);
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            ensureInputLayer();
        }
    }

    @Override
    public boolean act() {
        ensureInputLayer();
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return BuffIndicator.PREPARATION;
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(0xB06CFF);
    }

    @Override
    public String iconTextDisplay() {
        return "A";
    }

    @Override
    public String name() {
        return "Assassin Instinct";
    }

    @Override
    public String desc() {
        return "Permanent Master Mode buff for the Hero. Press and hold a normal map cell or character for about half a second to invoke Mod Assassin on that cell. "
                + "Dragging, pinching, ordinary taps, and other targeting modes keep their original controls.";
    }

    private static void ensureInputLayer() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)) {
            return;
        }

        if (inputLayer != null
                && inputLayer.exists
                && inputLayer.parent == ShatteredPixelDungeon.scene()) {
            return;
        }

        if (installPending) {
            return;
        }
        installPending = true;

        ShatteredPixelDungeon.runOnRenderThread(new Callback() {
            @Override
            public void call() {
                installPending = false;
                if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                        || Dungeon.hero == null
                        || Dungeon.hero.buffs(ModAssassinBuff.class).isEmpty()) {
                    return;
                }

                CellSelector selector = currentCellSelector();
                if (selector == null) {
                    return;
                }

                Group scene = (Group) ShatteredPixelDungeon.scene();
                if (inputLayer == null || !inputLayer.exists || inputLayer.parent != scene) {
                    inputLayer = new LongPressLayer(selector);
                    scene.addToFront(inputLayer);
                }
            }
        });
    }

    private static CellSelector currentCellSelector() {
        try {
            if (cellSelectorField == null) {
                cellSelectorField = GameScene.class.getDeclaredField("cellSelector");
                cellSelectorField.setAccessible(true);
            }
            return (CellSelector) cellSelectorField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object defaultCellListener() {
        try {
            if (defaultCellListenerField == null) {
                defaultCellListenerField = GameScene.class.getDeclaredField("defaultCellListener");
                defaultCellListenerField.setAccessible(true);
            }
            return defaultCellListenerField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PointerEvent selectorEvent(CellSelector selector) {
        try {
            if (selectorEventField == null) {
                selectorEventField = PointerArea.class.getDeclaredField("curEvent");
                selectorEventField.setAccessible(true);
            }
            return (PointerEvent) selectorEventField.get(selector);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean normalMapMode(CellSelector selector) {
        Object defaultListener = defaultCellListener();
        return selector != null
                && selector.enabled
                && defaultListener != null
                && selector.listener == defaultListener;
    }

    private static boolean isCancelEvent(PointerEvent event) {
        // CANCEL does not exist in some older SPD-family PointerEvent.Type enums.
        // Compare its runtime enum text instead of linking the payload against
        // PointerEvent.Type.CANCEL as a mandatory target field.
        return event != null && "CANCEL".equals(String.valueOf(event.type));
    }

    private static class LongPressLayer extends Gizmo implements Signal.Listener<PointerEvent> {

        private final CellSelector selector;
        private final float dragThreshold;

        private PointerEvent press;
        private float heldTime;
        private boolean cancelled;

        LongPressLayer(CellSelector selector) {
            this.selector = selector;
            dragThreshold = PixelScene.defaultZoom * DungeonTilemap.SIZE / 2f;
            PointerEvent.addPointerListener(this);
        }

        @Override
        public boolean onSignal(PointerEvent event) {
            if (!activeForHero()) {
                return false;
            }

            if (event == null) {
                if (press != null && movedTooFar()) {
                    cancelled = true;
                }
                return false;
            }

            if (event.type == PointerEvent.Type.DOWN) {
                if (press != null) {
                    // A second pointer means pinch/gesture input, never Assassin.
                    cancelled = true;
                    return false;
                }

                if (event.button == PointerEvent.RIGHT
                        || event.button == PointerEvent.MIDDLE
                        || event.button == PointerEvent.BACK
                        || event.button == PointerEvent.FORWARD
                        || !normalMapMode(selector)
                        || selector.target == null
                        || !selector.target.overlapsScreenPoint(
                                (int) event.current.x, (int) event.current.y)) {
                    return false;
                }

                press = event;
                heldTime = 0f;
                cancelled = false;
                return false;
            }

            if (press != null && event == press
                    && (event.type == PointerEvent.Type.UP || isCancelEvent(event))) {
                clearPress();
            }

            return false;
        }

        @Override
        public void update() {
            super.update();

            if (!activeForHero()
                    || parent != ShatteredPixelDungeon.scene()
                    || currentCellSelector() != selector) {
                killAndErase();
                return;
            }

            if (press == null) {
                return;
            }

            if (cancelled || movedTooFar()) {
                clearPress();
                return;
            }

            heldTime += Game.elapsed;
            if (heldTime < Button.longClick) {
                return;
            }

            Hero hero = Dungeon.hero;
            if (!normalMapMode(selector)
                    || selectorEvent(selector) != press
                    || hero == null
                    || !hero.ready
                    || GameScene.interfaceBlockingHero()) {
                clearPress();
                return;
            }

            int cell = resolveCell(press.current);
            if (cell == -1) {
                clearPress();
                return;
            }

            // Cancel the ordinary CellSelector click before invoking Assassin.
            selector.reset();
            clearPress();

            // Reuse the exact dispatcher already used by ModAssassin.cast().
            new ModAssassin.Selector(hero).onSelect(cell);
            GameScene.ready();
        }

        private boolean activeForHero() {
            return ShatteredPixelDungeon.scene() instanceof GameScene
                    && Dungeon.hero != null
                    && !Dungeon.hero.buffs(ModAssassinBuff.class).isEmpty();
        }

        private boolean movedTooFar() {
            return press != null
                    && PointF.distance(press.current, press.start) > dragThreshold;
        }

        private int resolveCell(PointF screenPos) {
            if (screenPos == null || Dungeon.level == null || Dungeon.hero == null) {
                return -1;
            }

            PointF p = Camera.main.screenToCamera((int) screenPos.x, (int) screenPos.y);
            Hero hero = Dungeon.hero;

            if (hero.sprite != null && hero.sprite.overlapsPoint(p.x, p.y)) {
                PointF center = DungeonTilemap.tileCenterToWorld(hero.pos);
                if (Math.abs(p.x - center.x) <= 12 && Math.abs(p.y - center.y) <= 12) {
                    return hero.pos;
                }
            }

            for (Mob mob : Dungeon.level.mobs.toArray(new Mob[0])) {
                if (mob.sprite != null && mob.sprite.overlapsPoint(p.x, p.y)) {
                    PointF center = DungeonTilemap.tileCenterToWorld(mob.pos);
                    if (Math.abs(p.x - center.x) <= 12 && Math.abs(p.y - center.y) <= 12) {
                        return mob.pos;
                    }
                }
            }

            if (selector.target instanceof DungeonTilemap) {
                return ((DungeonTilemap) selector.target).screenToTile(
                        (int) screenPos.x, (int) screenPos.y, true);
            }

            return -1;
        }

        private void clearPress() {
            press = null;
            heldTime = 0f;
            cancelled = false;
        }

        @Override
        public void destroy() {
            PointerEvent.removePointerListener(this);
            clearPress();
            if (inputLayer == this) {
                inputLayer = null;
            }
            super.destroy();
        }
    }
}
