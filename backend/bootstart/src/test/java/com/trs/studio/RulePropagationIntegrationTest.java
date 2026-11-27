package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import com.trs.simulation.SimulationTypes.ActionCondition;
import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.ActionOperation;
import com.trs.simulation.SimulationTypes.ActionProgram;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.SchedulingPolicy;
import com.trs.simulation.SimulationTypes.TypeDefinition;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.agent.AgentScopeDecisionProvider;
import com.trs.simulation.common.Json;
import com.trs.simulation.history.HistoryService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.world.WorldService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exercises deterministic rule propagation through the normal Action and queue APIs. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class RulePropagationIntegrationTest {
    @LocalServerPort
    int port;

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

    private final HttpClient http =
            HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @Test
    void propagatesAtT1AndT2WithoutModelsAndFreezesRuleRevisions() throws Exception {
        var world = fixture(false);
        var run = setup(world);
        var frozen = get("/runs/" + run.id() + "/rules");
        @SuppressWarnings("unchecked")
        var definitions = (List<Map<String, Object>>) frozen.get("rules");
        String ruleId = (String) definitions.getFirst().get("id");
        var disabled = new LinkedHashMap<>(definitions.getFirst());
        disabled.put("enabled", false);
        request("PUT", "/rules/" + ruleId, disabled, 200);
        post("/rules/" + ruleId + "/publish", Map.of());
        assertEquals(frozen, get("/runs/" + run.id() + "/rules"));
        fail(run.id());
        runs.intervene(
                run.id(),
                "no-op",
                "operator",
                new Decision("FAIL_DEVICE", "A", Map.of(), "already failed", "ENTITY"),
                1);
        assertEquals(1L, runs.state(run.id()).version());
        assertEquals(
                1L,
                runs.events(run.id(), 0).stream()
                        .filter(e -> "DOMAIN_EVENT_SCHEDULED".equals(e.eventType()))
                        .count());
        long sequence = runs.events(run.id(), 0).getLast().sequence();
        var fork = history.fork(run.id(), sequence, "isolated", "fork");
        drain(run.id());
        assertEquals(2L, runs.state(run.id()).simulationTime());
        assertEquals("FAILED", status(run.id(), "A"));
        assertEquals("UNAVAILABLE", status(run.id(), "B"));
        assertEquals("INTERRUPTED", status(run.id(), "C"));
        assertEquals("INTERRUPTED", status(run.id(), "D"));
        assertEquals("RUNNING", status(run.id(), "E"));
        var accepted = runs.events(run.id(), 0).stream()
                .filter(e -> "ACTION_ACCEPTED".equals(e.eventType())
                        && "SYSTEM_RULE".equals(e.payload().get("actionOrigin")))
                .toList();
        assertEquals(
                List.of(1L, 2L, 2L),
                accepted.stream().map(Event::simulationTime).toList());
        assertEquals(
                List.of("B", "C", "D"),
                accepted.stream().map(e -> e.payload().get("targetId")).toList());
        assertTrue(accepted.stream()
                .allMatch(e ->
                        e.payload().containsKey("causeEventRef") && e.payload().containsKey("ruleRevision")));
        assertEquals("AVAILABLE", status(fork.id(), "B"));
        drain(fork.id());
        assertEquals("INTERRUPTED", status(fork.id(), "D"));
        assertEquals(1L, history.stateAt(run.id(), sequence).version());
        verifyNoInteractions(decisions);
    }

    @Test
    void matchesOnlyExplicitRelationsAndDoesNotWriteDuringPreview() throws Exception {
        var world = fixture(true);
        var run = setup(world);
        var rule = body(world.id(), "DEVICE", "SYSTEM", "MARK_UNAVAILABLE", "'B'", "1");
        var before = runs.state(run.id());
        post(
                "/rules/preview",
                Map.of("rule", rule, "event", Map.of("objectId", "A", "oldValue", "NORMAL", "newValue", "FAILED")));
        assertEquals(before, runs.state(run.id()));
        rule.put("conditionExpression", "unknownVariable()");
        request("POST", "/rules/validate", rule, 400);
        fail(run.id());
        drain(run.id());
        assertEquals("AVAILABLE", status(run.id(), "B"));
        assertEquals(1L, runs.state(run.id()).version());
        verifyNoInteractions(decisions);
    }

    @Test
    void rechecksActionPreconditionsAtTheDueTimeWithoutRollingBackTheParent() throws Exception {
        var run = setup(fixture(false));
        fail(run.id());
        assertEquals(0L, runs.step(run.id(), "match").simulationTime());
        runs.intervene(
                run.id(),
                "enable-backup",
                "operator",
                new Decision("ENABLE_BACKUP", "B", Map.of(), "backup restored", "ENTITY"),
                1);
        drain(run.id());
        assertEquals("FAILED", status(run.id(), "A"));
        assertEquals("AVAILABLE", status(run.id(), "B"));
        assertEquals(2L, runs.state(run.id()).version());
        var rejected = runs.events(run.id(), 0).stream()
                .filter(e -> "ACTION_REJECTED".equals(e.eventType())
                        && "SYSTEM_RULE".equals(e.payload().get("actionOrigin")))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, rejected.simulationTime());
        assertEquals("B", rejected.payload().get("targetId"));
    }

    @Test
    void userActionCannotDeclareSystemRuleProvenance() throws Exception {
        var run = setup(fixture(false));
        request(
                "POST",
                "/runs/" + run.id() + "/actions",
                Map.of(
                        "agentId",
                        "operator",
                        "expectedVersion",
                        0,
                        "actionOrigin",
                        "SYSTEM_RULE",
                        "decision",
                        new Decision("FAIL_DEVICE", "A", Map.of(), "simulated", "ENTITY")),
                400);
        assertEquals(0L, runs.state(run.id()).version());
    }

    @ParameterizedTest
    @CsvSource({"6,MAX_WORK_PER_TIME,3", "100,MAX_RULE_DEPTH,16"})
    void capsZeroDelayOscillationAndRetainsThePendingWork(int budget, String reason, long accepted) throws Exception {
        var world = fixture(false);
        post("/auth/login", Map.of("username", "admin", "password", "123456"));
        var whenFailed = body(world.id(), "DEVICE", "DEVICE", "RESET_DEVICE", "'A'", "0");
        var whenRestored = body(world.id(), "DEVICE", "DEVICE", "FAIL_DEVICE", "'A'", "0");
        whenRestored.put("conditionExpression", "event.oldValue == 'FAILED' && event.newValue == 'NORMAL'");
        var run = createRun(world, List.of(publish(whenFailed), publish(whenRestored)), budget);
        fail(run.id());
        var state = runs.step(run.id(), "bounded");
        assertEquals(0L, state.simulationTime());
        assertEquals("BUDGET_EXHAUSTED", runs.get(run.id()).status());
        assertEquals(reason, runs.get(run.id()).terminationReason());
        assertEquals(
                accepted,
                runs.events(run.id(), 0).stream()
                        .filter(e -> "ACTION_ACCEPTED".equals(e.eventType())
                                && "SYSTEM_RULE".equals(e.payload().get("actionOrigin")))
                        .count());
        assertEquals("PENDING", runs.scheduling(run.id()).get("queueState"));
    }

    @Test
    void aRuleCannotSelectAHiddenTargetEvenWhenItsTypeIsGranted() throws Exception {
        var world = fixture(false);
        post("/auth/login", Map.of("username", "admin", "password", "123456"));
        var rule = body(world.id(), "DEVICE", "PROCESS", "INTERRUPT_PROCESS", "'E'", "1");
        var run = createRun(world, List.of(publish(rule)), 30);
        fail(run.id());
        drain(run.id());
        assertEquals("RUNNING", status(run.id(), "E"));
        assertEquals(1L, runs.state(run.id()).version());
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> "RULE_MATCH_RECORDED".equals(e.eventType())
                        && "RULE_EVALUATION_FAILED".equals(e.payload().get("code"))));
    }

    @Test
    void rollsBackRuleConsumptionWithSchedulingFailureAndResumesExactlyOnce() throws Exception {
        var run = setup(fixture(false));
        fail(run.id());
        db.execute("""
                CREATE TRIGGER reject_rule_queue BEFORE INSERT ON simulation_event
                WHEN NEW.event_type='RULE_ACTION_SCHEDULED'
                BEGIN SELECT RAISE(ABORT,'enqueue failure'); END
                """);
        try {
            assertThrows(DataAccessException.class, () -> runs.step(run.id(), "retry-consume"));
        } finally {
            db.execute("DROP TRIGGER reject_rule_queue");
        }
        assertEquals(1L, runs.state(run.id()).version());
        assertTrue(runs.events(run.id(), 0).stream().noneMatch(e -> "RULE_ACTION_SCHEDULED".equals(e.eventType())));
        runs.recover();
        runs.step(run.id(), "retry-consume");
        db.execute("""
                CREATE TRIGGER reject_rule_completion BEFORE INSERT ON simulation_event
                WHEN NEW.event_type='RULE_ACTION_PROCESSED'
                BEGIN SELECT RAISE(ABORT,'completion failure'); END
                """);
        try {
            assertThrows(DataAccessException.class, () -> runs.step(run.id(), "first-rule-action"));
        } finally {
            db.execute("DROP TRIGGER reject_rule_completion");
        }
        assertEquals(0L, runs.state(run.id()).simulationTime());
        assertEquals(1L, runs.state(run.id()).version());
        assertEquals("AVAILABLE", status(run.id(), "B"));
        assertEquals(1L, runs.step(run.id(), "first-rule-action").simulationTime());
        assertEquals(2L, runs.state(run.id()).version());
        db.update("""
                UPDATE simulation_scheduled_event SET delivered=0,work_status='PENDING',completed_sequence=NULL
                WHERE run_id=? AND auto_action=1 AND simulation_time=1
                """, run.id());
        assertEquals(2L, runs.step(run.id(), "duplicate-rule-delivery").version());
        drain(run.id());
        assertEquals(4L, runs.state(run.id()).version());
        assertEquals(
                3L,
                runs.events(run.id(), 0).stream()
                        .filter(e -> "RULE_ACTION_SCHEDULED".equals(e.eventType()))
                        .count());
    }

    private Run setup(World world) throws Exception {
        post("/auth/login", Map.of("username", "admin", "password", "123456"));
        var first = body(
                world.id(),
                "DEVICE",
                "SYSTEM",
                "MARK_UNAVAILABLE",
                "relations.filter(r, r.enabled && r.relationType == 'DEPENDS_ON' && r.targetId == event.objectId)"
                        + ".map(r, r.sourceId).filter(id, entities.exists(e, e.id == id && !e.properties.hasBackup))",
                "1");
        var second = body(
                world.id(),
                "SYSTEM",
                "PROCESS",
                "INTERRUPT_PROCESS",
                "relations.filter(r, r.enabled && r.relationType == 'DEPENDS_ON' && r.targetId == event.objectId)"
                        + ".map(r, r.sourceId)",
                "1");
        return createRun(world, List.of(publish(first), publish(second)), 40);
    }

    private Run createRun(World world, List<String> ids, int maxWork) throws Exception {
        var scene = post(
                "/scenarios",
                Map.of(
                        "name",
                        "ECA",
                        "worldId",
                        world.id(),
                        "maxSteps",
                        10,
                        "maxEvents",
                        500,
                        "agents",
                        List.of(new AgentDefinition(
                                "operator",
                                "Operator",
                                "Observe",
                                "mock",
                                List.of("FAIL_DEVICE", "ENABLE_BACKUP"),
                                List.of("A", "B"),
                                10)),
                        "scheduling",
                        new SchedulingPolicy(1, "EVENT_DRIVEN", "TICK", maxWork),
                        "ruleIds",
                        ids));
        return runs.create((String) scene.get("id"));
    }

    private String publish(Map<String, Object> rule) throws Exception {
        var draft = post("/rules", rule);
        String id = (String) draft.get("id");
        post("/rules/" + id + "/publish", Map.of());
        return id;
    }

    private void fail(String id) {
        runs.intervene(id, "fail", "operator", new Decision("FAIL_DEVICE", "A", Map.of(), "simulation", "ENTITY"), 0);
    }

    private void drain(String id) {
        for (int step = 0; step < 10 && "PAUSED".equals(runs.get(id).status()); step++) runs.step(id, "drain-" + step);
        assertEquals("COMPLETED", runs.get(id).status());
    }

    private Object status(String id, String entity) {
        return runs.state(id).entities().stream()
                .filter(e -> entity.equals(e.id()))
                .findFirst()
                .orElseThrow()
                .properties()
                .get("status");
    }

    private Map<String, Object> body(
            String world, String source, String target, String action, String expression, String delay) {
        var rule = new LinkedHashMap<String, Object>();
        rule.put("worldId", world);
        rule.put("name", action);
        rule.put("scope", "WORLD");
        rule.put("enabled", true);
        rule.put("priority", 10);
        rule.put("trigger", Map.of("eventType", "PROPERTY_CHANGED", "objectType", source, "property", "status"));
        rule.put(
                "conditionExpression",
                source.equals("DEVICE") ? "event.newValue == 'FAILED'" : "event.newValue == 'UNAVAILABLE'");
        rule.put(
                "actions",
                List.of(Map.of(
                        "actionType",
                        action,
                        "targetType",
                        target,
                        "targetExpression",
                        expression,
                        "parameterExpressions",
                        Map.of(),
                        "delayExpression",
                        delay)));
        rule.put(
                "grant",
                Map.of(
                        "visibleEntityIds",
                        List.of("A", "B", "C", "D"),
                        "targetTypes",
                        List.of(target),
                        "allowedActions",
                        List.of(action)));
        return rule;
    }

    private World fixture(boolean backup) {
        return worlds.create(new World(
                null,
                "Fixture",
                "information",
                List.of("DEVICE", "SYSTEM", "PROCESS"),
                List.of("DEPENDS_ON"),
                List.of(
                        entity("A", "DEVICE", "NORMAL"),
                        new Entity("B", "SYSTEM", "B", Map.of("status", "AVAILABLE", "hasBackup", backup)),
                        entity("C", "PROCESS", "RUNNING"),
                        entity("D", "PROCESS", "RUNNING"),
                        entity("E", "PROCESS", "RUNNING")),
                List.of(edge("ba", "B", "A"), edge("cb", "C", "B"), edge("db", "D", "B")),
                0,
                new Ontology(
                        3,
                        List.of(
                                new TypeDefinition(
                                        "ENTITY",
                                        "DEVICE",
                                        List.of(
                                                action("FAIL_DEVICE", "status", "'FAILED'", List.of()),
                                                action("RESET_DEVICE", "status", "'NORMAL'", List.of()))),
                                new TypeDefinition(
                                        "ENTITY",
                                        "SYSTEM",
                                        List.of(
                                                action(
                                                        "MARK_UNAVAILABLE",
                                                        "status",
                                                        "'UNAVAILABLE'",
                                                        List.of(new ActionCondition(
                                                                "!target.properties.hasBackup",
                                                                "Backup available",
                                                                "Backup available"))),
                                                action("ENABLE_BACKUP", "hasBackup", "true", List.of()))),
                                new TypeDefinition(
                                        "ENTITY",
                                        "PROCESS",
                                        List.of(action("INTERRUPT_PROCESS", "status", "'INTERRUPTED'", List.of())))))));
    }

    private ActionDefinition action(String action, String property, String value, List<ActionCondition> conditions) {
        var schema = Map.<String, Object>of(
                "type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false);
        return new ActionDefinition(
                action,
                action,
                action,
                action,
                schema,
                List.of(),
                "CUSTOM",
                new ActionProgram(
                        1,
                        conditions,
                        List.of(new ActionOperation(
                                "SET_PROPERTY",
                                Map.of("object", "target.id", "property", "'" + property + "'", "value", value))),
                        Map.of()));
    }

    private Entity entity(String id, String type, String status) {
        return new Entity(id, type, id, Map.of("status", status));
    }

    private Relation edge(String id, String source, String target) {
        return new Relation(id, source, target, "DEPENDS_ON", Map.of(), true, "FACT", "fixture");
    }

    private Map<String, Object> post(String path, Object value) throws Exception {
        return request("POST", path, value, 200);
    }

    private Map<String, Object> get(String path) throws Exception {
        return request("GET", path, null, 200);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, String path, Object value, int expected) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "fixture-http")
                        .method(
                                method,
                                value == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(Json.write(value)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        return response.body().isBlank() ? Map.of() : Json.read(response.body(), Map.class);
    }
}
