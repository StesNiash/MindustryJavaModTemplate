package autobuild;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.entities.units.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.liquid.*;

public class ConfigHandler{
    private static ObjectMap<Long, Object> pending = new ObjectMap<>();

    public static void init(){
        Events.on(BlockBuildEndEvent.class, event -> {
            long key = key(event.tile.x, event.tile.y);

            if(event.breaking){
                pending.remove(key);
                return;
            }

            Building b = event.tile.build;
            if(b == null) return;
            Object cfg = pending.remove(key);
            if(cfg == null) return;
            Log.info("ConfigHandler: applying config for @ @, @", b.block, event.tile.x, event.tile.y);
            b.configure(cfg);
        });
    }

    public static void store(int x, int y, Object config){
        if(config == null) return;
        if(pending.size > 5000){
            pending.clear();
            Log.warn("ConfigHandler: cleared pending map (too many entries)");
        }
        pending.put(key(x, y), config);
    }

    public static void remove(int x, int y){
        pending.remove(key(x, y));
    }

    public static boolean needsPlayerConfig(BuildPlan p){
        if(p.config == null) return false;
        if(p.block instanceof ItemBridge && !(p.block instanceof DirectionBridge)) return true;
        if(p.block instanceof LogicBlock) return true;
        return false;
    }

    private static long key(int x, int y){
        return (long)x << 32 | (y & 0xFFFFFFFFL);
    }
}
