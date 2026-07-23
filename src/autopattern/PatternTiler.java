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

    static class ExitPoint {
        int pos;
        int rotation;
    }

    static class GraphResult {
        IntSet reachedPos;
        Seq<ExitPoint> exits;
    }

    public static final Seq<ExitPoint> allExits = new Seq<>();

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

        Seq<Point2> positions = new Seq<>();
        for (int cx = startX; cx <= maxX + pw + pw; cx += stepX) {
            for (int cy = startY; cy <= maxY + ph + ph; cy += stepY) {
                if (overlapsOre(pattern, cx, cy, oreSet)) {
                    positions.add(new Point2(cx, cy));
                }
            }
        }

        if (positions.isEmpty()) return;

        GraphResult combined = computeGraphMulti(
            pattern, positions, oreSet, minOreTiles);

        allExits.clear();
        for (ExitPoint ep : combined.exits) {
            allExits.add(ep);
        }

        Team team = Vars.player.team();
        for (Point2 p : positions) {
            int cx = p.x;
            int cy = p.y;

            if (instant) {
                Schematics.place(pattern, cx, cy, team, false);
                removeRedundantDrills(pattern, cx, cy, oreSet,
                    minOreTiles);
                cleanOrphanedInstant(pattern, cx, cy,
                    combined.reachedPos);
            } else {
                Seq<BuildPlan> plans = Vars.schematics.toPlans(
                    pattern, cx, cy, false);
                Seq<BuildPlan> filtered = filterOrphanedDrone(
                    pattern, cx, cy, oreSet, minOreTiles, plans,
                    combined.reachedPos);
                for (BuildPlan plan : filtered) {
                    Vars.player.unit().addBuild(plan);
                }
            }
        }

        log("=== Tiling done: @ placements, @ total exits ===",
            positions.size, allExits.size);
    }

    // ---- Graph-based transport analysis ----

    private static class Node {
        int pos, blockSize, rotation;
        boolean isDrill, isTransport, isConveyor, isDirBridge;
        int bridgeLinkPos;  // ItemBridge: packed pos of linked partner
        int oreCount;       // drill only
    }

    private static GraphResult computeGraph(Schematic pattern,
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
            GraphResult empty = new GraphResult();
            empty.reachedPos = new IntSet();
            empty.exits = new Seq<>();
            return empty;
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

        Seq<ExitPoint> exits = new Seq<>();
        for (int i = 0; i < nodes.size; i++) {
            Node n = nodes.get(i);
            if (n.isTransport) {
                if (!reached[i]) {
                    log("ORPHAN: transport #@ at (@,@) will be removed",
                        i, Point2.x(n.pos), Point2.y(n.pos));
                } else if (outputList.get(i).isEmpty()) {
                    ExitPoint ep = new ExitPoint();
                    ep.pos = n.pos;
                    ep.rotation = n.rotation;
                    exits.add(ep);
                    log("EXIT: transport #@ at (@,@) type=@ outDegree=0",
                        i, Point2.x(n.pos), Point2.y(n.pos),
                        n.isConveyor ? "conveyor" : "junction");
                }
            }
            if (n.isDrill && reached[i] && outputList.get(i).isEmpty()) {
                log("DEAD-END: drill #@ at (@,@) has no connected transport",
                    i, Point2.x(n.pos), Point2.y(n.pos));
            }
        }

        log("Graph summary: @ nodes, @ reached, @ exits, @ orphans",
            nodes.size,
            countReached(reached),
            exits.size,
            countOrphans(nodes, reached));

        GraphResult result = new GraphResult();
        result.reachedPos = reachedPos;
        result.exits = exits;
        return result;
    }

    private static GraphResult computeGraphMulti(Schematic pattern,
            Seq<Point2> positions, IntSet oreSet, int minOreTiles) {
        Seq<Node> nodes = new Seq<>();
        ObjectIntMap<Integer> posToNode = new ObjectIntMap<>();

        log("=== Combined graph: @ positions ===", positions.size);

        for (Point2 p : positions) {
            int cx = p.x;
            int cy = p.y;
            int px = cx - pattern.width / 2;
            int py = cy - pattern.height / 2;

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
                    n.bridgeLinkPos = Point2.pack(
                        wx + cfg.x, wy + cfg.y);
                }

                if (n.isDrill) {
                    n.oreCount = countDrillOreTiles(
                        st.block, wx, wy, oreSet);
                }

                int idx = nodes.size;
                nodes.add(n);
                for (int dx = 0; dx < n.blockSize; dx++) {
                    for (int dy = 0; dy < n.blockSize; dy++) {
                        posToNode.put(
                            Point2.pack(wx + dx, wy + dy), idx);
                    }
                }
            }
        }

        log("Combined: @ nodes total", nodes.size);

        if (nodes.isEmpty()) {
            GraphResult empty = new GraphResult();
            empty.reachedPos = new IntSet();
            empty.exits = new Seq<>();
            return empty;
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
                    addEdgeToTransport(posToNode, nodes, set,
                        x - 1, y + e, i);
                    addEdgeToTransport(posToNode, nodes, set,
                        x + n.blockSize, y + e, i);
                    addEdgeToTransport(posToNode, nodes, set,
                        x + e, y - 1, i);
                    addEdgeToTransport(posToNode, nodes, set,
                        x + e, y + n.blockSize, i);
                }
            }

            if (n.isConveyor) {
                addEdgeToTransport(posToNode, nodes, set,
                    x + D4X[n.rotation], y + D4Y[n.rotation], i);
            }

            if (n.bridgeLinkPos != 0) {
                addEdgeToTransport(posToNode, nodes, set,
                    n.bridgeLinkPos, i);
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
                }
            }
        }

        Seq<ExitPoint> exits = new Seq<>();
        for (int i = 0; i < nodes.size; i++) {
            Node n = nodes.get(i);
            if (n.isTransport) {
                if (!reached[i]) {
                    log("ORPHAN: transport at (@,@) type=@",
                        Point2.x(n.pos), Point2.y(n.pos),
                        n.isConveyor ? "conveyor" : "junction");
                } else if (outputList.get(i).isEmpty()) {
                    ExitPoint ep = new ExitPoint();
                    ep.pos = n.pos;
                    ep.rotation = n.rotation;
                    exits.add(ep);
                    log("EXIT: transport at (@,@) rot=@ type=@",
                        Point2.x(n.pos), Point2.y(n.pos),
                        n.rotation,
                        n.isConveyor ? "conveyor" : "junction");
                }
            }
            if (n.isDrill && reached[i]
                    && outputList.get(i).isEmpty()) {
                log("DEAD-END: drill at (@,@) no connected transport",
                    Point2.x(n.pos), Point2.y(n.pos));
            }
        }

        log("Summary: @ nodes, @ reached, @ exits, @ orphans",
            nodes.size,
            countReached(reached),
            exits.size,
            countOrphans(nodes, reached));

        GraphResult result = new GraphResult();
        result.reachedPos = reachedPos;
        result.exits = exits;
        return result;
    }

    private static int countReached(boolean[] reached) {
        int c = 0;
        for (boolean r : reached) if (r) c++;
        return c;
    }

    private static int countOrphans(Seq<Node> nodes, boolean[] reached) {
        int c = 0;
        for (int i = 0; i < nodes.size; i++) {
            if (nodes.get(i).isTransport && !reached[i]) c++;
        }
        return c;
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
            int cx, int cy, IntSet reached) {
        int px = cx - pattern.width / 2;
        int py = cy - pattern.height / 2;

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
            Seq<BuildPlan> plans, IntSet reached) {

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

    // ---- Bridge connection ----

    private static int manhattanDist(int packedPos, int tx, int ty) {
        return Math.abs(Point2.x(packedPos) - tx)
            + Math.abs(Point2.y(packedPos) - ty);
    }

    static Block findBridgeBlock(Schematic pattern) {
        for (Schematic.Stile st : pattern.tiles) {
            if (st.block instanceof ItemBridge) return st.block;
        }
        return Vars.content.block("bridge-conveyor");
    }

    private static int findNearestOccupied(int px, int py,
            IntSet occupied) {
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        IntSet.IntSetIterator it = occupied.iterator();
        while (it.hasNext) {
            int sp = it.next();
            int sx = Point2.x(sp);
            int sy = Point2.y(sp);
            int d = Math.abs(sx - px) + Math.abs(sy - py);
            if (d < bestDist) {
                bestDist = d;
                best = sp;
            }
        }
        return best;
    }

    public static void connectExits(Seq<ExitPoint> exits,
            int targetX, int targetY, boolean instant,
            Block bridgeBlock, Team team) {
        if (exits.isEmpty() || bridgeBlock == null) return;

        int range = 4;

        clearLog();
        log("=== connectExits: @ exits, target=(@,@) ===",
            exits.size, targetX, targetY);

        IntSet occupied = new IntSet();

        exits.sort((a, b) -> {
            int da = manhattanDist(a.pos, targetX, targetY);
            int db = manhattanDist(b.pos, targetX, targetY);
            return Integer.compare(da, db);
        });

        boolean first = true;
        for (ExitPoint exit : exits) {
            int x = Point2.x(exit.pos);
            int y = Point2.y(exit.pos);

            log("Exit at (@,@)", x, y);

            int startPos = Point2.pack(x, y);
            if (occupied.contains(startPos)) {
                log("  start occupied, merge");
                continue;
            }

            int goalX, goalY;
            if (first) {
                goalX = targetX;
                goalY = targetY;
                first = false;
            } else {
                int near = findNearestOccupied(x, y, occupied);
                if (near >= 0) {
                    goalX = Point2.x(near);
                    goalY = Point2.y(near);
                    log("  merge target nearest occupied (@,@)", goalX, goalY);
                } else {
                    goalX = targetX;
                    goalY = targetY;
                }
            }

            int steps = 0;
            while (steps < 100) {
                int dx = Integer.signum(goalX - x);
                int dy = Integer.signum(goalY - y);

                if (dx == 0 && dy == 0) {
                    log("  reached goal");
                    break;
                }

                int remX = Math.abs(goalX - x);
                int remY = Math.abs(goalY - y);

                int bestNextX = 0, bestNextY = 0, bestDir = -1;
                int bestStep = 0;

                // try preferred direction, then perpendicular
                int[][] dirChecks = (remX >= remY)
                    ? new int[][]{{dx, 0}, {0, dy}}
                    : new int[][]{{0, dy}, {dx, 0}};

                for (int[] dc : dirChecks) {
                    int ddx = dc[0], ddy = dc[1];
                    if (ddx == 0 && ddy == 0) continue;
                    int step = Math.min(
                        ddx != 0 ? remX : remY, range);
                    int nx = x + ddx * step;
                    int ny = y + ddy * step;
                    int dir = ddx > 0 ? 0 : ddx < 0 ? 2
                        : ddy > 0 ? 1 : 3;

                    if (canPlaceAt(x, y) && canPlaceAt(nx, ny)) {
                        bestNextX = nx;
                        bestNextY = ny;
                        bestDir = dir;
                        bestStep = step;
                        break;
                    }
                    log("  blocked: (@,@)->(@,@)", x, y, nx, ny);
                }

                if (bestDir < 0) {
                    log("  no valid direction from (@,@)", x, y);
                    break;
                }

                int nextX = bestNextX;
                int nextY = bestNextY;
                int dir = bestDir;

                int curPos = Point2.pack(x, y);

                Tile st = Vars.world.tile(x, y);
                if (st != null && st.build != null
                        && st.build.block != null
                        && st.build.block instanceof ItemBridge) {
                    log("  (@,@) existing bridge, merge", x, y);
                    occupied.add(curPos);
                    break;
                }

                if (occupied.contains(curPos)) {
                    log("  (@,@) occupied, merge", x, y);
                    break;
                }

                placeBridge(x, y, nextX, nextY, dir,
                    bridgeBlock, team, instant);
                occupied.add(curPos);
                log("  bridge (@,@) -> (@,@) step=@",
                    x, y, nextX, nextY,
                    Math.abs(nextX - x) + Math.abs(nextY - y));

                x = nextX;
                y = nextY;
                steps++;
            }
        }

        log("connectExits done: @ bridges", occupied.size);
    }

    private static boolean canPlaceAt(int x, int y) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null) return false;
        Building build = tile.build;
        if (build != null && build.block != null
                && !(build.block instanceof ItemBridge)) {
            return false;
        }
        return true;
    }

    private static void placeBridge(int x, int y, int linkX, int linkY,
            int dir, Block bridgeBlock, Team team, boolean instant) {
        int offsetX = linkX - x;
        int offsetY = linkY - y;
        Point2 config = new Point2(offsetX, offsetY);

        if (instant) {
            Tile tile = Vars.world.tile(x, y);
            if (tile != null) {
                tile.setNet(bridgeBlock, team, dir);
                if (tile.build != null) {
                    tile.build.configure(config);
                }
            }
        } else {
            BuildPlan plan = new BuildPlan(x, y, dir,
                bridgeBlock, config);
            Vars.player.unit().addBuild(plan);
        }
    }
}
