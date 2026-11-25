package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.GoalCondition;
import com.trs.simulation.SimulationTypes.ObjectiveReview;
import com.trs.simulation.SimulationTypes.RoundReport;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.SimulationTypes.TeamConfig;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.evaluation.ObjectiveDraftService;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.scenario.ScenarioProfiles;
import com.trs.simulation.world.WorldService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Real OpenAI-compatible protocol fixture; verifies SDK nesting, not real LLM quality. @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class HierarchyModelContractTest {
    @Autowired
    ModelService models;

    @Autowired
    ScenarioProfiles profiles;

    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    ObjectiveDraftService drafts;

    @Test
    void endsAnEvidenceBackedTextObjectiveAndReviewsHistorySeparatelyFromFinalState() throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var runId = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request =
                    Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
            requests.add(request);
            String input = Json.write(request.get("messages"));
            int call = requests.size();
            String tool;
            Object args;
            if (input.contains("endGraph") && input.contains("historicalActions")) {
                tool = "generate_response";
                long sequence = runs.events(runId.get(), 0).stream()
                        .filter(e -> e.eventType().equals("ACTION_ACCEPTED"))
                        .findFirst()
                        .orElseThrow()
                        .sequence();
                args = Map.of(
                        "response",
                        Map.of(
                                "conclusion",
                                "已核对目标",
                                "history",
                                "人员 A 在模拟中接收了文件 F。",
                                "finalState",
                                "推演结束时人员 A 到人员 C 的共享已关闭。",
                                "evidenceSequences",
                                List.of(sequence),
                                "limitations",
                                ""));
            } else if (call == 1) {
                tool = "delegate_research";
                args = Map.of("goal", "核对人员 A 到 C 的共享状态");
            } else if (call == 2) {
                tool = "graph_query";
                args = Map.of(
                        "tool", "graph_neighbors", "arguments", Map.of("entityId", "person_a", "direction", "out"));
            } else if (call == 4 || call == 5) {
                tool = "complete_objective";
                long sequence = call == 4
                        ? 99999L
                        : runs.events(runId.get(), 0).stream()
                                .filter(e -> e.eventType().equals("GRAPH_QUERY_RESULT"))
                                .findFirst()
                                .orElseThrow()
                                .sequence();
                args = Map.of("summary", "复查确认共享已关闭。", "evidenceSequences", List.of(sequence));
            } else if (call == 6) {
                tool = "delegate_response";
                args = Map.of("goal", "审查系统 S", "allowedActions", List.of("MARK_REVIEWED"));
            } else {
                tool = "generate_response";
                args = Map.of("response", new RoundReport("已核对共享状态。", List.of(), List.of()));
            }
            var message = call == 3
                    ? Map.of("role", "assistant", "content", "共享已关闭。")
                    : Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "id",
                                    "done-" + call,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", tool, "arguments", Json.write(args)))));
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "done-" + call,
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "finish_reason",
                                    call == 3 ? "stop" : "tool_calls",
                                    "message",
                                    message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var model = models.save(new ModelService.Input(
                    "Objective completion fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    1024,
                    5,
                    1));
            var world = worlds.create(Examples.informationLeak());
            var base = profiles.scenario("information", world, model.id(), "Objective completion", 6);
            var template = base.team();
            var team = new TeamConfig(
                    template.rootAgentId(),
                    template.maxDelegationsPerRound(),
                    template.maxActionsPerRound(),
                    template.mockRules(),
                    List.of(),
                    template.inputActions());
            var run = runs.create(worlds.scenario(new Scenario(
                            null, base.name(), world.id(), base.agents(), 6, 2000, team, "关闭人员 A 到 C 的共享并核对。"))
                    .id());
            runId.set(run.id());
            long initialVersion = runs.state(run.id()).version();
            runs.intervene(
                    run.id(),
                    "simulate",
                    "propagation",
                    new Decision(
                            "SIMULATE_PROPAGATION",
                            "person_a",
                            Map.of("sourceId", "document_f"),
                            "Historical receipt",
                            "ENTITY"),
                    initialVersion);
            runs.intervene(
                    run.id(),
                    "revoke",
                    "response",
                    new Decision(
                            "REVOKE_SHARING",
                            "person_a",
                            Map.of("recipientId", "person_c"),
                            "Disable current path",
                            "ENTITY"),
                    initialVersion + 1);
            runs.step(run.id(), "verify");

            var events = runs.events(run.id(), 0);
            assertEquals("COMPLETED", runs.get(run.id()).status(), Json.write(events));
            assertEquals(1, runs.get(run.id()).step());
            assertEquals("NO_CONDITIONS", runs.outcome(run.id()).result());
            assertEquals(
                    1,
                    events.stream()
                            .filter(e -> e.eventType().equals("OBJECTIVE_COMPLETION_RECORDED"))
                            .count());
            assertEquals(
                    List.of("research"),
                    runs.tasks(run.id()).stream()
                            .filter(t -> t.parentTaskId() != null)
                            .map(TaskRecord::agentId)
                            .toList());
            var finalRequest = requests.stream()
                    .map(request -> Json.write(request.get("messages")))
                    .filter(request -> request.contains("historicalActions") && request.contains("endGraph"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(finalRequest.contains("exposureSource"));
            assertTrue(finalRequest.contains("share_ac"));
            assertTrue(finalRequest.contains("enabled\\\":false"));
            var review = events.stream()
                    .filter(e -> e.eventType().equals("OBJECTIVE_REVIEWED"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("AVAILABLE", review.payload().get("status"));
            assertTrue(Json.write(review.payload().get("review")).contains("人员 A 在模拟中接收了文件 F"));
            assertTrue(Json.write(review.payload().get("review")).contains("推演结束时人员 A 到人员 C 的共享已关闭"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routesAUserObjectiveAndReusesOnlyCurrentGraphEvidenceAcrossRounds() throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(
                    Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class));
            int call = requests.size();
            String tool = call == 1 ? "delegate_research" : call == 2 ? "graph_query" : "generate_response";
            Object arguments = switch (call) {
                case 1 -> Map.of("goal", "查询文件 F 的启用路径");
                case 2 -> Map.of("tool", "graph_path", "arguments", Map.of("from", "document_f", "to", "person_a"));
                default -> Map.of("response", new RoundReport("已汇总本轮查询依据", List.of(), List.of()));
            };
            var message = call == 3
                    ? Map.of("role", "assistant", "content", "已返回路径查询。")
                    : Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "id",
                                    "route-" + call,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", tool, "arguments", Json.write(arguments)))));
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "route-" + call,
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "finish_reason",
                                    call == 3 ? "stop" : "tool_calls",
                                    "message",
                                    message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var model = models.save(new ModelService.Input(
                    "Objective routing fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    1024,
                    5,
                    1));
            var world = worlds.create(Examples.informationLeak());
            var base = profiles.scenario("information", world, model.id(), "Objective routing", 4);
            var template = base.team();
            var team = new TeamConfig(
                    template.rootAgentId(),
                    template.maxDelegationsPerRound(),
                    template.maxActionsPerRound(),
                    template.mockRules(),
                    List.of(),
                    template.inputActions());
            String objective = "从文件 F 出发，哪条路径涉及的人员最多？";
            var run = runs.create(worlds.scenario(
                            new Scenario(null, base.name(), world.id(), base.agents(), 4, 2000, team, objective))
                    .id());
            runs.step(run.id(), "query");
            runs.step(run.id(), "reuse");
            assertEquals(objective, runs.tasks(run.id()).getFirst().request().goal());
            assertFalse(Json.write(requests.getFirst().get("messages")).contains("先控制外传通道"));
            assertTrue(Json.write(requests.get(4).get("messages")).contains("recentGraphEvidence"));
            assertTrue(Json.write(requests.get(4).get("messages")).contains("graph_path"));
            assertEquals(
                    1,
                    runs.events(run.id(), 0).stream()
                            .filter(e -> e.eventType().equals("GRAPH_QUERY_RESULT"))
                            .count());

            runs.intervene(
                    run.id(),
                    "change-graph",
                    "response",
                    new Decision(
                            "REVOKE_SHARING",
                            "person_a",
                            Map.of("recipientId", "person_c"),
                            "Changed scope of reachability",
                            "ENTITY"),
                    0);
            assertEquals(1, runs.state(run.id()).version());
            runs.step(run.id(), "refresh");
            assertFalse(Json.write(requests.get(5).get("messages")).contains("recentGraphEvidence"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesCommittedActionsAndEvaluatesTheHorizonWhenTheMasterOmitsItsStructuredReport() throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(
                    Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class));
            int call = requests.size();
            String tool = call == 1 ? "delegate_response" : "submit_disable_sync";
            Object arguments = call == 1
                    ? Map.of("goal", "禁用同步", "allowedActions", List.of("DISABLE_SYNC"))
                    : Map.of(
                            "targetId",
                            "system_s",
                            "expectedVersion",
                            0,
                            "reason",
                            "关闭同步",
                            "parameters",
                            Map.of("destinationId", "system_t"));
            var message = call > 2
                    ? Map.of("role", "assistant", "content", "已检查执行记录。")
                    : Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "id",
                                    "round-" + call,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", tool, "arguments", Json.write(arguments)))));
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "round-" + call,
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "finish_reason",
                                    call > 2 ? "stop" : "tool_calls",
                                    "message",
                                    message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var model = models.save(new ModelService.Input(
                    "Round report fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    1024,
                    5,
                    1));
            var world = worlds.create(Examples.informationLeak());
            var base = profiles.scenario("information", world, model.id(), "Round report fallback", 1);
            var team = new TeamConfig(
                    base.team().rootAgentId(),
                    1,
                    1,
                    base.team().mockRules(),
                    base.team().conditions(),
                    base.team().inputActions());
            var run = runs.create(
                    worlds.scenario(new Scenario(null, base.name(), world.id(), base.agents(), 1, 2000, team))
                            .id());
            runs.step(run.id(), "report-fallback");

            var events = runs.events(run.id(), 0);
            assertEquals("COMPLETED", runs.get(run.id()).status(), Json.write(events));
            assertEquals("FAILED", runs.outcome(run.id()).result());
            assertTrue(events.stream()
                    .anyMatch(e -> e.eventType().equals("ACTION_ACCEPTED")
                            && "DISABLE_SYNC".equals(e.payload().get("actionType"))));
            assertFalse(runs.state(run.id()).relations().stream()
                    .filter(r -> r.id().equals("sync_st"))
                    .findFirst()
                    .orElseThrow()
                    .enabled());
            var fallback = events.stream()
                    .filter(e -> e.eventType().equals("MASTER_REVIEW"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("ENGINE_FALLBACK", fallback.payload().get("reportSource"));
            assertEquals(
                    1, ((Number) ((Map<?, ?>) fallback.payload().get("report")).get("acceptedActions")).intValue());
            assertFalse(Json.write(requests.getLast().get("tools")).contains("delegate_response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesObjectBoundLeakSchemasAndBlocksMalformedCallsBeforeExecution() throws Exception {
        var calls = new AtomicInteger();
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(
                    Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class));
            int call = calls.incrementAndGet();
            boolean workerSummary = call == 3 || call == 6;
            String tool = switch (call) {
                case 1, 4 -> "delegate_response";
                case 2, 5 -> "submit_disable_sync";
                default -> "generate_response";
            };
            Object arguments = switch (call) {
                case 1, 4 -> Map.of("goal", "关闭系统 S 到系统 T 的同步");
                case 2, 5 ->
                    Map.of(
                            "targetId",
                            "system_s",
                            "expectedVersion",
                            0,
                            "reason",
                            "关闭同步",
                            "parameters",
                            call == 2 ? Map.of("source", "system_s") : Map.of("destinationId", "system_t"));
                default -> Map.of("response", new RoundReport("已核对同步操作回执", List.of(), List.of()));
            };
            var message = workerSummary
                    ? Map.of("role", "assistant", "content", "已返回操作回执。")
                    : Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "id",
                                    "schema-" + call,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", tool, "arguments", Json.write(arguments)))));
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "schema-" + call,
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "finish_reason",
                                    workerSummary ? "stop" : "tool_calls",
                                    "message",
                                    message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var model = models.save(new ModelService.Input(
                    "Action schema fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    2048,
                    5,
                    1));
            var world = worlds.create(Examples.informationLeak());
            var run = runs.create(
                    worlds.scenario(profiles.scenario("information", world, model.id(), "Action schemas", 2))
                            .id());
            runs.seedInput(run.id(), profiles);
            runs.step(run.id(), "schema-protocol");
            assertEquals("PAUSED", runs.get(run.id()).status(), Json.write(runs.events(run.id(), 0)));
            var delegate = functionParameters(requests.getFirst(), "delegate_response");
            assertEquals(false, delegate.get("additionalProperties"));
            assertFalse(((Map<?, ?>) delegate.get("properties")).containsKey("inputEventId"));
            var fields = (Map<?, ?>)
                    functionParameters(requests.get(1), "submit_disable_sync").get("properties");
            assertFalse(fields.containsKey("inputEventId"));
            assertEquals(List.of("system_s"), ((Map<?, ?>) fields.get("targetId")).get("enum"));
            var parameters = (Map<?, ?>) fields.get("parameters");
            assertEquals(List.of("destinationId"), parameters.get("required"));
            assertEquals(false, parameters.get("additionalProperties"));
            var choices = (Map<?, ?>) ((Map<?, ?>) parameters.get("properties")).get("destinationId");
            assertEquals(List.of("system_t"), choices.get("enum"));
            var reviewFields = (Map<?, ?>)
                    functionParameters(requests.get(1), "submit_mark_reviewed").get("properties");
            assertFalse(reviewFields.containsKey("inputEventId"));
            assertEquals(Map.of(), ((Map<?, ?>) reviewFields.get("parameters")).get("properties"));
            var events = runs.events(run.id(), 0);
            assertEquals(
                    1,
                    events.stream()
                            .filter(e -> e.eventType().equals("ACTION_PROPOSED"))
                            .count(),
                    Json.write(events));
            var responseTasks = runs.tasks(run.id()).stream()
                    .filter(t -> t.agentId().equals("response"))
                    .toList();
            assertEquals(2, responseTasks.size());
            assertEquals(
                    responseTasks.getFirst().inputVersion(),
                    responseTasks.getFirst().outputVersion());
            assertEquals(List.of(), responseTasks.getFirst().result().get("receipts"));
            assertEquals(
                    1,
                    events.stream()
                            .filter(e -> e.eventType().equals("ACTION_ACCEPTED")
                                    && "DISABLE_SYNC".equals(e.payload().get("actionType")))
                            .count());
            assertFalse(runs.state(run.id()).relations().stream()
                    .filter(r -> r.id().equals("sync_st"))
                    .findFirst()
                    .orElseThrow()
                    .enabled());
        } finally {
            server.stop(0);
        }
    }

    private Map<?, ?> functionParameters(Map<String, Object> request, String name) {
        for (Object tool : (List<?>) request.get("tools")) {
            if (tool instanceof Map<?, ?> entry
                    && entry.get("function") instanceof Map<?, ?> function
                    && name.equals(function.get("name"))) return (Map<?, ?>) function.get("parameters");
        }
        throw new AssertionError("Tool schema not supplied for " + name);
    }

    @Test
    void draftsLeakConditionsAndChecksFinalModelCitationsThroughTheProviderProtocol() throws Exception {
        var calls = new AtomicInteger();
        var currentRun = new java.util.concurrent.atomic.AtomicReference<String>();
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var condition = new GoalCondition(
                "access",
                "存在访问记录",
                "Document has an access record",
                "relations.exists(r, r.sourceId == 'document_f' && r.relationType == 'ACCESSED_BY')",
                "size(relations.filter(r, r.sourceId == 'document_f' && r.relationType == 'ACCESSED_BY'))");
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(
                    Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class));
            int call = calls.incrementAndGet();
            Object response = switch (call) {
                case 1 -> Map.of("conditions", List.of(condition), "unresolved", List.of());
                case 2, 4 -> new RoundReport("已完成本轮研判", List.of(), List.of());
                case 3 -> {
                    long sequence = runs.events(currentRun.get(), 0).stream()
                            .filter(e -> e.eventType().equals("SCENARIO_EVALUATED"))
                            .findFirst()
                            .orElseThrow()
                            .sequence();
                    yield new ObjectiveReview("存在对应的访问记录。", List.of(sequence, 99999L), "访问记录仅说明图谱中的接触情况。");
                }
                default -> new ObjectiveReview("该引用不存在。", List.of(99999L), "");
            };
            var message = Map.of(
                    "role",
                    "assistant",
                    "tool_calls",
                    List.of(Map.of(
                            "id",
                            "objective-" + call,
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    "generate_response",
                                    "arguments",
                                    Json.write(Map.of("response", response))))));
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "objective-" + call,
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message", message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var model = models.save(new ModelService.Input(
                    "Objective fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    1024,
                    5,
                    1));
            var world = worlds.create(Examples.informationLeak());
            String objective = "检查文件 F 在截止轮是否存在访问记录";
            var draft = drafts.generate(world.id(), model.id(), objective, 1);
            assertEquals("LLM", draft.mode());
            assertEquals(List.of(condition), draft.conditions());
            var template = profiles.scenario("information", world, model.id(), "Objective protocol", 1);
            var team = template.team();
            var configured = new TeamConfig(
                    team.rootAgentId(),
                    team.maxDelegationsPerRound(),
                    team.maxActionsPerRound(),
                    team.mockRules(),
                    draft.conditions(),
                    team.inputActions());
            var scene = worlds.scenario(
                    new Scenario(null, template.name(), world.id(), template.agents(), 1, 2000, configured, objective));
            for (boolean goodCitation : List.of(true, false)) {
                var run = runs.create(scene.id());
                currentRun.set(run.id());
                runs.step(run.id(), "objective-protocol");
                assertEquals("SUCCEEDED", runs.outcome(run.id()).result());
                assertEquals("COMPLETED", runs.get(run.id()).status());
                var review = runs.events(run.id(), 0).stream()
                        .filter(e -> e.eventType().equals("OBJECTIVE_REVIEWED"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        goodCitation ? "AVAILABLE" : "UNAVAILABLE",
                        review.payload().get("status"),
                        Json.write(runs.events(run.id(), 0)));
                if (goodCitation) {
                    var report = Json.read(Json.write(review.payload().get("review")), ObjectiveReview.class);
                    assertEquals(1, report.evidenceSequences().size());
                    assertFalse(report.evidenceSequences().contains(99999L));
                }
            }
            assertEquals(5, calls.get());
            assertTrue(Json.write(requests.get(0).get("messages")).contains("document_f"));
            assertTrue(Json.write(requests.get(1).get("messages")).contains(objective));
            assertTrue(Json.write(requests.get(2).get("messages")).contains("SUCCEEDED"));
            assertFalse(Json.write(requests.get(2).get("tools")).contains("delegate_research"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredModelDelegatesAndReceivesQueryFeedbackWithConcurrencyOne() throws Exception {
        var calls = new AtomicInteger();
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(
                    Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class));
            int call = calls.incrementAndGet();
            Map<String, Object> message;
            if (call == 3) message = Map.of("role", "assistant", "content", "已读取当前图谱并返回查询证据。");
            else {
                String tool = call == 1 ? "delegate_research" : call == 2 ? "graph_query" : "generate_response";
                Object arguments = call == 1
                        ? Map.of("goal", "检查关联对象")
                        : call == 2
                                ? Map.of("tool", "graph_subgraph", "arguments", Map.of())
                                : Map.of(
                                        "response",
                                        new RoundReport("已核对查询证据", List.of("查询不修改状态"), List.of("继续委派对象操作")));
                message = Map.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        List.of(Map.of(
                                "id",
                                "call-" + call,
                                "type",
                                "function",
                                "function",
                                Map.of("name", tool, "arguments", Json.write(arguments)))));
            }
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "fixture-" + call,
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "finish_reason",
                                    call == 3 ? "stop" : "tool_calls",
                                    "message",
                                    message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = models.save(new ModelService.Input(
                    "Fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    8192,
                    3,
                    1));
            var world = worlds.create(Examples.bankTransfer());
            var scene = worlds.scenario(profiles.scenario("bank", world, model.id(), "Real protocol", 4));
            var run = runs.create(scene.id());
            runs.step(run.id(), "protocol");
            assertEquals("PAUSED", runs.get(run.id()).status(), Json.write(runs.events(run.id(), 0)));
            assertEquals(4, calls.get());
            assertEquals(4, ((Number) runs.usage(run.id()).get("requests")).intValue());
            assertTrue(requests.stream().allMatch(body -> Json.write(body).contains("8192")));
            var queryTools = (List<Map<String, Object>>) requests.get(1).get("tools");
            assertFalse(queryTools.stream().anyMatch(t -> Json.write(t).contains("submit_")));
            assertTrue(Json.write(requests.get(3)).contains("queryResults"));
            assertTrue(runs.events(run.id(), 0).stream()
                    .filter(e -> e.eventType().equals("AGENT_STARTED"))
                    .allMatch(e -> "LLM".equals(e.payload().get("modelMode"))));
            assertTrue(runs.tasks(run.id()).stream().allMatch(t -> t.status().equals("SUCCEEDED")));
            assertTrue(
                    requests.stream()
                            .allMatch(body -> Json.write(body.get("messages")).contains("简体中文")),
                    "Both master and worker prompts must require Chinese public reports");
            var worker = runs.tasks(run.id()).stream()
                    .filter(t -> t.parentTaskId() != null)
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    "已读取当前图谱并返回查询证据。",
                    worker.result().get("summary"),
                    "SDK session identifiers do not belong in public summaries");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pausingANestedNetworkRequestCancelsTheWholeTaskTree() throws Exception {
        var calls = new AtomicInteger();
        var childEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            if (call > 1) {
                childEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            var message = call == 1
                    ? Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "id",
                                    "delegate",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            "delegate_research",
                                            "arguments",
                                            Json.write(Map.of("goal", "Read"))))))
                    : Map.of("role", "assistant", "content", "Late answer");
            byte[] bytes = Json.write(Map.of(
                            "id",
                            "slow",
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "fixture",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "finish_reason",
                                    call == 1 ? "tool_calls" : "stop",
                                    "message",
                                    message))))
                    .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) {
                /* The client is expected to cancel its connection. */
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var model = models.save(new ModelService.Input(
                    "Slow fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    1024,
                    10,
                    1));
            var world = worlds.create(Examples.bankTransfer());
            var run = runs.create(worlds.scenario(profiles.scenario("bank", world, model.id(), "Cancel tree", 4))
                    .id());
            var future = pool.submit(() -> runs.step(run.id(), "slow"));
            assertTrue(childEntered.await(5, TimeUnit.SECONDS));
            runs.pause(run.id());
            future.get(5, TimeUnit.SECONDS);
            release.countDown();
            assertEquals("PAUSED", runs.get(run.id()).status());
            assertEquals(0, runs.state(run.id()).version());
            assertEquals(2, runs.tasks(run.id()).size());
            assertTrue(
                    runs.tasks(run.id()).stream().allMatch(t -> t.status().equals("CANCELLED")),
                    Json.write(runs.tasks(run.id())));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
