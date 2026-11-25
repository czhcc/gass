package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.common.Json;
import com.trs.simulation.config.DemoService;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.world.WorldService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Checks authenticated scenario deletion and preservation of independent runs.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class ScenarioDeletionIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    DemoService demos;

    @Autowired
    RunService runs;

    @Autowired
    WorldService worlds;

    @Test
    void hierarchyEndpointsRequireLoginAndExposeTaskAndOutcomeRecords() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(401, send(client, "GET", "/api/scenario-profiles", null).statusCode());
        assertEquals(
                200,
                send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"))
                        .statusCode());
        assertTrue(send(client, "GET", "/api/scenario-profiles", null).body().contains("rootAgentId"));
        var run = demos.create("bank", worlds.create(Examples.bankTransfer()).id(), 3);
        assertEquals(
                200,
                send(client, "GET", "/api/runs/" + run.id() + "/tasks", null).statusCode());
        assertEquals(
                204,
                send(client, "POST", "/api/runs/" + run.id() + "/initial-input", Map.of())
                        .statusCode());
        runs.step(run.id(), "trace");
        assertTrue(send(client, "GET", "/api/runs/" + run.id() + "/tasks", null)
                .body()
                .contains("parentTaskId"));
        assertTrue(send(client, "GET", "/api/runs/" + run.id() + "/outcome", null)
                .body()
                .contains("NOT_EVALUATED"));
        assertTrue(send(client, "GET", "/api/runs/" + run.id() + "/usage", null)
                .body()
                .contains("reserved_tokens"));
    }

    @Test
    @Timeout(20)
    void reconnectStreamsPersistedEventsInOrderUsingLastEventId() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(
                200,
                send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"))
                        .statusCode());
        var run = demos.create("bank", worlds.create(Examples.bankTransfer()).id(), 3);
        runs.step(run.id(), "stream-fixture");
        var persisted = runs.events(run.id(), 0);
        var cursor = persisted.getLast().sequence() - 3;
        var expected =
                persisted.stream().filter(event -> event.sequence() > cursor).toList();
        assertEquals(3, expected.size());

        String path = "/api/runs/" + run.id();
        var eventResponse = send(client, "GET", path + "/events?after=" + cursor, null);
        assertEquals(200, eventResponse.statusCode());
        assertEquals(expected, List.of(Json.read(eventResponse.body(), Event[].class)));

        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "/stream?after=0"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", Long.toString(cursor))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (var body = response.body()) {
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            var lines = body.iterator();
            for (var event : expected) {
                assertEquals("id:" + event.sequence(), lines.next());
                assertEquals("event:simulation", lines.next());
                var data = lines.next();
                assertTrue(data.startsWith("data:"));
                assertEquals(event, Json.read(data.substring("data:".length()), Event.class));
                assertEquals("", lines.next());
            }
        }
        assertEquals(persisted, runs.events(run.id(), 0));
    }

    @Test
    void demoRoundLimitsAreRequiredValidatedPersistedAndEnforcedForBothDomains() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(
                200,
                send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"))
                        .statusCode());
        for (var template : java.util.List.of(Examples.informationLeak(), Examples.bankTransfer())) {
            var world = worlds.create(template);
            String endpoint = "/api/examples/" + world.domainCode() + "/demo";
            int scenarioCount = worlds.scenarios().size(),
                    runCount = runs.list().size();
            assertEquals(
                    400,
                    send(client, "POST", endpoint, Map.of("worldId", world.id()))
                            .statusCode());
            for (int invalid : new int[] {-1, 0, 21})
                assertEquals(
                        400,
                        send(client, "POST", endpoint, Map.of("worldId", world.id(), "maxSteps", invalid))
                                .statusCode());
            assertEquals(scenarioCount, worlds.scenarios().size());
            assertEquals(runCount, runs.list().size());
            for (int limit : new int[] {1, 13, 20}) {
                var response = send(client, "POST", endpoint, Map.of("worldId", world.id(), "maxSteps", limit));
                assertEquals(200, response.statusCode(), response.body());
                var run = Json.read(response.body(), Run.class);
                assertEquals(limit, run.maxSteps());
                assertEquals(limit, worlds.scenario(run.scenarioId()).maxSteps());
                assertEquals(limit, runs.get(run.id()).maxSteps());
                if (limit == 1) {
                    runs.step(run.id(), "only-round");
                    assertEquals("COMPLETED", runs.get(run.id()).status());
                    assertEquals("OBJECTIVE_NOT_MET", runs.get(run.id()).terminationReason());
                    assertEquals(1, runs.get(run.id()).step());
                }
            }
        }
    }

    @Test
    void deletesOnlySelectedScenarioAndPreservesRunnableHistory() throws Exception {
        var run = demos.create(
                "information", worlds.create(Examples.informationLeak()).id(), 8);
        var other = demos.create("bank", worlds.create(Examples.bankTransfer()).id(), 8);
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String path = "/api/scenarios/" + run.scenarioId();
        assertEquals(401, send(client, "DELETE", path, null).statusCode());
        assertNotNull(worlds.scenario(run.scenarioId()));
        assertEquals(
                200,
                send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"))
                        .statusCode());

        var state = runs.state(run.id());
        var events = runs.events(run.id(), 0);
        var deleted = send(client, "DELETE", path, null);
        assertEquals(204, deleted.statusCode());
        assertTrue(deleted.body().isEmpty());
        assertEquals(404, send(client, "GET", path, null).statusCode());
        assertEquals(404, send(client, "DELETE", path, null).statusCode());
        assertEquals(404, send(client, "POST", path + "/runs", null).statusCode());
        assertTrue(worlds.scenarios().stream().noneMatch(s -> s.id().equals(run.scenarioId())));
        assertNotNull(worlds.scenario(other.scenarioId()));
        assertEquals(run, runs.get(run.id()));
        assertEquals(state, runs.state(run.id()));
        assertEquals(events, runs.events(run.id(), 0));
        assertEquals(state, runs.replay(run.id(), state.version()));
        assertEquals(4, runs.agents(run.id()).size());
        runs.step(run.id(), "after-scenario-deletion");
        assertNotEquals("FAILED", runs.get(run.id()).status());
    }

    @Test
    void demosRequireSelectedWorldAndNeverCreateWorldCopies() throws Exception {
        var leak = worlds.create(Examples.informationLeak());
        var bank = worlds.create(Examples.bankTransfer());
        int worldCount = worlds.list().size();
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(
                200,
                send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"))
                        .statusCode());
        String endpoint = "/api/examples/information/demo";
        int scenarioCount = worlds.scenarios().size();
        int runCount = runs.list().size();
        assertEquals(400, send(client, "POST", endpoint, Map.of()).statusCode());
        assertEquals(
                404,
                send(client, "POST", endpoint, Map.of("worldId", "missing", "maxSteps", 8))
                        .statusCode());
        assertEquals(
                400,
                send(client, "POST", endpoint, Map.of("worldId", bank.id(), "maxSteps", 8))
                        .statusCode());
        assertEquals(scenarioCount, worlds.scenarios().size());
        assertEquals(runCount, runs.list().size());
        for (int n = 0; n < 2; n++) {
            var response = send(client, "POST", endpoint, Map.of("worldId", leak.id(), "maxSteps", 8));
            assertEquals(200, response.statusCode(), response.body());
            var run = Json.read(response.body(), com.trs.simulation.SimulationTypes.Run.class);
            assertEquals(leak.id(), worlds.scenario(run.scenarioId()).worldId());
            assertEquals(4, runs.agents(run.id()).size());
            assertEquals(leak.entities(), runs.state(run.id()).entities());
            assertEquals("PAUSED", run.status());
        }
        var bankResponse = send(client, "POST", "/api/examples/bank/demo", Map.of("worldId", bank.id(), "maxSteps", 8));
        assertEquals(200, bankResponse.statusCode());
        var bankRun = Json.read(bankResponse.body(), com.trs.simulation.SimulationTypes.Run.class);
        assertEquals(bank.id(), worlds.scenario(bankRun.scenarioId()).worldId());
        assertEquals(4, runs.agents(bankRun.id()).size());
        assertEquals(worldCount, worlds.list().size());
    }

    @Test
    void selectedWorldEntityIdsAreUsedForInitialDemoEvents() {
        for (var template : java.util.List.of(Examples.informationLeak(), Examples.bankTransfer())) {
            var custom = worlds.create(new World(
                    null,
                    "Custom world",
                    template.domainCode(),
                    template.entityTypes(),
                    template.relationTypes(),
                    template.entities().stream()
                            .map(e -> new Entity("custom_" + e.id(), e.type(), e.name(), e.properties()))
                            .toList(),
                    template.relations().stream()
                            .map(r -> new Relation(
                                    r.id(),
                                    "custom_" + r.sourceId(),
                                    "custom_" + r.targetId(),
                                    r.relationType(),
                                    r.properties(),
                                    r.enabled(),
                                    r.evidence(),
                                    r.source()))
                            .toList(),
                    1));
            var run = demos.create(custom.domainCode(), custom.id(), 8);
            var scheduled = runs.events(run.id(), 0).stream()
                    .filter(e -> e.eventType().equals("SIMULATION_EVENT_SCHEDULED"))
                    .findFirst()
                    .orElseThrow();
            var input = Json.read(Json.write(scheduled.payload().get("input")), ScheduledInput.class);
            assertTrue(input.targetId().startsWith("custom_"));
            assertTrue(custom.entities().stream().anyMatch(e -> e.id().equals(input.targetId())));
            assertEquals(custom.id(), worlds.scenario(run.scenarioId()).worldId());
        }
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, Object body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
