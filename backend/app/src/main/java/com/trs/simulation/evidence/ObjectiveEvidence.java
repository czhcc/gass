package com.trs.simulation.evidence;

import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import com.trs.simulation.state.VisibilityPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Prepares scoped audit evidence for text objectives. @author czhcc-trsdsj\codexsandboxoffline */
public final class ObjectiveEvidence {
    public static final String COMPLETION_EVENT = "OBJECTIVE_COMPLETION_RECORDED";

    private ObjectiveEvidence() {}

    /** Checks stored query scope when full graph authorization has not been established. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean visibleQuery(Event event, Set<String> scope) {
        return visibleQuery(event, scope, false);
    }

    /** Permits missing legacy query scope only with independently proven full visibility. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean visibleQuery(Event event, Set<String> scope, boolean fullScope) {
        return "GRAPH_QUERY_RESULT".equals(event.eventType()) && allowed(event, "scopeIds", scope, fullScope);
    }

    /** Validates a completion citation against the current version and authorized scope. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean usableForCompletion(Event event, Set<String> scope, long version) {
        return usableForCompletion(event, scope, version, false);
    }

    /** Authorizes a citation within the current graph version or a visible historical Action. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean usableForCompletion(Event event, Set<String> scope, long version, boolean fullScope) {
        var payload = event.payload();
        if (visibleQuery(event, scope, fullScope)
                && payload.get("result") instanceof Map<?, ?> result
                && result.get("version") instanceof Number observed
                && observed.longValue() == version) {
            if (result.get("pathRanking") instanceof Map<?, ?> ranking)
                return Boolean.TRUE.equals(ranking.get("complete"))
                        && !Boolean.TRUE.equals(ranking.get("tiesTruncated"));
            return !Boolean.TRUE.equals(result.get("truncated"));
        }
        return visibleAction(event, scope, fullScope)
                && ("ACTION_ACCEPTED".equals(event.eventType())
                        || "ACTION_TASK_RECEIPT".equals(event.eventType())
                                && Boolean.TRUE.equals(payload.get("accepted")));
    }

    private static boolean visibleAction(Event event, Set<String> scope, boolean fullScope) {
        return Set.of("ACTION_ACCEPTED", "ACTION_REJECTED", "ACTION_TASK_RECEIPT")
                        .contains(event.eventType())
                && event.payload().get("targetId") instanceof String target
                && scope.contains(target)
                && allowed(event, "relatedIds", scope, fullScope);
    }

    private static boolean allowed(Event event, String key, Set<String> scope, boolean fullScope) {
        if (!event.payload().containsKey(key)) {
            return fullScope && !event.payload().containsKey("actionRunId");
        }
        return event.payload().get(key) instanceof List<?> ids
                && ids.stream().allMatch(id -> id instanceof String && scope.contains(id));
    }

    /** Explicit identity is scoped to the persisted owning run. @author czhcc-trsdsj\codexsandboxoffline */
    private record ActionKey(String runId, String actionRunId) {}

    private static ActionKey actionKey(Event event) {
        return event.payload().get("actionRunId") instanceof String id
                        && !id.isBlank()
                        && event.runId().equals(event.payload().get("runId"))
                ? new ActionKey(event.runId(), id)
                : null;
    }

    private static Event linked(Event event, Map<ActionKey, Event> indexed, Event adjacent, String type) {
        var key = actionKey(event);
        var match = key == null ? adjacent : indexed.get(key);
        if (match == null
                || !type.equals(match.eventType())
                || !event.runId().equals(match.runId())
                || !Objects.equals(event.agentId(), match.agentId())) {
            return null;
        }
        if (key != null) {
            return Objects.equals(
                                    event.payload().get("inputVersion"),
                                    match.payload().get("inputVersion"))
                            && Objects.equals(
                                    event.payload().get("outputVersion"),
                                    match.payload().get("outputVersion"))
                    ? match
                    : null;
        }
        return !event.payload().containsKey("actionRunId")
                        && !match.payload().containsKey("actionRunId")
                        && event.simulationTime() == match.simulationTime()
                ? match
                : null;
    }

