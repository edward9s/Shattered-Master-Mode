package com.spd.mod.journal;

import com.watabou.noosa.ui.Component;

public class ModEnvironmentTab extends Component {

    public static float scrollTop;

    public ModScrollingGridPane sharedGrid;

    public ModEnvironmentTab() {
        super();

        sharedGrid = new ModScrollingGridPane();
        add(sharedGrid);

        // Terrain/blob inspection is presentation-only. Keep the other section
        // available even when one target fork cannot construct a section.
        try {
            ModTerrainPane.populate(sharedGrid);
        } catch (Throwable ignore) {
        }
        try {
            ModBlobPane.populate(sharedGrid);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void update() {
        super.update();
        scrollTop = sharedGrid.content().camera.scroll.y;
    }

    @Override
    public void layout() {
        super.layout();
        sharedGrid.setRect(x, y, width, height);
    }

    public void restoreScroll() {
        sharedGrid.scrollTo(0f, scrollTop);
    }
}
