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
    if (allowed.contains("ASSESS_RISK_CEL")) {
      var score = observation.availableActions().stream().filter(c -> c.available() && c.action().actionType().equals("ASSESS_RISK_CEL")).findFirst();
      if (score.isPresent()) return new Decision("ASSESS_RISK_CEL", score.get().targetId(), Map.of(), "Mock: request declarative CEL risk score", "ENTITY");
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
    if ("information".equals(observation.domainCode()) || !observation.availableActions().isEmpty()) {
      var priority = List.of("DISABLE_SYNC", "REVOKE_SHARING", "SIMULATE_PROPAGATION", "MARK_REVIEWED", "ANALYZE_RELATIONS");
      var selected = observation.availableActions().stream().filter(ActionCapability::available)
          .filter(c -> !c.action().actionType().equals("TRANSFER"))
          .filter(c -> ((List<?>) c.action().parametersSchema().getOrDefault("required", List.of())).stream().allMatch(p -> !c.parameterOptions().getOrDefault(p, List.of()).isEmpty()))
          .sorted(Comparator.comparingInt(c -> priority.indexOf(c.action().actionType()))).findFirst();
      if (selected.isPresent()) {
        var capability = selected.get();
        Map<String, Object> parameters = new LinkedHashMap<>();
        capability.parameterOptions().forEach((name, ids) -> parameters.put(name, ids.getFirst()));
        return new Decision(capability.action().actionType(), capability.targetId(), parameters,
            "Mock: choose a currently available type-owned action", capability.targetKind());
      }
      return new Decision("END", "", Map.of(), "Mock: no available object actions remain", "AGENT");
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
