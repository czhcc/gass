package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Creates input worlds and goals only; all subsequent events come from the real runtime. @author
 * agent
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
   * Creates an immediately usable credential-free demo, paused for user-controlled
   * stepping. @author agent
   */
  public Run create(String domain) {
    if (!List.of("information", "bank").contains(domain))
      throw new IllegalArgumentException("Unknown demo domain");
    var model = models.mock();
    var world =
        worlds.create(domain.equals("bank") ? Examples.bankTransfer() : Examples.informationLeak());
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
                  List.of("FLAG_RISK", "ANALYZE_ACCOUNT", "END"),
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
    runs.inject(
        run.id(),
        "demo-initial",
        domain.equals("bank")
            ? new ScheduledInput(
                "BANK_TRANSFER",
                "account_b",
                0,
                Map.of("sourceId", "account_a", "amount", "5000000.00", "currency", "CNY"))
            : new ScheduledInput(
                "POTENTIAL_LEAK",
                "document_f",
                0,
                Map.of("confidence", 0.8, "source", "system_s")));
    return run;
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
