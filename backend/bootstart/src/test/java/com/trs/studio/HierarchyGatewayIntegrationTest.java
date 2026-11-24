package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/** Adversarial gateway tests independent of model prose or SDK mock selection. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class HierarchyGatewayIntegrationTest {
  @Autowired RunService runs;
  @Autowired DemoService demos;
  @Autowired WorldService worlds;
  @Autowired ModelService models;
  @Autowired RunGraphReadService graph;
  @Autowired HistoryService history;
  @MockitoBean HierarchicalCoordinator coordinator;
  @BeforeEach void resetLimits() { ReflectionTestUtils.setField(runs,"requestBudget",300); ReflectionTestUtils.setField(runs,"tokenBudget",307200L); }
  private Run bank() { return demos.create("bank",worlds.create(Examples.bankTransfer()).id(),4); }

  @Test void rejectsStaleVersionsAndReusesAcceptedReceiptsWithoutAnotherTransfer() {
    var run=bank();
    when(coordinator.run(any())).thenAnswer(call -> {
      HierarchyHost host=call.getArgument(0); var master=host.scenario().agents().getFirst(); var agent=host.scenario().agents().stream().filter(a -> a.id().equals("bank")).findFirst().orElseThrow();
      host.startTask("root",null,master,new TaskRequest("Plan",List.of(),List.of(),null));
      var request=new TaskRequest("Transfer",List.of(),List.of("TRANSFER"),"demo-initial");
      var task=host.startTask("old","root",agent,request);
      var action=new Decision("TRANSFER","account_b",Map.of("sourceId","account_a","amount","5000000.00","currency","CNY"),"Requested transfer","ENTITY");
      var before=host.state(); assertEquals("VERSION_CONFLICT",host.execute(task,agent,action,99).get("code")); assertEquals(before,host.state());
      host.completeTask("old","REJECTED",Map.of());
      task=host.startTask("new","root",agent,request);
      var receipt=host.execute(task,agent,action,before.version()); assertEquals(true,receipt.get("accepted"));
      assertEquals(receipt,host.execute(task,agent,action,host.state().version()));
      host.completeTask("new","SUCCEEDED",Map.of());
      var duplicate=host.startTask("duplicate","root",agent,request);
      assertEquals("INPUT_ALREADY_HANDLED",host.execute(duplicate,agent,action,host.state().version()).get("code"));
      host.completeTask("duplicate","REJECTED",Map.of());
      return new RoundReport("Done",List.of(),List.of());
    });
    runs.step(run.id(),"step");
    assertEquals(1,runs.state(run.id()).entities().stream().filter(e->e.type().equals("TRANSACTION")).count());
    var accepted=runs.events(run.id(),0).stream().filter(e->e.eventType().equals("ACTION_ACCEPTED")).findFirst().orElseThrow();
    var branch=history.fork(run.id(),accepted.sequence()+2,"Between commit and task completion","fork");
    assertTrue(runs.events(branch.id(),0).stream().anyMatch(e->e.eventType().equals("ACTION_INPUT_HANDLED")));
  }

  @Test void pauseCancelsInFlightWorkAndPreventsLateStateWrites() throws Exception {
    var run=bank(); var entered=new CountDownLatch(1); var continueWork=new CountDownLatch(1);
    when(coordinator.run(any())).thenAnswer(call->{ HierarchyHost host=call.getArgument(0); entered.countDown(); assertTrue(continueWork.await(5,TimeUnit.SECONDS)); assertThrows(CancellationException.class,host::check); return new RoundReport("Cancelled",List.of(),List.of()); });
    try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
      var future=pool.submit(()->runs.step(run.id(),"slow")); assertTrue(entered.await(5,TimeUnit.SECONDS));
      runs.pause(run.id()); continueWork.countDown(); future.get(5,TimeUnit.SECONDS);
    }
    assertEquals("PAUSED",runs.get(run.id()).status()); assertEquals(0,runs.state(run.id()).version());
  }

  @Test void actualRequestsShareOneBudgetAcrossMasterAndChildren() {
    ReflectionTestUtils.setField(runs,"requestBudget",1);
    ReflectionTestUtils.setField(runs,"tokenBudget",1L);
    var run=bank();
    when(coordinator.run(any())).thenAnswer(call->{ HierarchyHost host=call.getArgument(0); var agents=host.scenario().agents(); host.beforeModel(agents.getFirst(),"root",1024); host.beforeModel(agents.get(1),"child",1024); return null; });
    runs.step(run.id(),"budget");
    assertEquals("BUDGET_EXHAUSTED",runs.get(run.id()).status()); assertEquals("MODEL_REQUEST_BUDGET",runs.get(run.id()).terminationReason());
    assertEquals(1,((Number)runs.usage(run.id()).get("requests")).intValue()); assertEquals("NOT_EVALUATED",runs.outcome(run.id()).result());
    assertEquals(1024L, ((Number) runs.usage(run.id()).get("reserved_tokens")).longValue());
  }

  @Test void anEnabledOutputTokenBudgetIsSharedAcrossMasterAndChildren() {
    ReflectionTestUtils.setField(runs, "tokenBudget", 1024L);
    var previous = ReflectionTestUtils.getField(runs, "enforceOutputTokenBudget");
    ReflectionTestUtils.setField(runs, "enforceOutputTokenBudget", true);
    try {
      var run = bank();
      when(coordinator.run(any())).thenAnswer(call -> {
        HierarchyHost host = call.getArgument(0);
        var agents = host.scenario().agents();
        host.beforeModel(agents.getFirst(), "root", 1024);
        host.beforeModel(agents.get(1), "child", 1024);
        return null;
      });
      runs.step(run.id(), "budget");
      assertEquals("BUDGET_EXHAUSTED", runs.get(run.id()).status());
      assertEquals("TOKEN_BUDGET", runs.get(run.id()).terminationReason());
      assertEquals(1, ((Number) runs.usage(run.id()).get("requests")).intValue());
      assertEquals(1024L, ((Number) runs.usage(run.id()).get("reserved_tokens")).longValue());
    } finally {
      ReflectionTestUtils.setField(runs, "enforceOutputTokenBudget", previous);
    }
  }

  @Test void graphQueriesCannotReadHiddenObjectsAndObserveCurrentRelationState() {
    var world=worlds.create(Examples.informationLeak()); var template=world.entities().getFirst();
    var agent=new AgentDefinition("query","Query","Read",models.mock().id(),List.of(),List.of(template.id()),5,new AgentRuntime("QUERY","query","Read",List.of("graph_get_entity","graph_neighbors")));
    var state=new State("r",0,7,world.entities(),world.relations());
    assertThrows(IllegalArgumentException.class,()->graph.query(agent,state,"graph_get_entity",Map.of("entityId",world.entities().getLast().id())));
    assertThrows(IllegalArgumentException.class,()->graph.query(agent,state,"graph_metrics",Map.of()));
    assertEquals(7L,graph.query(agent,state,"graph_get_entity",Map.of("entityId",template.id())).get("version"));
    assertTrue(((List<?>)graph.query(agent,state,"graph_neighbors",Map.of("entityId",template.id())).get("relations")).isEmpty());
  }

  @Test void terminalFailureRecordsSafeDiagnosticFieldsWithoutProviderMessages() {
    var run = bank();
    when(coordinator.run(any())).thenThrow(new IllegalStateException("provider response body",
        new TimeoutException("private provider details")));
    runs.step(run.id(), "failed-request");

    assertEquals("FAILED", runs.get(run.id()).status());
    var event = runs.events(run.id(), 0).getLast();
    assertEquals("RUN_FAILED", event.eventType());
    assertEquals("MODEL_DECISION_FAILED", event.payload().get("reasonCode"));
    assertEquals("MODEL_REQUEST_TIMEOUT", event.payload().get("diagnosticCode"));
    assertEquals("TimeoutException", event.payload().get("exceptionType"));
    assertFalse(Json.write(event.payload()).contains("private provider details"));
    assertFalse(Json.write(event.payload()).contains("provider response body"));
  }

  @Test void anInputSelectedByTheActionStillRequiresTheOriginalTargetAndParameters() {
    var run = bank();
    when(coordinator.run(any())).thenAnswer(call -> {
      HierarchyHost host = call.getArgument(0);
      var master = host.scenario().agents().getFirst();
      var worker = host.scenario().agents().stream().filter(a -> a.id().equals("bank")).findFirst().orElseThrow();
      host.startTask("root", null, master, new TaskRequest("Plan", List.of(), List.of(), null));
      var request = new TaskRequest("Transfer", List.of(), List.of("TRANSFER"), null);
      var missing = host.startTask("missing", "root", worker, request);
      var changedAmount = new Decision("TRANSFER", "account_b", Map.of("sourceId", "account_a", "amount", "1.00", "currency", "CNY"), "Transfer", "ENTITY");
      assertEquals("INPUT_REQUIRED", host.execute(missing, worker, changedAmount, 0, null).get("code"));
      var mismatch = host.startTask("mismatch", "root", worker, request);
      var receipt = host.execute(mismatch, worker, changedAmount, 0, "demo-initial");
      assertEquals("INPUT_ACTION_MISMATCH", receipt.get("code"));
      assertEquals("demo-initial", receipt.get("inputEventId"));
      return new RoundReport("Submitted", List.of(), List.of());
    });
    runs.step(run.id(), "bound-input");
    assertEquals(0, runs.state(run.id()).version());
    assertTrue(runs.events(run.id(), 0).stream().noneMatch(e -> e.eventType().equals("ACTION_INPUT_HANDLED")));
  }

  @Test void restartFinalizesInterruptedTasksAndPreservesCommittedReceipts() {
    var run=bank();
    when(coordinator.run(any())).thenAnswer(call->{
      HierarchyHost host=call.getArgument(0); var master=host.scenario().agents().getFirst(); var worker=host.scenario().agents().stream().filter(a->a.id().equals("bank")).findFirst().orElseThrow();
      host.startTask("interrupted-root",null,master,new TaskRequest("Plan",List.of(),List.of(),null));
      var task=host.startTask("interrupted-child","interrupted-root",worker,new TaskRequest("Transfer",List.of(),List.of("TRANSFER"),"demo-initial"));
      host.execute(task,worker,new Decision("TRANSFER","account_b",Map.of("sourceId","account_a","amount","5000000.00","currency","CNY"),"Transfer","ENTITY"),host.state().version());
      return new RoundReport("Crash before task summaries",List.of(),List.of());
    });
    runs.step(run.id(),"before-restart");var before=runs.state(run.id());
    assertTrue(runs.tasks(run.id()).stream().allMatch(t->t.status().equals("RUNNING")));
    runs.recover();
    assertEquals(before,runs.state(run.id()));assertTrue(runs.tasks(run.id()).stream().allMatch(t->t.status().equals("CANCELLED")&&t.completedSequence()!=null));
    var result=runs.tasks(run.id()).stream().filter(t->t.agentId().equals("bank")).findFirst().orElseThrow().result();
    assertTrue(Json.write(result).contains("TRANSFER"));assertTrue(Json.write(result).contains("accepted"));
    long count=runs.events(run.id(),0).size();runs.recover();assertEquals(count,runs.events(run.id(),0).size());
  }
}
