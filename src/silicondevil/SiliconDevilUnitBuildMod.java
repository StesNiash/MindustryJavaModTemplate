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
    private ObjectMap<Long, Integer> configFailures = new ObjectMap<>();
    private ObjectMap<Long, Long> lastConfigAttempt = new ObjectMap<>();

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
                configFailures.remove(pos);
                lastConfigAttempt.remove(pos);
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
                boolean isProcessor = plan.block instanceof mindustry.world.blocks.logic.LogicBlock;

                // Processor: non-null config means code was written
                if (isProcessor && currentConfig != null) {
                    configFailures.remove(pos);
                    lastConfigAttempt.remove(pos);
                    continue;
                }

                // Other blocks: exact match with target
                if (configsEqual(currentConfig, plan.config)) {
                    configFailures.remove(pos);
                    lastConfigAttempt.remove(pos);
                    continue;
                }

                long now = Time.millis();
                int failures = configFailures.get(pos, 0);
                long lastAttempt = lastConfigAttempt.get(pos, 0L);

                long backoff = failures == 0 ? 0
                    : Math.min(1000L * (1L << Math.min(failures - 1, 5)), 30000L);

                if (now - lastAttempt < backoff) {
                    remaining.add(plan);
                    continue;
                }

                if (!configApplied) {
                    Log.info("Call.tileConfig at (@, @) fail @", plan.x, plan.y, failures);
                    Call.tileConfig(Vars.player, tile.build, plan.config);
                    lastConfigAttempt.put(pos, now);
                    configFailures.put(pos, failures + 1);
                    configApplied = true;
                }
                remaining.add(plan);
            } else if (buildQueue.contains(plan)) {
                configFailures.remove(packCoord(plan.x, plan.y));
                lastConfigAttempt.remove(packCoord(plan.x, plan.y));
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
        int totalBlocks = Math.min(plans.size, ModConfig.maxBlocksPerProcessor());
        if (totalBlocks == 0) return ModConfig.targetString() + "\nend\n";

        int mapH = Vars.world.height();
        int branchCount = Math.min(Math.max(totalBlocks / 15, 2), 8);

        Seq<BuildPlan> batch = new Seq<>();
        for (int i = 0; i < totalBlocks; i++) batch.add(plans.get(i));
        Seq<BuildTreeBuilder.Branch> branches = BuildTreeBuilder.build(batch, branchCount);
        branchCount = branches.size;

        // Address layout:
        // setup: 1(jump) + 3(redist vars) + 2N(pointers) + 1(setup flag) + 1(target) = 6+2N
        // bind..jumpB0: 4 lines
        // decoder: 12 lines
        // redist: 9 + 3(flag-clear) = 12 lines
        // dispatch: 1(op add) + 2N
        // blocks: 5 per block
        // calc_flag: 4

        int addrBind = 6 + 2 * branchCount;
        int addrDecode = addrBind + 4;
        int addrRedist = addrDecode + 12;
        int addrDistCheck = addrRedist + 7;      // jump dispatch equal dist_finish null
        int addrEnd = addrRedist + 12;            // end after flag-clear
        int addrDispatch = addrRedist + 13;       // dispatch starts
        int addrDispGp = addrDispatch + 1;
        int addrDispBp = addrDispGp + branchCount;
        int addrBlocks = addrDispBp + branchCount;
        int addrCalcFlag = addrBlocks + totalBlocks * 5;

        int howMuch = Math.max(totalBlocks / (branchCount * 2), 2);

        // Block address table
        int[] blockAddr = new int[totalBlocks];
        int idx = 0;
        for (int b = 0; b < branchCount; b++) {
            for (int j = 0; j < branches.get(b).blocks.size; j++) {
                blockAddr[idx++] = addrBlocks + idx * 5;
            }
        }

        StringBuilder sb = new StringBuilder();

        // ── Setup (auto-balancing) ──
        sb.append("jump ").append(addrBind).append(" equal setup 1\n");
        sb.append("set TakeFromGroup 0\n");
        sb.append("set GiveToGroup 1\n");
        sb.append("set HowMuch ").append(howMuch).append("\n");
        for (int i = 0; i < branchCount; i++) {
            sb.append("set group_ptr").append(i).append(" ").append(addrDispBp + i).append("\n");
        }
        for (int i = 0; i < branchCount; i++) {
            int firstBlockAddr = addrBlocks;
            for (int k = 0; k < i; k++) firstBlockAddr += branches.get(k).blocks.size * 5;
            sb.append("set block_ptr").append(i).append(" ").append(firstBlockAddr).append("\n");
        }
        sb.append("set setup 1\n");
        sb.append("print \"UB_2 Made by SiliconDevil\"\n");

        // ── Bind + decode ──
        sb.append("ubind @mega\n");
        sb.append("sensor uFlag @unit @flag\n");
        sb.append("op idiv bFlag uFlag 100\n");
        sb.append("jump ").append(addrRedist).append(" equal bFlag 0\n");

        // ── Decoder (12 lines) ──
        sb.append("op idiv bx bFlag @maph\n");
        sb.append("op mod by bFlag @maph\n");
        sb.append("ucontrol move bx by 0 0 0\n");
        sb.append("ucontrol within bx by 5 bNear 0\n");
        sb.append("sensor uBuilding @unit @building\n");
        sb.append("op notEqual notBuilding uBuilding 1\n");
        sb.append("op land crashed notBuilding bNear\n");
        sb.append("jump ").append(addrRedist).append(" equal crashed 1\n");
        sb.append("ucontrol getBlock bx by bt bb 0\n");
        sb.append("jump ").append(addrBind).append(" equal bb null\n");
        sb.append("sensor bbmh bb @maxHealth\n");
        sb.append("jump ").append(addrBind).append(" equal bbmh 10\n");

        // ── Redistribution + flag-clear ──
        sb.append("op mod group_id uFlag 100\n");
        sb.append("jump ").append(addrDistCheck).append(" notEqual group_id TakeFromGroup\n");
        sb.append("jump ").append(addrDistCheck).append(" lessThanEq HowMuch 0\n");
        sb.append("op mul bFlag2 bFlag 100\n");
        sb.append("op add nFlag bFlag2 GiveToGroup\n");
        sb.append("ucontrol flag nFlag 0 0 0 0\n");
        sb.append("op sub HowMuch HowMuch 1\n");
        sb.append("jump ").append(addrDispatch).append(" equal dist_finish null\n");
        // flag-clear: dist_finish == 1, no more blocks
        sb.append("sensor curFlag @unit @flag\n");
        sb.append("op mod gFlag curFlag 100\n");
        sb.append("jump ").append(addrEnd).append(" equal curFlag gFlag\n");
        sb.append("ucontrol flag gFlag 0 0 0 0\n");
        sb.append("end\n");

        // ── Dispatch ──
        sb.append("op add @counter @counter group_id\n");
        for (int i = 0; i < branchCount; i++) {
            sb.append("set @counter group_ptr").append(i).append("\n");
        }
        for (int i = 0; i < branchCount; i++) {
            sb.append("set @counter block_ptr").append(i).append("\n");
        }

        // ── Block sections ──
        int bi = 0;
        for (int b = 0; b < branchCount; b++) {
            BuildTreeBuilder.Branch branch = branches.get(b);
            int blkInBranch = branch.blocks.size;
            for (int j = 0; j < blkInBranch; j++) {
                BuildPlan plan = branch.blocks.get(j);
                String blockConst = "@" + plan.block.name;
                String configStr = configToString(plan.config);
                if (configStr.length() > 20) configStr = configStr.substring(0, 20);
                int rotation = plan.rotation;
                int rawPos = plan.x * mapH + plan.y;

                sb.append("ucontrol build ").append(plan.x).append(" ").append(plan.y).append(" ").append(blockConst).append(" ").append(rotation).append(" ").append(configStr).append("\n");
                sb.append("ucontrol move ").append(plan.x).append(" ").append(plan.y).append(" 0 0 0\n");

                boolean isLastBlock = (b == branchCount - 1) && (j == blkInBranch - 1);
                boolean isBranchEnd = (j == blkInBranch - 1);

                if (isLastBlock) {
                    sb.append("set bFlag ").append(rawPos * 100).append("\n");
                    sb.append("set dist_finish 1\n");
                } else {
                    if (isBranchEnd) {
                        // Merge: bFlag=0 → dispatch resolves next block via block_ptr[target]
                        sb.append("set bFlag 0\n");
                    } else {
                        int nextB = b, nextJ = j + 1;
                        BuildPlan nextPlan = branches.get(nextB).blocks.get(nextJ);
                        int rawNext = nextPlan.x * mapH + nextPlan.y;
                        sb.append("set bFlag ").append(rawNext * 100).append("\n");
                    }

                    if (isBranchEnd) {
                        int mergeTarget = branch.mergeToIdx;
                        if (mergeTarget >= 0) {
                            sb.append("set group_ptr").append(b).append(" ").append(addrDispBp + mergeTarget).append("\n");
                        }
                    } else {
                        int nextBlockAddr = blockAddr[bi + 1];
                        sb.append("set block_ptr").append(b).append(" ").append(nextBlockAddr).append("\n");
                    }
                    sb.append("set @counter ").append(addrCalcFlag).append("\n");
                }
                bi++;
            }
        }

        // ── calc_flag ──
        sb.append("sensor uFlag @unit @flag\n");
        sb.append("op mod gFlag uFlag 100\n");
        sb.append("op add newFlag gFlag bFlag\n");
        sb.append("ucontrol flag newFlag 0 0 0 0\n");

        return sb.toString();
    }

    public IntMap<LogicBuild> getProcessorsWithString() {
        return processorsWithString;
    }

    public IntMap<Seq<BuildPlan>> getAssignedPlans() {
        return assignedPlans;
    }
}
