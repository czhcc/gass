package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** World catalog CRUD preserves graph indexes and independent run history. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"
})
class WorldMaintenanceIntegrationTest {
  @Autowired WorldService worlds;
  @Autowired DemoService demos;
  @Autowired RunService runs;
  @Autowired HistoryService history;
  @Autowired JdbcTemplate db;
  @LocalServerPort int port;

  @Test
  void builtinImportsAreIdempotentAndBothWorldsAreAvailable() {
    var leak = worlds.example("information");
    var bank = worlds.example("bank");
    assertEquals("demo-information", leak.id());
    assertEquals("demo-bank", bank.id());
    int count = worlds.list().size();
    assertEquals(leak, worlds.example("information"));
    assertEquals(bank, worlds.example("bank"));
    assertEquals(count, worlds.list().size());
    assertThrows(IllegalStateException.class, () -> worlds.delete(leak.id()));
  }

  @Test
  void editsValidateVersionGraphAndSceneReferencesAndDoNotChangeFrozenRuns() {
    var original = worlds.create(Examples.informationLeak());
    var run = demos.create("information", original.id(), 8);
    var state = runs.state(run.id());
    var updated = worlds.update(original.id(), renamed(original, "Edited world"));
    assertEquals(original.id(), updated.id());
    assertEquals(2, updated.version());
    assertEquals(state, runs.state(run.id()));
    assertThrows(IllegalStateException.class, () -> worlds.update(original.id(), original));
    var bad = new World(updated.id(), "Bad", updated.domainCode(), updated.entityTypes(),
        updated.relationTypes(), List.of(), updated.relations(), updated.version());
    assertThrows(IllegalArgumentException.class, () -> worlds.update(updated.id(), bad));
    assertEquals(updated, worlds.get(updated.id()));
    assertTrue(worlds.expand(updated.id(), "document_f", 4, null).contains("organization_d"));
    assertThrows(IllegalStateException.class, () -> worlds.delete(original.id()));
    worlds.deleteScenario(run.scenarioId());
    worlds.delete(original.id());
    assertThrows(NoSuchElementException.class, () -> worlds.get(original.id()));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM world_entity WHERE world_id=?", Integer.class, original.id()));
    assertEquals(state, runs.replay(run.id(), 0));
    assertNotNull(runs.evaluate(run.id()));
    runs.step(run.id(), "after-world-deleted");
    assertNotEquals("FAILED", runs.get(run.id()).status());
    var child = history.fork(run.id(), 0, "After deletion", "world-test-fork");
    assertNotNull(runs.evaluate(child.id()));
  }

  @Test
  void authenticatedWorldEndpointsSupportUpdateCopyExportAndDeletion() throws Exception {
    var world = worlds.create(Examples.bankTransfer());
    var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    String path = "/api/worlds/" + world.id();
    assertEquals(401, send(client, "DELETE", path, null).statusCode());
    assertEquals(200, send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456")).statusCode());
    var edited = send(client, "PUT", path, renamed(world, "Bank custom"));
    assertEquals(200, edited.statusCode(), edited.body());
    assertEquals("Bank custom", worlds.get(world.id()).name());
    var stale = send(client, "PUT", path, world);
    assertEquals(409, stale.statusCode());
    assertTrue(stale.body().contains("WORLD_STALE"));
    assertEquals(400, send(client, "POST", "/api/worlds", Map.of(
        "name", "Malformed", "domainCode", "bank", "version", 1, "entityTypes", List.of("BANK_ACCOUNT"),
        "relationTypes", List.of(), "entities", Collections.singletonList(null), "relations", List.of())).statusCode());
    var copy = send(client, "POST", path + "/copy", Map.of("name", "Bank copy"));
    assertEquals(200, copy.statusCode());
    var copied = Json.read(copy.body(), World.class);
    assertNotEquals(world.id(), copied.id());
    assertEquals("Bank copy", copied.name());
    var exported = send(client, "GET", path + "/export", null);
    assertEquals(200, exported.statusCode());
    assertTrue(exported.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
    assertEquals("Bank custom", Json.read(exported.body(), World.class).name());
    assertEquals(204, send(client, "DELETE", path, null).statusCode());
    assertEquals(404, send(client, "GET", path, null).statusCode());
    assertEquals(404, send(client, "DELETE", path, null).statusCode());
    assertEquals(409, send(client, "DELETE", "/api/worlds/demo-bank", null).statusCode());
  }

  private World renamed(World w, String name) {
    return new World(w.id(), name, w.domainCode(), w.entityTypes(), w.relationTypes(), w.entities(), w.relations(), w.version());
  }

  private HttpResponse<String> send(HttpClient client, String method, String path, Object input) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").method(method, input == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(input)))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
