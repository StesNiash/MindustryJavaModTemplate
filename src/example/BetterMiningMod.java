package example;

import arc.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

public class BetterMiningMod extends Mod {
    private static final String PREFIX = "better-mining-";
    private static final String SETTING_ORES = PREFIX + "ores";
    private static final String SETTING_INVENTORY_MINE = PREFIX + "mine-full";
    private static final String KEYBIND_AUTO = PREFIX + "auto-mine";
    private static final String DEFAULT_ORES = "copper,lead";

    private boolean autoMineActive;
    private Seq<Item> targetOres = new Seq<>();
    private Interval scanTimer = new Interval();
    private Tile trackedMineTile;
    private final KeyBind autoMineKey;

    public BetterMiningMod() {
        Core.settings.defaults(
            SETTING_ORES, DEFAULT_ORES,
            SETTING_INVENTORY_MINE, true
        );

        autoMineKey = KeyBind.add(KEYBIND_AUTO, KeyCode.unset, "Better Mining");

        Events.on(ClientLoadEvent.class, e -> {
            Vars.ui.settings.addCategory("Better Mining", t -> {
                t.textPref(SETTING_ORES, DEFAULT_ORES, s -> rebuildOres());
                t.checkPref(SETTING_INVENTORY_MINE, true);
            });
            rebuildOres();
        });

        Events.on(UnitChangeEvent.class, e -> {
            if (e.player == player) {
                autoMineActive = false;
                trackedMineTile = null;
            }
        });

        Events.run(Trigger.update, this::update);
    }

    private void rebuildOres() {
        targetOres.clear();
        String raw = Core.settings.getString(SETTING_ORES, DEFAULT_ORES);
        for (String name : raw.split(",")) {
            name = name.trim().toLowerCase();
            if (name.isEmpty()) continue;
            Item item = Vars.content.item(name);
            if (item != null) targetOres.add(item);
        }
    }

    private void update() {
        if (player == null || player.dead()) return;
        Unit unit = player.unit();
        if (unit == null || !unit.canMine() || !unit.isLocal()) return;

        if (unit.mining()) {
            trackedMineTile = unit.mineTile();
        } else if (trackedMineTile != null && !unit.validMine(trackedMineTile)) {
            trackedMineTile = null;
        }

        if (Core.settings.getBool(SETTING_INVENTORY_MINE, true)) {
            ensureMiningContinuation(unit);
        }

        handleAutoMineToggle(unit);
    }

    private void ensureMiningContinuation(Unit unit) {
        Tile tile = unit.mining() ? unit.mineTile() : trackedMineTile;
        if (tile == null) return;

        Item mined = unit.getMineResult(tile);
        if (mined == null) return;

        if (!unit.acceptsItem(mined)) {
            Building core = unit.closestCore();
            if (core != null && unit.within(core.x(), core.y(), mineTransferRange)) {
                int accepted = core.acceptStack(unit.item(), unit.stack().amount, unit);
                if (accepted > 0) {
                    Call.transferItemTo(unit, unit.item(), accepted,
                        tile.worldx() + Mathf.range(tilesize / 2f),
                        tile.worldy() + Mathf.range(tilesize / 2f), core);
                }
            }
            unit.clearItem();

            if (!unit.mining() && unit.validMine(tile) && unit.acceptsItem(mined)) {
                unit.mineTile(tile);
                trackedMineTile = tile;
            }
        }
    }

    private void handleAutoMineToggle(Unit unit) {
        if (Core.input.keyTap(autoMineKey)) {
            autoMineActive = !autoMineActive;
            if (!autoMineActive) {
                unit.mineTile(null);
                trackedMineTile = null;
            }
        }
        if (!autoMineActive) return;

        if (targetOres.isEmpty()) rebuildOres();
        if (targetOres.isEmpty()) return;

        if (scanTimer.get(30)) {
            scanAndSetTarget(unit);
        }
    }

    private void scanAndSetTarget(Unit unit) {
        int tx = world.toTile(unit.x);
        int ty = world.toTile(unit.y);
        int range = (int)(unit.type().mineRange / tilesize) + 2;

        Building core = unit.closestCore();
        Item bestOre = null;
        int bestCount = Integer.MAX_VALUE;
        Tile bestTile = null;
        float bestDist = Float.MAX_VALUE;

        for (Item ore : targetOres) {
            if (!unit.canMine(ore)) continue;

            Tile found = findClosestOre(unit, tx, ty, range, ore);
            if (found == null) continue;

            int have = (core != null && core.items != null) ? core.items.get(ore) : Integer.MAX_VALUE;

            if (have < bestCount || (have == bestCount && unit.dst2(found.worldx(), found.worldy()) < bestDist)) {
                bestCount = have;
                bestOre = ore;
                bestTile = found;
                bestDist = unit.dst2(found.worldx(), found.worldy());
            }
        }

        if (bestTile != null) {
            if (unit.mineTile() != bestTile) {
                unit.mineTile(bestTile);
                trackedMineTile = bestTile;
                unit.mineTimer(0f);
            }
        } else if (unit.mining()) {
            unit.mineTile(null);
            trackedMineTile = null;
        }
    }

    private Tile findClosestOre(Unit unit, int tx, int ty, int range, Item ore) {
        Tile[] result = {null};
        float[] best = {Float.MAX_VALUE};

        Geometry.circle(tx, ty, world.width(), world.height(), range, (x, y) -> {
            Tile tile = world.tiles.get(x, y);
            if (tile == null) return;
            if (unit.getMineResult(tile) != ore) return;
            float d = unit.dst2(tile.worldx(), tile.worldy());
            if (d < best[0]) {
                best[0] = d;
                result[0] = tile;
            }
        });

        return result[0];
    }
}
