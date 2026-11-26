package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.ActionCondition;
import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.ActionOperation;
import com.trs.simulation.SimulationTypes.ActionProgram;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.AgentRuntime;
import com.trs.simulation.SimulationTypes.GoalCondition;
import com.trs.simulation.SimulationTypes.MockActionRule;
import com.trs.simulation.SimulationTypes.ObjectiveReview;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.SchedulingPolicy;
import com.trs.simulation.SimulationTypes.TeamConfig;
import com.trs.simulation.SimulationTypes.TypeDefinition;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.common.Json;
import com.trs.simulation.config.DemoService;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.evaluation.ObjectiveDraftService;
import com.trs.simulation.history.HistoryService;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.scenario.ScenarioProfiles;
import com.trs.simulation.world.WorldService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises both domains through the same SDK hierarchy and durable execution gateway. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class HierarchicalFrameworkIntegrationTest {
    @Autowired
    ScenarioProfiles profiles;

    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    ModelService models;

    @Autowired
    DemoService demos;

    @Autowired
    HistoryService history;

    @Autowired
    ObjectiveDraftService drafts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recoversAnInterruptedMasterWithoutRepeatingCommittedBankEffects() {
        var world = worlds.create(Examples.bankTransfer());
        var template = profiles.scenario("bank", world, models.mock().id(), "Interrupted transfer", 4);
        var base = template.team();
        var team = new TeamConfig(
                base.rootAgentId(),
                base.maxDelegationsPerRound(),
                base.maxActionsPerRound(),
                base.mockRules().stream()
                        .filter(rule -> "TRANSFER".equals(rule.actionType()))
                        .toList(),
                base.conditions(),
                base.inputActions());
        var scene = worlds.scenario(new Scenario(
                null,
                template.name(),
                world.id(),
                template.agents(),
                4,
                2000,
                team,
                null,
                new SchedulingPolicy(1, "EVENT_DRIVEN", "TICK", 20),
                null));
        var id = runs.create(scene.id()).id();
        var input = profiles.initialInput(world);
        runs.inject(id, "transfer", new ScheduledInput(input.eventType(), input.targetId(), 2, input.parameters()));
        jdbc.execute("""
                CREATE TRIGGER reject_master_completion BEFORE INSERT ON simulation_event
                WHEN NEW.event_type='MASTER_TASK_COMPLETED'
                BEGIN SELECT RAISE(ABORT, 'master completion interrupted'); END
                """);
        try {
            assertThrows(DataAccessException.class, () -> runs.step(id, "interrupted-master"));
        } finally {
            jdbc.execute("DROP TRIGGER reject_master_completion");
        }
        var committed = runs.state(id);
        assertEquals(1L, committed.version());
        assertEquals("NOT_EVALUATED", runs.outcome(id).result());
        assertTrue(runs.tasks(id).stream()
                .anyMatch(task -> task.parentTaskId() == null && "RUNNING".equals(task.status())));
        runs.recover();
        assertEquals(committed, runs.step(id, "interrupted-master"));
        assertEquals(1, runs.get(id).step());
        assertEquals("COMPLETED", runs.get(id).status());
        assertEquals(
                1L,
                committed.entities().stream()
                        .filter(e -> "TRANSACTION".equals(e.type()))
                        .count());
        assertEquals(
                1200000000L,
                committed.entities().stream()
                        .filter(e -> "BANK_ACCOUNT".equals(e.type()))
                        .mapToLong(e -> ((Number) e.properties().get("balanceMinor")).longValue())
                        .sum());
        assertEquals(
                1L,
                runs.events(id, 0).stream()
                        .filter(e -> "ACTION_INPUT_HANDLED".equals(e.eventType()))
                        .count());
    }

    @Test
    void configuredLeakObjectiveFreezesEditedRulesAndRecordsAnEvidenceBackedMasterReview() {
        var world = worlds.create(Examples.informationLeak());
        var model = models.mock().id();
        String objective = "第 1 轮结束时，禁用所有同步通道并说明执行依据。";
        var draft = drafts.generate(world.id(), model, objective, 1);
        assertEquals("MOCK", draft.mode());
        assertFalse(draft.conditions().isEmpty());
        var template = profiles.scenario("information", world, model, "Leak objective", 1);
        var team = template.team();
        var edited = new GoalCondition(
                "sync",
                "同步通道全部关闭",
                "All sync channels closed",
                "!relations.exists(r, r.relationType == 'SYNCS_TO' && r.enabled)",
                "relations.filter(r, r.relationType == 'SYNCS_TO' && r.enabled).map(r, r.id)");
        var configured = new TeamConfig(
                team.rootAgentId(),
                team.maxDelegationsPerRound(),
                team.maxActionsPerRound(),
                team.mockRules(),
                List.of(edited),
                team.inputActions());
        var scene = worlds.scenario(
                new Scenario(null, template.name(), world.id(), template.agents(), 1, 2000, configured, objective));
        var run = runs.create(scene.id());
        assertEquals(objective, runs.get(run.id()).objective());
        runs.seedInput(run.id(), profiles);
        worlds.scenario(
                new Scenario(scene.id(), template.name(), world.id(), template.agents(), 1, 2000, configured, "另一项目标"));
        assertEquals("NOT_EVALUATED", runs.outcome(run.id()).result());
        runs.step(run.id(), "objective-check");

        var outcome = runs.outcome(run.id());
        assertEquals("SUCCEEDED", outcome.result());
        assertEquals("sync", outcome.conditions().getFirst().id());
        assertEquals(List.of(), outcome.conditions().getFirst().evidence());
        var events = runs.events(run.id(), 0);
        var assessment = events.stream()
                .filter(e -> e.eventType().equals("SCENARIO_EVALUATED"))
                .findFirst()
                .orElseThrow();
        var reviewEvent = events.stream()
                .filter(e -> e.eventType().equals("OBJECTIVE_REVIEWED"))
                .findFirst()
                .orElseThrow();
        assertEquals("lead", reviewEvent.agentId());
        assertEquals("MOCK", reviewEvent.payload().get("mode"));
        assertEquals("AVAILABLE", reviewEvent.payload().get("status"));
        var review = Json.read(Json.write(reviewEvent.payload().get("review")), ObjectiveReview.class);
        assertFalse(review.conclusion().isBlank());
        assertTrue(review.evidenceSequences().contains(assessment.sequence()));
        assertTrue(review.evidenceSequences().stream()
                .allMatch(sequence ->
                        events.stream().anyMatch(e -> e.sequence() == sequence && sequence < reviewEvent.sequence())));
        assertEquals(objective, runs.get(run.id()).objective());
    }

    @Test
    void strictClockReassessesTheGraphAfterAnActionChangesItDuringReasoning() {
        var world = worlds.create(Examples.bankTransfer());
        var template = profiles.scenario("bank", world, models.mock().id(), "Reassess after transfer", 5);
        var base = template.team();
        var team = new TeamConfig(
                base.rootAgentId(),
                base.maxDelegationsPerRound(),
                base.maxActionsPerRound(),
                base.mockRules().stream()
                        .filter(rule -> "TRANSFER".equals(rule.actionType()))
                        .toList(),
                base.conditions(),
                base.inputActions());
        var scene = worlds.scenario(new Scenario(
                null,
                template.name(),
                world.id(),
                template.agents(),
                5,
                2000,
                team,
                "Inspect the current simulated account balances.",
                new SchedulingPolicy(1, "EVENT_DRIVEN", "TICK", 20),
                null));
        var id = runs.create(scene.id()).id();
        runs.seedInput(id, profiles);
        runs.inject(id, "next", new ScheduledInput("USER_NOTE", "account_a", 50, Map.of()));
        var afterAction = runs.step(id, "transfer");
        assertTrue(afterAction.version() > 0);
        assertEquals("NOT_EVALUATED", runs.outcome(id).result());
        assertEquals(0L, runs.step(id, "reassess").simulationTime());
        assertEquals(2, runs.get(id).step());
        assertEquals(50L, runs.step(id, "to-next-event").simulationTime());
        assertEquals(
                1L,
                runs.state(id).entities().stream()
                        .filter(e -> "TRANSACTION".equals(e.type()))
                        .count());
    }

    @Test
    void strictClockCommitsBoundInputAndDerivedEventsExactlyOnce() {
        var world = worlds.create(Examples.bankTransfer());
        var template = profiles.scenario("bank", world, models.mock().id(), "Scheduled transfer", 4);
        var scene = worlds.scenario(new Scenario(
                null,
                template.name(),
                world.id(),
                template.agents(),
                4,
                2000,
                template.team(),
                null,
                new SchedulingPolicy(1, "EVENT_DRIVEN", "TICK", 20),
                null));
        var id = runs.create(scene.id()).id();
        var input = profiles.initialInput(world);
        runs.inject(id, "transfer", new ScheduledInput(input.eventType(), input.targetId(), 2, input.parameters()));
        runs.inject(id, "future", new ScheduledInput("USER_NOTE", input.targetId(), 50, Map.of()));
        var state = runs.step(id, "to-transfer");
        assertEquals(2L, state.simulationTime());
        assertEquals(
                1L,
                state.entities().stream()
                        .filter(e -> "TRANSACTION".equals(e.type()))
                        .count());
        var queue = (List<Map<String, Object>>) runs.scheduling(id).get("work");
        assertEquals("COMPLETED", queue.getFirst().get("status"));
        assertTrue(queue.stream()
                .anyMatch(work -> "DOMAIN_EVENT".equals(work.get("type")) && "COMPLETED".equals(work.get("status"))));
        var audit = runs.events(id, 0);
        assertEquals(
                1,
                audit.stream()
                        .filter(e -> "ACTION_INPUT_HANDLED".equals(e.eventType())
                                && "transfer".equals(e.payload().get("inputEventId")))
                        .count());
        assertEquals(state, runs.step(id, "to-transfer"));
        assertEquals(50L, runs.step(id, "to-future").simulationTime());
        assertEquals(
                1L,
                runs.state(id).entities().stream()
                        .filter(e -> "TRANSACTION".equals(e.type()))
                        .count());
    }

    @Test
    void bankDemoUsesRealSdkSubagentsWithConcurrencyOneAndPreservesMoney() {
        var world = worlds.create(Examples.bankTransfer());
        var run = demos.create("bank", world.id(), 4);
        assertEquals("MASTER", runs.agents(run.id()).getFirst().runtime().role());
        assertTrue(runs.agents(run.id()).stream().anyMatch(a -> a.id().equals("bank")));
        assertTrue(runs.agents(run.id()).stream().anyMatch(a -> a.id().equals("risk")));
        runs.step(run.id(), "first");
        var first = runs.state(run.id());
        assertEquals(
                1,
                first.entities().stream()
                        .filter(e -> e.type().equals("TRANSACTION"))
                        .count());
        assertEquals(
                1200000000L,
                first.entities().stream()
                        .filter(e -> e.type().equals("BANK_ACCOUNT"))
                        .mapToLong(e -> ((Number) e.properties().get("balanceMinor")).longValue())
                        .sum());
        assertTrue(runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("SUBTASK_COMPLETED")));
        assertTrue(runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("GRAPH_QUERY_RESULT")));
        assertTrue(runs.tasks(run.id()).stream().anyMatch(t -> t.parentTaskId() != null));
        var transferReceipt = runs.events(run.id(), 0).stream()
                .filter(e -> e.eventType().equals("ACTION_TASK_RECEIPT")
                        && "TRANSFER".equals(e.payload().get("actionType")))
                .findFirst()
                .orElseThrow();
        assertEquals("demo-initial", transferReceipt.payload().get("inputEventId"));
        assertEquals(first, runs.step(run.id(), "first"));
        for (int i = 1; i < 4; i++) runs.step(run.id(), "round-" + i);
        assertEquals(
                1,
                runs.state(run.id()).entities().stream()
                        .filter(e -> e.type().equals("TRANSACTION"))
                        .count());
        assertEquals("SUCCEEDED", runs.outcome(run.id()).result());
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED")
                        && "ASSESS_RISK_CEL".equals(e.payload().get("actionType"))));
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
        assertTrue(runs.state(run.id()).relations().stream()
                .filter(r -> Set.of("SHARED_WITH", "SYNCS_TO").contains(r.relationType()))
                .noneMatch(Relation::enabled));
        assertTrue(runs.tasks(run.id()).stream()
                .filter(t -> t.status().equals("SUCCEEDED"))
                .anyMatch(t -> t.agentId().equals("response")));
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
        assertEquals(
                1,
                runs.state(child.id()).entities().stream()
                        .filter(e -> e.type().equals("TRANSACTION"))
                        .count());
        assertTrue(runs.tasks(child.id()).stream().allMatch(t -> t.runId().equals(child.id())));
    }

    @Test
    void profilesAreConfigurationAndInvalidDelegationRolesAreRejected() {
        assertEquals(
                Set.of("information", "bank"),
                profiles.list().stream()
                        .map(ScenarioProfiles.Profile::domainCode)
                        .collect(java.util.stream.Collectors.toSet()));
        var world = worlds.create(Examples.bankTransfer());
        var scenario = profiles.scenario("bank", world, models.mock().id(), "Configured", 3);
        var root = scenario.agents().getFirst();
        assertTrue(root.allowedActions().isEmpty());
        assertNotNull(scenario.team());
        var bad = new AgentDefinition(
                root.id(),
                root.name(),
                root.goal(),
                root.modelId(),
                List.of("TRANSFER"),
                root.visibleEntityIds(),
                root.maxDecisions(),
                root.runtime());
        var agents = new ArrayList<>(scenario.agents());
        agents.set(0, bad);
        assertThrows(
                IllegalArgumentException.class,
                () -> worlds.scenario(
                        new Scenario(null, "Invalid master", world.id(), agents, 3, 1500, scenario.team())));
    }

    @Test
    void newRolesAndCustomObjectActionUseTheFrameworkWithoutDomainDispatchCode() {
        var template = Examples.informationLeak();
        var action = new ActionDefinition(
                "CUSTOM_STAMP",
                "自定义确认",
                "Custom confirmation",
                "测试配置驱动的场景",
                Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false),
                List.of(),
                "CUSTOM",
                new ActionProgram(
                        1,
                        List.of(new ActionCondition("!has(target.properties.customDone)", "尚未确认", "Not confirmed")),
                        List.of(new ActionOperation(
                                "SET_PROPERTY",
                                Map.of("object", "target.id", "property", "'customDone'", "value", "true"))),
                        Map.of()));
        var types = template.ontology().types().stream()
                .map(t -> {
                    var definitions = new ArrayList<>(t.actions());
                    if (t.type().equals("PERSON")) definitions.add(action);
                    return new TypeDefinition(t.targetKind(), t.type(), definitions);
                })
                .toList();
        var world = worlds.create(new World(
                null,
                "Custom",
                template.domainCode(),
                template.entityTypes(),
                template.relationTypes(),
                template.entities(),
                template.relations(),
                1,
                new Ontology(3, types)));
        var visible = List.of(world.entities().stream()
                .filter(e -> e.type().equals("PERSON"))
                .findFirst()
                .orElseThrow()
                .id());
        var model = models.mock().id();
        var agents = List.of(
                new AgentDefinition(
                        "planner",
                        "Planner",
                        "Arrange confirmation",
                        model,
                        List.of(),
                        visible,
                        5,
                        new AgentRuntime("MASTER", "planner", "Plan", List.of("evidence", "operator"))),
                new AgentDefinition(
                        "evidence",
                        "Evidence",
                        "Calculate graph metrics",
                        model,
                        List.of(),
                        visible,
                        5,
                        new AgentRuntime("QUERY", "delegate_evidence", "Return metrics", List.of("graph_metrics"))),
                new AgentDefinition(
                        "operator",
                        "Operator",
                        "Confirm object",
                        model,
                        List.of("CUSTOM_STAMP"),
                        visible,
                        5,
                        new AgentRuntime("ACTION", "delegate_operator", "Confirm", List.of("graph_get_entity"))));
        var team = new TeamConfig(
                "planner",
                3,
                1,
                List.of(new MockActionRule("CUSTOM_STAMP", null, "true")),
                List.of(new GoalCondition(
                        "confirmed",
                        "对象完成确认",
                        "Object confirmed",
                        "entities.exists(e, has(e.properties.customDone) && e.properties.customDone == true)")));
        var run = runs.create(
                worlds.scenario(new Scenario(null, "Configured third workflow", world.id(), agents, 2, 1000, team))
                        .id());
        runs.step(run.id(), "one");
        runs.step(run.id(), "two");
        assertEquals("SUCCEEDED", runs.outcome(run.id()).result());
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("GRAPH_QUERY_RESULT")
                        && "graph_metrics".equals(e.payload().get("tool"))));
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED")
                        && "CUSTOM_STAMP".equals(e.payload().get("actionType"))));
    }
}
