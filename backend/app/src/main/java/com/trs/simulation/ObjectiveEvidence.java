package com.trs.simulation;

import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Prepares scoped audit evidence for text objectives. @author czhcc-trsdsj\codexsandboxoffline */
final class ObjectiveEvidence {
    static final String COMPLETION_EVENT = "OBJECTIVE_COMPLETION_RECORDED";

    private ObjectiveEvidence() {}

    static boolean visibleQuery(Event event, Set<String> scope) {
        return "GRAPH_QUERY_RESULT".equals(event.eventType())
                && (!(event.payload().get("scopeIds") instanceof List<?> ids) || scope.containsAll(ids));
    }

    static boolean usableForCompletion(Event event, Set<String> scope, long version) {
        var payload = event.payload();
        if (visibleQuery(event, scope)
                && payload.get("result") instanceof Map<?, ?> result
                && result.get("version") instanceof Number observed
                && observed.longValue() == version) {
            if (result.get("pathRanking") instanceof Map<?, ?> ranking)
                return Boolean.TRUE.equals(ranking.get("complete"))
                        && !Boolean.TRUE.equals(ranking.get("tiesTruncated"));
            return !Boolean.TRUE.equals(result.get("truncated"));
        }
        return visibleAction(event, scope) && "ACTION_ACCEPTED".equals(event.eventType())
                || "ACTION_TASK_RECEIPT".equals(event.eventType())
                        && Boolean.TRUE.equals(payload.get("accepted"))
                        && scope.contains(payload.get("targetId"));
    }

    private static boolean visibleAction(Event event, Set<String> scope) {
        return Set.of("ACTION_ACCEPTED", "ACTION_REJECTED").contains(event.eventType())
                && scope.contains(event.payload().get("targetId"));
    }

    static Map<Long, Map<String, Object>> historicalActions(
            List<Event> history, Set<String> scope, boolean fullScope, Set<Long> cited) {
        var events = new HashMap<Long, Event>();
        history.forEach(event -> events.put(event.sequence(), event));
        var selected = history.stream()
                .filter(event -> visibleAction(event, scope)
                        || cited.contains(event.sequence())
                                && "ACTION_TASK_RECEIPT".equals(event.eventType())
                                && Boolean.TRUE.equals(event.payload().get("accepted"))
                                && scope.contains(event.payload().get("targetId")))
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
            var proposal = events.get(event.sequence() - 1);
            var changed = events.get(event.sequence() + 1);
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
            if (event.eventType().equals("ACTION_ACCEPTED")
                    && changed != null
                    && changed.eventType().equals("WORLD_STATE_CHANGED")) {
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

    static Map<String, Object> endGraph(State state, Set<String> scope, boolean fullScope) {
        var entities = state.entities().stream()
                .filter(entity -> scope.contains(entity.id()))
                .toList();
        var relations = state.relations().stream()
                .filter(relation -> scope.contains(relation.sourceId()) && scope.contains(relation.targetId()))
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
}
