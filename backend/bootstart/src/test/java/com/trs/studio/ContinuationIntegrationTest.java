package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.trs.simulation.AgentScopeDecisionProvider;
import com.trs.simulation.BankEnvironment;
import com.trs.simulation.DemoService;
import com.trs.simulation.Examples;
import com.trs.simulation.HistoryService;
import com.trs.simulation.ModelService;
import com.trs.simulation.RunService;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.WorldService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Mock-backed runtime, replay, branch and financial-domain acceptance. @author agent */
@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class ContinuationIntegrationTest {
    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    HistoryService history;

    @Autowired
    ModelService models;

    @Autowired
    DemoService demos;

    @MockitoSpyBean
    AgentScopeDecisionProvider provider;

    @Test
    void bankDemoActuallyExecutesCelScoringAndKeepsMoneyConserved() {
        var world = worlds.create(Examples.bankTransfer());
        var run = demos.create("bank", world.id(), 8);
        runs.step(run.id(), "demo-cel");
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED")
                        && e.payload().get("actionType").equals("ASSESS_RISK_CEL")));
        assertTrue(runs.state(run.id()).entities().stream()
                .anyMatch(e -> Long.valueOf(80).equals(e.properties().get("riskScore"))
                        || Integer.valueOf(80).equals(e.properties().get("riskScore"))));
        assertEquals(
                1200000000L,
                runs.state(run.id()).entities().stream()
                        .filter(e -> e.type().equals("BANK_ACCOUNT"))
                        .mapToLong(e -> ((Number) e.properties().get("balanceMinor")).longValue())
                        .sum());
        assertFalse(
                runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("ACTION_REJECTED")));
    }

    private Scenario leak() {
        var model = models.mock();
        var w = worlds.create(Examples.informationLeak());
        var visible = w.entities().stream().map(Entity::id).toList();
        return worlds.scenario(new Scenario(
                null,
                "mock leak",
                w.id(),
                List.of(
                        new AgentDefinition(
                                "investigation",
                                "Investigator",
                                "Investigate spread",
                                model.id(),
                                List.of("MARK_REVIEWED", "ANALYZE_RELATIONS", "END"),
                                visible,
                                10),
                        new AgentDefinition(
                                "response",
                                "Response",
                                "Reduce risk",
                                model.id(),
                                List.of("DISABLE_SYNC", "REVOKE_SHARING", "MARK_REVIEWED", "END"),
                                visible,
                                10)),
                10,
                500));
    }

    @Test
    void simulatedModelUsesSdkToolsAndCurrentStateForFiveRounds() {
        var run = runs.create(leak().id());
        for (int n = 0; n < 5; n++) runs.step(run.id(), "round-" + n);
        var events = runs.events(run.id(), 0);
        assertEquals(
                10,
                events.stream()
                        .filter(e -> e.eventType().equals("AGENT_DECISION"))
                        .count());
        assertTrue(events.stream()
                        .filter(e -> e.eventType().equals("WORLD_STATE_CHANGED"))
                        .count()
                >= 3);
        assertFalse(events.stream().anyMatch(e -> e.eventType().equals("ACTION_REJECTED")));
        assertTrue(events.stream().anyMatch(e -> e.eventType().equals("AGENT_TOOL_CALL")));
        assertTrue(events.stream()
                .filter(e -> e.eventType().equals("AGENT_STARTED"))
                .allMatch(e -> "MOCK".equals(e.payload().get("modelMode"))));
    }

    @Test
    void simulatedAgentEndsWhenItsWorldDefinesNoObjectActions() {
        var template = Examples.informationLeak();
        var world = worlds.create(new World(
                null,
                "No actions",
                "information",
                template.entityTypes(),
                template.relationTypes(),
                template.entities(),
                template.relations(),
                1,
                new Ontology(2, List.of())));
        var agent = new AgentDefinition(
                "a",
                "A",
                "Analyze",
                models.mock().id(),
                List.of("ANALYZE_RELATIONS", "END"),
                List.of("document_f"),
                10);
        var run = runs.create(worlds.scenario(new Scenario(null, "No actions", world.id(), List.of(agent), 5, 200))
                .id());
        runs.step(run.id(), "one");
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED")
                        && e.payload().get("actionType").equals("END")));
        assertFalse(
                runs.events(run.id(), 0).stream().anyMatch(e -> e.eventType().equals("ACTION_REJECTED")));
    }

    @Test
    void forkCopiesExactCheckpointWithoutChangingParentOrCallingModelsDuringReplay() {
        var parent = runs.create(leak().id());
        runs.step(parent.id(), "first");
        var baseline = runs.state(parent.id());
        var point = history.checkpoints(parent.id()).getLast();
        var child = history.fork(parent.id(), point.sequence(), "intervention", "fork-one");
        assertEquals(
                child.id(),
                history.fork(parent.id(), point.sequence(), "intervention", "fork-one")
                        .id());
        assertEquals(baseline.entities(), runs.state(child.id()).entities());
        clearInvocations(provider);
        assertEquals(
                baseline.entities(),
                history.stateAt(parent.id(), point.sequence()).entities());
        verify(provider, never()).decide(any(), any());
        runs.intervene(
                child.id(),
                "manual-one",
                "response",
                new Decision("REVOKE_SHARING", "share_ac", Map.of(), "branch intervention"),
                baseline.version());
        assertEquals(baseline, runs.state(parent.id()));
        assertFalse(
                history.compare(parent.id(), child.id()).relationDifferences().isEmpty());
        assertEquals(0, history.stateAt(parent.id(), 0).version());
        assertEquals(1, runs.events(child.id(), 0).getFirst().sequence());
    }

    @Test
    void bankRunsInSameRuntimeAndConservesFunds() {
        var model = models.mock();
        var w = worlds.create(Examples.bankTransfer());
        var ids = w.entities().stream().map(Entity::id).toList();
        var scenario = worlds.scenario(new Scenario(
                null,
                "bank",
                w.id(),
                List.of(
                        new AgentDefinition(
                                "bank",
                                "Bank",
                                "Process transfer",
                                model.id(),
                                List.of("TRANSFER", "ANALYZE_ACCOUNT", "END"),
                                ids,
                                10),
                        new AgentDefinition(
                                "risk",
                                "Risk",
                                "Flag large transfers",
                                model.id(),
                                List.of("FLAG_RISK", "ANALYZE_ACCOUNT", "END"),
                                ids,
                                10)),
                5,
                300));
        var run = runs.create(scenario.id());
        runs.inject(
                run.id(),
                "initial",
                new ScheduledInput(
                        "BANK_TRANSFER",
                        "account_b",
                        0,
                        Map.of("sourceId", "account_a", "amount", "5000000.00", "currency", "CNY")));
        runs.step(run.id(), "first");
        var accounts = runs.state(run.id()).entities();
        assertEquals(
                500000000L,
                ((Number) accounts.stream()
                                .filter(e -> e.id().equals("account_a"))
                                .findFirst()
                                .orElseThrow()
                                .properties()
                                .get("balanceMinor"))
                        .longValue());
        assertEquals(
                700000000L,
                ((Number) accounts.stream()
                                .filter(e -> e.id().equals("account_b"))
                                .findFirst()
                                .orElseThrow()
                                .properties()
                                .get("balanceMinor"))
                        .longValue());
        var bank = new BankEnvironment();
        var agent = scenario.agents().getFirst();
        var state = runs.state(run.id());
        assertFalse(bank.execute(
                        agent,
                        new Decision(
                                "TRANSFER",
                                "account_b",
                                Map.of("sourceId", "account_a", "amount", "99999999.00", "currency", "CNY"),
                                "insufficient"),
                        state)
                .accepted());
        assertFalse(bank.execute(
                        agent,
                        new Decision(
                                "TRANSFER",
                                "account_a",
                                Map.of("sourceId", "account_a", "amount", "1.00", "currency", "CNY"),
                                "self transfer"),
                        state)
                .accepted());
        assertFalse(bank.execute(
                        agent,
                        new Decision(
                                "TRANSFER",
                                "account_b",
                                Map.of("sourceId", "account_a", "amount", "-1.00", "currency", "CNY"),
                                "negative"),
                        state)
                .accepted());
    }

    @Test
    void branchClockContinuesAndLateEventsAreNotCopied() {
        var parent = runs.create(leak().id());
        runs.step(parent.id(), "one");
        var position = runs.events(parent.id(), 0).getLast().sequence();
        runs.inject(parent.id(), "late", new ScheduledInput("USER_NOTE", "document_f", 3, Map.of()));
        var child = history.fork(parent.id(), position, "historical", "historical-one");
        long before = runs.state(child.id()).simulationTime();
        runs.step(child.id(), "child-step");
        assertTrue(runs.state(child.id()).simulationTime() > before);
        assertEquals(1, runs.get(child.id()).step());
        assertFalse(runs.events(child.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("SIMULATION_EVENT_CREATED")
                        && "late".equals(e.payload().get("scheduledEventId"))));
        assertTrue(runs.events(child.id(), 0).stream().allMatch(e -> e.simulationTime() >= before));
    }

    @Test
    void bankRejectsInvalidInitialBalancesAndFractionalMinorUnits() {
        var w = Examples.bankTransfer();
        var invalid = new ArrayList<Entity>(w.entities());
        invalid.set(0, new Entity("account_a", "BANK_ACCOUNT", "Bad", Map.of("balanceMinor", -1, "currency", "CNY")));
        assertThrows(
                IllegalArgumentException.class,
                () -> worlds.create(new World(
                        null,
                        w.name(),
                        w.domainCode(),
                        w.entityTypes(),
                        w.relationTypes(),
                        invalid,
                        w.relations(),
                        1)));
        var domain = new BankEnvironment();
        var agent = new AgentDefinition(
                "bank", "bank", "transfer", "mock", List.of("TRANSFER"), List.of("account_a", "account_b"), 5);
        assertFalse(domain.execute(
                        agent,
                        new Decision(
                                "TRANSFER",
                                "account_b",
                                Map.of("sourceId", "account_a", "amount", "0.001", "currency", "CNY"),
                                "precision"),
                        new State("bank", 0, 0, w.entities(), w.relations()))
                .accepted());
    }

    @Test
    void runListDoesNotNestConnectionRequests() {
        runs.create(leak().id());
        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> assertFalse(runs.list().isEmpty()));
    }

    @Test
    void pauseResumeAndStopControlComputationAtRoundBoundaries() throws Exception {
        var run = runs.create(leak().id());
        runs.resume(run.id());
        runs.pause(run.id());
        int paused = runs.get(run.id()).step();
        long pausedRuntime = runs.get(run.id()).activeRuntimeMs();
        Thread.sleep(150);
        assertEquals(paused, runs.get(run.id()).step());
        assertEquals(pausedRuntime, runs.get(run.id()).activeRuntimeMs());
        runs.resume(run.id());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (runs.get(run.id()).step() <= paused) Thread.sleep(10);
        });
        runs.pause(run.id());
        runs.stop(run.id());
        assertEquals("STOPPED", runs.get(run.id()).status());
        assertThrows(IllegalStateException.class, () -> runs.step(run.id(), "after-stop"));
    }
}
