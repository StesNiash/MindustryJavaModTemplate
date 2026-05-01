package example;

import arc.util.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;

public class ExampleJavaMod extends Mod {

    public ExampleJavaMod() {
        Events.on(BlockBuildBeginEvent.class, e -> {
            if (!e.breaking && e.block == Blocks.coreShard) {
                Call.constructBegin(e.tile, Blocks.vault, e.unit);
            }
        });
    }

    @Override
    public void loadContent() {
        Log.info("Core-shard to vault replacer mod loaded.");
    }
}
