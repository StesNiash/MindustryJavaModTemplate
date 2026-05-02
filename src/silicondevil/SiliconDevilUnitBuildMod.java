package silicondevil;

import arc.*;
import arc.struct.*;
import arc.util.*;
import java.util.Arrays;
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
    /** Map of processor IDs to processor buildings that contain the target string. */
    private IntMap<LogicBuild> processorsWithString = new IntMap<>();
    /** Map of processor IDs to currently assigned build plans (list of up to MAX_BLOCKS_PER_PROCESSOR). */
    private IntMap<Seq<BuildPlan>> assignedPlans = new IntMap<>();
    /** Set of coordinates where config has already been applied. */
    private IntSet appliedConfigs = new IntSet();
    /** Map of processor IDs to last known config (to avoid unnecessary updates). */
    private IntMap<byte[]> lastConfigs = new IntMap<>();
    /** Map of processor IDs to last update time (for cooldown). */
    private IntMap<Long> lastUpdateTime = new IntMap<>();
    
    /** The target string to search for in processor code. */
    private static final String TARGET_STRING = "print \"SILICONDEVIL UNIT BUILD MOD\"";
    
    /** Interval between scans in seconds. */
    private static final float SCAN_INTERVAL = 5.0f;

    /** Maximum number of blocks to encode per processor. */
    private static final int MAX_BLOCKS_PER_PROCESSOR = 10;

    /** Prefix of processor code (must be present). */
    private static final String CODE_PREFIX = "print \"SILICONDEVIL UNIT BUILD MOD\"\n"
        + "sensor uDead myUnit @dead\n"
        + "jump 9 equal uDead 0\n"
        + "ubind @mega\n"
        + "sensor uCont @unit @controller\n"
        + "jump 8 equal uCont @this\n"
        + "jump 8 equal uCont processor1\n"
        + "end\n"
        + "set myUnit @unit\n"
        + "ubind myUnit\n"
        + "set @counter current\n"
        + "set current @counter";
    
    public SiliconDevilUnitBuildMod() {
        Log.info("Loaded SiliconDevil Unit Build Mod constructor.");
        
        // Listen for game load event to start scanning
        Events.on(ClientLoadEvent.class, e -> {
            Log.info("Starting periodic scan for processors with target string.");
            // Schedule first scan after 0 seconds, then recursively schedule
            scheduleScan();
        });
    }
    
    @Override
    public void loadContent() {
        Log.info("Loading SiliconDevil Unit Build Mod content.");
        // No custom content needed for now
    }
    
    /** Schedules a scan after SCAN_INTERVAL seconds. */
    private void scheduleScan() {
        Time.runTask(SCAN_INTERVAL, () -> {
            scanProcessors();
            assignBuildTasks();
            applyPendingConfigs();
            scheduleScan(); // reschedule
        });
    }
    
    /** Scans all processors in the world and updates the map. */
    private void scanProcessors() {
        if (Vars.world == null) return;
        
        // Clear previous results (or keep and update)
        IntMap<LogicBuild> newMap = new IntMap<>();
        
        // Iterate over all tiles (or use Groups.build if available)
        // Using world.tiles.eachTile as in MapProcessorsDialog
        Vars.world.tiles.eachTile(tile -> {
            if (!tile.isCenter()) return;
            Building building = tile.build;
            if (building instanceof LogicBuild) {
                LogicBuild processor = (LogicBuild) building;
                // Check if processor code contains the target string
                if (processor.code != null && processor.code.contains(TARGET_STRING)) {
                    newMap.put(processor.id, processor);
                    // Store current config to avoid unnecessary updates
                    lastConfigs.put(processor.id, processor.config());
                }
            }
        });
        
        // Replace old map with new results
        processorsWithString = newMap;
        
        // Optional logging
        if (newMap.size > 0) {
            Log.info("Found @ processors with target string.", newMap.size);
        }
    }
    
    /** Retrieves the player's current build queue. Returns empty seq if player is dead or not a builder. */
    private Seq<BuildPlan> getPlayerBuildQueue() {
        if (Vars.player == null || Vars.player.dead()) return new Seq<>();
        Unit playerUnit = Vars.player.unit();
        if (playerUnit == null || !playerUnit.canBuild()) return new Seq<>();
        // playerUnit.plans() returns a Queue<BuildPlan>, convert to Seq for convenience
        Seq<BuildPlan> queue = new Seq<>();
        for (BuildPlan plan : playerUnit.plans()) {
            if (!plan.breaking) queue.add(plan);
        }
        return queue;
    }
    
    /** Determines if a build plan is already completed (block built). */
    private boolean isPlanCompleted(BuildPlan plan) {
        if (plan == null) return true;
        Tile tile = Vars.world.tile(plan.x, plan.y);
        if (tile == null) return false;
        Building building = tile.build;
        // Consider completed if a building of the same type exists and is fully built (health >= maxHealth?)
        // For simplicity, assume if building exists and block matches, plan is done.
        return building != null && building.block == plan.block;
    }

    /** Applies config to a built block only once. */
    private void applyConfigOnce(BuildPlan plan) {
        if (plan.config == null) return;
        int key = plan.x * 10000 + plan.y; // simple hash
        if (appliedConfigs.contains(key)) return;
        Tile tile = Vars.world.tile(plan.x, plan.y);
        if (tile != null && tile.build != null && tile.build.block == plan.block) {
            Call.tileConfig(null, tile.build, plan.config);
            Log.info("Applied config for block at @,@", plan.x, plan.y);
            appliedConfigs.add(key);
        }
    }
    
    /** Assigns build tasks to idle processors based on player's queue. */
    private void assignBuildTasks() {
        Seq<BuildPlan> queue = getPlayerBuildQueue();
        IntMap<Seq<BuildPlan>> newAssignments = new IntMap<>();
        
        // Track which plans are already assigned to processors (to avoid double assignment)
        Seq<BuildPlan> unassignedPlans = new Seq<>(queue);
        
        // First, check existing assignments: keep if still valid (plan still in queue and not completed)
        for (IntMap.Entry<LogicBuild> entry : processorsWithString) {
            int pid = entry.key;
            LogicBuild processor = entry.value;
            Seq<BuildPlan> oldPlans = assignedPlans.get(pid);
            Seq<BuildPlan> keptPlans = new Seq<>();
            int completedCount = 0;
            
            if (oldPlans != null) {
                for (BuildPlan oldPlan : oldPlans) {
                    if (oldPlan == null) continue;
                    
                    if (isPlanCompleted(oldPlan)) {
                        // Plan completed: keep in batch but mark as completed
                        keptPlans.add(oldPlan);
                        completedCount++;
                        // Apply config once (if any)
                        applyConfigOnce(oldPlan);
                    } else if (queue.contains(oldPlan)) {
                        // Plan still pending and in queue
                        keptPlans.add(oldPlan);
                        unassignedPlans.remove(oldPlan);
                    } else {
                        // Plan not in queue and not completed (cancelled or removed) – discard
                    }
                }
            }
            
            // If all old plans are completed, clear the batch and allow new assignments
            if (oldPlans != null && completedCount == oldPlans.size) {
                keptPlans.clear();
                // Processor becomes idle, we will assign new plans below
            }
            
            // Fill remaining slots with new plans from unassigned queue
            int remainingSlots = MAX_BLOCKS_PER_PROCESSOR - keptPlans.size;
            for (int i = 0; i < remainingSlots && unassignedPlans.size > 0; i++) {
                BuildPlan newPlan = unassignedPlans.pop();
                keptPlans.add(newPlan);
            }
            
            // If no plans kept and no new plans, assign empty list (idle)
            if (keptPlans.size == 0) {
                newAssignments.put(pid, new Seq<>());
                // Update processor code to idle (empty list)
                updateProcessorCodeMultiple(processor, new Seq<>());
            } else {
                newAssignments.put(pid, keptPlans);
                // Update processor code with keptPlans (only if batch changed)
                updateProcessorCodeMultiple(processor, keptPlans);
            }
        }
        
        // Update assignments map
        assignedPlans = newAssignments;
    }
    
    /** Applies saved configs for blocks that have been built. */
    private void applyPendingConfigs() {
        for (IntMap.Entry<Seq<BuildPlan>> entry : assignedPlans) {
            Seq<BuildPlan> plans = entry.value;
            if (plans == null) continue;
            for (BuildPlan plan : plans) {
                if (plan == null) continue;
                applyConfigOnce(plan);
            }
        }
    }
    
    /** Updates the processor's code to reflect the assigned build plan (or idle). */
    private void updateProcessorCode(LogicBuild processor, BuildPlan plan) {
        Seq<BuildPlan> plans = new Seq<>();
        if (plan != null) plans.add(plan);
        updateProcessorCodeMultiple(processor, plans);
    }

    /** Updates the processor's code with multiple build plans (up to MAX_BLOCKS_PER_PROCESSOR). */
    private void updateProcessorCodeMultiple(LogicBuild processor, Seq<BuildPlan> plans) {
        if (processor.code == null) return;

        // Cooldown check: skip if updated too recently
        long now = Time.millis();
        long last = lastUpdateTime.get(processor.id, 0L);
        if (now - last < 250) {
            Log.debug("Skipping update for processor @ due to cooldown.", processor.id);
            return;
        }

        // Generate new code
        String newCode = generateProcessorCode(plans);
        // Compare with existing code to avoid unnecessary updates
        if (newCode.equals(processor.code)) {
            Log.debug("Processor @ code unchanged.", processor.id);
            return;
        }

        byte[] oldConfig = processor.config();
        // Update processor code (this will trigger recompilation)
        processor.code = newCode;
        processor.updateCode(newCode);
        // Sync config to server only if config changed
        byte[] newConfig = processor.config();
        if (!Arrays.equals(oldConfig, newConfig)) {
            Call.tileConfig(null, processor, newConfig);
        }
        // Store latest config and update time
        lastConfigs.put(processor.id, newConfig);
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
            // fallback: try to convert to string, maybe it's a content
            return config.toString();
        } else {
            return "0";
        }
    }
    
    /** Generates full processor code for the given list of build plans (up to MAX_BLOCKS_PER_PROCESSOR). */
    private String generateProcessorCode(Seq<BuildPlan> plans) {
        StringBuilder sb = new StringBuilder();
        sb.append(CODE_PREFIX).append("\n");
        int count = Math.min(plans.size, MAX_BLOCKS_PER_PROCESSOR);
        for (int i = 0; i < count; i++) {
            BuildPlan plan = plans.get(i);
            String blockConst = "@" + plan.block.name;
            String configStr = configToString(plan.config);
            int rotation = plan.rotation;
            // Block section
            sb.append("\nBlock").append(i + 1).append(":\n");
            sb.append("    ucontrol move ").append(plan.x).append(" ").append(plan.y).append(" 0 0 0\n");
            sb.append("    ucontrol build ").append(plan.x).append(" ").append(plan.y).append(" ").append(blockConst).append(" ").append(rotation).append(" ").append(configStr).append("\n");
            sb.append("    ucontrol getBlock ").append(plan.x).append(" ").append(plan.y).append(" bt 0 0\n");
            sb.append("    jump End").append(i + 1).append(" equal bt ").append(blockConst).append("\n");
            sb.append("    end\n");
            sb.append("End").append(i + 1).append(":\n");
            sb.append("    set current @counter\n");
        }
        // If there are fewer plans than max, we can optionally add idle blocks (air) but not required.
        // The processor will just loop.
        return sb.toString();
    }
    
    /** Returns the current map of processor IDs to LogicBuild instances. */
    public IntMap<LogicBuild> getProcessorsWithString() {
        return processorsWithString;
    }
    
    /** Returns the current map of processor IDs to assigned build plans. */
    public IntMap<Seq<BuildPlan>> getAssignedPlans() {
        return assignedPlans;
    }
}