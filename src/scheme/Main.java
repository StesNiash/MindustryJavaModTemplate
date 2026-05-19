package scheme;

import arc.graphics.g2d.Draw;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.logic.LogicDisplay;

import static arc.Core.*;
import static mindustry.Vars.*;
import static scheme.SchemeVars.*;

public class Main {

    public static void log(String info) {
        app.post(() -> Log.infoTag("Scheme", info));
    }

    public static void error(Throwable info) {
        app.post(() -> Log.err("Scheme", info));
    }

    public static void copy(String text) {
        if (text == null) return;

        app.setClipboardText(text);
        ui.showInfoFade("@copied");
    }
}
