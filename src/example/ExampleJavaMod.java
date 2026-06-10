package example;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;

public class ExampleJavaMod extends Mod {

    public ExampleJavaMod() {
        Log.info("No Player Collision mod loaded.");

        Events.on(ClientLoadEvent.class, e -> {
            Events.run(Trigger.update, () -> {
                if (Vars.state.isGame() && Vars.player != null && Vars.player.unit() != null) {
                    Vars.player.unit().type.physics = false;
                }
            });
        });
    }

    @Override
    public void loadContent() {
        Log.info("Loading no player collision content.");
    }

}
