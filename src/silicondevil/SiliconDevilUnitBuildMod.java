package silicondevil;

import arc.*;
import arc.struct.*;
import arc.util.*;
import java.util.Arrays;
import java.util.Objects;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.gen.Call;
import mindustry.mod.*;
import mindustry.world.*;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.entities.units.BuildPlan;

public class SiliconDevilUnitBuildMod extends Mod {
    private IntMap<LogicBuild> processorsWithString = new IntMap<>();
    private IntMap<Seq<BuildPlan>> assignedPlans = new IntMap<>();
    private IntMap<Long> lastUpdateTime = new IntMap<>();
    private Seq<BuildPlan> configQueue = new Seq<>();
    private ObjectMap<Long, Integer> configAttempts = new ObjectMap<>();
    private ObjectMap<Long, Object> initialConfigs = new ObjectMap<>();

    public SiliconDevilUnitBuildMod() {
        Log.info("Loaded SiliconDevil Unit Build Mod constructor.");

        Events.on(ClientLoadEvent.class, e -> {
            Log.info("Starting periodic scan for processors with target string.");
            ModConfig.registerSettings(Vars.ui.settings);
            scheduleProcessorScan();
            scheduleTaskAssignment();
            scheduleConfigCheck();
        });
    }

    @Override
    public void loadContent() {
        Log.info("Loading SiliconDevil Unit Build Mod content.");
    }

    private void scheduleProcessorScan() {
        Time.runTask(ModConfig.processorScanInterval(), () -> {
            scanProcessors();
            scheduleProcessorScan();
        });
    }

    private void scheduleTaskAssignment() {
        Time.runTask(ModConfig.scanInterval(), () -> {
            assignBuildTasks();
            enqueueBatchPlans();
            scheduleTaskAssignment();
        });
    }

    private void enqueueBatchPlans() {
        for (IntMap.Entry<Seq<BuildPlan>> entry : assignedPlans) {
            Seq<BuildPlan> plans = entry.value;
            if (plans == null) continue;
            for (BuildPlan plan : plans) {
                if (plan.config == null) continue;
                if (!isQueued(plan.x, plan.y)) {
                    long pos = packCoord(plan.x, plan.y);
                    configAttempts.remove(pos);
                    initialConfigs.remove(pos);
                    configQueue.insert(0, plan);
                }
            }
        }
    }

    private void scheduleConfigCheck() {
        Time.runTask(ModConfig.configCheckInterval(), () -> {
            processConfigQueue();
            scheduleConfigCheck();
        });
    }

    private void scanProcessors() {
        if (Vars.world == null) return;
        IntMap<LogicBuild> newMap = new IntMap<>();
        Vars.world.tiles.eachTile(tile -> {
            if (!tile.isCenter()) return;
            Building building = tile.build;
            if (building instanceof LogicBuild) {
                LogicBuild processor = (LogicBuild) building;
                if (processor.code != null && processor.code.contains(ModConfig.targetString())) {
                    newMap.put(processor.id, processor);
                }
            }
        });
        processorsWithString = newMap;
    }

    private Seq<BuildPlan> getPlayerBuildQueue() {
        if (Vars.player == null || Vars.player.dead()) return new Seq<>();
        Unit playerUnit = Vars.player.unit();
        if (playerUnit == null || !playerUnit.canBuild()) return new Seq<>();
        Seq<BuildPlan> queue = new Seq<>();
        for (BuildPlan plan : playerUnit.plans()) {
            if (!plan.breaking) queue.add(plan);
        }
        return queue;
    }

    private boolean isPlanCompleted(BuildPlan plan) {
        if (plan == null) return true;
        Tile tile = Vars.world.tile(plan.x, plan.y);
        if (tile == null) return false;
        Building building = tile.build;
        return building != null && building.block == plan.block;
    }

    private boolean isQueued(int x, int y) {
        for (BuildPlan p : configQueue) {
            if (p.x == x && p.y == y) return true;
        }
        return false;
    }

    private void addToConfigQueue(Seq<BuildPlan> plans) {
        for (BuildPlan plan : plans) {
            if (plan.config == null) continue;
            if (!isQueued(plan.x, plan.y)) {
                long pos = packCoord(plan.x, plan.y);
                configAttempts.remove(pos);
                initialConfigs.remove(pos);
                configQueue.insert(0, plan);
            }
        }
    }

