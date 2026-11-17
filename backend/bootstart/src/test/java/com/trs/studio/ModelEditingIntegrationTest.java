package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.simulation.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Saved model editing preserves identity and encrypted credentials. @author agent */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "spring.datasource.hikari.maximum-pool-size=1",
      "studio.model-key-file=${java.io.tmpdir}/gass-model-edit-test.key"
    })
class ModelEditingIntegrationTest {
  @LocalServerPort int port;
  @Autowired ModelService models;

  @Test
  void editingPersistsExistingIdRetainsBlankKeyAndRejectsInvalidUpdates() throws Exception {
    var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    var input =
        new ModelService.Input(
            "Original",
            "https://example.com/v1",
            "qwen",
            "edit-test-key",
            "PREVIOUS_UNUSED_KEY",
            0.2,
            512,
            10,
            1);
    assertEquals(401, send(client, "PUT", "/api/models/missing", input).statusCode());
    assertEquals(
        200,
        send(client, "POST", "/api/auth/login", Map.of("username", "admin", "password", "123456"))
            .statusCode());
    var created = send(client, "POST", "/api/models", input);
    assertEquals(200, created.statusCode());
    var saved = Json.read(created.body(), ModelService.View.class);
    String cipher = models.get(saved.id()).keyCipher();
    var changed =
        new ModelService.Input(
            "Updated",
            "https://updated.example.com/v1",
            "qwen-updated",
            "",
            "PREVIOUS_UNUSED_KEY",
            0.7,
            768,
            20,
            2);
    var updated = send(client, "PUT", "/api/models/" + saved.id(), changed);
    assertEquals(200, updated.statusCode());
    var view = Json.read(updated.body(), ModelService.View.class);
    assertEquals(saved.id(), view.id());
    assertEquals("Updated", view.name());
    assertEquals(768, view.maxTokens());
    assertEquals(2, view.concurrency());
    assertEquals(cipher, models.get(view.id()).keyCipher());
    assertEquals("edit-test-key", models.credential(models.get(view.id())));
    assertEquals(1, models.list().size());
    var list = send(client, "GET", "/api/models", null);
    assertTrue(list.body().contains("Updated"));
    assertFalse(list.body().contains("edit-test-key"));
    assertFalse(list.body().contains("keyCipher"));
    var invalid =
        new ModelService.Input("Bad", "https://example.com/v1", "qwen", "", null, 0.2, 0, 10, 1);
    assertEquals(400, send(client, "PUT", "/api/models/" + saved.id(), invalid).statusCode());
    assertEquals("Updated", models.get(saved.id()).name());
    assertEquals(404, send(client, "PUT", "/api/models/missing", changed).statusCode());
    var replacement =
        new ModelService.Input(
            "Updated",
            view.baseUrl(),
            view.modelName(),
            "replacement-test-key",
            null,
            view.temperature(),
            view.maxTokens(),
            view.timeoutSeconds(),
            view.concurrency());
    assertEquals(200, send(client, "PUT", "/api/models/" + saved.id(), replacement).statusCode());
    assertEquals("replacement-test-key", models.credential(models.get(saved.id())));
    var envSource =
        new ModelService.Input(
            "Updated",
            view.baseUrl(),
            view.modelName(),
            "",
            "NEW_MODEL_KEY",
            view.temperature(),
            view.maxTokens(),
            view.timeoutSeconds(),
            view.concurrency());
    assertEquals(200, send(client, "PUT", "/api/models/" + saved.id(), envSource).statusCode());
    assertNull(models.get(saved.id()).keyCipher());
    assertEquals("NEW_MODEL_KEY", models.get(saved.id()).apiKeyEnv());
  }

  private HttpResponse<String> send(HttpClient client, String method, String path, Object input)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                input == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.write(input)))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
