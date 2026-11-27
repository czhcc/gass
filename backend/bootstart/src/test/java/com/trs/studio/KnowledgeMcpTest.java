package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trs.simulation.common.Json;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Operation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.McpKnowledgeGraphProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** 本地 MCP 协议、只读工具映射与网络边界。 @author czhcc-trsdsj\codexsandboxoffline */
class KnowledgeMcpTest {
    @Test
    void callsOnlyTheConfiguredReadOnlyToolAndMapsStructuredContent() throws IOException {
        var requests = new CopyOnWriteArrayList<Map>();
        HttpServer server = server();
        try {
            server.createContext("/mcp", request -> {
                var body = Json.read(
                        new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
                requests.add(body);
                var result = switch (body.get("method").toString()) {
                    case "initialize" ->
                        Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()));
                    case "notifications/initialized" -> null;
                    case "tools/list" ->
                        Map.of(
                                "tools",
                                List.of(Map.of("name", "find_nodes", "annotations", Map.of("readOnlyHint", true))));
                    default ->
                        Map.of(
                                "structuredContent",
                                Map.of(
                                        "sourceVersion",
                                        "fixed",
                                        "fetchedAt",
                                        "2026-01-01T00:00:00Z",
                                        "coverage",
                                        Map.of(
                                                "queryScope",
                                                "fixture",
                                                "completeness",
                                                "COMPLETE",
                                                "truncated",
                                                false,
                                                "hasMore",
                                                false),
                                        "entities",
                                        List.of(),
                                        "relations",
                                        List.of()));
                };
                if (result == null) {
                    request.sendResponseHeaders(202, -1);
                    request.close();
                } else {
                    reply(request, 200, Json.write(Map.of("jsonrpc", "2.0", "id", body.get("id"), "result", result)));
                }
            });
            server.start();
            var provider = provider(server, 32000);
            assertThrows(IllegalArgumentException.class, () -> provider.query(new Query(Operation.EXPAND, "A", 1, 2)));
            assertTrue(requests.isEmpty());
            assertEquals(
                    "fixed",
                    provider.query(new Query(Operation.SEARCH, "A", 0, 2)).sourceVersion());
            assertEquals(
                    List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                    requests.stream().map(message -> message.get("method")).toList());
            assertEquals(
                    Map.of("name", "find_nodes", "arguments", Map.of("selector", "A", "depth", 0, "limit", 2)),
                    requests.getLast().get("params"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectsAndUnboundedResponses() throws IOException {
        var destinations = new CopyOnWriteArrayList<String>();
        HttpServer server = server();
        try {
            server.createContext("/mcp", request -> {
                destinations.add("/mcp");
                request.getRequestBody().readAllBytes();
                request.getResponseHeaders().add("Location", "/write");
                reply(request, 302, "");
            });
            server.createContext("/write", request -> {
                destinations.add("/write");
                reply(request, 200, "{}");
            });
            server.start();
            assertThrows(
                    IllegalStateException.class,
                    () -> provider(server, 1024).query(new Query(Operation.SEARCH, "A", 0, 2)));
            assertEquals(List.of("/mcp"), destinations);
            server.removeContext("/mcp");
            server.createContext("/mcp", request -> {
                request.getRequestBody().readAllBytes();
                reply(request, 200, "x".repeat(4096));
            });
            assertThrows(
                    IllegalStateException.class,
                    () -> provider(server, 1024).query(new Query(Operation.SEARCH, "A", 0, 2)));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static McpKnowledgeGraphProvider provider(HttpServer server, int maxBytes) {
        return new McpKnowledgeGraphProvider(
                "fixture",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                Map.of(Operation.SEARCH, "find_nodes"),
                Duration.ofSeconds(3),
                maxBytes,
                () -> null);
    }

    private static void reply(HttpExchange request, int status, String content) throws IOException {
        request.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        request.sendResponseHeaders(status, bytes.length);
        try (var output = request.getResponseBody()) {
            output.write(bytes);
        }
    }
}
