package com.trs.simulation.knowledge;

import com.trs.simulation.common.Json;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Streamable HTTP 只读连接器，客户端只选择预先登记的结构化操作。 @author czhcc-trsdsj\codexsandboxoffline */
public final class McpKnowledgeGraphProvider implements KnowledgeGraphProvider {
    private static final String PROTOCOL = "2025-06-18";
    private final String providerCode;
    private final URI endpoint;
    private final Map<Operation, String> tools;
    private final Duration timeout;
    private final int maxBytes;
    private final Supplier<String> token;
    private final HttpClient client;

    /** 登记固定的管理员端点和能力，认证引用按调用解析。 @author czhcc-trsdsj\codexsandboxoffline */
    public McpKnowledgeGraphProvider(
            String providerCode,
            URI endpoint,
            Map<Operation, String> tools,
            Duration timeout,
            int maxBytes,
            Supplier<String> token) {
        ExternalSubgraph.requireIdentifier(providerCode);
        if (!safeEndpoint(endpoint)
                || tools.isEmpty()
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || maxBytes < 1024
                || maxBytes > 2_000_000) {
            throw new IllegalArgumentException("Invalid MCP provider configuration");
        }
        tools.values().forEach(name -> {
            if (!name.matches("[a-zA-Z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException("Invalid MCP tool configuration");
            }
        });
        this.providerCode = providerCode;
        this.endpoint = endpoint;
        this.tools = Map.copyOf(tools);
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.token = token;
        this.client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    /** 返回管理员登记的外部源代号。 @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public String providerCode() {
        return providerCode;
    }

    /** 协商协议后调用白名单内的只读工具，返回可通过本体映射的结构化结果。 @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public ExternalSubgraph query(Query query) {
        String tool = tools.get(query.operation());
        if (tool == null) {
            throw new IllegalArgumentException("External operation is unavailable for this provider");
        }
        try {
            var connection = new Exchange(token.get(), System.nanoTime() + timeout.toNanos());
            var initialize = connection.send(
                    1,
                    "initialize",
                    Map.of(
                            "protocolVersion",
                            PROTOCOL,
                            "capabilities",
                            Map.of(),
                            "clientInfo",
                            Map.of("name", "GASS knowledge graph", "version", "1")));
            var capabilities = result(initialize, 1);
            if (!PROTOCOL.equals(capabilities.get("protocolVersion"))
                    || !(capabilities.get("capabilities") instanceof Map<?, ?> offered)
                    || !offered.containsKey("tools")) {
                throw new IllegalStateException();
            }
            connection.initialized = true;
            connection.sessionId =
                    initialize.headers().firstValue("Mcp-Session-Id").orElse(null);
            connection.send(null, "notifications/initialized", null);
            var listed = result(connection.send(2, "tools/list", Map.of()), 2).get("tools");
            if (!(listed instanceof List<?> available)
                    || available.stream().noneMatch(item -> declaredReadOnly(item, tool))) {
                throw new IllegalStateException();
            }
            var called = result(
                    connection.send(
                            3,
                            "tools/call",
                            Map.of(
                                    "name",
                                    tool,
                                    "arguments",
                                    Map.of(
                                            "selector",
                                            query.selector(),
                                            "depth",
                                            query.depth(),
                                            "limit",
                                            query.limit()))),
                    3);
            if (Boolean.TRUE.equals(called.get("isError")) || !(called.get("structuredContent") instanceof Map)) {
                throw new IllegalStateException();
            }
            return Json.read(Json.write(called.get("structuredContent")), ExternalSubgraph.class);
        } catch (RuntimeException ex) {
            // 上游响应、鉴权引用和目标地址不得出现在对外错误中。
            throw new IllegalStateException("External knowledge provider request failed");
        }
    }

    private static boolean declaredReadOnly(Object value, String tool) {
        return value instanceof Map<?, ?> item
                && tool.equals(item.get("name"))
                && item.get("annotations") instanceof Map<?, ?> annotations
                && Boolean.TRUE.equals(annotations.get("readOnlyHint"));
    }

    private static boolean safeEndpoint(URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getRawQuery() != null) {
            return false;
        }
        return "https".equals(uri.getScheme())
                || "http".equals(uri.getScheme())
                        && Set.of("127.0.0.1", "[::1]").contains(uri.getHost());
    }

    private static Map<?, ?> result(HttpResponse<byte[]> response, int id) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException();
        }
        var type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        var body = new String(response.body(), StandardCharsets.UTF_8);
        Map<?, ?> message;
        if (type.startsWith("application/json")) {
            message = Json.read(body, Map.class);
        } else if (type.startsWith("text/event-stream")) {
            message = matchingEvent(body, id);
        } else {
            throw new IllegalStateException();
        }
        if (!"2.0".equals(message.get("jsonrpc"))
                || !Integer.valueOf(id).equals(message.get("id"))
                || message.containsKey("error")
                || !(message.get("result") instanceof Map<?, ?> result)) {
            throw new IllegalStateException();
        }
        return result;
    }

    private static Map<?, ?> matchingEvent(String body, int id) {
        var data = new StringBuilder();
        for (var line : body.split("\\R", -1)) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading()).append('\n');
            }
            if (line.isEmpty() && !data.isEmpty()) {
                var event = Json.read(data.toString(), Map.class);
                if (Integer.valueOf(id).equals(event.get("id"))) {
                    return event;
                }
                data.setLength(0);
            }
        }
        throw new IllegalStateException();
    }

    /** 一次外部查询的临时 MCP 会话，所有请求共享一个截止时间。 @author czhcc-trsdsj\codexsandboxoffline */
    private final class Exchange {
        private final String credential;
        private final long deadline;
        private boolean initialized;
        private String sessionId;

        private Exchange(String credential, long deadline) {
            this.credential = credential;
            this.deadline = deadline;
        }

        private HttpResponse<byte[]> send(Integer id, String method, Map<String, ?> parameters) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException();
            }
            var payload = new LinkedHashMap<String, Object>(Map.of("jsonrpc", "2.0", "method", method));
            if (id != null) {
                payload.put("id", id);
            }
            if (parameters != null) {
                payload.put("params", parameters);
            }
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofNanos(remaining))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (initialized) {
                request.header("MCP-Protocol-Version", PROTOCOL);
            }
            if (sessionId != null) {
                request.header("Mcp-Session-Id", sessionId);
            }
            if (credential != null && !credential.isBlank()) {
                request.header("Authorization", "Bearer " + credential);
            }
            var pending = client.sendAsync(
                    request.POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                            .build(),
                    info -> new LimitedResponse(maxBytes));
            try {
                var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (id == null && (response.statusCode() != 202 || response.body().length != 0)) {
                    throw new IllegalStateException();
                }
                return response;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                pending.cancel(true);
                throw new IllegalStateException();
            } catch (ExecutionException | TimeoutException ex) {
                pending.cancel(true);
                throw new IllegalStateException();
            }
        }
    }

    /** 在收集上游响应时限制总字节数，超限后取消网络订阅。 @author czhcc-trsdsj\codexsandboxoffline */
    private static final class LimitedResponse implements BodySubscriber<byte[]> {
        private final BodySubscriber<byte[]> response = BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long remaining;

        private LimitedResponse(int maxBytes) {
            remaining = maxBytes;
        }

        /** 供 HTTP 客户端等待有界响应。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public CompletionStage<byte[]> getBody() {
            return response.getBody();
        }

        /** 保存取消网络订阅的句柄。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public void onSubscribe(Flow.Subscription registered) {
            subscription = registered;
            response.onSubscribe(registered);
        }

        /** 累加实际收到的字节，避免信任 Content-Length。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                remaining -= buffer.remaining();
            }
            if (remaining < 0) {
                subscription.cancel();
                response.onError(new IllegalStateException("External response exceeded its byte budget"));
            } else {
                response.onNext(buffers);
            }
        }

        /** 将传输失败交给请求调用方。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public void onError(Throwable failure) {
            response.onError(failure);
        }

        /** 完成有界响应。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public void onComplete() {
            response.onComplete();
        }
    }
}
