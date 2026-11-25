package com.trs.simulation.graph;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.State;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Queries an explicitly supplied runtime version, never the mutable world template index. @author czhcc-trsdsj\codexsandboxoffline */
@Service
public class RunGraphReadService {
    public static final Set<String> TOOLS =
            Set.of("graph_get_entity", "graph_neighbors", "graph_subgraph", "graph_path", "graph_metrics");
    private final LinkedHashMap<QueryKey, Map<String, Object>> cache = new LinkedHashMap<>(128, 0.75f, true);

    /** Auditable read result and whether computation was reused. @author czhcc-trsdsj\codexsandboxoffline */
    public record GraphRead(Map<String, Object> result, boolean reused) {}

    /** Identifies the authorized projection and effective query arguments. @author czhcc-trsdsj\codexsandboxoffline */
    private record QueryKey(
            String runId, long version, Set<String> scope, String tool, Map<String, Object> arguments) {}

    /** Reads the graph without requesting cache provenance. @author czhcc-trsdsj\codexsandboxoffline */
    public Map<String, Object> query(AgentDefinition agent, State state, String tool, Map<String, Object> arguments) {
        return read(agent, state, tool, arguments).result();
    }

    /** Enforces read permissions and reuses bounded results for equivalent requests on the same snapshot. @author czhcc-trsdsj\codexsandboxoffline */
    public synchronized GraphRead read(AgentDefinition agent, State state, String tool, Map<String, Object> arguments) {
        if (agent.runtime() == null || !agent.runtime().tools().contains(tool) || !TOOLS.contains(tool))
            throw new IllegalArgumentException("Query tool is not permitted for this role");
        var ids = Set.copyOf(agent.visibleEntityIds());
        var args = normalized(tool, arguments);
        var key = new QueryKey(state.runId(), state.version(), ids, tool, args);
        var previous = cache.get(key);
        if (previous != null) return new GraphRead(previous, true);
        var result = query(ids, state, tool, args);
        cache.put(key, result);
        if (cache.size() > 128) cache.pollFirstEntry();
        return new GraphRead(result, false);
    }

    private Map<String, Object> normalized(String tool, Map<String, Object> args) {
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("offset", integer(args, "offset", 0, 0, 200));
        normalized.put("limit", integer(args, "limit", 50, 1, 100));
        switch (tool) {
            case "graph_get_entity", "graph_neighbors", "graph_subgraph" ->
                normalized.put("entityId", text(args, "entityId"));
            case "graph_path" -> {
                normalized.put("from", text(args, "from"));
                normalized.put("to", text(args, "to"));
                normalized.put("rankByType", text(args, "rankByType"));
                if (!normalized.get("rankByType").equals(""))
                    normalized.put("maxDepth", integer(args, "maxDepth", 64, 1, 64));
            }
            default -> {}
        }
        if (tool.equals("graph_neighbors")) {
            String direction = text(args, "direction");
            normalized.put("direction", direction.isEmpty() ? "both" : direction);
            normalized.put("relationType", text(args, "relationType"));
        }
        if (tool.equals("graph_subgraph")) normalized.put("depth", integer(args, "depth", 2, 0, 6));
        return Map.copyOf(normalized);
    }

