package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Creates demo goals and runs from explicitly selected existing worlds.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
@Service
public class DemoService {
  private final WorldService worlds;
  private final ModelService models;
  private final RunService runs;

  /** Injects application services. @author agent */
  public DemoService(WorldService worlds, ModelService models, RunService runs) {
    this.worlds = worlds;
    this.models = models;
    this.runs = runs;
  }

  /**
   * Creates a credential-free paused demo using an existing world without copying its template.
   * @author czhcc-trsdsj\codexsandboxoffline
   */
  public Run create(String domain, String worldId) {
    if (!List.of("information", "bank").contains(domain))
      throw new IllegalArgumentException("Unknown demo domain");
    if (worldId == null || worldId.isBlank())
      throw new IllegalArgumentException("Select an existing world for the demo");
    var world = worlds.get(worldId);
    if (!domain.equals(world.domainCode()))
      throw new IllegalArgumentException("Selected world does not match the demo domain");
    var initial = initialEvent(world);
    var model = models.mock();
    var visible = world.entities().stream().map(Entity::id).toList();
    List<AgentDefinition> agents;
    if (domain.equals("bank"))
      agents =
          List.of(
              agent(
                  "bank",
                  "银行 / Bank Agent",
                  "Process a simulated transfer with exact money conservation",
                  model.id(),
                  List.of("TRANSFER", "ANALYZE_ACCOUNT", "END"),
                  visible),
              agent(
                  "risk",
                  "风控 / Risk Agent",
                  "Review current transfers and flag simulated risks",
                  model.id(),
                  List.of("ASSESS_RISK_CEL", "FLAG_RISK", "ANALYZE_ACCOUNT", "END"),
                  visible));
    else
      agents =
          List.of(
              agent(
                  "investigation",
                  "调查 / Investigation Agent",
                  "Investigate visible leak paths and review objects",
                  model.id(),
                  List.of("MARK_REVIEWED", "ANALYZE_RELATIONS", "END"),
                  visible),
              agent(
                  "propagation",
                  "传播 / Propagation Agent",
                  "Simulate hypothetical propagation only over enabled current paths",
                  model.id(),
                  List.of("SIMULATE_PROPAGATION", "ANALYZE_RELATIONS", "END"),
                  visible),
              agent(
                  "response",
                  "响应 / Response Agent",
                  "Reduce risk using observed channel state",
                  model.id(),
                  List.of("DISABLE_SYNC", "REVOKE_SHARING", "MARK_REVIEWED", "END"),
                  visible),
              agent(
                  "evaluation",
                  "评估 / Evaluation Agent",
                  "Evaluate current visible relationships",
                  model.id(),
                  List.of("ANALYZE_RELATIONS", "END"),
                  visible));
    var scenario =
        worlds.scenario(new Scenario(null, world.name() + " · MOCK", world.id(), agents, 8, 1500));
    var run = runs.create(scenario.id());
    runs.inject(run.id(), "demo-initial", initial);
    return run;
  }

  private ScheduledInput initialEvent(World world) {
    if (world.domainCode().equals("bank")) {
      var accounts = world.entities().stream().filter(e -> e.type().equals("BANK_ACCOUNT")).toList();
      for (var source : accounts) {
        long balance = ((Number) source.properties().get("balanceMinor")).longValue();
        if (balance < 1) continue;
        for (var target : accounts) {
          if (!source.id().equals(target.id())
              && Objects.equals(source.properties().get("currency"), target.properties().get("currency"))) {
            return new ScheduledInput("BANK_TRANSFER", target.id(), 0,
                Map.of("sourceId", source.id(),
                    "amount", java.math.BigDecimal.valueOf(Math.min(balance, 500000000L), 2).toPlainString(),
                    "currency", source.properties().get("currency")));
          }
        }
      }
      throw new IllegalArgumentException("Bank demo requires two accounts with the same currency and a funded source");
    }
    var document = world.entities().stream().filter(e -> e.type().equals("DOCUMENT")).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Leak demo requires a document"));
    var system = world.entities().stream().filter(e -> e.type().equals("SYSTEM")).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Leak demo requires a system"));
    return new ScheduledInput("POTENTIAL_LEAK", document.id(), 0,
        Map.of("confidence", 0.8, "source", system.id()));
  }

  private AgentDefinition agent(
      String id,
      String name,
      String goal,
      String model,
      List<String> actions,
      List<String> visible) {
    return new AgentDefinition(id, name, goal, model, actions, visible, 10);
  }
}
