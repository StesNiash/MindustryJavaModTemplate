package silicondevil;

import arc.struct.*;
import mindustry.entities.units.BuildPlan;

public class BuildTreeBuilder {
    public static class Branch {
        public Seq<BuildPlan> blocks = new Seq<>();
        public int mergeToIdx = -1;
    }

    public static Seq<Branch> build(Seq<BuildPlan> plans, int branchCount) {
        int n = plans.size;
        if (n == 0) return new Seq<>();
        branchCount = Math.min(Math.max(branchCount, 1), n);

        Seq<Edge> edges = new Seq<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                BuildPlan a = plans.get(i), b = plans.get(j);
                float dx = a.x - b.x, dy = a.y - b.y;
                edges.add(new Edge(i, j, dx * dx + dy * dy));
            }
        }
        edges.sort((a, b) -> Float.compare(a.dist, b.dist));

        int[] parent = new int[n];
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int components = n;
        for (Edge e : edges) {
            if (components <= branchCount) break;
            int ra = find(parent, e.a), rb = find(parent, e.b);
            if (ra != rb) { union(parent, rank, ra, rb); components--; }
        }

        IntMap<Seq<Integer>> clusters = new IntMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            Seq<Integer> c = clusters.get(root, new Seq<>());
            c.add(i);
            clusters.put(root, c);
        }

        Seq<Branch> branches = new Seq<>();
        for (IntMap.Entry<Seq<Integer>> entry : clusters) {
            Branch b = new Branch();
            b.blocks = nearestNeighbor(entry.value, plans);
            branches.add(b);
        }

        if (branches.size > 1) linkBranches(branches);
        return branches;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private static void union(int[] p, int[] r, int a, int b) {
        if (r[a] < r[b]) { p[a] = b; }
        else if (r[a] > r[b]) { p[b] = a; }
        else { p[b] = a; r[a]++; }
    }

    private static Seq<BuildPlan> nearestNeighbor(Seq<Integer> indices, Seq<BuildPlan> plans) {
        int[] arr = new int[indices.size];
        for (int i = 0; i < indices.size; i++) arr[i] = indices.get(i);
        if (arr.length == 1) {
            return new Seq<>(new BuildPlan[]{plans.get(arr[0])});
        }

        float cx = 0, cy = 0;
        for (int i : arr) { cx += plans.get(i).x; cy += plans.get(i).y; }
        cx /= arr.length; cy /= arr.length;

        int start = arr[0];
        float best = Float.MAX_VALUE;
        for (int i : arr) {
            float dx = plans.get(i).x - cx, dy = plans.get(i).y - cy;
            float d = dx * dx + dy * dy;
            if (d < best) { best = d; start = i; }
        }

        Seq<BuildPlan> result = new Seq<>();
        boolean[] used = new boolean[arr.length];
        int cur = indexOf(arr, start);

        for (int k = 0; k < arr.length; k++) {
            used[cur] = true;
            result.add(plans.get(arr[cur]));
            if (k == arr.length - 1) break;

            int next = -1;
            float near = Float.MAX_VALUE;
            for (int j = 0; j < arr.length; j++) {
                if (used[j]) continue;
                float dx = plans.get(arr[j]).x - plans.get(arr[cur]).x;
                float dy = plans.get(arr[j]).y - plans.get(arr[cur]).y;
                float d = dx * dx + dy * dy;
                if (d < near) { near = d; next = j; }
            }
            cur = next;
        }
        return result;
    }

    private static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return 0;
    }

    private static void linkBranches(Seq<Branch> branches) {
        int m = branches.size;
        boolean[] taken = new boolean[m];
        int[] next = new int[m];
        for (int i = 0; i < m; i++) next[i] = -1;

        for (int i = 0; i < m; i++) {
            if (taken[i]) continue;
            BuildPlan tail = branches.get(i).blocks.peek();
            int best = -1;
            float bestDist = Float.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                if (i == j || taken[j]) continue;
                BuildPlan head = branches.get(j).blocks.first();
                float dx = tail.x - head.x, dy = tail.y - head.y;
                float d = dx * dx + dy * dy;
                if (d < bestDist) { bestDist = d; best = j; }
            }
            if (best != -1) { next[i] = best; taken[i] = true; }
        }

        for (int i = 0; i < m; i++) branches.get(i).mergeToIdx = next[i];
    }

    private static class Edge {
        final int a, b;
        final float dist;
        Edge(int a, int b, float dist) { this.a = a; this.b = b; this.dist = dist; }
    }
}
