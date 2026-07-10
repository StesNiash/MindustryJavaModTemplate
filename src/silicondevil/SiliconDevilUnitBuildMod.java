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
import mindustry.world.blocks.power.PowerNode;
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
                if (plan.block instanceof PowerNode) continue;
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
            if (plan.block instanceof PowerNode) continue;
            if (!isQueued(plan.x, plan.y)) {
                long pos = packCoord(plan.x, plan.y);
                configAttempts.remove(pos);
                initialConfigs.remove(pos);
                configQueue.insert(0, plan);
            }
        }
    }

    private static boolean configsEqual(Object a, Object b) {
        if (a instanceof byte[] && b instanceof byte[]) {
            return Arrays.equals((byte[])a, (byte[])b);
        }
        return Objects.equals(a, b);
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

                if (configsEqual(currentConfig, plan.config)) {
                    configAttempts.remove(pos);
                    initialConfigs.remove(pos);
                    continue;
                }

                int attempts = configAttempts.get(pos, 0);

                if (attempts == 0) {
                    if (!configApplied) {
                        initialConfigs.put(pos, currentConfig);
                        Log.info("Call.tileConfig at (@, @)", plan.x, plan.y);
                        Call.tileConfig(Vars.player, tile.build, plan.config);
                        configApplied = true;
                        configAttempts.put(pos, 1);
                    }
                    remaining.add(plan);
                } else {
                    if (!configsEqual(initialConfigs.get(pos), currentConfig)) {
                        configAttempts.remove(pos);
                        initialConfigs.remove(pos);
                        continue;
                    }

                    if (attempts >= 3) {
                        configAttempts.remove(pos);
                        initialConfigs.remove(pos);
                        continue;
                    }

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

    private static boolean batchEquals(Seq<BuildPlan> a, Seq<BuildPlan> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size != b.size) return false;
        for (int i = 0; i < a.size; i++) {
            BuildPlan pa = a.get(i);
            BuildPlan pb = b.get(i);
            if (pa.x != pb.x || pa.y != pb.y || pa.block != pb.block) return false;
        }
        return true;
    }

    private void assignBuildTasks() {
        Seq<BuildPlan> queue = getPlayerBuildQueue();
        IntMap<Seq<BuildPlan>> newAssignments = new IntMap<>();
        Seq<BuildPlan> unassignedPlans = new Seq<>(queue);

        for (IntMap.Entry<LogicBuild> entry : processorsWithString) {
            int pid = entry.key;
            LogicBuild processor = entry.value;
            Seq<BuildPlan> oldBatch = assignedPlans.get(pid);

            int slots = Math.min(ModConfig.maxBlocksPerProcessor(), unassignedPlans.size);
            if (slots > 0) {
                Seq<BuildPlan> newBatch = new Seq<>();
                for (int i = 0; i < slots; i++) {
                    newBatch.add(unassignedPlans.pop());
                }
                newAssignments.put(pid, newBatch);
                if (!batchEquals(oldBatch, newBatch)) {
                    addToConfigQueue(newBatch);
                    updateProcessorCodeMultiple(processor, newBatch);
                }
            } else {
                newAssignments.put(pid, oldBatch != null ? oldBatch : new Seq<>());
            }
        }

        assignedPlans = newAssignments;
    }

    private void updateProcessorCodeMultiple(LogicBuild processor, Seq<BuildPlan> plans) {
        long now = Time.millis();
        long last = lastUpdateTime.get(processor.id, 0L);
        if (now - last < ModConfig.processorUpdateCooldownMs()) return;

        String newCode = generateProcessorCode(plans);
        if (processor.code != null && newCode.equals(processor.code)) return;

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
        int count = Math.min(plans.size, ModConfig.maxBlocksPerProcessor());
        int mapH = Vars.world.height();

        StringBuilder sb = new StringBuilder();

        if (count == 0) {
            // Preamble (lines 0-4)
            sb.append("print \"UB_2 Made by SiliconDevil\"\n");
            sb.append("ubind @mega\n");
            sb.append("sensor uFlag @unit @flag\n");
            sb.append("jump 5 notEqual uFlag 0\n");
            sb.append("end\n");

            // Decoder (lines 5-18)
            sb.append("op idiv bx uFlag ").append(mapH).append("\n");
            sb.append("op mod by uFlag ").append(mapH).append("\n");
            sb.append("ucontrol move bx by 0 0 0\n");
            sb.append("ucontrol within bx by 5 bNear 0\n");
            sb.append("sensor uBuilding @unit @building\n");
            sb.append("op notEqual notBuilding uBuilding 1\n");
            sb.append("op land crashed notBuilding bNear\n");
            sb.append("jump 17 equal crashed 1\n");
            sb.append("ucontrol getBlock bx by bt bb 0\n");
            sb.append("jump 1 equal bb null\n");
            sb.append("sensor bbmh bb @maxHealth\n");
            sb.append("jump 1 equal bbmh 10\n");
            sb.append("ucontrol flag 0 0 0 0 0\n");
            sb.append("end\n");

            return sb.toString();
        }

        int PREAMBLE = 6;
        int DECODER = 14;
        int HEADER = 1;

        int decoderAddr = PREAMBLE;                    // 6
        int headerAddr = PREAMBLE + DECODER;           // 20
        int rebindAddr = 1;                             // ubind @mega
        int clearAddr = decoderAddr + 12;               // 18 — ucontrol flag

        // Preamble (lines 0-5)
        sb.append("print \"UB_2 Made by SiliconDevil\"\n");
        sb.append("ubind @mega\n");
        sb.append("sensor uFlag @unit @flag\n");
        sb.append("jump ").append(decoderAddr).append(" notEqual uFlag 0\n");
        sb.append("jump ").append(headerAddr).append(" equal dist_finish null\n");
        sb.append("end\n");

        // Decoder (lines 6-19)
        sb.append("op idiv bx uFlag ").append(mapH).append("\n");
        sb.append("op mod by uFlag ").append(mapH).append("\n");
        sb.append("ucontrol move bx by 0 0 0\n");
        sb.append("ucontrol within bx by 5 bNear 0\n");
        sb.append("sensor uBuilding @unit @building\n");
        sb.append("op notEqual notBuilding uBuilding 1\n");
        sb.append("op land crashed notBuilding bNear\n");
        sb.append("jump ").append(clearAddr).append(" equal crashed 1\n");
        sb.append("ucontrol getBlock bx by bt bb 0\n");
        sb.append("jump ").append(rebindAddr).append(" equal bb null\n");
        sb.append("sensor bbmh bb @maxHealth\n");
        sb.append("jump ").append(rebindAddr).append(" equal bbmh 10\n");
        sb.append("ucontrol flag 0 0 0 0 0\n");
        sb.append("end\n");

        // Block header (line 20)
        sb.append("set @counter next_block\n");

        // Block sections (5 lines each), start at line 21
        for (int i = 0; i < count; i++) {
            BuildPlan plan = plans.get(i);
            String blockConst = "@" + plan.block.name;
            String configStr = configToString(plan.config);
            int rotation = plan.rotation;
            int flag = plan.x * mapH + plan.y;

            sb.append("ucontrol flag ").append(flag).append(" 0 0 0 0\n");
            sb.append("ucontrol build ").append(plan.x).append(" ").append(plan.y).append(" ").append(blockConst).append(" ").append(rotation).append(" ").append(configStr).append("\n");
            sb.append("ucontrol move ").append(plan.x).append(" ").append(plan.y).append(" ").append(blockConst).append(" ").append(rotation).append(" ").append(configStr).append("\n");

            if (i < count - 1) {
                sb.append("op add next_block @counter 1\n");
            } else {
                sb.append("set dist_finish 1\n");
            }
            sb.append("end\n");
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
