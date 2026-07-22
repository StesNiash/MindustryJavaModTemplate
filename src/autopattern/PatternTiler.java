package autopattern;

import arc.struct.*;
import arc.math.geom.*;
import mindustry.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

public class PatternTiler {

    public static Seq<Tile> getConnectedOreTiles(Tile start, int maxTiles) {
        Item targetOre = start.overlay().itemDrop;
        if (targetOre == null) return new Seq<>();

        IntSet visited = new IntSet();
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> result = new Seq<>();

        queue.addLast(start);
        visited.add(start.pos());

        while (!queue.isEmpty() && result.size < maxTiles) {
            Tile current = queue.removeFirst();
            result.add(current);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    Tile neighbor = Vars.world.tile(
                        current.x + dx, current.y + dy);
                    if (neighbor == null) continue;
                    if (visited.contains(neighbor.pos())) continue;
                    if (neighbor.overlay().itemDrop != targetOre) continue;

                    visited.add(neighbor.pos());
                    queue.addLast(neighbor);
                }
            }
        }

        return result;
    }

    public static int countPlacements(Schematic pattern, Seq<Tile> oreTiles,
            int ox, int oy) {
        if (oreTiles.isEmpty()) return 0;

        int pw = pattern.width;
        int ph = pattern.height;
        int stepX = ox > 0 ? ox : pw;
        int stepY = oy > 0 ? oy : ph;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        IntSet oreSet = new IntSet();
        for (Tile t : oreTiles) {
            oreSet.add(t.pos());
            if (t.x < minX) minX = t.x;
            if (t.y < minY) minY = t.y;
            if (t.x > maxX) maxX = t.x;
            if (t.y > maxY) maxY = t.y;
        }

        int startX = minX + pw / 2 - pw;
        int startY = minY + ph / 2 - ph;
        int count = 0;

        for (int cx = startX; cx <= maxX + pw + pw; cx += stepX) {
            for (int cy = startY; cy <= maxY + ph + ph; cy += stepY) {
                if (overlapsOre(pattern, cx, cy, oreSet)) count++;
            }
        }
        return count;
    }

    public static void tile(Schematic pattern, Seq<Tile> oreTiles,
            int ox, int oy, int minOreTiles, boolean instant) {
        if (oreTiles.isEmpty()) return;

        int pw = pattern.width;
        int ph = pattern.height;
        int stepX = ox > 0 ? ox : pw;
        int stepY = oy > 0 ? oy : ph;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        IntSet oreSet = new IntSet();
        for (Tile t : oreTiles) {
            oreSet.add(t.pos());
            if (t.x < minX) minX = t.x;
            if (t.y < minY) minY = t.y;
            if (t.x > maxX) maxX = t.x;
            if (t.y > maxY) maxY = t.y;
        }

        int startX = minX + pw / 2 - pw;
        int startY = minY + ph / 2 - ph;
        Team team = Vars.player.team();

        if (instant) {
            for (int cx = startX; cx <= maxX + pw + pw; cx += stepX) {
                for (int cy = startY; cy <= maxY + ph + ph; cy += stepY) {
                    if (!overlapsOre(pattern, cx, cy, oreSet)) continue;
                    Schematics.place(pattern, cx, cy, team, false);
                    removeRedundantDrills(pattern, cx, cy, oreSet,
                        minOreTiles);
                }
            }
        } else {
            for (int cx = startX; cx <= maxX + pw + pw; cx += stepX) {
                for (int cy = startY; cy <= maxY + ph + ph; cy += stepY) {
                    if (!overlapsOre(pattern, cx, cy, oreSet)) continue;
                    Seq<BuildPlan> plans = Vars.schematics.toPlans(
                        pattern, cx, cy, false);
                    int worldOX = cx - pw / 2;
                    int worldOY = cy - ph / 2;
                    for (BuildPlan plan : plans) {
                        if (isDrillBlock(plan.block)
                                && countDrillOreTiles(plan.block,
                                    plan.x, plan.y, oreSet) < minOreTiles) {
                            continue;
                        }
                        Vars.player.unit().addBuild(plan);
                    }
                }
            }
        }
    }

    private static void removeRedundantDrills(Schematic pattern,
            int cx, int cy, IntSet oreSet, int minOreTiles) {
        int ox = cx - pattern.width / 2;
        int oy = cy - pattern.height / 2;
        for (Schematic.Stile st : pattern.tiles) {
            if (!isDrillBlock(st.block)) continue;
            int wx = ox + st.x;
            int wy = oy + st.y;
            if (countDrillOreTiles(st.block, wx, wy, oreSet)
                    >= minOreTiles) continue;
            Tile tile = Vars.world.tile(wx, wy);
            if (tile != null && tile.build != null) {
                tile.build.kill();
            }
        }
    }

    private static boolean overlapsOre(Schematic pattern, int cx, int cy,
            IntSet oreSet) {
        int ox = cx - pattern.width / 2;
        int oy = cy - pattern.height / 2;
        for (Schematic.Stile st : pattern.tiles) {
            if (oreSet.contains(
                    Point2.pack(st.x + ox, st.y + oy))) return true;
        }
        return false;
    }

    private static boolean isDrillBlock(Block block) {
        return block instanceof Drill
            || block.name.toLowerCase().contains("drill");
    }

    private static int countDrillOreTiles(Block block, int wx, int wy,
            IntSet oreSet) {
        int count = 0;
        for (int dx = 0; dx < block.size; dx++) {
            for (int dy = 0; dy < block.size; dy++) {
                if (oreSet.contains(
                        Point2.pack(wx + dx, wy + dy))) count++;
            }
        }
        return count;
    }
}
