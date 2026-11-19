package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.simulation.*;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies isolated deletion and branch lineage protection.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class RunDeletionIntegrationTest {
  @LocalServerPort int port;
  @Autowired DemoService demos;
  @Autowired WorldService worlds;
  @Autowired RunService runs;
  @Autowired HistoryService history;
  @Autowired JdbcTemplate db;

  @Test
  void deletesLeafHistoryOnlyAndRequiresAuthentication() throws Exception {
    var parent = demos.create("information", worlds.example("information").id(), 8);
    runs.step(parent.id(), "before-delete");
    var child = history.fork(parent.id(), 0, "child", "fork");
    var sibling = history.fork(parent.id(), 0, "sibling", "fork-other");
    var parentState = runs.state(parent.id());
    var siblingEvents = runs.events(sibling.id(), 0);
    var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    String path = "/api/runs/" + child.id();
    assertEquals(401, send(client, "DELETE", path, null).statusCode());
    assertEquals(200, send(client, "POST", "/api/auth/login",
        Map.of("username", "admin", "password", "123456")).statusCode());
    var conflict = send(client, "DELETE", "/api/runs/" + parent.id(), null);
    assertEquals(409, conflict.statusCode());
    assertTrue(conflict.body().contains("RUN_HAS_BRANCHES"));
    assertEquals(204, send(client, "DELETE", path, null).statusCode());
    assertEquals(404, send(client, "GET", path, null).statusCode());
    assertEquals(404, send(client, "DELETE", path, null).statusCode());
    assertEquals(404, send(client, "POST", path + "/step", null).statusCode());
    for (String table : java.util.List.of("simulation_event", "simulation_snapshot",
        "simulation_scheduled_event", "simulation_command", "simulation_branch")) {
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=?",
          Integer.class, child.id()));
    }
    assertEquals(parentState, runs.state(parent.id()));
    assertEquals(siblingEvents, runs.events(sibling.id(), 0));
    assertNotNull(worlds.scenario(parent.scenarioId()));
    runs.delete(sibling.id());
    runs.delete(parent.id());
    assertTrue(history.branches().isEmpty());
    assertNotNull(worlds.get(worlds.scenario(parent.scenarioId()).worldId()));
  }

  @Test
  void refusesRunningRunThenDeletesPausedRunWithQueuedData() throws Exception {
    var run = demos.create("bank", worlds.example("bank").id(), 8);
    // Persist RUNNING without starting a worker, making the conflict deterministic.
    db.update("UPDATE simulation_run SET status='RUNNING' WHERE id=?", run.id());
    var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"));
    var response = send(client, "DELETE", "/api/runs/" + run.id(), null);
    assertEquals(409, response.statusCode());
    assertTrue(response.body().contains("RUN_ACTIVE"));
    runs.pause(run.id());
    runs.step(run.id(), "queued-delete");
    runs.delete(run.id());
    for (String table : java.util.List.of("simulation_event", "simulation_snapshot",
        "simulation_scheduled_event", "simulation_command")) {
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=?",
          Integer.class, run.id()));
    }
  }

  private HttpResponse<String> send(HttpClient client, String method, String path, Object body)
      throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").header("Idempotency-Key", "test-delete")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(Json.write(body)))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