    private void processConfigQueue() {
        if (configQueue.size == 0) return;

        Seq<BuildPlan> buildQueue = getPlayerBuildQueue();
        Seq<BuildPlan> remaining = new Seq<>();
        boolean configApplied = false;

        for (BuildPlan plan : configQueue) {
            Tile tile = Vars.world.tile(plan.x, plan.y);
            boolean blockInWorld = tile != null && tile.build != null && tile.build.block == plan.block;

            if (blockInWorld) {
                long pos = packCoord(plan.x, plan.y);
                Object currentConfig = tile.build.config();

                // Check 1: direct config match
                if (Objects.equals(currentConfig, plan.config)) {
                    configAttempts.remove(pos);
                    initialConfigs.remove(pos);
                    continue;
                }

                int attempts = configAttempts.get(pos, 0);

                if (attempts == 0) {
                    // first time: snapshot and apply only if slot available
                    if (!configApplied) {
                        initialConfigs.put(pos, currentConfig);
                        Log.info("Call.tileConfig at (@, @)", plan.x, plan.y);
                        Call.tileConfig(Vars.player, tile.build, plan.config);
                        configApplied = true;
                        configAttempts.put(pos, 1);
                    }
                    remaining.add(plan);
                } else {
                    // Check 2: config changed from initial (application had effect)
                    if (!Objects.equals(initialConfigs.get(pos), currentConfig)) {
                        configAttempts.remove(pos);
                        initialConfigs.remove(pos);
                        continue;
                    }

                    // Check 3: retry limit exceeded
                    if (attempts >= 3) {
                        configAttempts.remove(pos);
                        initialConfigs.remove(pos);
                        continue;
                    }

                    // retry: apply again and increment attempts
                    if (!configApplied) {
                        Log.info("Call.tileConfig retry at (@, @) attempt @", plan.x, plan.y, attempts + 1);
                        Call.tileConfig(Vars.player, tile.build, plan.config);
                        configApplied = true;
                    }
                    configAttempts.put(pos, attempts + 1);
                    remaining.add(plan);
                }
            } else if (buildQueue.contains(plan)) {
                long pos = packCoord(plan.x, plan.y);
                configAttempts.remove(pos);
                initialConfigs.remove(pos);
                remaining.add(plan);
            }
        }

        configQueue = remaining;
    }

    private static long packCoord(int x, int y) {
        return ((long)x << 32) | (y & 0xffffffffL);
    }

    private void assignBuildTasks() {
        Seq<BuildPlan> queue = getPlayerBuildQueue();
        IntMap<Seq<BuildPlan>> newAssignments = new IntMap<>();
        Seq<BuildPlan> unassignedPlans = new Seq<>(queue);

        for (IntMap.Entry<LogicBuild> entry : processorsWithString) {
            int pid = entry.key;
            LogicBuild processor = entry.value;
            Seq<BuildPlan> oldBatch = assignedPlans.get(pid);

            boolean batchResolved = true;
            if (oldBatch != null && oldBatch.size > 0) {
                for (BuildPlan plan : oldBatch) {
                    if (plan == null) continue;
                    if (!isPlanCompleted(plan) && queue.contains(plan)) {
                        batchResolved = false;
                        break;
                    }
                }
            }

            if (!batchResolved) {
                newAssignments.put(pid, oldBatch);
                if (oldBatch != null) {
                    for (BuildPlan plan : oldBatch) {
                        if (plan != null) unassignedPlans.remove(plan);
                    }
                }
                continue;
            }

            Seq<BuildPlan> newBatch = new Seq<>();
            int slots = Math.min(ModConfig.maxBlocksPerProcessor(), unassignedPlans.size);
            for (int i = 0; i < slots; i++) {
                newBatch.add(unassignedPlans.pop());
            }

            newAssignments.put(pid, newBatch);
            addToConfigQueue(newBatch);
            updateProcessorCodeMultiple(processor, newBatch);
        }

        assignedPlans = newAssignments;
    }

    private void updateProcessorCodeMultiple(LogicBuild processor, Seq<BuildPlan> plans) {
        if (processor.code == null) return;

        long now = Time.millis();
        long last = lastUpdateTime.get(processor.id, 0L);
        if (now - last < ModConfig.processorUpdateCooldownMs()) return;

        String newCode = generateProcessorCode(plans);
        if (newCode.equals(processor.code)) return;

        byte[] oldConfig = processor.config();
        processor.code = newCode;
        processor.updateCode(newCode);
        byte[] newConfig = processor.config();
        if (!Arrays.equals(oldConfig, newConfig)) {
            Call.tileConfig(null, processor, newConfig);
        }
        lastUpdateTime.put(processor.id, now);
        Log.info("Updated processor @ code.", processor.id);
    }

    private String configToString(Object config) {
        if (config instanceof Item) {
            return "@" + ((Item)config).name;
        } else if (config instanceof Liquid) {
            return "@" + ((Liquid)config).name;
        } else if (config instanceof Block) {
            return "@" + ((Block)config).name;
        } else if (config instanceof Integer) {
            return config.toString();
        } else if (config != null) {
            return config.toString();
        } else {
            return "0";
        }
    }

    private String generateProcessorCode(Seq<BuildPlan> plans) {
        StringBuilder sb = new StringBuilder();
        sb.append(ModConfig.codePrefix()).append("\n");
        int count = Math.min(plans.size, ModConfig.maxBlocksPerProcessor());
        for (int i = 0; i < count; i++) {
            BuildPlan plan = plans.get(i);
            String blockConst = "@" + plan.block.name;
            String configStr = configToString(plan.config);
            int rotation = plan.rotation;
            sb.append("\nBlock").append(i + 1).append(":\n");
            sb.append("    ucontrol move ").append(plan.x).append(" ").append(plan.y).append(" 0 0 0\n");
            sb.append("    ucontrol build ").append(plan.x).append(" ").append(plan.y).append(" ").append(blockConst).append(" ").append(rotation).append(" ").append(configStr).append("\n");
            sb.append("    ucontrol getBlock ").append(plan.x).append(" ").append(plan.y).append(" bt 0 0\n");
            sb.append("    jump End").append(i + 1).append(" equal bt ").append(blockConst).append("\n");
            sb.append("    end\n");
            sb.append("End").append(i + 1).append(":\n");
            sb.append("    set current @counter\n");
        }
        return sb.toString();
    }

    public IntMap<LogicBuild> getProcessorsWithString() {
        return processorsWithString;
    }

    public IntMap<Seq<BuildPlan>> getAssignedPlans() {
        return assignedPlans;
    }
}
