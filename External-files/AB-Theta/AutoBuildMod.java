package autobuild;

import arc.*;
import arc.input.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.*;
import mindustry.content.*;

public class AutoBuildMod extends Mod{
    private static final String SETTING_KEY = "autobuild-key";
    private static final String MODIFIER_KEY = "autobuild-modifier";
    private long lastKeyTime = 0;
    private boolean buildHandlerRegistered = false;

    public AutoBuildMod(){
        Events.on(ClientLoadEvent.class, e -> {
            initSettings();
            registerKeybind();
            registerBuildHandler();
        });
    }

    private void initSettings(){
        Core.settings.defaults(SETTING_KEY, "b");
        Core.settings.defaults(MODIFIER_KEY, "ctrl");
        Core.settings.defaults("autobuild-micro-threshold", 40);

        Vars.ui.settings.addCategory("AutoBuild", t -> {
            t.textPref(SETTING_KEY, "b", newKey -> {});
            t.textPref(MODIFIER_KEY, "ctrl", newMod -> {});
            t.sliderPref("autobuild-micro-threshold", 40, 0, 200, 1, v -> Integer.toString(v));
        });
    }

    private void registerKeybind(){
        Events.run(Trigger.update, () -> {
            if(!Vars.state.isGame() || Vars.player == null) return;

            String keyName = Core.settings.getString(SETTING_KEY, "b");
            KeyCode keyCode;
            try{
                keyCode = KeyCode.valueOf(keyName.toLowerCase());
            }catch(Exception e){
                Log.err("AutoBuildMod: invalid key setting: " + keyName, e);
                return;
            }

            String modName = Core.settings.getString(MODIFIER_KEY, "ctrl");

            boolean trigger;
            if(modName.equals("none") || modName.equalsIgnoreCase(keyName)){
                trigger = Core.input.keyDown(keyCode);
            }else{
                boolean modDown;
                switch(modName){
                    case "ctrl":  modDown = Core.input.ctrl();  break;
                    case "alt":   modDown = Core.input.alt();   break;
                    case "shift": modDown = Core.input.shift(); break;
                    default:
                        try{
                            modDown = Core.input.keyDown(KeyCode.valueOf(modName.toLowerCase()));
                        }catch(Exception e){
                            Log.err("AutoBuildMod: invalid modifier setting: " + modName, e);
                            return;
                        }
                }
                trigger = modDown && Core.input.keyDown(keyCode);
            }

            long now = Time.millis();
            if(trigger && now - lastKeyTime >= 250){
                lastKeyTime = now;
                onBuild();
            }
        });
    }

    private void registerBuildHandler(){
        if(buildHandlerRegistered) return;
        buildHandlerRegistered = true;

        ConfigHandler.init();

        Events.on(BlockBuildEndEvent.class, event -> {
            if(event.breaking) return;
            Block b = event.tile.block();
            if(b != Blocks.logicProcessor && b != Blocks.microProcessor) return;
            if(!(event.tile.build instanceof LogicBlock.LogicBuild proc)) return;

            String key = "autobuild-pending-" + event.tile.x + "-" + event.tile.y;
            String code = Core.settings.getString(key, "");
            if(code.isEmpty()) return;

            byte[] compressed = LogicBlock.compress(code, new Seq<>());
            proc.configure(compressed);
            Core.settings.put(key, "");

        });
    }

    private void onBuild(){
        if(Vars.player == null || Vars.player.unit() == null) return;

        int threshold = Core.settings.getInt("autobuild-micro-threshold", 40);

        // only use plans from cursor, NOT from unit queue
        Seq<BuildPlan> cursorPlans = new Seq<>();
        if(Vars.control.input != null && Vars.control.input.selectPlans != null){
            cursorPlans.addAll(Vars.control.input.selectPlans);
        }

        if(cursorPlans.isEmpty()){
            Vars.ui.hudfrag.showToast(Core.bundle.get("autobuild.noplans"));
            return;
        }

        // compute bounding box of ALL cursor plans (processor must be OUTSIDE the schematic)
        int bbMinX = Integer.MAX_VALUE, bbMinY = Integer.MAX_VALUE;
        int bbMaxX = Integer.MIN_VALUE, bbMaxY = Integer.MIN_VALUE;
        for(BuildPlan p : cursorPlans){
            if(p.block == null) continue;
            int ex = p.x + p.block.size;
            int ey = p.y + p.block.size;
            if(p.x < bbMinX) bbMinX = p.x;
            if(p.y < bbMinY) bbMinY = p.y;
            if(ex > bbMaxX) bbMaxX = ex;
            if(ey > bbMaxY) bbMaxY = ey;
        }

        // store configs for blocks that need player-side config (bridges, processors)
        for(BuildPlan p : cursorPlans){
            ConfigHandler.remove(p.x, p.y);
            if(ConfigHandler.needsPlayerConfig(p)){
                ConfigHandler.store(p.x, p.y, p.config);
            }
        }

        // filter out already-built blocks
        Seq<BuildPlan> toBuild = new Seq<>();
        for(BuildPlan p : cursorPlans){
            Tile t = Vars.world.tile(p.x, p.y);
            if(t != null && t.build != null && !(t.build instanceof ConstructBlock.ConstructBuild)){
                continue;
            }
            toBuild.add(p);
        }

        if(toBuild.size > MlogBuilder.maxBlocks()){
            Vars.ui.hudfrag.showToast(Core.bundle.format("autobuild.toobig", MlogBuilder.maxBlocks()));
            return;
        }

        for(BuildPlan p : toBuild){
            Log.err("toBuild: block=@ x=@ y=@ size=@", p.block, p.x, p.y, p.block.size);
        }

        Block procBlock = toBuild.size > threshold ? Blocks.logicProcessor : Blocks.microProcessor;

        String code = MlogBuilder.generate(toBuild);
        if(code.isEmpty()){
            Vars.ui.hudfrag.showToast(Core.bundle.get("autobuild.noplans"));
            return;
        }

        // collect all unit plans to exclude those positions
        Seq<BuildPlan> excludePlans = new Seq<>();
        if(Vars.player.unit().plans != null){
            for(BuildPlan p : Vars.player.unit().plans){
                Log.err("unitPlan: block=@ x=@ y=@ size=@", p.block, p.x, p.y, p.block != null ? p.block.size : 0);
                excludePlans.add(p);
            }
        }
        excludePlans.addAll(toBuild);
        Tile tile = ProcessorManager.findPlaceTile(excludePlans, procBlock, bbMinX, bbMinY, bbMaxX, bbMaxY);
        Log.err("findPlaceTile result: tile=@", tile);
        if(tile == null){
            Vars.ui.showErrorMessage(Core.bundle.get("autobuild.nospace"));
            return;
        }

        // save code by tile so BlockBuildEndEvent can find it
        String key = "autobuild-pending-" + tile.x + "-" + tile.y;
        Core.settings.put(key, code);

        // add processor plan to front of unit queue
        BuildPlan plan = new BuildPlan(tile.x, tile.y, 0, procBlock);
        if(Vars.player.unit().plans == null){
            Vars.player.unit().plans = new Queue<>();
        }
        Vars.player.unit().plans.addFirst(plan);
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.register("autobuild", "Generate processor code from current build plans.", (args, player) -> {
            onBuild();
        });
    }
}
