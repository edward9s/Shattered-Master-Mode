package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.ui.Component;

public class ModEnvironmentTab extends Component {

    public static float scrollTop;

    public ScrollingGridPane sharedGrid;

    public ModEnvironmentTab() {
        super();

        sharedGrid = new ScrollingGridPane();
        add(sharedGrid);

        // 呼叫子模組填充資料
        ModTerrainPane.populate(sharedGrid);
        ModBlobPane.populate(sharedGrid);
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
