package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import com.trs.simulation.*;
import com.trs.simulation.SimulationTypes.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Published contracts are immutable and editing cannot change an existing run. @author czhcc-trsdsj\czhcc */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class ActionLibraryIntegrationTest {
  @Autowired ActionLibraryService library;
  @Autowired WorldService worlds;
  @Autowired RunService runs;
  @Autowired ModelService models;
  @MockitoBean AgentScopeDecisionProvider provider;
  @LocalServerPort int port;

  @Test
  void draftPreviewPublicationBindingAndRevisionsPreserveFrozenRuns() {
    var definition = score("CUSTOM_SCORE", "42");
    var draft = library.create(new ActionLibraryService.Input("bank", "BANK_ACCOUNT", definition, 0));
    assertEquals("DRAFT", draft.status());
    var world = worlds.create(Examples.bankTransfer());
    var preview = library.preview(new ActionLibraryService.Preview("bank", "BANK_ACCOUNT", definition,
        world.id(), "account_a", Map.of()));
    assertTrue(preview.accepted());
    assertEquals(42L, preview.state().entities().getFirst().properties().get("customScore"));
    assertFalse(worlds.get(world.id()).entities().getFirst().properties().containsKey("customScore"));
    assertThrows(IllegalStateException.class, () -> library.bind(draft.id(), world.id(), world.version()));
    var published = library.publish(draft.id(), draft.version());
    assertEquals("PUBLISHED", published.status());
    assertThrows(IllegalStateException.class, () -> library.update(published.id(), new ActionLibraryService.Input("bank", "BANK_ACCOUNT", score("CUSTOM_SCORE", "99"), published.version())));
    assertThrows(IllegalStateException.class, () -> library.delete(published.id(), published.version()));
    var bound = library.bind(published.id(), world.id(), world.version());
    assertThrows(WorldService.Conflict.class, () -> library.bind(published.id(), world.id(), world.version()));
    var model = models.mock();
    var agent = new AgentDefinition("custom", "Custom", "Score", model.id(), List.of("CUSTOM_SCORE", "END"), List.of("account_a", "account_b"), 5);
    var scenario = worlds.scenario(new Scenario(null, "Custom CEL", bound.id(), List.of(agent), 5, 100));
    var frozen = runs.create(scenario.id());
    var revision = library.create(new ActionLibraryService.Input("bank", "BANK_ACCOUNT", score("CUSTOM_SCORE", "99"), 0));
    assertEquals(published.revision() + 1, revision.revision());
    library.publish(revision.id(), revision.version());
    library.bind(revision.id(), bound.id(), bound.version());
    var decision = new Decision("CUSTOM_SCORE", "account_a", Map.of(), "Test", "ENTITY");
    runs.intervene(frozen.id(), "frozen", agent.id(), decision, 0);
    assertEquals(42L, ((Number) runs.state(frozen.id()).entities().getFirst().properties().get("customScore")).longValue());
    var fresh = runs.create(scenario.id());
    runs.intervene(fresh.id(), "new", agent.id(), decision, 0);
    assertEquals(99L, ((Number) runs.state(fresh.id()).entities().getFirst().properties().get("customScore")).longValue());
    var disposable = library.create(new ActionLibraryService.Input("bank", "BANK_ACCOUNT", score("DELETE_ME", "1"), 0));
    assertThrows(IllegalStateException.class, () -> library.delete(disposable.id(), disposable.version() + 1));
    library.delete(disposable.id(), disposable.version());
    assertThrows(NoSuchElementException.class, () -> library.get(disposable.id()));
  }

  @Test
  void generationValidatesBeforeSavingAndNeverPublishesOrBinds() {
    var mock = models.mock();
    when(provider.generateAction(anyString(), anyString(), anyString(), anyString())).thenReturn(score("GENERATED_TEST", "7"));
    var generated = library.generate(new ActionLibraryService.Generation("bank", "BANK_ACCOUNT", mock.id(), "评估当前账户"));
    assertEquals("MOCK", generated.source());
    assertEquals("DRAFT", generated.status());
    when(provider.generateAction(anyString(), anyString(), anyString(), anyString())).thenReturn(score("UNSAFE", "java.lang.Runtime.getRuntime()"));
    assertThrows(IllegalArgumentException.class, () -> library.generate(new ActionLibraryService.Generation("bank", "BANK_ACCOUNT", mock.id(), "不合法表达式")));
    assertTrue(library.list().stream().noneMatch(e -> e.definition().actionType().equals("UNSAFE")));
    assertThrows(IllegalArgumentException.class, () -> library.create(new ActionLibraryService.Input("bank", "UNKNOWN", score("BAD_TYPE", "1"), 0)));
  }

  @Test
  void libraryEndpointsRequireAuthenticationAndExposeValidationFailures() throws Exception {
    var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    assertEquals(401, send(client, "GET", "/api/action-library", null).statusCode());
    assertEquals(200, send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456")).statusCode());
    assertEquals(200, send(client, "GET", "/api/action-library", null).statusCode());
    assertEquals(400, send(client, "POST", "/api/action-library/validate", Map.of("domainCode", "bank", "ownerType", "BANK_ACCOUNT", "version", 0, "definition", score("INVALID", "target.getClass()"))).statusCode());
    var entry = library.create(new ActionLibraryService.Input("bank", "BANK_ACCOUNT", score("DELETE_API", "1"), 0));
    assertEquals(204, send(client, "DELETE", "/api/action-library/" + entry.id() + "?version=" + entry.version(), null).statusCode());
    var world = worlds.create(Examples.bankTransfer());
    var definition = score("HTTP_CUSTOM_SCORE", "8");
    var input = new ActionLibraryService.Input("bank", "BANK_ACCOUNT", definition, 0);
    var created = send(client, "POST", "/api/action-library", input);
    assertEquals(200, created.statusCode(), created.body());
    var draft = Json.read(created.body(), ActionLibraryService.Entry.class);
    var preview = send(client, "POST", "/api/action-library/preview", new ActionLibraryService.Preview("bank", "BANK_ACCOUNT", definition, world.id(), "account_a", Map.of()));
    assertEquals(200, preview.statusCode(), preview.body());
    assertTrue(Json.read(preview.body(), Outcome.class).accepted());
    assertEquals(409, send(client, "POST", "/api/action-library/" + draft.id() + "/bind", Map.of("worldId", world.id(), "expectedVersion", world.version())).statusCode());
    assertEquals(200, send(client, "POST", "/api/action-library/" + draft.id() + "/publish", Map.of("version", draft.version())).statusCode());
    assertEquals(200, send(client, "POST", "/api/action-library/" + draft.id() + "/bind", Map.of("worldId", world.id(), "expectedVersion", world.version())).statusCode());
    assertEquals(409, send(client, "POST", "/api/action-library/" + draft.id() + "/bind", Map.of("worldId", world.id(), "expectedVersion", world.version())).statusCode());
  }

  private HttpResponse<String> send(HttpClient client, String method, String path, Object body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
  }
  private ActionDefinition score(String id, String expression) {
    return new ActionDefinition(id, "自定义评分", "Custom score", "计算模拟评分", Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false), List.of(), "CUSTOM",
        new ActionProgram(1, List.of(), List.of(new ActionOperation("SET_PROPERTY", Map.of("object", "target.id", "property", "'customScore'", "value", expression))), Map.of()));
  }
}
