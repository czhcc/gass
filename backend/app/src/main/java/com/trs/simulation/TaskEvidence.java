package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;

/** Interprets durable engine receipts independently of any real or simulated model. @author czhcc-trsdsj\codexsandboxoffline */
public final class TaskEvidence {
  private TaskEvidence() {}

  static boolean requiresInput(TeamConfig team, String action) {
    return team.inputActions().values().stream().anyMatch(actions -> actions.contains(action));
  }

  static boolean matchesInputType(TeamConfig team, String action, Event event) {
    return team.inputActions().getOrDefault(String.valueOf(event.payload().get("eventType")), List.of()).contains(action);
  }
  /** Includes checkpoint commits even when the child summary has not yet returned. @author czhcc-trsdsj\codexsandboxoffline */
  public static Set<String> handledInputs(List<Event> events) {
    var result = new HashSet<String>();
    for (var event : events) {
      if (!Set.of("SUBTASK_COMPLETED", "TASK_MEMORY_RESTORED", "ACTION_INPUT_HANDLED", "WORLD_STATE_CHANGED").contains(event.eventType())) continue;
      var payload = event.payload();
      if (event.eventType().equals("ACTION_INPUT_HANDLED") || event.eventType().equals("WORLD_STATE_CHANGED")) {
        String input = String.valueOf(payload.getOrDefault("inputEventId", ""));
        if (!input.isBlank()) result.add(input);
        continue;
      }
      if (payload.get("result") instanceof Map<?,?> map) payload = (Map<String,Object>) map;
      if (payload.get("receipts") instanceof List<?> receipts && receipts.stream().anyMatch(r -> r instanceof Map<?,?> map && Boolean.TRUE.equals(map.get("accepted")))) {
        String input = String.valueOf(payload.getOrDefault("inputEventId", ""));
        if (!input.isBlank()) result.add(input);
      }
    }
    return result;
  }
}