    /** Projects visible historical actions and cited receipts. @author czhcc-trsdsj\codexsandboxoffline */
    public static Map<Long, Map<String, Object>> historicalActions(
            List<Event> history, Set<String> scope, boolean fullScope, Set<Long> cited) {
        var events = new HashMap<Long, Event>();
        var proposals = new HashMap<ActionKey, Event>();
        var changes = new HashMap<ActionKey, Event>();
        for (var event : history) {
            events.put(event.sequence(), event);
            var key = actionKey(event);
            if (key != null && "ACTION_PROPOSED".equals(event.eventType())) {
                proposals.put(key, event);
            }
            if (key != null && "WORLD_STATE_CHANGED".equals(event.eventType())) {
                changes.put(key, event);
            }
        }
        var selected = history.stream()
                .filter(event -> visibleAction(event, scope, fullScope)
                        && (!"ACTION_TASK_RECEIPT".equals(event.eventType())
                                || cited.contains(event.sequence())
                                        && Boolean.TRUE.equals(event.payload().get("accepted"))))
                .toList();
        var actions = new TreeMap<Long, Map<String, Object>>();
        int budget = 8500;
        for (var event : selected.reversed()) {
            if (actions.size() >= 16 && !cited.contains(event.sequence())) continue;
            var payload = event.payload();
            var item = new LinkedHashMap<String, Object>();
            item.put("type", event.eventType());
            item.put("time", event.simulationTime());
            item.put("action", payload.getOrDefault("actionType", ""));
            item.put("targetId", payload.get("targetId"));
            item.put("accepted", !event.eventType().equals("ACTION_REJECTED"));
            item.put("code", payload.getOrDefault("code", ""));
            var proposal = linked(event, proposals, events.get(event.sequence() - 1), "ACTION_PROPOSED");
            var changed = linked(event, changes, events.get(event.sequence() + 1), "WORLD_STATE_CHANGED");
            if (actionKey(event) != null) {
                item.put("actionRunId", payload.get("actionRunId"));
                item.put("inputVersion", payload.get("inputVersion"));
                item.put("outputVersion", payload.get("outputVersion"));
            }
            if (event.eventType().equals("ACTION_TASK_RECEIPT")) {
                item.put("inputVersion", payload.get("inputVersion"));
                item.put("outputVersion", payload.get("outputVersion"));
                if (fullScope) item.put("parameters", payload.getOrDefault("parameters", Map.of()));
            } else if (fullScope
                    && proposal != null
                    && proposal.eventType().equals("ACTION_PROPOSED")
                    && proposal.payload().get("action") instanceof Map<?, ?> decision) {
                item.put("parameters", decision.get("parameters"));
            }
            if (Boolean.TRUE.equals(item.get("accepted")) && changed != null) {
                appendChanges(item, changed, scope, fullScope, String.valueOf(payload.get("targetId")));
            }
            if (fullScope) item.put("affectedRelationIds", payload.getOrDefault("affectedRelationIds", List.of()));
            int length = Json.write(item).length();
            if (length <= 2000 && (length <= budget || cited.contains(event.sequence()))) {
                actions.put(event.sequence(), item);
                budget -= length;
            }
        }
        return actions;
    }

    private static void appendChanges(
            Map<String, Object> item, Event change, Set<String> scope, boolean fullScope, String targetId) {
        if (!(change.payload().get("before") instanceof Map<?, ?> before)
                || !(change.payload().get("after") instanceof Map<?, ?> after)) return;
        item.put("inputVersion", before.get("version"));
        item.put("outputVersion", after.get("version"));
        if (fullScope) {
            var oldTarget = row(before.get("entities"), targetId);
            var newTarget = row(after.get("entities"), targetId);
            if (newTarget.get("properties") instanceof Map<?, ?> properties) {
                Map<?, ?> previous = oldTarget.get("properties") instanceof Map<?, ?> map ? map : Map.of();
                var diff = new TreeMap<String, Object>();
                properties.forEach((key, value) -> {
                    if (!Objects.equals(value, previous.get(key))) diff.put(String.valueOf(key), value);
                });
                if (!diff.isEmpty() && Json.write(diff).length() <= 800) item.put("targetPropertiesWritten", diff);
            }
        }
        if (after.get("relations") instanceof List<?> relations) {
            var changed = new ArrayList<Map<?, ?>>();
            for (var value : relations) {
                if (value instanceof Map<?, ?> relation
                        && scope.contains(relation.get("sourceId"))
                        && scope.contains(relation.get("targetId"))
                        && !Objects.equals(
                                row(before.get("relations"), String.valueOf(relation.get("id")))
                                        .get("enabled"),
                                relation.get("enabled"))) {
                    var observed = new LinkedHashMap<String, Object>();
                    for (String key : List.of("id", "sourceId", "targetId", "relationType", "enabled", "evidence"))
                        if (relation.containsKey(key)) observed.put(key, relation.get(key));
                    changed.add(observed);
                }
                if (changed.size() == 12) break;
            }
            if (!changed.isEmpty()) item.put("relationsAfterAction", changed);
        }
    }

