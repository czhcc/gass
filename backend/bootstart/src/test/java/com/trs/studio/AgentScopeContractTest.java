package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.trs.simulation.ActionPrograms;
import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.ActionOperation;
import com.trs.simulation.SimulationTypes.ActionProgram;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Observation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.agent.AgentScopeDecisionProvider;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.domain.information.InformationActionEngine;
import com.trs.simulation.model.ModelService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real SDK against a deterministic OpenAI protocol fixture, not a real LLM acceptance. @author
 * agent
 */
class AgentScopeContractTest {
    @TempDir
    java.nio.file.Path directory;

    @Test
    void configuredLlmGeneratesNestedDeclarativeContractWithoutGraphTools() throws Exception {
        var sawTools = new java.util.concurrent.atomic.AtomicBoolean();
        var invalid = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("graph_search_entity") || body.contains("graph_get_entity")) sawTools.set(true);
            var template =
                    ActionPrograms.ontology("bank").types().getFirst().actions().getLast();
            var action = invalid.get()
                    ? new ActionDefinition(
                            template.actionType(),
                            template.nameZh(),
                            template.nameEn(),
                            template.description(),
                            template.parametersSchema(),
                            List.of(),
                            template.effect(),
                            new ActionProgram(
                                    1,
                                    List.of(),
                                    List.of(new ActionOperation(
                                            "SET_PROPERTY",
                                            Map.of(
                                                    "object",
                                                    "target.id",
                                                    "property",
                                                    "'riskScore'",
                                                    "value",
                                                    "target.getClass()"))),
                                    Map.of()))
                    : template;
            String args = Json.write(Map.of("response", action));
            String json = Json.write(Map.of(
                    "id",
                    "generation",
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
                            "tool_calls",
                            "message",
                            Map.of(
                                    "role",
                                    "assistant",
                                    "tool_calls",
                                    List.of(Map.of(
                                            "id",
                                            "draft",
                                            "type",
                                            "function",
                                            "function",
                                            Map.of("name", "generate_response", "arguments", args))))))));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var models = mock(ModelService.class);
            var config = new ModelService.Config(
                    "author",
                    "Author",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    2048,
                    10,
                    1);
            when(models.get("author")).thenReturn(config);
            when(models.credential(config)).thenReturn("fixture-key");
            var provider = new AgentScopeDecisionProvider(models);
            var generated = provider.generateAction("author", "bank", "BANK_ACCOUNT", "根据余额生成风险评分");
            assertEquals("ASSESS_RISK_CEL", generated.actionType());
            assertTrue(generated
                    .program()
                    .operations()
                    .getFirst()
                    .arguments()
                    .get("value")
                    .contains("balanceMinor"));
            assertFalse(sawTools.get());
            invalid.set(true);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> provider.generateAction("author", "bank", "BANK_ACCOUNT", "生成无效表达式"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sdkSelectsReadOnlyToolAndParsesStructuredDecision() throws Exception {
        var calls = new AtomicInteger();
        var sawToolFeedback = new java.util.concurrent.atomic.AtomicBoolean();
        var sawObjectContracts = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("tool_call_id")) sawToolFeedback.set(true);
            if (body.contains("availableActions")
                    && body.contains("parametersSchema")
                    && body.contains("targetKind")
                    && body.contains("additionalProperties")) sawObjectContracts.set(true);
            int call = calls.incrementAndGet();
            String tool = call == 1 ? "graph_search_entity" : "generate_response";
            String args = call == 1
                    ? "{\"query\":\"document\"}"
                    : "{\"actionType\":\"MARK_REVIEWED\",\"targetKind\":\"ENTITY\",\"targetId\":\"document_f\",\"parameters\":{},\"reason\":\"review"
                            + " discovered document\"}";
            if (call > 1) args = "{\"response\":" + args + "}";
            String json = Json.write(Map.of(
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
                            "tool_calls",
                            "message",
                            Map.of(
                                    "role",
                                    "assistant",
                                    "tool_calls",
                                    List.of(Map.of(
                                            "id",
                                            "call-" + call,
                                            "type",
                                            "function",
                                            "function",
                                            Map.of("name", tool, "arguments", args))))))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var config = new ModelService.Config(
                    "fixture",
                    "Fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "fixture",
                    null,
                    null,
                    0,
                    512,
                    10,
                    1);
            var models = mock(ModelService.class);
            when(models.get("fixture")).thenReturn(config);
            when(models.credential(config)).thenReturn("fixture-key");
            var provider = new AgentScopeDecisionProvider(models);
            var decision = provider.decide(
                    new AgentDefinition(
                            "a",
                            "Investigator",
                            "Review document",
                            "fixture",
                            List.of("MARK_REVIEWED"),
                            List.of("document_f"),
                            5),
                    new Observation(
                            "fixture-run",
                            0,
                            0,
                            List.of(new Entity("document_f", "DOCUMENT", "Document", Map.of())),
                            List.of(),
                            List.of(),
                            List.of(),
                            new InformationActionEngine()
                                    .capabilities(
                                            Examples.informationLeak(),
                                            new AgentDefinition(
                                                    "a",
                                                    "Investigator",
                                                    "Review",
                                                    "fixture",
                                                    List.of("MARK_REVIEWED"),
                                                    List.of("document_f"),
                                                    5),
                                            new State(
                                                    "fixture-run",
                                                    0,
                                                    0,
                                                    Examples.informationLeak().entities(),
                                                    Examples.informationLeak().relations()))));
            assertEquals("MARK_REVIEWED", decision.actionType());
            assertEquals("document_f", decision.targetId());
            assertEquals("ENTITY", decision.targetKind());
            assertTrue(sawObjectContracts.get());
            assertEquals(1, provider.drainToolCalls().size());
            assertTrue(sawToolFeedback.get());
            assertTrue(calls.get() >= 2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modelCredentialsAreEncryptedAndNeverReturned() {
        var db = mock(JdbcTemplate.class);
        var service = new ModelService(
                db, new StandardEnvironment(), directory.resolve("key").toString());
        var view = service.save(new ModelService.Input(
                "Test", "https://example.com/v1", "model", "test-secret", null, 0.2, 512, 10, 1));
        assertTrue(view.keyConfigured());
        assertFalse(Json.write(view).contains("test-secret"));
        var value = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(db).update(eq("INSERT INTO model_config VALUES (?,?)"), eq(view.id()), value.capture());
        assertFalse(value.getValue().toString().contains("test-secret"));
        assertTrue(java.nio.file.Files.exists(directory.resolve("key")));
    }
}
