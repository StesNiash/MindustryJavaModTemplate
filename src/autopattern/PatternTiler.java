package autopattern;

import arc.struct.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;

public class PatternTiler {

    private static final int[] D4X = {1, 0, -1, 0};
    private static final int[] D4Y = {0, 1, 0, -1};

    public static final StringBuilder logBuf = new StringBuilder();

    private static void log(String fmt, Object... args) {
        String msg = args.length == 0 ? fmt : Strings.format(fmt, args);
        logBuf.append(msg).append('\n');
        Log.info("[AP] @", msg);
    }

    public static void clearLog() {
        logBuf.setLength(0);
    }

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

        for (int cx = startX; cx <= maxX + pw + pw; cx += stepX) {
            for (int cy = startY; cy <= maxY + ph + ph; cy += stepY) {
                if (!overlapsOre(pattern, cx, cy, oreSet)) continue;

                if (instant) {
                    Schematics.place(pattern, cx, cy, team, false);
                    removeRedundantDrills(pattern, cx, cy, oreSet,
                        minOreTiles);
                    cleanOrphanedInstant(pattern, cx, cy, oreSet,
                        minOreTiles);
                } else {
                    Seq<BuildPlan> plans = Vars.schematics.toPlans(
                        pattern, cx, cy, false);
                    Seq<BuildPlan> filtered = filterOrphanedDrone(
                        pattern, cx, cy, oreSet, minOreTiles, plans);
                    for (BuildPlan plan : filtered) {
                        Vars.player.unit().addBuild(plan);
                    }
                }
            }
        }
    }

    // ---- Graph-based transport analysis ----

    private static class Node {
        int pos, blockSize, rotation;
        boolean isDrill, isTransport, isConveyor, isDirBridge;
        int bridgeLinkPos;  // ItemBridge: packed pos of linked partner
        int oreCount;       // drill only
    }

    private static IntSet computeReachedPositions(Schematic pattern,
            int cx, int cy, IntSet oreSet, int minOreTiles) {
        int px = cx - pattern.width / 2;
        int py = cy - pattern.height / 2;

        Seq<Node> nodes = new Seq<>();
        ObjectIntMap<Integer> posToNode = new ObjectIntMap<>();

        log("--- Graph build at center (@,@) ---", cx, cy);

        for (Schematic.Stile st : pattern.tiles) {
            int wx = px + st.x;
            int wy = py + st.y;
            int pos = Point2.pack(wx, wy);

            if (posToNode.containsKey(pos)) continue;

            Node n = new Node();
            n.pos = pos;
            n.blockSize = st.block.size;
            n.rotation = st.rotation;
            n.isDrill = isDrillBlock(st.block);
            n.isTransport = isTransportBlock(st.block);

            if (!n.isDrill && !n.isTransport) continue;

            n.isConveyor = st.block instanceof Conveyor
                || st.block instanceof Duct;
            n.isDirBridge = st.block instanceof DirectionBridge;

            if (st.block instanceof ItemBridge
                    && st.config instanceof Point2) {
                Point2 cfg = (Point2) st.config;
                n.bridgeLinkPos = Point2.pack(wx + cfg.x, wy + cfg.y);
                log("ItemBridge at (@,@) link=(@,@)",
                    wx, wy, wx + cfg.x, wy + cfg.y);
            }

            if (n.isDrill) {
                n.oreCount = countDrillOreTiles(st.block, wx, wy, oreSet);
                log("Drill at (@,@) size=@ ore=@",
                    wx, wy, st.block.size, n.oreCount);
            } else {
                log("Transport @ at (@,@) rot=@ conveyor=@ dirBridge=@",
                    st.block.name, wx, wy, n.rotation, n.isConveyor, n.isDirBridge);
            }

            int idx = nodes.size;
            nodes.add(n);
            for (int dx = 0; dx < n.blockSize; dx++) {
                for (int dy = 0; dy < n.blockSize; dy++) {
                    posToNode.put(Point2.pack(wx + dx, wy + dy), idx);
                }
            }
        }

        if (nodes.isEmpty()) {
            log("No nodes (no drills or transport)");
            return new IntSet();
        }

        // Build output edges
        Seq<IntSet> outputList = new Seq<>(nodes.size);
        for (int i = 0; i < nodes.size; i++) {
            IntSet set = new IntSet();
            outputList.add(set);
            Node n = nodes.get(i);
            int x = Point2.x(n.pos);
            int y = Point2.y(n.pos);

            if (n.isDrill) {
                for (int e = 0; e < n.blockSize; e++) {
                    addEdgeToTransport(posToNode, nodes, set, x - 1, y + e, i);
                    addEdgeToTransport(posToNode, nodes, set, x + n.blockSize, y + e, i);
                    addEdgeToTransport(posToNode, nodes, set, x + e, y - 1, i);
                    addEdgeToTransport(posToNode, nodes, set, x + e, y + n.blockSize, i);
                }
            }

            if (n.isConveyor) {
                addEdgeToTransport(posToNode, nodes, set,
                    x + D4X[n.rotation], y + D4Y[n.rotation], i);
            }

            if (n.bridgeLinkPos != 0) {
                addEdgeToTransport(posToNode, nodes, set, n.bridgeLinkPos, i);
            }

            if (n.isDirBridge) {
                for (int dist = 1; dist <= 4; dist++) {
                    int sx = x + D4X[n.rotation] * dist;
                    int sy = y + D4Y[n.rotation] * dist;
                    int sp = Point2.pack(sx, sy);
                    if (posToNode.containsKey(sp)) {
                        int si = posToNode.get(sp);
                        if (si != i && nodes.get(si).isDirBridge) {
                            set.add(si);
                        }
                        break;
                    }
                }
            }

            if (!n.isDrill && !n.isConveyor
                    && n.bridgeLinkPos == 0 && !n.isDirBridge) {
                for (int dir = 0; dir < 4; dir++) {
                    addEdgeToTransport(posToNode, nodes, set,
                        x + D4X[dir], y + D4Y[dir], i);
                }
            }

            if (set.size > 0) {
                StringBuilder sb = new StringBuilder();
                IntSet.IntSetIterator it = set.iterator();
                while (it.hasNext) {
                    int si = it.next();
                    int sp = nodes.get(si).pos;
                    sb.append("(").append(Point2.x(sp)).append(",")
                        .append(Point2.y(sp)).append(") ");
                }
                log("Node[@] (@,@) -> @ targets: @",
                    i, x, y, set.size, sb.toString());
            }
        }

        // BFS from surviving drills
        boolean[] reached = new boolean[nodes.size];
        IntSet reachedPos = new IntSet();
        int[] queue = new int[nodes.size];
        int head = 0, tail = 0;

        for (int i = 0; i < nodes.size; i++) {
            Node n = nodes.get(i);
            if (n.isDrill && n.oreCount >= minOreTiles) {
                reached[i] = true;
                reachedPos.add(n.pos);
                queue[tail++] = i;
                log("BFS start: drill #@ at (@,@)", i,
                    Point2.x(n.pos), Point2.y(n.pos));
            }
        }

        while (head < tail) {
            int idx = queue[head++];
            IntSet outs = outputList.get(idx);

            IntSet.IntSetIterator it = outs.iterator();
            while (it.hasNext) {
                int nidx = it.next();
                if (!reached[nidx]) {
                    reached[nidx] = true;
                    reachedPos.add(nodes.get(nidx).pos);
                    queue[tail++] = nidx;
                    log("BFS reached: node #@ (@,@) via #@",
                        nidx, Point2.x(nodes.get(nidx).pos),
                        Point2.y(nodes.get(nidx).pos), idx);
                }
            }
        }

        for (int i = 0; i < nodes.size; i++) {
            Node n = nodes.get(i);
            if (n.isTransport && !reached[i]) {
                log("ORPHAN: transport #@ at (@,@) will be removed",
                    i, Point2.x(n.pos), Point2.y(n.pos));
            }
        }

        return reachedPos;
    }

    private static void addEdgeToTransport(ObjectIntMap<Integer> posToNode,
            Seq<Node> nodes, IntSet outs, int wx, int wy, int selfIdx) {
        int p = Point2.pack(wx, wy);
        if (posToNode.containsKey(p)) {
            int idx = posToNode.get(p);
            if (idx != selfIdx && nodes.get(idx).isTransport) {
                outs.add(idx);
            }
        }
    }

    private static void addEdgeToTransport(ObjectIntMap<Integer> posToNode,
            Seq<Node> nodes, IntSet outs, int packedPos, int selfIdx) {
        if (posToNode.containsKey(packedPos)) {
            int idx = posToNode.get(packedPos);
            if (idx != selfIdx && nodes.get(idx).isTransport) {
                outs.add(idx);
            }
        }
    }

    // ---- Instant mode ----

    private static void cleanOrphanedInstant(Schematic pattern,
            int cx, int cy, IntSet oreSet, int minOreTiles) {
        int px = cx - pattern.width / 2;
        int py = cy - pattern.height / 2;

        IntSet reached = computeReachedPositions(
            pattern, cx, cy, oreSet, minOreTiles);

        for (Schematic.Stile st : pattern.tiles) {
            if (!isTransportBlock(st.block)) continue;
            int pos = Point2.pack(px + st.x, py + st.y);
            if (!reached.contains(pos)) {
                Tile t = Vars.world.tile(pos);
                if (t != null && t.build != null) t.build.kill();
            }
        }
    }

    // ---- Drone mode ----

    private static Seq<BuildPlan> filterOrphanedDrone(Schematic pattern,
            int cx, int cy, IntSet oreSet, int minOreTiles,
            Seq<BuildPlan> plans) {
        IntSet reached = computeReachedPositions(
            pattern, cx, cy, oreSet, minOreTiles);

        Seq<BuildPlan> keep = new Seq<>();
        for (BuildPlan plan : plans) {
            if (isDrillBlock(plan.block)) {
                if (countDrillOreTiles(plan.block,
                        plan.x, plan.y, oreSet) < minOreTiles) {
                    continue;
                }
                keep.add(plan);
            } else if (isTransportBlock(plan.block)) {
                int pos = Point2.pack(plan.x, plan.y);
                if (reached.contains(pos)) {
                    keep.add(plan);
                }
            } else {
                keep.add(plan);
            }
        }
        return keep;
    }

    // ---- Helpers ----

    private static void removeRedundantDrills(Schematic pattern,
            int cx, int cy, IntSet oreSet, int minOreTiles) {
        int px = cx - pattern.width / 2;
        int py = cy - pattern.height / 2;
        for (Schematic.Stile st : pattern.tiles) {
            if (!isDrillBlock(st.block)) continue;
            int wx = px + st.x;
            int wy = py + st.y;
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

    private static boolean isTransportBlock(Block block) {
        if (!block.hasItems) return false;
        if (isDrillBlock(block)) return false;
        if (block instanceof Conveyor) return true;
        if (block instanceof Duct) return true;
        if (block instanceof ItemBridge) return true;
        if (block instanceof DirectionBridge) return true;
        if (block instanceof Router) return true;
        if (block instanceof Junction) return true;
        if (block instanceof Sorter) return true;
        if (block instanceof OverflowGate) return true;
        return false;
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
