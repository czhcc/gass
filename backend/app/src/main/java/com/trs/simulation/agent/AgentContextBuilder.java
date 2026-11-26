package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.GoalCondition;
import com.trs.simulation.common.Json;
import com.trs.simulation.evidence.ObjectiveEvidence;
import com.trs.simulation.evidence.TaskEvidence;
import com.trs.simulation.state.VisibilityPolicy;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds bounded agent context from authorized runtime reads. @author czhcc-trsdsj\codexsandboxoffline */
final class AgentContextBuilder {
    private AgentContextBuilder() {}

    static Map<String, Object> overview(HierarchyHost host, AgentDefinition agent, List<AgentDefinition> children) {
        host.check();
        var state = host.state();
        var scope = host.visibleIds(agent);
        boolean fullScope = state.entities().stream().allMatch(entity -> scope.contains(entity.id()))
                && host.initial().entities().stream().allMatch(entity -> scope.contains(entity.id()));
        var objects = state.entities().stream()
                .filter(e -> scope.contains(e.id()))
                .limit(30)
                .map(e -> Map.of("id", e.id(), "type", e.type(), "name", e.name()))
                .toList();
        var history = host.history();
        var handled = TaskEvidence.handledInputs(history);
        var bindings = host.scenario().team().inputActions();
        var hidden = VisibilityPolicy.hiddenIds(state, scope);
        var inputs = history.stream()
                .filter(e -> TaskEvidence.visibleInput(e, scope, hidden)
                        && !handled.contains(String.valueOf(e.payload().get("scheduledEventId"))))
                .limit(20)
                .map(e -> {
                    var input = new LinkedHashMap<>(e.payload());
                    input.put(
                            "boundActions",
                            bindings.getOrDefault(String.valueOf(e.payload().get("eventType")), List.of()));
                    return input;
                })
                .toList();
        var work = new ArrayList<Map<String, Object>>();
        for (var child : agent.runtime().role().equals("MASTER") ? children : List.of(agent)) {
            var capabilities = host.capabilities(VisibilityPolicy.underParent(child, agent)).stream()
                    .filter(ActionCapability::available)
                    .limit(24)
                    .map(c -> Map.of(
                            "actionType",
                            c.action().actionType(),
                            "name",
                            c.action().nameZh(),
                            "targetId",
                            c.targetId()))
                    .toList();
            work.add(Map.of(
                    "agentId",
                    child.id(),
                    "role",
                    child.runtime().role(),
                    "tool",
                    child.runtime().toolName(),
                    "capabilities",
                    capabilities));
        }
        var past = history.stream()
                .filter(e -> Set.of("MASTER_REVIEW", "TASK_MEMORY_RESTORED").contains(e.eventType()))
                .filter(e -> e.payload().get("scopeIds") instanceof List<?> ids ? scope.containsAll(ids) : fullScope)
                .toList();
        var result = new LinkedHashMap<>(Map.<String, Object>of(
                "round",
                host.round(),
                "version",
                state.version(),
                "objects",
                objects,
                "inputs",
                inputs,
                "workers",
                work,
                "recentReports",
                past.stream()
                        .skip(Math.max(0, past.size() - 3))
                        .map(e -> {
                            var p = e.payload();
                            var nested = p.get("result") instanceof Map<?, ?> m ? m : p;
                            Object summary = nested.get("report");
                            if (summary == null) summary = nested.get("summary");
                            String text = summary == null ? "" : Json.write(summary);
                            return Map.of(
                                    "agentId",
                                    e.agentId() == null ? "" : e.agentId(),
                                    "summary",
                                    text.substring(0, Math.min(2000, text.length())));
                        })
                        .toList()));
        var scene = host.scenario();
        if (scene.objective() != null) {
            boolean master = agent.runtime().role().equals("MASTER");
            if (master) {
                result.put("objective", scene.objective());
                result.put("evaluationRound", scene.maxSteps());
                result.put(
                        "conditions",
                        scene.team().conditions().stream()
                                .map(GoalCondition::nameZh)
                                .toList());
                var actions = ObjectiveEvidence.historicalActions(history, scope, fullScope, Set.of());
                if (!actions.isEmpty() && Json.write(actions).length() <= 6000)
                    result.put("recentActionEvidence", actions);
            }
            var previousQueries = ObjectiveEvidence.recentGraphEvidence(history, state.version(), scope, fullScope);
            if (!previousQueries.isEmpty()) {
                result.put("recentGraphEvidence", previousQueries);
                if (Json.write(result).length() > 32000) result.remove("recentGraphEvidence");
            }
        }
        if (Json.write(result).length() > 32000)
            throw new IllegalArgumentException("Planning context exceeds input budget; narrow the task scope");
        return result;
    }

    static Msg message(String value) {
        return Msg.builder()
                .name("runtime")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(value).build())
                .build();
    }
}
