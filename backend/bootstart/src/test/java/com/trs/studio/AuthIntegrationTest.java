package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Authentication REST integration tests. @author agent */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "spring.datasource.hikari.maximum-pool-size=1"
    })
class AuthIntegrationTest {
  @LocalServerPort int port;

  @Test
  void loginAndLogout() throws Exception {
    var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
    assertEquals(401, send(client, "GET", "/api/auth/me", null).statusCode());
    assertEquals(
        401,
        send(client, "POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"wrong\"}")
            .statusCode());
    assertEquals(
        200,
        send(client, "POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"123456\"}")
            .statusCode());
    assertTrue(send(client, "GET", "/api/auth/me", null).body().contains("admin"));
    assertEquals(204, send(client, "POST", "/api/auth/logout", "{}").statusCode());
    assertEquals(401, send(client, "GET", "/api/auth/me", null).statusCode());
  }

  private HttpResponse<String> send(HttpClient c, String method, String path, String body)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body != null) b.header("Content-Type", "application/json");
    return c.send(
        b.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
