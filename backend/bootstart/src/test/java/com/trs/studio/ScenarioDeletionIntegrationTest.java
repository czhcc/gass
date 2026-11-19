package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Checks authenticated scenario deletion and preservation of independent runs.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "spring.datasource.hikari.maximum-pool-size=1"
    })
class ScenarioDeletionIntegrationTest {
  @LocalServerPort int port;
  @Autowired DemoService demos;
  @Autowired RunService runs;
  @Autowired WorldService worlds;

  @Test
  void deletesOnlySelectedScenarioAndPreservesRunnableHistory() throws Exception {
    var run = demos.create("information", worlds.create(Examples.informationLeak()).id());
    var other = demos.create("bank", worlds.create(Examples.bankTransfer()).id());
    var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    String path = "/api/scenarios/" + run.scenarioId();
    assertEquals(401, send(client, "DELETE", path, null).statusCode());
    assertNotNull(worlds.scenario(run.scenarioId()));
    assertEquals(200, send(client, "POST", "/api/auth/login",
        Map.of("username", "admin", "password", "123456")).statusCode());

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
    assertEquals(200, send(client, "POST", "/api/auth/login",
        Map.of("username", "admin", "password", "123456")).statusCode());
    String endpoint = "/api/examples/information/demo";
    int scenarioCount = worlds.scenarios().size();
    int runCount = runs.list().size();
    assertEquals(400, send(client, "POST", endpoint, Map.of()).statusCode());
    assertEquals(404, send(client, "POST", endpoint, Map.of("worldId", "missing")).statusCode());
    assertEquals(400, send(client, "POST", endpoint, Map.of("worldId", bank.id())).statusCode());
    assertEquals(scenarioCount, worlds.scenarios().size());
    assertEquals(runCount, runs.list().size());
    for (int n = 0; n < 2; n++) {
      var response = send(client, "POST", endpoint, Map.of("worldId", leak.id()));
      assertEquals(200, response.statusCode(), response.body());
      var run = Json.read(response.body(), com.trs.simulation.SimulationTypes.Run.class);
      assertEquals(leak.id(), worlds.scenario(run.scenarioId()).worldId());
      assertEquals(4, runs.agents(run.id()).size());
      assertEquals(leak.entities(), runs.state(run.id()).entities());
      assertEquals("PAUSED", run.status());
    }
    var bankResponse = send(client, "POST", "/api/examples/bank/demo", Map.of("worldId", bank.id()));
    assertEquals(200, bankResponse.statusCode());
    var bankRun = Json.read(bankResponse.body(), com.trs.simulation.SimulationTypes.Run.class);
    assertEquals(bank.id(), worlds.scenario(bankRun.scenarioId()).worldId());
    assertEquals(2, runs.agents(bankRun.id()).size());
    assertEquals(worldCount, worlds.list().size());
  }

  @Test
  void selectedWorldEntityIdsAreUsedForInitialDemoEvents() {
    for (var template : java.util.List.of(Examples.informationLeak(), Examples.bankTransfer())) {
      var custom = worlds.create(new World(null, "Custom world", template.domainCode(),
          template.entityTypes(), template.relationTypes(),
          template.entities().stream().map(e -> new Entity("custom_" + e.id(), e.type(), e.name(), e.properties())).toList(),
          template.relations().stream().map(r -> new Relation(r.id(), "custom_" + r.sourceId(),
              "custom_" + r.targetId(), r.relationType(), r.properties(), r.enabled(), r.evidence(), r.source())).toList(),
          1));
      var run = demos.create(custom.domainCode(), custom.id());
      var scheduled = runs.events(run.id(), 0).stream()
          .filter(e -> e.eventType().equals("SIMULATION_EVENT_SCHEDULED")).findFirst().orElseThrow();
      var input = Json.read(Json.write(scheduled.payload().get("input")), ScheduledInput.class);
      assertTrue(input.targetId().startsWith("custom_"));
      assertTrue(custom.entities().stream().anyMatch(e -> e.id().equals(input.targetId())));
      assertEquals(custom.id(), worlds.scenario(run.scenarioId()).worldId());
    }
  }

  private HttpResponse<String> send(HttpClient client, String method, String path, Object body)
      throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(Json.write(body)))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
