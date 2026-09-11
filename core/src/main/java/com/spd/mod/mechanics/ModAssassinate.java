package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;
import com.watabou.utils.Signal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Permanent Hero buff that exposes Assassinate through a map long press. */
public class ModAssassinate extends Buff {

    private static final String ASSASSIN_ENABLED = "assassin_enabled";
    private static final float DEFAULT_LONG_CLICK = 0.5f;

    private static LongPressLayer inputLayer;
    private static boolean installPending;

    private static Field cellSelectorField;
    private static Field defaultCellListenerField;
    private static Field selectorEventField;
    private static Field gameSceneField;
    private static Float longClickDelay;

    private boolean assassinEnabled = true;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    /** Stable across supported SPD forks; avoids Char.buff(Class) ABI variance. */
    public static ModAssassinate find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModAssassinate buff : ch.buffs(ModAssassinate.class)) {
            return buff;
        }
        return null;
    }

    public boolean assassinEnabled() {
        return assassinEnabled;
    }

    public void toggleAssassin() {
        assassinEnabled = !assassinEnabled;
        if (assassinEnabled) {
            ensureInputLayer();
        }
        BuffIndicator.refreshHero();
        invokeOptionalOverlay("refreshIndicators");
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        if (!super.attachTo(target)) {
            return false;
        }
        invokeOptionalOverlay("ensureInstalled");
        if (assassinEnabled) {
            ensureInputLayer();
        }
        return true;
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            invokeOptionalOverlay("ensureInstalled");
            if (assassinEnabled) {
                ensureInputLayer();
            }
        }
    }

    @Override
    public boolean act() {
        invokeOptionalOverlay("ensureInstalled");
        if (assassinEnabled) {
            ensureInputLayer();
        }
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
        return ModBuffIconCompat.get("PREPARATION");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(assassinEnabled ? 0xB06CFF : 0xAAAAAA);
    }

    @Override
    public String iconTextDisplay() {
        return "A";
    }

    @Override
    public String name() {
        return "Assassinate";
    }

    @Override
    public String desc() {
        return "While enabled, long-press a map cell or character to use Assassinate. "
                + "Attacks follow SPD's normal surprise-attack and weapon accuracy rules. Tap to configure.";
    }

    private static void invokeOptionalOverlay(String methodName) {
        try {
            Class<?> overlay = Class.forName(
                    "com.spd.mod.journal.ModTotalInfoOverlay",
                    false,
                    ModAssassinate.class.getClassLoader());
            Method method = overlay.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The narrow injection payload deliberately omits the optional overlay.
        }
    }

    private static Group currentScene() {
        if (Game.instance == null) {
            return null;
        }
        try {
            if (gameSceneField == null) {
                try {
                    gameSceneField = Game.class.getDeclaredField("scene");
                } catch (NoSuchFieldException ignored) {
                    for (Field field : Game.class.getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                                && Group.class.isAssignableFrom(field.getType())) {
                            if (gameSceneField != null) {
                                gameSceneField = null;
                                break;
                            }
                            gameSceneField = field;
                        }
                    }
                }
                if (gameSceneField != null) {
                    gameSceneField.setAccessible(true);
                }
            }
            if (gameSceneField == null) {
                return null;
            }
            Object scene = gameSceneField.get(Game.instance);
            return scene instanceof Group ? (Group) scene : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static boolean assassinEnabledForHero() {
        if (!(currentScene() instanceof GameScene) || Dungeon.hero == null) {
            return false;
        }
        ModAssassinate buff = find(Dungeon.hero);
        return buff != null && buff.assassinEnabled;
    }

    private static void ensureInputLayer() {
        if (!assassinEnabledForHero()) {
            return;
        }

        Group scene = currentScene();
        if (scene == null) {
            return;
        }

        if (inputLayer != null
                && inputLayer.exists
                && inputLayer.parent == scene) {
            return;
        }

        if (installPending) {
            return;
        }
        installPending = true;

        Callback install = new Callback() {
            @Override
            public void call() {
                installPending = false;
                if (!assassinEnabledForHero()) {
                    return;
                }

                CellSelector selector = currentCellSelector();
                Group activeScene = currentScene();
                if (selector == null || activeScene == null) {
                    return;
                }

                if (inputLayer == null || !inputLayer.exists || inputLayer.parent != activeScene) {
                    inputLayer = new LongPressLayer(selector);
                    activeScene.addToFront(inputLayer);
                }
            }
        };

        if (!runOnRenderThreadIfAvailable(install)) {
            install.call();
        }
    }

    private static boolean runOnRenderThreadIfAvailable(Callback callback) {
        try {
            Class<?> game = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon",
                    false,
                    ModAssassinate.class.getClassLoader());
            Method method = game.getMethod("runOnRenderThread", Callback.class);
            method.invoke(null, callback);
            return true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
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

    private static float longClickDelay() {
        if (longClickDelay != null) {
            return longClickDelay;
        }
        float value = DEFAULT_LONG_CLICK;
        try {
            Class<?> button = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.ui.Button",
                    false,
                    ModAssassinate.class.getClassLoader());
            Field field = button.getDeclaredField("longClick");
            field.setAccessible(true);
            value = field.getFloat(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Old targets without Button use the traditional half-second threshold.
        }
        longClickDelay = value;
        return value;
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
            if (!assassinEnabledForHero()) {
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
                    // A second pointer means pinch/gesture input, never Assassinate.
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

            if (!assassinEnabledForHero()
                    || parent != currentScene()
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
            if (heldTime < longClickDelay()) {
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

            // Cancel the ordinary CellSelector click before invoking Assassinate.
            selector.reset();
            clearPress();

            // Reuse the exact dispatcher already used by ModAssassin.cast().
            // All combat handling belongs to ModAssassin.perform(); this layer
            // is intentionally input-only so one long press can never hit twice.
            new ModAssassin.Selector(hero).onSelect(cell);
            GameScene.ready();
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

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(ASSASSIN_ENABLED, assassinEnabled);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        assassinEnabled = !bundle.contains(ASSASSIN_ENABLED)
                || bundle.getBoolean(ASSASSIN_ENABLED);
    }
}
