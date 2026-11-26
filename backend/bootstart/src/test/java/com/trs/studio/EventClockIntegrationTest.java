package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.agent.AgentScopeDecisionProvider;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.history.HistoryService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.world.WorldService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies the strict clock and the durable queue through the normal runtime. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class EventClockIntegrationTest {
    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    HistoryService history;

    @Autowired
    JdbcTemplate db;

    @MockitoBean
    AgentScopeDecisionProvider decisions;

    private String world;

    @BeforeEach
    void setup() {
        world = worlds.create(Examples.informationLeak()).id();
        when(decisions.decide(any(), any()))
                .thenReturn(new Decision("ANALYZE_RELATIONS", "document_f", Map.of(), "review"));
    }

    @Test
    void jumpsToDueTimesAndDrainsTheWholeTimeSliceInStableOrder() {
        var id = create(25, 100L);
        inject(id, "initial", 2, 0);
        var order = new ArrayList<String>();
        for (int i = 14; i >= 0; i--) {
            inject(id, "item-" + i, 5, i == 1 ? 9 : 0);
            if (i != 1) order.add("item-" + i);
        }
        order.addFirst("item-1");
        inject(id, "last", 100, 0);
        assertEquals(2L, runs.step(id, "advance-2").simulationTime());
        assertEquals(5L, runs.step(id, "advance-5").simulationTime());
        var atFive = delivered(id).stream()
                .filter(event -> event.simulationTime() == 5)
                .toList();
        assertEquals(
                order,
                atFive.stream()
                        .map(event -> event.payload().get("scheduledEventId"))
                        .toList());
        assertEquals(100L, runs.step(id, "advance-100").simulationTime());
        assertEquals(17, delivered(id).size());
        assertEquals(0L, runs.state(id).version());
        inject(id, "initial", 2, 0);
        assertThrows(IllegalStateException.class, () -> inject(id, "initial", 2, 9));
        assertEquals(17, delivered(id).size());
    }

    @Test
    void preservesWorkAtTheBudgetBoundary() {
        var id = create(2, null);
        inject(id, "one", 5, 0);
        inject(id, "two", 5, 0);
        inject(id, "three", 5, 0);
        assertEquals(5L, runs.step(id, "advance").simulationTime());
        assertEquals(2, delivered(id).size());
        assertEquals("MAX_WORK_PER_TIME", runs.get(id).terminationReason());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=? AND delivered=0",
                        Integer.class,
                        id));
    }

    @Test
    void recoversAClaimAfterTransactionalFailureAndForksTheExactPendingQueue() {
        var id = create(10, 100L);
        inject(id, "first", 2, 0);
        inject(id, "second", 100, 0);
        long beforeFuture = runs.events(id, 0).getLast().sequence();
        inject(id, "future", 101, 0);
        db.execute("""
                CREATE TRIGGER reject_delivery BEFORE INSERT ON simulation_event
                WHEN NEW.event_type='SIMULATION_EVENT_CREATED' BEGIN
                    SELECT RAISE(ABORT, 'test delivery failure');
                END
                """);
        try {
            assertThrows(DataAccessException.class, () -> runs.step(id, "retryable"));
            assertEquals(0L, runs.state(id).simulationTime());
            assertEquals(0, delivered(id).size());
        } finally {
            db.execute("DROP TRIGGER reject_delivery");
        }
        runs.recover();
        assertEquals(2L, runs.step(id, "retryable").simulationTime());
        var afterFirst = runs.events(id, 0).getLast().sequence();
        assertEquals(0L, history.stateAt(id, beforeFuture).simulationTime());
        assertEquals(2L, history.stateAt(id, afterFirst).simulationTime());
        assertEquals(
                history.stateAt(id, beforeFuture).version(),
                history.stateAt(id, afterFirst).version());
        var branch = history.fork(id, beforeFuture, "before the future input", "fork");
        assertNotEquals(id, branch.id());
        assertEquals(2L, runs.step(branch.id(), "fork-2").simulationTime());
        assertEquals(100L, runs.step(branch.id(), "fork-100").simulationTime());
        assertEquals(
                List.of("first", "second"),
                delivered(branch.id()).stream()
                        .map(event -> event.payload().get("scheduledEventId"))
                        .toList());
        assertEquals(2L, runs.state(id).simulationTime());
        assertEquals(1, delivered(id).size());
    }

    @Test
    void distinguishesAnEmptyQueueFromAConfiguredHorizonWithFutureWork() {
        var empty = create(10, 30L);
        runs.step(empty, "idle");
        assertEquals("QUIESCENT", runs.get(empty).terminationReason());
        assertEquals(0L, runs.state(empty).simulationTime());
        var future = create(10, 30L);
        inject(future, "later", 50, 0);
        assertEquals(30L, runs.step(future, "horizon").simulationTime());
        assertEquals("HORIZON_REACHED", runs.get(future).terminationReason());
        assertEquals("HORIZON_REACHED", runs.scheduling(future).get("queueState"));
        assertEquals(
                "PENDING",
                ((List<Map<String, Object>>) runs.scheduling(future).get("work"))
                        .getFirst()
                        .get("status"));
        assertEquals(0, delivered(future).size());
    }

    @Test
    void waitsForCurrentWorkWithoutAdvancingTheClockAndRevokesItsCommitOnStop() throws Exception {
        var id = create(10, null);
        inject(id, "at-two", 2, 0);
        inject(id, "later", 100, 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(decisions.decide(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return new Decision("ANALYZE_RELATIONS", "document_f", Map.of(), "late");
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> runs.step(id, "blocked"));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(2L, runs.state(id).simulationTime());
                assertEquals("STOPPED", runs.stop(id).status());
            } finally {
                release.countDown();
            }
            assertEquals(2L, running.get(10, TimeUnit.SECONDS).simulationTime());
        }
        assertEquals("USER_STOPPED", runs.get(id).terminationReason());
        assertEquals(1, delivered(id).size());
        assertEquals(
                "CANCELLED",
                db.queryForObject(
                        "SELECT work_status FROM simulation_scheduled_event WHERE run_id=? AND event_id='later'",
                        String.class,
                        id));
        assertTrue(
                runs.events(id, 0).stream().noneMatch(event -> event.eventType().equals("ACTION_ACCEPTED")));
    }

    private String create(int budget, Long horizon) {
        var legacy = new Scenario(
                null,
                "Clock",
                world,
                List.of(new AgentDefinition(
                        "reviewer",
                        "Review",
                        "Observe",
                        "model",
                        List.of("ANALYZE_RELATIONS"),
                        List.of("document_f"),
                        20)),
                20,
                2000);
        var body = new java.util.LinkedHashMap<>(Json.read(Json.write(legacy), Map.class));
        body.put(
                "scheduling",
                Map.of("contractVersion", 1, "mode", "EVENT_DRIVEN", "timeUnit", "TICK", "maxWorkPerTime", budget));
        if (horizon != null) body.put("evaluationPolicy", Map.of("horizonSimulationTime", horizon));
        return runs.create(worlds.scenario(Json.read(Json.write(body), Scenario.class))
                        .id())
                .id();
    }

    private void inject(String id, String key, long time, int priority) {
        var input = Json.read(
                Json.write(Map.of(
                        "eventType",
                        "USER_NOTE",
                        "targetId",
                        "document_f",
                        "simulationTime",
                        time,
                        "parameters",
                        Map.of(),
                        "priority",
                        priority)),
                ScheduledInput.class);
        runs.inject(id, key, input);
    }

    private List<Event> delivered(String id) {
        return runs.events(id, 0).stream()
                .filter(event -> event.eventType().equals("SIMULATION_EVENT_CREATED"))
                .toList();
    }
}
