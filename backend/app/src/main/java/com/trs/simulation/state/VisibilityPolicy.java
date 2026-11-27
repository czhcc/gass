package com.trs.simulation.state;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRequest;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
        var limit = agent.taskScopeLimit();
        if (limit != null) {
            granted.retainAll(limit);
        }
        var readable = new TreeSet<String>();
        state.entities().forEach(entity -> {
            if (!"TRANSACTION".equals(entity.type()) && granted.contains(entity.id())) {
                readable.add(entity.id());
            }
        });
        var endpoints = Set.copyOf(readable);
        state.entities().forEach(entity -> {
            if ("TRANSACTION".equals(entity.type())
                    && (limit == null || limit.contains(entity.id()))
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

    /** Intersects the final visible range with an explicit scope, including derived objects. @author czhcc-trsdsj\codexsandboxoffline */
    public static AgentDefinition restrict(AgentDefinition agent, Collection<String> scope) {
        var limit = new HashSet<>(scope);
        if (agent.taskScopeLimit() != null) {
            limit.retainAll(agent.taskScopeLimit());
        }
        return scoped(
                agent,
                agent.visibleEntityIds().stream().filter(limit::contains).toList(),
                agent.allowedActions(),
                limit);
    }

    /** Inherits parent grants and any explicit parent limit while retaining ordinary derived visibility. @author czhcc-trsdsj\codexsandboxoffline */
    public static AgentDefinition underParent(AgentDefinition role, AgentDefinition parent) {
        var parentGrants = new HashSet<>(parent.visibleEntityIds());
        var narrowed = scoped(
                role,
                role.visibleEntityIds().stream().filter(parentGrants::contains).toList(),
                role.allowedActions(),
                role.taskScopeLimit());
        return parent.taskScopeLimit() == null ? narrowed : restrict(narrowed, parent.taskScopeLimit());
    }

    /** Applies the same delegation limits to the query adapter and the Action gateway. @author czhcc-trsdsj\codexsandboxoffline */
    public static AgentDefinition delegated(AgentDefinition role, AgentDefinition parent, TaskRequest task) {
        var inherited = underParent(role, parent);
        var limited = task.scopeIds().isEmpty() ? inherited : restrict(inherited, task.scopeIds());
        if (task.allowedActions().isEmpty()) {
            return limited;
        }
        return scoped(
                limited,
                limited.visibleEntityIds(),
                limited.allowedActions().stream()
                        .filter(task.allowedActions()::contains)
                        .toList(),
                limited.taskScopeLimit());
    }

    /** 将已持久批准的运行对象授权应用于冻结角色，再由任务委派计算最终范围。 @author czhcc-trsdsj\codexsandboxoffline */
    public static AgentDefinition grantRunAccess(
            AgentDefinition agent, Collection<String> objectIds, Collection<String> writableIds) {
        if (objectIds.isEmpty()) {
            return agent;
        }
        var granted = new TreeSet<>(agent.visibleEntityIds());
        granted.addAll(objectIds);
        if (agent.taskScopeLimit() != null) {
            granted.retainAll(agent.taskScopeLimit());
        }
        var readOnly = new HashSet<>(agent.readOnlyEntityIds());
        readOnly.addAll(objectIds);
        readOnly.removeAll(writableIds);
        readOnly.retainAll(granted);
        return new AgentDefinition(
                agent.id(),
                agent.name(),
                agent.goal(),
                agent.modelId(),
                agent.allowedActions(),
                List.copyOf(granted),
                agent.maxDecisions(),
                agent.runtime(),
                agent.taskScopeLimit(),
                readOnly);
    }

    private static AgentDefinition scoped(
            AgentDefinition agent, List<String> grants, List<String> actions, Set<String> limit) {
        return new AgentDefinition(
                agent.id(),
                agent.name(),
                agent.goal(),
                agent.modelId(),
                actions,
                grants,
                agent.maxDecisions(),
                agent.runtime(),
                limit,
                agent.readOnlyEntityIds());
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
