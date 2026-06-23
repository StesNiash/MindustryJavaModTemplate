package autobuild;

import arc.struct.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.content.*;
import mindustry.entities.units.*;

public class ProcessorManager{

    public static Tile findPlaceTile(Seq<BuildPlan> excludePlans, Block processorBlock,
                                     int bbMinX, int bbMinY, int bbMaxX, int bbMaxY){
        Player player = Vars.player;
        if(player == null) return null;
        int startX = World.toTile(player.x);
        int startY = World.toTile(player.y);

        int range = 5;
        for(int dx = -range; dx <= range; dx++){
            for(int dy = -range; dy <= range; dy++){
                int tx = startX + dx;
                int ty = startY + dy;
                Tile t = Vars.world.tile(tx, ty);
                if(t == null) continue;
                if(!isOccupied(excludePlans, tx, ty, processorBlock)
                    && !isInsideBB(tx, ty, processorBlock, bbMinX, bbMinY, bbMaxX, bbMaxY)
                    && Build.validPlace(processorBlock, Vars.player.team(), tx, ty, 0)){
                    return t;
                }
            }
        }

        range = 12;
        for(int dx = -range; dx <= range; dx++){
            for(int dy = -range; dy <= range; dy++){
                int tx = startX + dx;
                int ty = startY + dy;
                Tile t = Vars.world.tile(tx, ty);
                if(t == null) continue;
                if(!isOccupied(excludePlans, tx, ty, processorBlock)
                    && !isInsideBB(tx, ty, processorBlock, bbMinX, bbMinY, bbMaxX, bbMaxY)
                    && Build.validPlace(processorBlock, Vars.player.team(), tx, ty, 0)){
                    return t;
                }
            }
        }

        return null;
    }

    private static boolean isInsideBB(int x, int y, Block proc, int bbMinX, int bbMinY, int bbMaxX, int bbMaxY){
        if(bbMinX > bbMaxX || bbMinY > bbMaxY) return false;
        int pw = proc.size;
        return !((x + pw <= bbMinX) || (x >= bbMaxX) || (y + pw <= bbMinY) || (y >= bbMaxY));
    }

    private static boolean isOccupied(Seq<BuildPlan> plans, int x, int y, Block proc){
        if(plans == null) return false;
        int pw = proc.size;
        for(BuildPlan p : plans){
            if(p.block == null) continue;
            if(x < p.x + p.block.size && x + pw > p.x &&
               y < p.y + p.block.size && y + pw > p.y) return true;
        }
        return false;
    }
}