    private static Map<?, ?> row(Object rows, String id) {
        if (rows instanceof List<?> list)
            for (var value : list)
                if (value instanceof Map<?, ?> candidate && id.equals(candidate.get("id"))) return candidate;
        return Map.of();
    }

    /** Projects the final graph within the caller's scope and size bounds. @author czhcc-trsdsj\codexsandboxoffline */
    public static Map<String, Object> endGraph(State state, Set<String> scope, boolean fullScope) {
        var entities = state.entities().stream()
                .filter(entity -> scope.contains(entity.id()))
                .toList();
        var relations = state.relations().stream()
                .filter(relation -> VisibilityPolicy.readable(relation, scope))
                .toList();
        var objects = bounded(entities.stream()
                .map(entity -> {
                    var item = new LinkedHashMap<String, Object>(
                            Map.of("id", entity.id(), "name", entity.name(), "type", entity.type()));
                    if (fullScope && Json.write(entity.properties()).length() <= 800)
                        item.put("properties", entity.properties());
                    return item;
                })
                .toList());
        var edges = bounded(relations.stream().map(ObjectiveEvidence::relation).toList());
        return Map.of(
                "version",
                state.version(),
                "entities",
                objects,
                "relations",
                edges,
                "totalEntities",
                entities.size(),
                "totalRelations",
                relations.size(),
                "truncated",
                objects.size() < entities.size() || edges.size() < relations.size());
    }

    private static Map<String, Object> relation(Relation relation) {
        return Map.of(
                "id",
                relation.id(),
                "sourceId",
                relation.sourceId(),
                "targetId",
                relation.targetId(),
                "type",
                relation.relationType(),
                "enabled",
                relation.enabled(),
                "evidence",
                Objects.toString(relation.evidence(), ""));
    }

    private static <T> List<T> bounded(List<T> values) {
        int budget = 4500;
        var result = new ArrayList<T>();
        for (var value : values) {
            budget -= Json.write(value).length();
            if (budget < 0 || result.size() >= 50) break;
            result.add(value);
        }
        return result;
    }
    /** Projects bounded query evidence for model input. @author czhcc-trsdsj\codexsandboxoffline */
    public static Map<String, Object> queryEvidence(Event query, Map<?, ?> result) {
        var projection = new LinkedHashMap<String, Object>();
        for (String key : List.of(
                "version",
                "scope",
                "evidence",
                "totalObjects",
                "totalRelations",
                "truncated",
                "path",
                "pathRanking",
                "metrics",
                "entities",
                "relations")) {
            var value = result.get(key);
            if (value != null && Json.write(value).length() <= (key.equals("pathRanking") ? 4000 : 1000))
                projection.put(key, value);
        }
        if (projection.size() < result.size() - 2) projection.put("abridged", true);
        var evidence = new LinkedHashMap<String, Object>(Map.of(
                "type",
                query.eventType(),
                "tool",
                query.payload().getOrDefault("tool", ""),
                "result",
                projection,
                "scopeStatus",
                query.payload().containsKey("scopeIds") ? "RECORDED" : "UNKNOWN"));
        var args = query.payload().get("arguments");
        if (args != null && Json.write(args).length() <= 500) evidence.put("arguments", args);
        if (query.payload().get("scopeSize") instanceof Number size) evidence.put("scopeSize", size);
        return evidence;
    }

    /** Selects distinct visible queries for the current state version. @author czhcc-trsdsj\codexsandboxoffline */
    public static List<Map<String, Object>> recentGraphEvidence(
            List<Event> history, long version, Set<String> visible, boolean fullScope) {
        var seen = new HashSet<Map<String, Object>>();
        var evidence = new ArrayList<Map<String, Object>>();
        for (var query : history.reversed()) {
            if (!visibleQuery(query, visible, fullScope)
                    || !(query.payload().get("result") instanceof Map<?, ?> result)
                    || !(result.get("version") instanceof Number observed)
                    || observed.longValue() != version) continue;
            var entry = queryEvidence(query, result);
            if (!seen.add(entry)) continue;
            var referenced = new LinkedHashMap<>(entry);
            referenced.put("eventSequence", query.sequence());
            evidence.add(referenced);
            if (evidence.size() == 5) break;
        }
        return evidence;
    }
}
