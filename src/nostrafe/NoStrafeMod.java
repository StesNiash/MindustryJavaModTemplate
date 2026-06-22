package nostrafe;

import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ContentInitEvent;
import mindustry.mod.Mod;
import mindustry.type.UnitType;

public class NoStrafeMod extends Mod{

    public NoStrafeMod(){
        Events.on(ContentInitEvent.class, e -> {
            Log.info("Disabling strafe penalty for all unit types...");
            for(UnitType type : Vars.content.units()){
                type.strafePenalty = 1f;
            }
            Log.info("Strafe penalty disabled for @ unit types.", Vars.content.units().size);
        });
    }

}
