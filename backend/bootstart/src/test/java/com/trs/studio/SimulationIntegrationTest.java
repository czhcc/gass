package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exercises persisted state, graph queries and the independent action arbiter. @author agent */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "spring.datasource.hikari.maximum-pool-size=1"
    })
class SimulationIntegrationTest {
  @Autowired WorldService worlds;
  @Autowired RunService runs;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean AgentScopeDecisionProvider decisions;

  @Test
  void sqliteCapabilitiesAndGraph() {
    assertEquals(2, jdbc.queryForObject("SELECT JSON_EXTRACT('{\"a\":2}', '$.a')", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<3) SELECT MAX(x)"
                + " FROM n",
            Integer.class));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT rn FROM (SELECT ROW_NUMBER() OVER (ORDER BY x) rn FROM (SELECT 1 x UNION ALL"
                + " SELECT 2)) WHERE rn=2",
            Integer.class));
    var w = worlds.create(Examples.informationLeak());
    assertTrue(worlds.expand(w.id(), "document_f", 4, null).contains("organization_d"));
    assertEquals(
        List.of("document_f", "system_s", "system_t"),
        worlds.path(w.id(), "document_f", "system_t"));
    assertFalse(worlds.centrality(w.id()).isEmpty());
    assertTrue(
        GraphQueries.reachable(w.entities(), w.relations(), "document_f", 4)
            .contains("organization_d"));
    assertTrue(GraphQueries.metrics(w.entities(), w.relations()).containsKey("bridgeCandidates"));
    assertThrows(
        IllegalArgumentException.class, () -> worlds.expand(w.id(), "document_f", 100, null));
  }

  @Test
  void actionsAreAtomicIdempotentAndVisibleToNextAgent() {
    var w = worlds.create(Examples.informationLeak());
    var s =
        worlds.scenario(
            new Scenario(
                null,
                "leak",
                w.id(),
                List.of(
                    new AgentDefinition(
                        "response",
                        "Response",
                        "Reduce risk",
                        "model",
                        List.of("DISABLE_SYNC", "MARK_REVIEWED"),
                        List.of("system_s", "system_t"),
                        10),
                    new AgentDefinition(
                        "investigator",
                        "Investigator",
                        "Investigate",
                        "model",
                        List.of("ANALYZE_RELATIONS"),
                        List.of("document_f", "system_s", "system_t"),
                        10)),
                5,
                100));
    when(decisions.decide(any(), any()))
        .thenAnswer(
            inv -> {
              AgentDefinition a = inv.getArgument(0);
              Observation o = inv.getArgument(1);
              if (a.id().equals("response"))
                return new Decision("DISABLE_SYNC", "sync_st", Map.of(), "contain spread");
              assertFalse(
                  o.relations().stream()
                      .filter(x -> x.id().equals("sync_st"))
                      .findFirst()
                      .orElseThrow()
                      .enabled());
              return new Decision(
                  "ANALYZE_RELATIONS", "document_f", Map.of(), "investigate updated graph");
            });
    var run = runs.create(s.id());
    var after = runs.step(run.id(), "step-one");
    assertEquals(1, after.version());
    assertFalse(
        after.relations().stream()
            .filter(x -> x.id().equals("sync_st"))
            .findFirst()
            .orElseThrow()
            .enabled());
    int count = runs.events(run.id(), 0).size();
    runs.step(run.id(), "step-one");
    assertEquals(count, runs.events(run.id(), 0).size());
    runs.step(run.id(), "step-two");
    assertTrue(
        runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("ACTION_REJECTED")));
    assertEquals(1, runs.state(run.id()).version());
    assertTrue(
        worlds.get(w.id()).relations().stream()
            .filter(x -> x.id().equals("sync_st"))
            .findFirst()
            .orElseThrow()
            .enabled());
    assertEquals(1, runs.replay(run.id(), 1).version());
    assertTrue(
        runs.replay(run.id(), 0).relations().stream()
            .filter(x -> x.id().equals("sync_st"))
            .findFirst()
            .orElseThrow()
            .enabled());
    runs.stop(run.id());
    assertThrows(IllegalStateException.class, () -> runs.step(run.id(), "stopped"));
  }

  @Test
  void forbiddenActionDoesNotChangeWorldAndRunsRepeatDeterministically() {
    var w = worlds.create(Examples.informationLeak());
    var a =
        new AgentDefinition(
            "limited",
            "Limited",
            "Investigate",
            "model",
            List.of("ANALYZE_RELATIONS"),
            List.of("document_f"),
            10);
    var s = worlds.scenario(new Scenario(null, "limited", w.id(), List.of(a), 5, 20));
    when(decisions.decide(any(), any()))
        .thenReturn(new Decision("DISABLE_SYNC", "sync_st", Map.of(), "not permitted"));
    var one = runs.create(s.id());
    var two = runs.create(s.id());
    runs.step(one.id(), "1");
    runs.step(two.id(), "1");
    assertEquals(0, runs.state(one.id()).version());
    assertEquals(runs.state(one.id()).entities(), runs.state(two.id()).entities());
    assertEquals(
        runs.events(one.id(), 0).stream().map(Event::eventType).toList(),
        runs.events(two.id(), 0).stream().map(Event::eventType).toList());
    assertTrue(
        runs.events(one.id(), 0).stream().anyMatch(e -> e.eventType().equals("ACTION_REJECTED")));
  }

  @Test
  void twoAgentsUseFiveRoundsOfFeedbackWithoutMutatingTemplates() {
    var w = worlds.create(Examples.informationLeak());
    var visible = w.entities().stream().map(Entity::id).toList();
    var scenario =
        worlds.scenario(
            new Scenario(
                null,
                "five rounds",
                w.id(),
                List.of(
                    new AgentDefinition(
                        "investigator",
                        "Investigator",
                        "Review visible objects",
                        "model",
                        List.of("MARK_REVIEWED"),
                        visible,
                        10),
                    new AgentDefinition(
                        "response",
                        "Response",
                        "Close channels then review systems",
                        "model",
                        List.of("DISABLE_SYNC", "REVOKE_SHARING", "MARK_REVIEWED"),
                        visible,
                        10)),
                5,
                500));
    when(decisions.decide(any(), any()))
        .thenAnswer(
            inv -> {
              AgentDefinition agent = inv.getArgument(0);
              Observation observation = inv.getArgument(1);
              if (agent.id().equals("response")) {
                var channel =
                    observation.relations().stream()
                        .filter(
                            e ->
                                e.enabled()
                                    && List.of("SYNCS_TO", "SHARED_WITH")
                                        .contains(e.relationType()))
                        .findFirst();
                if (channel.isPresent())
                  return new Decision(
                      channel.get().relationType().equals("SYNCS_TO")
                          ? "DISABLE_SYNC"
                          : "REVOKE_SHARING",
                      channel.get().id(),
                      Map.of(),
                      "Close currently enabled channel");
              }
              var target =
                  observation.entities().stream()
                      .filter(e -> !Boolean.TRUE.equals(e.properties().get("reviewed")))
                      .findFirst();
              return new Decision(
                  "MARK_REVIEWED",
                  target.isPresent() ? target.get().id() : "missing",
                  Map.of(),
                  "Review remaining visible object");
            });
    var first = runs.create(scenario.id());
    var second = runs.create(scenario.id());
    for (int n = 0; n < 5; n++) {
      runs.step(first.id(), "step-" + n);
      runs.step(second.id(), "step-" + n);
    }
    assertEquals("BUDGET_EXHAUSTED", runs.get(first.id()).status());
    assertEquals("MAX_STEPS", runs.get(first.id()).terminationReason());
    assertTrue(runs.state(first.id()).version() >= 3);
    assertEquals(
        10,
        runs.events(first.id(), 0).stream()
            .filter(e -> e.eventType().equals("AGENT_DECISION"))
            .count());
    assertTrue(
        runs.events(first.id(), 0).stream().anyMatch(e -> e.eventType().equals("ACTION_REJECTED")));
    assertEquals(runs.state(first.id()).entities(), runs.state(second.id()).entities());
    assertEquals(runs.state(first.id()).relations(), runs.state(second.id()).relations());
    assertTrue(worlds.get(w.id()).relations().stream().allMatch(Relation::enabled));
  }

  @Test
  void injectedEventsAreScheduledAndVisibleOnlyInsideScope() {
    var w = worlds.create(Examples.informationLeak());
    var a =
        new AgentDefinition(
            "investigator",
            "Investigator",
            "Review leak event",
            "model",
            List.of("ANALYZE_RELATIONS"),
            List.of("document_f"),
            10);
    var s = worlds.scenario(new Scenario(null, "injection", w.id(), List.of(a), 5, 200));
    var run = runs.create(s.id());
    runs.inject(
        run.id(),
        "inject-1",
        new ScheduledInput("POTENTIAL_LEAK", "document_f", 1, Map.of("confidence", 0.8)));
    runs.inject(
        run.id(),
        "inject-1",
        new ScheduledInput("POTENTIAL_LEAK", "document_f", 1, Map.of("confidence", 0.8)));
    when(decisions.decide(any(), any()))
        .thenAnswer(
            inv -> {
              Observation observation = inv.getArgument(1);
              assertEquals(1, observation.visibleEvents().size());
              return new Decision(
                  "ANALYZE_RELATIONS", "document_f", Map.of(), "Inspect scheduled event");
            });
    runs.step(run.id(), "one");
    assertEquals(
        1,
        runs.events(run.id(), 0).stream()
            .filter(e -> e.eventType().equals("SIMULATION_EVENT_CREATED"))
            .count());
  }

  @Test
  void propagationRequiresAnEnabledPathAndPreservesSimulatedEvidence() {
    var w = Examples.informationLeak();
    var state = new State("fixture", 0, 0, w.entities(), w.relations());
    var agent =
        new AgentDefinition(
            "p",
            "Propagation",
            "Simulate spread",
            "m",
            List.of("SIMULATE_PROPAGATION"),
            w.entities().stream().map(Entity::id).toList(),
            10);
    var domain = new InformationEnvironment();
    var denied =
        domain.execute(
            agent,
            new Decision(
                "SIMULATE_PROPAGATION",
                "system_t",
                Map.of("sourceId", "document_f"),
                "No direct edge"),
            state);
    assertFalse(denied.accepted());
    var result =
        domain.execute(
            agent,
            new Decision(
                "SIMULATE_PROPAGATION",
                "system_s",
                Map.of("sourceId", "document_f"),
                "Hypothetical document source"),
            state);
    assertTrue(result.changed());
    assertEquals(
        List.of("person_a", "person_b"),
        domain.evaluate(result.state()).get("confirmedContactIds"));
    assertEquals(6, domain.actions().size());
    assertTrue(
        result.state().entities().stream()
            .filter(e -> e.id().equals("system_s"))
            .findFirst()
            .orElseThrow()
            .properties()
            .get("exposureEvidence")
            .equals("SIMULATED"));
  }
}
