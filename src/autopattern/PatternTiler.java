package autopattern;

import arc.struct.*;
import arc.math.geom.*;
import mindustry.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;

public class PatternTiler {

    public static Seq<Tile> getConnectedOreTiles(Tile start) {
        Item targetOre = start.drop();
        if (targetOre == null) return new Seq<>();

        IntSet visited = new IntSet();
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> result = new Seq<>();

        queue.addLast(start);
        visited.add(start.pos());

        while (!queue.isEmpty()) {
            Tile current = queue.removeFirst();
            result.add(current);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    Tile neighbor = Vars.world.tile(current.x + dx, current.y + dy);
                    if (neighbor == null) continue;
                    if (visited.contains(neighbor.pos())) continue;
                    if (neighbor.drop() != targetOre) continue;

                    visited.add(neighbor.pos());
                    queue.addLast(neighbor);
                }
            }
        }

        return result;
    }

    public static int countPlacements(Schematic pattern, Seq<Tile> oreTiles, int ox, int oy) {
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

        int startX = minX + pw / 2;
        int startY = minY + ph / 2;
        int count = 0;

        for (int cx = startX; cx <= maxX + pw; cx += stepX) {
            for (int cy = startY; cy <= maxY + ph; cy += stepY) {
                if (overlapsOre(pattern, cx, cy, oreSet)) count++;
            }
        }
        return count;
    }

    public static void tile(Schematic pattern, Seq<Tile> oreTiles, int ox, int oy, boolean instant) {
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

        int startX = minX + pw / 2;
        int startY = minY + ph / 2;
        Team team = Vars.player.team();

        for (int cx = startX; cx <= maxX + pw; cx += stepX) {
            for (int cy = startY; cy <= maxY + ph; cy += stepY) {
                if (!overlapsOre(pattern, cx, cy, oreSet)) continue;

                if (instant) {
                    Schematics.place(pattern, cx, cy, team, false);
                } else {
                    Seq<BuildPlan> plans = Vars.schematics.toPlans(pattern, cx, cy, false);
                    for (BuildPlan plan : plans) {
                        Vars.player.unit().addBuild(plan);
                    }
                }
            }
        }
    }

    private static boolean overlapsOre(Schematic pattern, int cx, int cy, IntSet oreSet) {
        int ox = cx - pattern.width / 2;
        int oy = cy - pattern.height / 2;
        for (Schematic.Stile st : pattern.tiles) {
            if (oreSet.contains(Point2.pack(st.x + ox, st.y + oy))) return true;
        }
        return false;
    }
}
