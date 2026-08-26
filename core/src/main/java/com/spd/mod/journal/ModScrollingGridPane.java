package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Game;
import com.watabou.utils.PointF;

import java.util.ArrayList;

public class ModScrollingGridPane extends ScrollingGridPane {

    private final ArrayList<ModGridEntry> gridEntries = new ArrayList<>();

    public ModScrollingGridPane() {
        super();

        remove(controller);
        controller.destroy();
        controller = new LongClickController();
        add(controller);
    }

    @Override
    public void addItem(ScrollingGridPane.GridItem item) {
        if (!(item instanceof ModGridEntry)) {
            throw new IllegalArgumentException("Mod grid panes only accept ModGridEntry items");
        }

        super.addItem(item);
        gridEntries.add((ModGridEntry) item);
    }

    @Override
    public synchronized void clear() {
        super.clear();
        gridEntries.clear();
    }

    private boolean onLongClick(float x, float y) {
        for (ModGridEntry entry : gridEntries) {
            if (entry.onLongClick(x, y)) {
                return true;
            }
        }
        return false;
    }

    private class LongClickController extends PointerController {

        private boolean pressing;
        private boolean longClicked;
        private float pressTime;
        private final PointF pressStart = new PointF();
        private final float pressDragThreshold = PixelScene.defaultZoom * 8;

        @Override
        protected void onPointerDown(PointerEvent event) {
            super.onPointerDown(event);
            pressing = true;
            longClicked = false;
            pressTime = 0;
            pressStart.set(event.current);
        }

        @Override
        protected void onPointerUp(PointerEvent event) {
            super.onPointerUp(event);
            pressing = false;
        }

        @Override
        protected void onDrag(PointerEvent event) {
            if (longClicked) {
                return;
            }
            if (pressing && PointF.distance(event.current, pressStart) > pressDragThreshold) {
                pressing = false;
            }
            super.onDrag(event);
        }

        @Override
        public void update() {
            super.update();
            if (pressing && (pressTime += Game.elapsed) >= Button.longClick) {
                pressing = false;
                PointF point = content.camera.screenToCamera(
                        (int) pressStart.x, (int) pressStart.y);

                if (ModScrollingGridPane.this.onLongClick(point.x, point.y)) {
                    longClicked = true;
                    if (SPDSettings.vibration()) {
                        Game.vibrate(50);
                    }
                }
            }
        }

        @Override
        protected void onClick(PointerEvent event) {
            if (longClicked) {
                longClicked = false;
                return;
            }
            super.onClick(event);
        }
    }
}
