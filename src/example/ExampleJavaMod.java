package example;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;

public class ExampleJavaMod extends Mod {

    public ExampleJavaMod() {
        Events.run(Trigger.update, () -> {
            if (Vars.state != null && Vars.state.isGame()) {
                for (Teams.TeamData data : Vars.state.teams.present) {
                    Queue<Teams.BlockPlan> plans = data.plans;
                    int size = plans.size;
                    for (int i = 0; i < size; i++) {
                        Teams.BlockPlan plan = plans.get(i);
                        if (!plan.removed && plan.block == Blocks.coreShard) {
                            plan.removed = true;
                            plans.addLast(new Teams.BlockPlan(plan.x, plan.y, plan.rotation, Blocks.vault, plan.config));
                        }
                    }
                }
            }
        });

        Events.on(BlockBuildBeginEvent.class, e -> {
            if (!e.breaking) {
                Building build = e.tile.build;
                if (build != null && build.block == Blocks.coreShard) {
                    build.block = Blocks.vault;
                }
            }
        });
    }

    @Override
    public void loadContent() {
    }
}
