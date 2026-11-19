package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies explicit termination reasons and computation-only monotonic timing. @author czhcc-trsdsj\czhcc */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1", "studio.simulation.max-runtime-seconds=10"})
class RunTerminationIntegrationTest {
  @Autowired RunService runs;
  @Autowired WorldService worlds;
  @Autowired ModelService models;
  @Autowired JdbcTemplate db;
  @Autowired HistoryService history;
  @MockitoBean AgentScopeDecisionProvider provider;
  private final AtomicLong nanos = new AtomicLong();

  @BeforeEach
  void setup() {
    ReflectionTestUtils.setField(runs, "nanoClock", (LongSupplier) nanos::get);
    ReflectionTestUtils.setField(runs, "timeBudget", 10);
    ReflectionTestUtils.setField(runs, "decisionBudget", 50);
    ReflectionTestUtils.setField(runs, "requestBudget", 300);
    ReflectionTestUtils.setField(runs, "tokenBudget", 307200L);
    ReflectionTestUtils.setField(runs, "changeBudget", 50);
    when(provider.mode(any())).thenReturn("MOCK");
    when(provider.drainToolCalls()).thenReturn(List.of());
    when(provider.decide(any(), any())).thenAnswer(call -> {
      nanos.addAndGet(2_000_000_000L);
      return new Decision("ANALYZE_RELATIONS", "document_f", Map.of(), "Read only", "ENTITY");
    });
  }

  private Run create(int steps, int decisions) {
    var w = worlds.create(Examples.informationLeak());
    var a = new AgentDefinition("a", "A", "Investigate", models.mock().id(), List.of("ANALYZE_RELATIONS", "END"), w.entities().stream().map(Entity::id).toList(), decisions);
    return runs.create(worlds.scenario(new Scenario(null, "Termination test", w.id(), List.of(a), steps, 500)).id());
  }

  @Test
  void pausedWallTimeIsExcludedAndActiveTimePersistsAcrossRecoveryAndFork() {
    var run = create(5, 10);
    db.update("UPDATE simulation_run SET created_at=0 WHERE id=?", run.id());
    runs.step(run.id(), "first");
    assertEquals("PAUSED", runs.get(run.id()).status());
    assertEquals(2000, runs.get(run.id()).activeRuntimeMs());
    nanos.addAndGet(86_400_000_000_000L);
    runs.recover();
    runs.step(run.id(), "second");
    assertEquals(4000, runs.get(run.id()).activeRuntimeMs());
    runs.step(run.id(), "second");
    assertEquals(4000, runs.get(run.id()).activeRuntimeMs());
    var child = history.fork(run.id(), history.checkpoints(run.id()).getLast().sequence(), "Fresh budget", "fork");
    assertEquals(0, runs.get(child.id()).activeRuntimeMs());
    assertNull(runs.get(child.id()).terminationReason());
  }

  @Test
  void allAcceptedEndsAreTaskCompletionEvenOnLastAllowedRound() {
    when(provider.decide(any(), any())).thenReturn(new Decision("END", "", Map.of(), "Done", "AGENT"));
    var run = create(1, 1);
    runs.step(run.id(), "end");
    assertEquals("COMPLETED", runs.get(run.id()).status());
    assertEquals("ALL_AGENTS_ENDED", runs.get(run.id()).terminationReason());
    assertEquals(1, runs.events(run.id(), 0).stream().filter(e -> e.eventType().equals("RUN_COMPLETED")).count());
  }

  @ParameterizedTest
  @CsvSource({"1,10,MAX_STEPS", "5,1,MAX_AGENT_DECISIONS"})
  void roundAndAgentLimitsAreNotTaskCompletion(int steps, int decisions, String reason) {
    var run = create(steps, decisions);
    runs.step(run.id(), "limit");
    assertBudget(run.id(), reason);
  }

  @ParameterizedTest
  @CsvSource({"decisionBudget,0,MAX_DECISIONS", "requestBudget,5,MODEL_REQUEST_BUDGET", "tokenBudget,6143,TOKEN_BUDGET", "changeBudget,0,MAX_STATE_CHANGES"})
  void eachGlobalBudgetRecordsItsSpecificReason(String field, long limit, String reason) {
    if (field.equals("tokenBudget")) ReflectionTestUtils.setField(runs, field, limit);
    else ReflectionTestUtils.setField(runs, field, (int) limit);
    var run = create(5, 10);
    runs.step(run.id(), "limit");
    assertBudget(run.id(), reason);
    verify(provider, never()).decide(any(), any());
  }

  @Test
  void slowFinalModelCallCountsImmediatelyAndTerminalStateCannotResume() {
    when(provider.decide(any(), any())).thenAnswer(call -> {
      nanos.addAndGet(11_000_000_000L);
      return new Decision("ANALYZE_RELATIONS", "document_f", Map.of(), "Read only", "ENTITY");
    });
    var run = create(5, 10);
    runs.step(run.id(), "slow");
    assertBudget(run.id(), "MAX_RUNTIME");
    assertEquals(11000, runs.get(run.id()).activeRuntimeMs());
    assertThrows(IllegalStateException.class, () -> runs.resume(run.id()));
    assertThrows(IllegalStateException.class, () -> runs.stop(run.id()));
  }

  @Test
  void eventBudgetAndLegacyCompletionRemainAuditable() {
    var run = create(5, 10);
    db.update("UPDATE simulation_run SET scenario_json=json_set(scenario_json,'$.maxEvents',10) WHERE id=?", run.id());
    runs.step(run.id(), "limit");
    assertBudget(run.id(), "MAX_EVENTS");
    var old = create(5, 10);
    db.update("UPDATE simulation_run SET status='COMPLETED' WHERE id=?", old.id());
    assertNull(runs.get(old.id()).terminationReason());
  }

  private void assertBudget(String id, String reason) {
    assertEquals("BUDGET_EXHAUSTED", runs.get(id).status());
    assertEquals(reason, runs.get(id).terminationReason());
    var events = runs.events(id, 0).stream().filter(e -> e.eventType().equals("RUN_BUDGET_EXHAUSTED")).toList();
    assertEquals(1, events.size());
    assertEquals(reason, events.getFirst().payload().get("reasonCode"));
    assertNotNull(events.getFirst().payload().get("actual"));
    assertNotNull(events.getFirst().payload().get("limit"));
    assertNotNull(events.getFirst().payload().get("activeRuntimeMs"));
    assertTrue(runs.events(id, 0).stream().noneMatch(e -> e.eventType().equals("RUN_COMPLETED")));
  }

  @Test
  void modelFailureTimeAndUserStopReasonsAreDurable() {
    when(provider.decide(any(), any())).thenAnswer(call -> {
      nanos.addAndGet(3_000_000_000L);
      throw new IllegalStateException("Test failure");
    });
    var failed = create(5, 10);
    runs.step(failed.id(), "fail");
    assertEquals("FAILED", runs.get(failed.id()).status());
    assertEquals("MODEL_DECISION_FAILED", runs.get(failed.id()).terminationReason());
    assertEquals(3000, runs.get(failed.id()).activeRuntimeMs());
    var stopped = create(5, 10);
    runs.stop(stopped.id());
    assertEquals("USER_STOPPED", runs.get(stopped.id()).terminationReason());
    assertEquals("USER_STOPPED", runs.list().stream().filter(r -> r.id().equals(stopped.id())).findFirst().orElseThrow().terminationReason());
    assertEquals(0, runs.get(stopped.id()).activeRuntimeMs());
  }
}
