package autobuild;

import arc.struct.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.type.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.logic.*;

public class MlogBuilder{
    private static final int HEADER = 6;
    private static final int BLOCK_INSTRS = 6;
    private static final int TAIL = 2;

    public static int maxBlocks(){
        return (LExecutor.maxInstructions - HEADER - TAIL) / BLOCK_INSTRS;
    }

    public static String generate(Seq<BuildPlan> plans){
        if(plans == null || plans.isEmpty()) return "";

        Seq<BuildPlan> valid = new Seq<>();
        for(BuildPlan p : plans){
            if(p.block != null && !p.breaking){
                valid.add(p);
            }
        }
        if(valid.isEmpty()) return "";

        int N = valid.size;
        int maxLines = LExecutor.maxInstructions;
        int maxBlocks = (maxLines - HEADER - TAIL) / BLOCK_INSTRS;
        if(N > maxBlocks) N = maxBlocks;
        if(N == 0) return "";

        int firstBlock = HEADER;
        int unbindLine = HEADER + N * BLOCK_INSTRS;

        StringBuilder sb = new StringBuilder();

        // header
        sb.append("jump 3 notEqual unit null\n");
        sb.append("ubind @poly\n");
        sb.append("set unit @unit\n");
        sb.append("sensor ct @unit @controller\n");
        sb.append("jump ").append(firstBlock).append(" equal ct unit\n");
        sb.append("jump 1 notEqual ct @this\n");

        // blocks
        for(int idx = 0; idx < N; idx++){
            BuildPlan p = valid.get(idx);

            int startLine = firstBlock + idx * BLOCK_INSTRS;
            int target = idx < N - 1 ? firstBlock + (idx + 1) * BLOCK_INSTRS : unbindLine;

            sb.append("jump ").append(target).append(" equal b").append(idx).append("_type @").append(p.block.name).append("\n");
            sb.append("ucontrol move ").append(worldX(p)).append(" ").append(worldY(p)).append(" 0 0 0\n");
            sb.append("ucontrol build ").append(worldX(p)).append(" ").append(worldY(p)).append(" @").append(p.block.name).append(" ").append(p.rotation).append(" ").append(configStr(p.config)).append("\n");
            sb.append("ucontrol getBlock ").append(worldX(p)).append(" ").append(worldY(p)).append(" b").append(idx).append("_type b").append(idx).append("_building 0\n");
            sb.append("jump 0 notEqual @unit this\n");
            sb.append("jump ").append(startLine).append(" always x false\n");
        }

        // tail
        sb.append("ucontrol unbind 0 0 0 0 0\n");
        sb.append("end\n");

        return sb.toString();
    }

    private static int worldX(BuildPlan p){
        return p.x;
    }

    private static int worldY(BuildPlan p){
        return p.y;
    }

    private static String configStr(Object config){
        if(config == null) return "null";
        if(config instanceof Item) return "@" + ((Item)config).name;
        if(config instanceof Block) return "@" + ((Block)config).name;
        if(config instanceof UnitType) return "@" + ((UnitType)config).name;
        if(config instanceof Team) return "null";
        if(config instanceof Integer || config instanceof Byte || config instanceof Short) return config.toString();
        return "null";
    }
}