    private Map<String, Object> query(Set<String> ids, State state, String tool, Map<String, Object> args) {
        var entities =
                state.entities().stream().filter(e -> ids.contains(e.id())).toList();
        var edges = state.relations().stream()
                .filter(r -> ids.contains(r.sourceId()) && ids.contains(r.targetId()))
                .toList();
        String root = text(args, "entityId"), from = text(args, "from"), to = text(args, "to");
        for (var id : List.of(root, from, to))
            if (!id.isEmpty() && entities.stream().noneMatch(e -> e.id().equals(id)))
                throw new IllegalArgumentException("Object is outside authorized runtime scope");
        int offset = integer(args, "offset", 0, 0, 200), limit = integer(args, "limit", 50, 1, 100);
        var selectedEntities = entities;
        var selectedEdges = edges;
        var result = new LinkedHashMap<String, Object>();
        result.put("version", state.version());
        result.put("scope", "AUTHORIZED_RUNTIME");
        result.put("evidence", "INFERRED");
        switch (tool) {
            case "graph_get_entity" -> {
                if (root.isEmpty()) throw new IllegalArgumentException("entityId required");
                selectedEntities =
                        entities.stream().filter(e -> e.id().equals(root)).toList();
                selectedEdges = List.of();
            }
            case "graph_neighbors" -> {
                if (root.isEmpty()) throw new IllegalArgumentException("entityId required");
                String direction = text(args, "direction"), type = text(args, "relationType");
                if (direction.isEmpty()) direction = "both";
                if (!Set.of("in", "out", "both").contains(direction))
                    throw new IllegalArgumentException("direction must be in, out or both");
                String dir = direction;
                selectedEdges = edges.stream()
                        .filter(r -> (type.isEmpty() || type.equals(r.relationType()))
                                && ((dir.equals("both") || dir.equals("out"))
                                                && r.sourceId().equals(root)
                                        || (dir.equals("both") || dir.equals("in"))
                                                && r.targetId().equals(root)))
                        .toList();
                var neighbors = new HashSet<String>();
                selectedEdges.forEach(r -> {
                    neighbors.add(r.sourceId());
                    neighbors.add(r.targetId());
                });
                selectedEntities = entities.stream()
                        .filter(e -> neighbors.contains(e.id()))
                        .toList();
            }
            case "graph_subgraph" -> {
                int depth = integer(args, "depth", 2, 0, 6);
                if (!root.isEmpty()) {
                    var reachable = GraphQueries.reachable(entities, edges, root, depth);
                    selectedEntities = entities.stream()
                            .filter(e -> reachable.contains(e.id()))
                            .toList();
                    selectedEdges = edges.stream()
                            .filter(r -> reachable.contains(r.sourceId()) && reachable.contains(r.targetId()))
                            .toList();
                }
            }
            case "graph_path" -> {
                String type = text(args, "rankByType");
                if (from.isEmpty() || to.isEmpty() && type.isEmpty())
                    throw new IllegalArgumentException("from and either to or rankByType are required");
                if (type.isEmpty()) result.put("path", GraphQueries.path(entities, edges, from, to));
                else {
                    if (offset != 0)
                        throw new IllegalArgumentException(
                                "Ranked paths require offset=0; use limit to bound returned ties");
                    result.put(
                            "pathRanking",
                            GraphQueries.rankPaths(entities, edges, from, to, type, (int) args.get("maxDepth"), limit));
                    selectedEntities = List.of();
                    selectedEdges = List.of();
                }
            }
            case "graph_metrics" -> result.put("metrics", GraphQueries.metrics(entities, edges));
            default -> throw new IllegalArgumentException("Unknown graph query");
        }
        result.put("totalObjects", selectedEntities.size());
        result.put("totalRelations", selectedEdges.size());
        result.put(
                "entities", selectedEntities.stream().skip(offset).limit(limit).toList());
        result.put("relations", selectedEdges.stream().skip(offset).limit(limit).toList());
        result.put("truncated", selectedEntities.size() > offset + limit || selectedEdges.size() > offset + limit);
        result.put("offset", offset);
        result.put("limit", limit);
        return Collections.unmodifiableMap(result);
    }

    private String text(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return "";
        if (!(value instanceof String s) || s.length() > 100)
            throw new IllegalArgumentException("Invalid query argument " + name);
        return s;
    }

    private int integer(Map<String, Object> args, String name, int fallback, int min, int max) {
        var value = args.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max)
            throw new IllegalArgumentException("Invalid query bound " + name);
        return ((Number) value).intValue();
    }
}
