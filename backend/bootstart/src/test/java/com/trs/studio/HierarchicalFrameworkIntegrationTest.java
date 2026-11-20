package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Exercises both domains through the same SDK hierarchy and durable execution gateway. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class HierarchicalFrameworkIntegrationTest {
  @Autowired ScenarioProfiles profiles;
  @Autowired WorldService worlds;
  @Autowired RunService runs;
  @Autowired ModelService models;
  @Autowired DemoService demos;
  @Autowired HistoryService history;

  @Test
  void bankDemoUsesRealSdkSubagentsWithConcurrencyOneAndPreservesMoney() {
    var world = worlds.create(Examples.bankTransfer());
    var run = demos.create("bank", world.id(), 4);
    assertEquals("MASTER", runs.agents(run.id()).getFirst().runtime().role());
    assertTrue(runs.agents(run.id()).stream().anyMatch(a -> a.id().equals("bank")));
    assertTrue(runs.agents(run.id()).stream().anyMatch(a -> a.id().equals("risk")));
    runs.step(run.id(), "first");
    var first = runs.state(run.id());
    assertEquals(1, first.entities().stream().filter(e -> e.type().equals("TRANSACTION")).count());
    assertEquals(1200000000L, first.entities().stream().filter(e -> e.type().equals("BANK_ACCOUNT")).mapToLong(e -> ((Number) e.properties().get("balanceMinor")).longValue()).sum());
    assertTrue(runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("SUBTASK_COMPLETED")));
    assertTrue(runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("GRAPH_QUERY_RESULT")));
    assertTrue(runs.tasks(run.id()).stream().anyMatch(t -> t.parentTaskId() != null));
    assertEquals(first, runs.step(run.id(), "first"));
    for (int i = 1; i < 4; i++) runs.step(run.id(), "round-" + i);
    assertEquals(1, runs.state(run.id()).entities().stream().filter(e -> e.type().equals("TRANSACTION")).count());
    assertEquals("SUCCEEDED", runs.outcome(run.id()).result());
    assertTrue(runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED") && "ASSESS_RISK_CEL".equals(e.payload().get("actionType"))));
  }

  @Test
  void leakDemoUsesTheSameFrameworkAndEvaluatesAtTheConfiguredHorizon() {
    var world = worlds.create(Examples.informationLeak());
    var shortRun = demos.create("information", world.id(), 1);
    runs.step(shortRun.id(), "one");
    assertEquals("COMPLETED", runs.get(shortRun.id()).status());
    assertEquals("OBJECTIVE_NOT_MET", runs.get(shortRun.id()).terminationReason());
    assertEquals("FAILED", runs.outcome(shortRun.id()).result());
    assertTrue(runs.outcome(shortRun.id()).conditions().stream().anyMatch(c -> !c.satisfied()));
    var run = demos.create("information", world.id(), 8);
    for (int i = 0; i < 8; i++) runs.step(run.id(), "step-" + i);
    assertEquals("SUCCEEDED", runs.outcome(run.id()).result());
    assertTrue(runs.state(run.id()).relations().stream().filter(r -> Set.of("SHARED_WITH", "SYNCS_TO").contains(r.relationType())).noneMatch(Relation::enabled));
    assertTrue(runs.tasks(run.id()).stream().filter(t -> t.status().equals("SUCCEEDED")).anyMatch(t -> t.agentId().equals("response")));
  }

  @Test
  void frozenTeamsAndTaskHistoryRemainIsolatedWhenForking() {
    var world = worlds.create(Examples.bankTransfer());
    var parent = demos.create("bank", world.id(), 4);
    runs.step(parent.id(), "parent-one");
    var parentState = runs.state(parent.id());
    var checkpoint = runs.events(parent.id(), 0).getLast().sequence();
    var child = history.fork(parent.id(), checkpoint, "Hierarchy branch", "fork");
    assertEquals(parentState.entities(), runs.state(child.id()).entities());
    assertTrue(runs.tasks(child.id()).isEmpty());
    runs.step(child.id(), "child-one");
    assertEquals(parentState, runs.state(parent.id()));
    assertEquals(1, runs.state(child.id()).entities().stream().filter(e -> e.type().equals("TRANSACTION")).count());
    assertTrue(runs.tasks(child.id()).stream().allMatch(t -> t.runId().equals(child.id())));
  }

  @Test
  void profilesAreConfigurationAndInvalidDelegationRolesAreRejected() {
    assertEquals(Set.of("information", "bank"), profiles.list().stream().map(ScenarioProfiles.Profile::domainCode).collect(java.util.stream.Collectors.toSet()));
    var world = worlds.create(Examples.bankTransfer());
    var scenario = profiles.scenario("bank", world, models.mock().id(), "Configured", 3);
    var root = scenario.agents().getFirst();
    assertTrue(root.allowedActions().isEmpty());
    assertNotNull(scenario.team());
    var bad = new AgentDefinition(root.id(), root.name(), root.goal(), root.modelId(), List.of("TRANSFER"), root.visibleEntityIds(), root.maxDecisions(), root.runtime());
    var agents = new ArrayList<>(scenario.agents()); agents.set(0, bad);
    assertThrows(IllegalArgumentException.class, () -> worlds.scenario(new Scenario(null, "Invalid master", world.id(), agents, 3, 1500, scenario.team())));
  }

  @Test
  void newRolesAndCustomObjectActionUseTheFrameworkWithoutDomainDispatchCode() {
    var template=Examples.informationLeak();
    var action=new ActionDefinition("CUSTOM_STAMP","自定义确认","Custom confirmation","测试配置驱动的场景",Map.of("type","object","properties",Map.of(),"required",List.of(),"additionalProperties",false),List.of(),"CUSTOM",new ActionProgram(1,List.of(new ActionCondition("!has(target.properties.customDone)","尚未确认","Not confirmed")),List.of(new ActionOperation("SET_PROPERTY",Map.of("object","target.id","property","'customDone'","value","true"))),Map.of()));
    var types=template.ontology().types().stream().map(t->{ var definitions=new ArrayList<>(t.actions()); if(t.type().equals("PERSON"))definitions.add(action); return new TypeDefinition(t.targetKind(),t.type(),definitions); }).toList();
    var world=worlds.create(new World(null,"Custom",template.domainCode(),template.entityTypes(),template.relationTypes(),template.entities(),template.relations(),1,new Ontology(3,types)));
    var visible=List.of(world.entities().stream().filter(e->e.type().equals("PERSON")).findFirst().orElseThrow().id()); var model=models.mock().id();
    var agents=List.of(new AgentDefinition("planner","Planner","Arrange confirmation",model,List.of(),visible,5,new AgentRuntime("MASTER","planner","Plan",List.of("evidence","operator"))),new AgentDefinition("evidence","Evidence","Calculate graph metrics",model,List.of(),visible,5,new AgentRuntime("QUERY","delegate_evidence","Return metrics",List.of("graph_metrics"))),new AgentDefinition("operator","Operator","Confirm object",model,List.of("CUSTOM_STAMP"),visible,5,new AgentRuntime("ACTION","delegate_operator","Confirm",List.of("graph_get_entity"))));
    var team=new TeamConfig("planner",3,1,List.of(new MockActionRule("CUSTOM_STAMP",null,"true")),List.of(new GoalCondition("confirmed","对象完成确认","Object confirmed","entities.exists(e, has(e.properties.customDone) && e.properties.customDone == true)")));
    var run=runs.create(worlds.scenario(new Scenario(null,"Configured third workflow",world.id(),agents,2,1000,team)).id());
    runs.step(run.id(),"one"); runs.step(run.id(),"two");
    assertEquals("SUCCEEDED",runs.outcome(run.id()).result());
    assertTrue(runs.events(run.id(),0).stream().anyMatch(e->e.eventType().equals("GRAPH_QUERY_RESULT")&&"graph_metrics".equals(e.payload().get("tool"))));
    assertTrue(runs.events(run.id(),0).stream().anyMatch(e->e.eventType().equals("ACTION_ACCEPTED")&&"CUSTOM_STAMP".equals(e.payload().get("actionType"))));
  }
}
