package com.trs.simulation.state;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Resolves readable graph identities from frozen grants and the current graph. @author czhcc-trsdsj\codexsandboxoffline */
public final class VisibilityPolicy {
    private VisibilityPolicy() {}

    /** Derived transactions require access to both parties, including explicitly named transactions. @author czhcc-trsdsj\codexsandboxoffline */
    public static Set<String> readableIds(AgentDefinition agent, State state) {
        var granted = new HashSet<>(agent.visibleEntityIds());
        var readable = new TreeSet<String>();
        state.entities().forEach(entity -> {
            if (!"TRANSACTION".equals(entity.type()) && granted.contains(entity.id())) {
                readable.add(entity.id());
            }
        });
        var endpoints = Set.copyOf(readable);
        state.entities().forEach(entity -> {
            if ("TRANSACTION".equals(entity.type())
                    && entity.properties().get("sourceId") instanceof String source
                    && entity.properties().get("targetId") instanceof String target
                    && endpoints.contains(source)
                    && endpoints.contains(target)) {
                readable.add(entity.id());
            }
        });
        return Collections.unmodifiableSet(readable);
    }

    /** Both endpoint identities must be visible to read an edge. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean readable(Relation edge, Set<String> scope) {
        return scope.contains(edge.sourceId()) && scope.contains(edge.targetId());
    }

    /** Intersects the existing role grant with a request or parent scope. @author czhcc-trsdsj\codexsandboxoffline */
    public static AgentDefinition restrict(AgentDefinition agent, Collection<String> scope) {
        return new AgentDefinition(
                agent.id(),
                agent.name(),
                agent.goal(),
                agent.modelId(),
                agent.allowedActions(),
                agent.visibleEntityIds().stream().filter(scope::contains).toList(),
                agent.maxDecisions(),
                agent.runtime());
    }

    /** Includes only visible objects, edges and properties that contain no known hidden graph identity. @author czhcc-trsdsj\codexsandboxoffline */
    public static State project(State state, Set<String> scope) {
        var hidden = hiddenIds(state, scope);
        return new State(
                state.runId(),
                state.simulationTime(),
                state.version(),
                state.entities().stream()
                        .filter(e -> scope.contains(e.id()))
                        .map(e -> new Entity(e.id(), e.type(), e.name(), visibleFields(e.properties(), hidden)))
                        .toList(),
                state.relations().stream()
                        .filter(r -> readable(r, scope))
                        .map(r -> new Relation(
                                r.id(),
                                r.sourceId(),
                                r.targetId(),
                                r.relationType(),
                                visibleFields(r.properties(), hidden),
                                r.enabled(),
                                r.evidence(),
                                r.source()))
                        .toList());
    }

    /** Returns identities whose direct references must not cross the scope boundary. @author czhcc-trsdsj\codexsandboxoffline */
    public static Set<String> hiddenIds(State state, Set<String> scope) {
        var hidden = state.entities().stream()
                .map(Entity::id)
                .filter(id -> !scope.contains(id))
                .collect(Collectors.toSet());
        state.relations().stream()
                .filter(relation -> !readable(relation, scope))
                .map(Relation::id)
                .forEach(hidden::add);
        return hidden;
    }

    /** Checks JSON-structured parameters for referenced identities outside the caller's scope. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean referencesVisible(Object value, Set<String> hidden) {
        if (hidden.isEmpty()) {
            return true;
        }
        if (value instanceof String id) {
            return !hidden.contains(id);
        }
        if (value instanceof Map<?, ?> fields) {
            return fields.entrySet().stream()
                    .allMatch(entry ->
                            referencesVisible(entry.getKey(), hidden) && referencesVisible(entry.getValue(), hidden));
        }
        if (value instanceof Collection<?> values) {
            return values.stream().allMatch(item -> referencesVisible(item, hidden));
        }
        return true;
    }

    private static Map<String, Object> visibleFields(Map<String, Object> source, Set<String> hidden) {
        if (hidden.isEmpty()) {
            return source;
        }
        var projected = new LinkedHashMap<String, Object>();
        source.forEach((code, value) -> {
            if (referencesVisible(value, hidden)) {
                projected.put(code, value);
            }
        });
        return Collections.unmodifiableMap(projected);
    }
}
