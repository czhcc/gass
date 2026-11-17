package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.util.*;
import reactor.core.publisher.Flux;

/**
 * Explicitly simulated SDK model: chooses from observed state, never emits stored story
 * events. @author agent
 */
public final class SimulatedModel implements Model {
  private final AgentDefinition agent;
  private final Observation observation;
  private int requests;

  /** Captures only the authorized observation and action permissions. @author agent */
  public SimulatedModel(AgentDefinition agent, Observation observation) {
    this.agent = agent;
    this.observation = observation;
  }

  /** Keeps AgentScope's real ReAct tool and structured-output execution path. @author agent */
  @Override
  public Flux<ChatResponse> stream(
      List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    String name;
    Map<String, Object> input;
    if (requests++ == 0 && !observation.entities().isEmpty()) {
      name = "graph_search_entity";
      input = Map.of("query", observation.entities().getFirst().id());
    } else {
      name = "generate_response";
      input = Map.of("response", Json.read(Json.write(choose()), Map.class));
    }
    return Flux.just(
        ChatResponse.builder()
            .id("mock-" + requests)
            .content(
                List.of(
                    ToolUseBlock.builder()
                        .id("mock-call-" + requests)
                        .name(name)
                        .input(input)
                        .build()))
            .finishReason("tool_calls")
            .build());
  }

  /** Identifies simulated output throughout SDK diagnostics. @author agent */
  @Override
  public String getModelName() {
    return "SIMULATED_MODEL";
  }

  private Decision choose() {
    var allowed = agent.allowedActions();
    if (allowed.contains("TRANSFER")) {
      var request =
          observation.visibleEvents().stream()
              .filter(e -> e.get("eventType").equals("BANK_TRANSFER"))
              .findFirst();
      if (request.isPresent()
          && observation.entities().stream().noneMatch(e -> e.type().equals("TRANSACTION"))) {
        var event = request.get();
        return decision(
            "TRANSFER",
            String.valueOf(event.get("targetId")),
            Json.read(Json.write(event.get("parameters")), Map.class),
            "Mock: evaluate requested simulated transfer");
      }
    }
    if (allowed.contains("FLAG_RISK")) {
      var account =
          observation.entities().stream()
              .filter(
                  e ->
                      e.type().equals("BANK_ACCOUNT")
                          && !Boolean.TRUE.equals(e.properties().get("riskFlag")))
              .findFirst();
      if (account.isPresent()
          && observation.entities().stream().anyMatch(e -> e.type().equals("TRANSACTION")))
        return decision(
            "FLAG_RISK",
            account.get().id(),
            Map.of(),
            "Mock: flag simulated large-transfer account");
    }
    for (var relation :
        observation.relations().stream()
            .sorted(Comparator.comparingInt(e -> e.relationType().equals("SYNCS_TO") ? 0 : 1))
            .toList()) {
      String action =
          relation.relationType().equals("SYNCS_TO")
              ? "DISABLE_SYNC"
              : relation.relationType().equals("SHARED_WITH") ? "REVOKE_SHARING" : "";
      if (relation.enabled() && allowed.contains(action))
        return decision(
            action, relation.id(), Map.of(), "Mock: contain an enabled channel observed now");
    }
    // A visible, explicitly labelled validation probe exercises rejection, then adapts to feedback.
    if (allowed.contains("DISABLE_SYNC")
        && observation.relations().stream()
            .anyMatch(e -> !e.enabled() && e.relationType().equals("SYNCS_TO"))
        && observation.feedback().stream()
            .noneMatch(f -> f.contains("disabled or has incompatible type")))
      return decision(
          "DISABLE_SYNC",
          observation.relations().stream()
              .filter(e -> e.relationType().equals("SYNCS_TO"))
              .findFirst()
              .orElseThrow()
              .id(),
          Map.of(),
          "Mock validation probe: confirm engine rejects an already disabled channel");
    if (allowed.contains("SIMULATE_PROPAGATION")) {
      for (var relation : observation.relations()) {
        var from =
            observation.entities().stream()
                .filter(e -> e.id().equals(relation.sourceId()))
                .findFirst();
        var to =
            observation.entities().stream()
                .filter(e -> e.id().equals(relation.targetId()))
                .findFirst();
        if (relation.enabled()
            && from.isPresent()
            && to.isPresent()
            && (from.get().type().equals("DOCUMENT")
                || Boolean.TRUE.equals(from.get().properties().get("exposed")))
            && !Boolean.TRUE.equals(to.get().properties().get("exposed")))
          return decision(
              "SIMULATE_PROPAGATION",
              to.get().id(),
              Map.of("sourceId", from.get().id()),
              "Mock: simulate a currently reachable propagation opportunity");
      }
    }
    if (allowed.contains("MARK_REVIEWED")) {
      var target =
          observation.entities().stream()
              .filter(e -> !Boolean.TRUE.equals(e.properties().get("reviewed")))
              .findFirst();
      if (target.isPresent())
        return decision(
            "MARK_REVIEWED",
            target.get().id(),
            Map.of(),
            "Mock: review an object not yet reviewed in the latest observation");
    }
    if (allowed.contains("ANALYZE_ACCOUNT") && !observation.entities().isEmpty())
      return decision(
          "ANALYZE_ACCOUNT",
          observation.entities().getFirst().id(),
          Map.of(),
          "Mock: inspect current account state");
    if (allowed.contains("ANALYZE_RELATIONS") && !observation.entities().isEmpty())
      return decision(
          "ANALYZE_RELATIONS",
          observation.entities().getFirst().id(),
          Map.of(),
          "Mock: inspect current graph");
    return decision("END", "", Map.of(), "Mock: no permitted state-dependent work remains");
  }

  private Decision decision(String type, String target, Map<String, Object> params, String reason) {
    return new Decision(type, target, params, reason);
  }
}
