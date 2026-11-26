package com.trs.simulation.evidence;

import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.TeamConfig;
import com.trs.simulation.state.VisibilityPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interprets durable engine receipts independently of any real or simulated model. @author czhcc-trsdsj\codexsandboxoffline */
public final class TaskEvidence {
    private TaskEvidence() {}

    /** Authorizes scheduled inputs as a whole before exposing parameters or selectors. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean visibleInput(Event event, Set<String> scope, Set<String> hidden) {
        return "SIMULATION_EVENT_CREATED".equals(event.eventType())
                && event.payload().get("targetId") instanceof String target
                && scope.contains(target)
                && VisibilityPolicy.referencesVisible(event.payload().get("parameters"), hidden);
    }

    /** Determines whether the Action is bound to a scheduled input. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean requiresInput(TeamConfig team, String action) {
        return team.inputActions().values().stream().anyMatch(actions -> actions.contains(action));
    }

    /** Checks the frozen input-type binding for an Action. @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean matchesInputType(TeamConfig team, String action, Event event) {
        return team.inputActions()
                .getOrDefault(String.valueOf(event.payload().get("eventType")), List.of())
                .contains(action);
    }
    /** Includes checkpoint commits even when the child summary has not yet returned. @author czhcc-trsdsj\codexsandboxoffline */
    public static Set<String> handledInputs(List<Event> events) {
        var result = new HashSet<String>();
        for (var event : events) {
            if (!Set.of("SUBTASK_COMPLETED", "TASK_MEMORY_RESTORED", "ACTION_INPUT_HANDLED", "WORLD_STATE_CHANGED")
                    .contains(event.eventType())) continue;
            var payload = event.payload();
            if (event.eventType().equals("ACTION_INPUT_HANDLED")
                    || event.eventType().equals("WORLD_STATE_CHANGED")) {
                String input = String.valueOf(payload.getOrDefault("inputEventId", ""));
                if (!input.isBlank()) result.add(input);
                continue;
            }
            if (payload.get("result") instanceof Map<?, ?> map) payload = (Map<String, Object>) map;
            if (payload.get("receipts") instanceof List<?> receipts
                    && receipts.stream()
                            .anyMatch(r -> r instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("accepted")))) {
                String input = String.valueOf(payload.getOrDefault("inputEventId", ""));
                if (!input.isBlank()) result.add(input);
            }
        }
        return result;
    }
}
