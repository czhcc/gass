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
import static org.mockito.Mockito.when;

import com.trs.simulation.AgentScopeDecisionProvider;
import com.trs.simulation.Examples;
import com.trs.simulation.HistoryService;
import com.trs.simulation.InformationOntology;
import com.trs.simulation.Json;
import com.trs.simulation.RunService;
import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.ActionCatalog;
import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Observation;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.TypeDefinition;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.WorldService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies frozen contracts, fresh observations, authenticated intervention and replay.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class ObjectActionRuntimeIntegrationTest {
    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    HistoryService history;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    @MockitoBean
    AgentScopeDecisionProvider provider;

    @LocalServerPort
    int port;

    @Test
    void freshCapabilitiesDriveSequentialDecisionsAndContractsStayFrozen() {
        var world = worlds.create(Examples.informationLeak());
        var visible = world.entities().stream().map(Entity::id).toList();
        var first = new AgentDefinition("first", "First", "Review", "m", List.of("MARK_REVIEWED", "END"), visible, 10);
        var second =
                new AgentDefinition("second", "Second", "Review", "m", List.of("MARK_REVIEWED", "END"), visible, 10);
        var scenario = worlds.scenario(new Scenario(null, "objects", world.id(), List.of(first, second), 5, 500));
        var run = runs.create(scenario.id());
        var narrowed = new Ontology(
                2,
                world.ontology().types().stream()
                        .map(t -> new TypeDefinition(t.targetKind(), t.type(), List.of()))
                        .toList());
        worlds.update(
                world.id(),
                new World(
                        world.id(),
                        world.name(),
                        world.domainCode(),
                        world.entityTypes(),
                        world.relationTypes(),
                        world.entities(),
                        world.relations(),
                        world.version(),
                        narrowed));
        when(provider.decide(any(), any())).thenAnswer(inv -> {
            AgentDefinition agent = inv.getArgument(0);
            Observation observation = inv.getArgument(1);
            var target = observation.availableActions().stream()
                    .filter(c -> c.targetId().equals("document_f"))
                    .findFirst()
                    .orElseThrow();
            if (agent.id().equals("first")) {
                assertTrue(target.available());
                return new Decision("MARK_REVIEWED", "document_f", Map.of(), "Review", "ENTITY");
            }
            assertEquals(1, observation.version());
            assertFalse(target.available());
            return new Decision("END", "", Map.of(), "Already reviewed", "AGENT");
        });
        assertEquals(1, runs.step(run.id(), "one").version());
        assertTrue(runs.events(run.id(), 0).stream()
                .anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED")
                        && e.payload().get("targetKind").equals("ENTITY")));
        var child = history.fork(run.id(), 0, "frozen", "fork");
        assertFalse(runs.actionCapabilities(child.id())
                .agents()
                .getFirst()
                .capabilities()
                .isEmpty());
        assertTrue(runs.actionCapabilities(runs.create(scenario.id()).id())
                .agents()
                .getFirst()
                .capabilities()
                .isEmpty());
        clearInvocations(provider);
        history.stateAt(run.id(), 0);
        verify(provider, never()).decide(any(), any());
    }

    @Test
    void authenticatedCapabilitiesAndManualActionShareValidationAndVersionChecks() throws Exception {
        var world = worlds.create(Examples.informationLeak());
        var agent = new AgentDefinition(
                "a", "A", "Review", "m", List.of("MARK_REVIEWED", "END"), List.of("document_f"), 10);
        var scenario = worlds.scenario(new Scenario(null, "manual", world.id(), List.of(agent), 5, 200));
        var run = runs.create(scenario.id());
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String path = "/api/runs/" + run.id();
        assertEquals(
                401,
                request(client, "GET", path + "/action-capabilities", null, "get")
                        .statusCode());
        request(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"), "login");
        var response = request(client, "GET", path + "/action-capabilities", null, "get");
        assertEquals(200, response.statusCode());
        var catalog = Json.read(response.body(), ActionCatalog.class);
        assertEquals(1, catalog.agents().getFirst().capabilities().size());
        assertEquals(
                "document_f",
                catalog.agents().getFirst().capabilities().getFirst().targetId());
        var invalid = new Decision("MARK_REVIEWED", "document_f", Map.of("reviewed", true), "Bad parameters", "ENTITY");
        assertEquals(
                200,
                request(
                                client,
                                "POST",
                                path + "/actions",
                                Map.of("agentId", "a", "expectedVersion", 0, "decision", invalid),
                                "bad")
                        .statusCode());
        assertEquals(
                "ACTION_PARAMETERS_INVALID",
                runs.events(run.id(), 0).getLast().payload().get("code"));
        var valid = new Decision("MARK_REVIEWED", "document_f", Map.of(), "Review", "ENTITY");
        var input = Map.of("agentId", "a", "expectedVersion", 0, "decision", valid);
        assertEquals(
                200, request(client, "POST", path + "/actions", input, "good").statusCode());
        assertEquals(1, runs.state(run.id()).version());
        assertEquals(
                200, request(client, "POST", path + "/actions", input, "good").statusCode());
        assertEquals(
                409, request(client, "POST", path + "/actions", input, "stale").statusCode());
        assertFalse(runs.actionCapabilities(run.id())
                .agents()
                .getFirst()
                .capabilities()
                .getFirst()
                .available());
        runs.intervene(run.id(), "end", "a", new Decision("END", "", Map.of(), "Finished", "AGENT"), 1);
        assertTrue(runs.actionCapabilities(run.id()).agents().getFirst().ended());
        assertTrue(runs.actionCapabilities(run.id())
                .agents()
                .getFirst()
                .capabilities()
                .isEmpty());
        assertThrows(IllegalStateException.class, () -> runs.intervene(run.id(), "after-end", "a", valid, 1));
    }

    @Test
    void rejectsMalformedOrAlteredContractsAndPreservesBindingsOnCopy() {
        var world = worlds.create(Examples.informationLeak());
        var type = world.ontology().types().getFirst();
        var changed = new ActionDefinition("MARK_REVIEWED", "Bad", "Bad", "Bad", Map.of(), List.of(), "EXPOSE_ENTITY");
        for (var definition : List.of(
                new TypeDefinition(null, "DOCUMENT", List.of()),
                new TypeDefinition("ENTITY", "DOCUMENT", List.of(changed)))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> worlds.create(new World(
                            null,
                            "Invalid",
                            world.domainCode(),
                            world.entityTypes(),
                            world.relationTypes(),
                            world.entities(),
                            world.relations(),
                            1,
                            new Ontology(3, List.of(definition)))));
        }
        var narrowed = new Ontology(
                3,
                List.of(new TypeDefinition(
                        type.targetKind(), type.type(), List.of(type.actions().getFirst()))));
        var updated = worlds.update(
                world.id(),
                new World(
                        world.id(),
                        world.name(),
                        world.domainCode(),
                        world.entityTypes(),
                        world.relationTypes(),
                        world.entities(),
                        world.relations(),
                        world.version(),
                        narrowed));
        assertEquals(narrowed, worlds.copy(updated.id(), "Copied bindings").ontology());
    }

    @Test
    void readingCapabilitiesDoesNotWaitForAnInFlightModelDecision() throws Exception {
        var world = worlds.create(Examples.informationLeak());
        var agent = new AgentDefinition(
                "a", "A", "Review", "m", List.of("MARK_REVIEWED", "END"), List.of("document_f"), 10);
        var scenario = worlds.scenario(new Scenario(null, "nonblocking", world.id(), List.of(agent), 5, 200));
        var run = runs.create(scenario.id());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(provider.decide(any(), any())).thenAnswer(inv -> {
            entered.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return new Decision("MARK_REVIEWED", "document_f", Map.of(), "Review", "ENTITY");
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> runs.step(run.id(), "slow"));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                    assertEquals(0, runs.actionCapabilities(run.id()).version());
                });
            } finally {
                release.countDown();
            }
            assertEquals(
                    1, pending.get(5, java.util.concurrent.TimeUnit.SECONDS).version());
        }
    }

    @Test
    void legacyFrozenRunUsesObjectDecisionsAndAuditsAffectedRelationsThroughHttp() throws Exception {
        var world = worlds.create(Examples.informationLeak());
        var agent = new AgentDefinition(
                "response",
                "Response",
                "Contain",
                "m",
                List.of("REVOKE_SHARING", "DISABLE_SYNC", "END"),
                world.entities().stream().map(Entity::id).toList(),
                10);
        var run = runs.create(worlds.scenario(new Scenario(null, "Legacy frozen", world.id(), List.of(agent), 5, 200))
                .id());
        var legacyJson = new LinkedHashMap<String, Object>(Json.read(Json.write(world), Map.class));
        legacyJson.put("ontology", InformationOntology.legacyDefinition());
        db.update("UPDATE simulation_run SET world_json=? WHERE id=?", Json.write(legacyJson), run.id());
        var oldEvents = runs.events(run.id(), 0);
        var oldState = history.stateAt(run.id(), 0);
        var catalog = runs.actionCapabilities(run.id());
        assertTrue(catalog.agents().getFirst().capabilities().stream()
                .allMatch(c -> c.targetKind().equals("ENTITY")));
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        request(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"), "login");
        var decision =
                new Decision("REVOKE_SHARING", "person_a", Map.of("recipientId", "person_c"), "Contain", "ENTITY");
        var body = Map.of("agentId", "response", "expectedVersion", 0, "decision", decision);
        assertEquals(
                200,
                request(client, "POST", "/api/runs/" + run.id() + "/actions", body, "object-revoke")
                        .statusCode());
        var accepted = runs.events(run.id(), 0).stream()
                .filter(e -> e.eventType().equals("ACTION_ACCEPTED"))
                .findFirst()
                .orElseThrow();
        assertEquals("ENTITY", accepted.payload().get("targetKind"));
        assertEquals("person_a", accepted.payload().get("targetId"));
        assertEquals(List.of("share_ac"), accepted.payload().get("affectedRelationIds"));
        assertEquals(oldEvents, runs.events(run.id(), 0).subList(0, oldEvents.size()));
        assertEquals(oldState, history.stateAt(run.id(), 0));
        var fork = history.fork(run.id(), 0, "Legacy fork", "legacy-fork");
        assertTrue(runs.actionCapabilities(fork.id()).agents().getFirst().capabilities().stream()
                .filter(c -> c.targetId().equals("person_a")
                        && c.action().actionType().equals("REVOKE_SHARING"))
                .anyMatch(ActionCapability::available));
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, Object input, String key)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .method(
                                method,
                                input == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(Json.write(input)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
