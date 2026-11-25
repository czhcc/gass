package com.trs.simulation;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

/** Read-only algorithms over an authorized local graph, never global persistence. @author agent */
public final class GraphQueries {
    private GraphQueries() {}

    /** Finds directed shortest paths through active relations. @author agent */
    public static List<String> path(List<Entity> entities, List<Relation> relations, String from, String to) {
        var g = graph(entities, relations);
        if (!g.containsVertex(from) || !g.containsVertex(to)) return List.of();
        var p = new BFSShortestPath<>(g).getPath(from, to);
        return p == null ? List.of() : p.getVertexList();
    }

    /** Ranks enabled directed simple paths by the count of objects of a requested type. @author czhcc-trsdsj\codexsandboxoffline */
    public static Map<String, Object> rankPaths(
            List<Entity> entities,
            List<Relation> relations,
            String from,
            String to,
            String type,
            int maxDepth,
            int limit) {
        var graph = graph(entities, relations);
        var matches = new HashSet<String>();
        entities.stream().filter(entity -> type.equals(entity.type())).forEach(entity -> matches.add(entity.id()));
        var search = new PathRanking(graph, matches, to, maxDepth, limit);
        if (graph.containsVertex(from)) search.visit(from, 0);
        return Map.of(
                "from",
                from,
                "to",
                to,
                "rankByType",
                type,
                "maxDepth",
                maxDepth,
                "maxCount",
                Math.max(0, search.best),
                "candidatePaths",
                search.candidates,
                "paths",
                List.copyOf(search.paths),
                "tieCount",
                search.ties,
                "tiesTruncated",
                search.ties > search.paths.size(),
                "complete",
                !search.depthLimited && !search.exhausted);
    }

    /** Bounds the cost of enumerating simple paths and reports when the search is incomplete. @author czhcc-trsdsj\codexsandboxoffline */
    private static final class PathRanking {
        private final Map<String, List<String>> outgoing = new HashMap<>();
        private final Set<String> matches;
        private final String to;
        private final int maxDepth;
        private final int limit;
        private final SequencedSet<String> path = new LinkedHashSet<>();
        private final List<List<String>> paths = new ArrayList<>();
        private int best = -1;
        private int ties;
        private int candidates;
        private int visits;
        private boolean depthLimited;
        private boolean exhausted;

        private PathRanking(
                DefaultDirectedGraph<String, DefaultEdge> graph,
                Set<String> matches,
                String to,
                int maxDepth,
                int limit) {
            graph.vertexSet()
                    .forEach(id -> outgoing.put(
                            id,
                            graph.outgoingEdgesOf(id).stream()
                                    .map(graph::getEdgeTarget)
                                    .sorted()
                                    .toList()));
            this.matches = matches;
            this.to = to;
            this.maxDepth = maxDepth;
            this.limit = limit;
        }

        private void visit(String id, int count) {
            if (++visits > 50000) {
                exhausted = true;
                return;
            }
            path.add(id);
            if (matches.contains(id)) count++;
            boolean atTarget = id.equals(to);
            if (atTarget || to.isEmpty() && path.size() > 1) record(count);
            if (!atTarget) {
                for (String next : outgoing.get(id)) {
                    if (path.contains(next)) continue;
                    if (path.size() > maxDepth) {
                        depthLimited = true;
                        break;
                    }
                    visit(next, count);
                    if (exhausted) break;
                }
            }
            path.removeLast();
        }

        private void record(int count) {
            candidates++;
            if (count < best) return;
            if (count > best) {
                paths.clear();
                best = count;
                ties = 0;
            }
            ties++;
            if (paths.size() < limit) paths.add(List.copyOf(path));
        }
    }

    /**
     * Computes degree and betweenness; bridge candidates are explanations, not facts. @author agent
     */
    public static Map<String, Object> metrics(List<Entity> entities, List<Relation> relations) {
        var graph = graph(entities, relations);
        Map<String, Integer> degree = new TreeMap<>();
        entities.forEach(e -> degree.put(e.id(), graph.degreeOf(e.id())));
        var centrality = new TreeMap<>(new BetweennessCentrality<>(graph).getScores());
        var bridges = centrality.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        return Map.of("evidence", "INFERRED", "degree", degree, "betweenness", centrality, "bridgeCandidates", bridges);
    }

    /** Bounded BFS used for impact estimates and local subgraphs. @author agent */
    public static List<String> reachable(List<Entity> entities, List<Relation> relations, String root, int depth) {
        if (depth < 0 || depth > 6) throw new IllegalArgumentException("Depth must be between 0 and 6");
        if (entities.stream().noneMatch(e -> e.id().equals(root)))
            throw new IllegalArgumentException("Entity is not visible");
        var seen = new TreeSet<String>();
        seen.add(root);
        for (int n = 0; n < depth; n++) {
            var previous = new HashSet<>(seen);
            relations.stream()
                    .filter(e -> e.enabled() && previous.contains(e.sourceId()))
                    .forEach(e -> seen.add(e.targetId()));
        }
        return List.copyOf(seen);
    }

    private static DefaultDirectedGraph<String, DefaultEdge> graph(List<Entity> entities, List<Relation> relations) {
        var g = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
        entities.forEach(e -> g.addVertex(e.id()));
        relations.stream()
                .filter(e -> e.enabled() && g.containsVertex(e.sourceId()) && g.containsVertex(e.targetId()))
                .forEach(e -> g.addEdge(e.sourceId(), e.targetId()));
        return g;
    }
}
