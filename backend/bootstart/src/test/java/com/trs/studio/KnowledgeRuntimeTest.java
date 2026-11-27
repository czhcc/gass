package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.SchedulingPolicy;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.history.HistoryService;
import com.trs.simulation.knowledge.ExpansionPolicy;
import com.trs.simulation.knowledge.ExternalSubgraph;
import com.trs.simulation.knowledge.ExternalSubgraph.Coverage;
import com.trs.simulation.knowledge.ExternalSubgraph.ExternalEntity;
import com.trs.simulation.knowledge.KnowledgeGraphProvider;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Operation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.MockKnowledgeGraphProvider;
import com.trs.simulation.knowledge.OntologyMapping;
import com.trs.simulation.knowledge.OntologyMapping.EntityType;
import com.trs.simulation.knowledge.OntologyMapping.Field;
import com.trs.simulation.knowledge.OntologyMapping.Unit;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.runtime.KnowledgeExpansionService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.state.VisibilityPolicy;
import com.trs.simulation.world.WorldService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 通过运行服务验证事务后仍可在无源路径按序号回放和分支。 @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite::memory:",
            "spring.datasource.hikari.maximum-pool-size=1",
            "studio.simulation.max-state-changes=1"
        })
@Import(KnowledgeRuntimeTest.ProviderConfig.class)
class KnowledgeRuntimeTest {
    private static final Query QUERY = new Query(Operation.SEARCH, "test account", 0, 5);
    private static final Query MIXED = new Query(Operation.SEARCH, "mixed currencies", 0, 5);
    private static final String PROVIDER = "bank-fixture";
    private static final String OPERATOR = "fixture-owner";

    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    HistoryService history;

    @Autowired
    ModelService models;

    @Autowired
    KnowledgeExpansionService imports;

    @Test
    void searchesWithoutChangingTheGraphAndPersistsAnIsolatedImport() {
        var run = createRun();
        var candidate = imports.query(run, OPERATOR, PROVIDER, QUERY);
        assertEquals(0, runs.state(run).version());
        assertEquals(2, runs.state(run).entities().size());
        assertThrows(IllegalArgumentException.class, () -> imports.commit(run, "another-user", candidate.id()));
        var receipt = imports.commit(run, OPERATOR, candidate.id());
        assertEquals(receipt, imports.commit(run, OPERATOR, candidate.id()));
        assertEquals(1, receipt.stateVersion());
        assertEquals(0, runs.get(run).step());
        assertEquals(0, runs.state(run).simulationTime());
        assertEquals(Map.of("CNY", 5000L), receipt.importedBalanceMinor());
        assertEquals(
                2, history.stateAt(run, receipt.eventSequence() - 1).entities().size());
        assertEquals(3, history.stateAt(run, receipt.eventSequence()).entities().size());
        var after = history.fork(run, receipt.eventSequence(), "after", "fork-after");
        var before = history.fork(run, receipt.eventSequence() - 1, "before", "fork-before");
        assertEquals(3, runs.state(after.id()).entities().size());
        assertEquals(2, runs.state(before.id()).entities().size());
        assertEquals(Boolean.FALSE, history.compare(before.id(), after.id()).comparableDataBaseline());
        assertThrows(IllegalArgumentException.class, () -> imports.commit(after.id(), OPERATOR, candidate.id()));
        var duplicate = imports.query(after.id(), OPERATOR, PROVIDER, QUERY);
        assertEquals(1, imports.commit(after.id(), OPERATOR, duplicate.id()).stateVersion());
        var events = runs.events(run, 0);
        assertEquals(
                1,
                events.stream()
                        .filter(e -> "WORLD_EXPANDED".equals(e.eventType()))
                        .count());
        assertTrue(events.stream()
                .noneMatch(
                        e -> Set.of("WORLD_STATE_CHANGED", "AUTO_ACTION", "ENTITY_CREATED", "SIMULATION_CLOCK_ADVANCED")
                                .contains(e.eventType())));
    }

    @Test
    void importDoesNotConsumeTheBusinessChangeBudgetOrHideItsFinancialContribution() {
        var run = createRun();
        var candidate = imports.query(run, OPERATOR, PROVIDER, QUERY);
        imports.commit(run, OPERATOR, candidate.id());
        var transferred = runs.intervene(
                run,
                "business",
                "agent",
                new Decision(
                        "TRANSFER",
                        "account_b",
                        Map.of("sourceId", "account_a", "amount", "20.00", "currency", "CNY"),
                        "fixture"),
                1);
        assertEquals(2, transferred.version());
        assertEquals(Map.of("CNY", 5000L), runs.evaluate(run).get("importDeltaMinorByCurrency"));
        assertEquals(Map.of("CNY", 0L), runs.evaluate(run).get("actionDeltaMinorByCurrency"));
    }

    @Autowired
    JdbcTemplate db;

    @Test
    void rollsBackSnapshotAndEventWhenWritingAnImportReceiptFails() {
        var run = createRun();
        var candidate = imports.query(run, OPERATOR, PROVIDER, QUERY);
        db.execute("""
                CREATE TRIGGER fixture_fail_external_import BEFORE INSERT ON simulation_knowledge_import
                BEGIN SELECT RAISE(ABORT,'fixture import failure'); END
                """);
        try {
            assertThrows(DataAccessException.class, () -> imports.commit(run, OPERATOR, candidate.id()));
            assertEquals(0, runs.state(run).version());
            assertEquals(2, runs.state(run).entities().size());
            assertTrue(history.checkpoints(run).stream().noneMatch(checkpoint -> checkpoint.version() != 0));
            assertTrue(runs.events(run, 0).stream().noneMatch(event -> "WORLD_EXPANDED".equals(event.eventType())));
        } finally {
            db.execute("DROP TRIGGER fixture_fail_external_import");
        }
        assertEquals(1, imports.commit(run, OPERATOR, candidate.id()).stateVersion());
    }

    @Test
    void importsOnlyGrantFrozenReadersAndNeverExpandAnExplicitTaskOrBusinessPermissions() {
        var run = createRun();
        var candidate = imports.query(run, OPERATOR, PROVIDER, QUERY);
        var receipt = imports.commit(run, OPERATOR, candidate.id());
        var state = runs.state(run);
        var id = state.entities().stream()
                .filter(e -> e.name().equals("External C"))
                .findFirst()
                .orElseThrow()
                .id();
        var roles = runs.agents(run).stream().collect(java.util.stream.Collectors.toMap(AgentDefinition::id, a -> a));
        assertTrue(VisibilityPolicy.readableIds(roles.get("reader"), state).contains(id));
        assertTrue(roles.get("reader").allowedActions().contains("TRANSFER"));
        var reader = runs.actionCapabilities(run).agents().stream()
                .filter(a -> a.agentId().equals("reader"))
                .findFirst()
                .orElseThrow();
        assertTrue(reader.capabilities().stream().noneMatch(action -> id.equals(action.targetId())));
        var attempt = runs.intervene(
                run,
                "reader-mutation",
                "reader",
                new Decision(
                        "TRANSFER",
                        id,
                        Map.of("sourceId", "account_a", "amount", "0.01", "currency", "CNY"),
                        "fixture"),
                state.version());
        assertEquals(state.version(), attempt.version());
        assertTrue(runs.events(run, 0).stream()
                .anyMatch(event -> "ACTION_REJECTED".equals(event.eventType())
                        && "ACTION_TARGET_READ_ONLY".equals(event.payload().get("code"))));
        assertTrue(!VisibilityPolicy.readableIds(roles.get("agent"), state).contains(id));
        var restricted = VisibilityPolicy.delegated(
                roles.get("reader"),
                roles.get("reader"),
                new TaskRequest("A only", List.of("account_a"), List.of(), null));
        assertEquals(Set.of("account_a"), VisibilityPolicy.readableIds(restricted, state));
        var without = history.fork(run, receipt.eventSequence() - 1, "before grants", "without");
        var with = history.fork(run, receipt.eventSequence(), "with grants", "with");
        assertTrue(runs.agents(with.id()).stream()
                .filter(a -> a.id().equals("reader"))
                .findFirst()
                .orElseThrow()
                .visibleEntityIds()
                .contains(id));
        assertTrue(runs.agents(without.id()).stream()
                .noneMatch(a -> a.visibleEntityIds().contains(id)));
        var unauthorized = runs.actionCapabilities(run).agents().stream()
                .filter(a -> a.agentId().equals("agent"))
                .findFirst()
                .orElseThrow();
        assertTrue(unauthorized.capabilities().stream().noneMatch(action -> id.equals(action.targetId())));
    }

    @Test
    void explicitLocalActionGrantLetsTheExistingBankEngineModifyAnImportedObject() {
        var run = createRun();
        imports.commit(
                run, OPERATOR, imports.query(run, OPERATOR, PROVIDER, QUERY).id());
        var state = runs.state(run);
        var id = state.entities().stream()
                .filter(e -> e.name().equals("External C"))
                .findFirst()
                .orElseThrow()
                .id();
        var writer = runs.actionCapabilities(run).agents().stream()
                .filter(a -> a.agentId().equals("writer"))
                .findFirst()
                .orElseThrow();
        assertTrue(writer.capabilities().stream()
                .anyMatch(action -> action.targetId().equals(id) && action.available()));
        var result = runs.intervene(
                run, "writer-flag", "writer", new Decision("FLAG_RISK", id, Map.of(), "fixture"), state.version());
        assertEquals(state.version() + 1, result.version());
        assertEquals(
                true,
                result.entities().stream()
                        .filter(e -> e.id().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .properties()
                        .get("riskFlag"));
        assertEquals(Map.of("CNY", 5000L), runs.evaluate(run).get("importDeltaMinorByCurrency"));
    }

    @Test
    void revocationKeepsTheGraphVersionAndHistoricalGrantAndDoesNotRegrantOnRepeatedImport() {
        var run = createRun();
        var receipt = imports.commit(
                run, OPERATOR, imports.query(run, OPERATOR, PROVIDER, QUERY).id());
        var id = runs.state(run).entities().stream()
                .filter(entity -> entity.name().equals("External C"))
                .findFirst()
                .orElseThrow()
                .id();
        assertThrows(IllegalArgumentException.class, () -> imports.revoke(run, "another-user", "reader", id));
        var revision = imports.revoke(run, OPERATOR, "reader", id);
        assertEquals(receipt.stateVersion(), runs.state(run).version());
        assertEquals(revision, imports.revoke(run, OPERATOR, "reader", id));
        imports.commit(
                run, OPERATOR, imports.query(run, OPERATOR, PROVIDER, QUERY).id());
        assertTrue(runs.agents(run).stream()
                .filter(agent -> agent.id().equals("reader"))
                .noneMatch(agent -> agent.visibleEntityIds().contains(id)));
        var before = history.fork(run, receipt.eventSequence(), "before revoke", "before-revoke");
        var after = history.fork(run, revision, "after revoke", "after-revoke");
        assertTrue(runs.agents(before.id()).stream()
                .filter(agent -> agent.id().equals("reader"))
                .anyMatch(agent -> agent.visibleEntityIds().contains(id)));
        assertTrue(runs.agents(after.id()).stream()
                .filter(agent -> agent.id().equals("reader"))
                .noneMatch(agent -> agent.visibleEntityIds().contains(id)));
    }

    @Test
    void operatorCanReadOnlyAuthorizedFieldProvenanceFromFrozenReceipts() {
        var run = createRun();
        var receipt = imports.commit(
                run, OPERATOR, imports.query(run, OPERATOR, PROVIDER, QUERY).id());
        var id = runs.state(run).entities().stream()
                .filter(entity -> entity.name().equals("External C"))
                .findFirst()
                .orElseThrow()
                .id();
        assertThrows(IllegalArgumentException.class, () -> imports.provenance(run, "another-user", id));
        var field = imports.provenance(run, OPERATOR, id).stream()
                .filter(row -> row.fields().contains("balanceMinor"))
                .findFirst()
                .orElseThrow();
        assertEquals("C", field.externalId());
        assertEquals(receipt.receiptId(), field.receiptId());
        assertEquals(receipt.stateVersion(), field.stateVersion());
    }

    @Test
    void importedAccountParticipatesInAuthorizedTransferAndCurrenciesStaySeparate() {
        var run = createRun();
        var receipt = imports.commit(
                run, OPERATOR, imports.query(run, OPERATOR, PROVIDER, MIXED).id());
        var state = runs.state(run);
        var imported = state.entities().stream()
                .filter(entity -> entity.name().equals("External C"))
                .findFirst()
                .orElseThrow()
                .id();
        assertEquals(Map.of("CNY", 5000L, "USD", 1200L), receipt.importedBalanceMinor());
        var decision = new Decision(
                "TRANSFER", "account_a", Map.of("sourceId", imported, "amount", "1.00", "currency", "CNY"), "fixture");
        var next = runs.intervene(run, "external-transfer", "writer", decision, state.version());
        assertEquals(state.version() + 1, next.version());
        assertEquals(
                4900L,
                ((Number) next.entities().stream()
                                .filter(entity -> imported.equals(entity.id()))
                                .findFirst()
                                .orElseThrow()
                                .properties()
                                .get("balanceMinor"))
                        .longValue());
        var metrics = runs.evaluate(run);
        assertEquals(Map.of("CNY", 0L, "USD", 0L), metrics.get("actionDeltaMinorByCurrency"));
        assertTrue(!metrics.containsKey("totalMinor"));
        assertTrue(imports.provenance(run, OPERATOR, imported).stream()
                .anyMatch(item ->
                        item.origin().equals("SIMULATED") && item.fields().contains("properties.balanceMinor")));
    }

    private String createRun() {
        var world = worlds.create(Examples.bankTransfer());
        var mapping = new OntologyMapping(
                PROVIDER,
                1,
                Map.of(
                        "account",
                        new EntityType(
                                "BANK_ACCOUNT",
                                Map.of(
                                        "amount",
                                        new Field("balanceMinor", Unit.INTEGER),
                                        "ccy",
                                        new Field("currency", Unit.VALUE)))),
                Map.of());
        var policy = new ExpansionPolicy(
                Set.of(OPERATOR),
                Map.of(
                        PROVIDER,
                        new ExpansionPolicy.Source(
                                mapping,
                                Identities.empty(),
                                Map.of("C", Set.of("reader", "writer")),
                                Map.of("C", Set.of("writer")))),
                5,
                2,
                5,
                10,
                10);
        var agent = new AgentDefinition(
                "agent",
                "Agent",
                "Test",
                models.mock().id(),
                List.of("TRANSFER"),
                world.entities().stream().map(e -> e.id()).toList(),
                3);
        var scene = worlds.scenario(new Scenario(
                null,
                "Expansion fixture",
                world.id(),
                List.of(
                        agent,
                        new AgentDefinition(
                                "reader",
                                "Reader",
                                "Test",
                                agent.modelId(),
                                List.of("TRANSFER"),
                                List.of("account_a"),
                                3),
                        new AgentDefinition(
                                "writer",
                                "Writer",
                                "Test",
                                agent.modelId(),
                                List.of("FLAG_RISK", "TRANSFER"),
                                List.of("account_a"),
                                3)),
                5,
                200,
                null,
                null,
                new SchedulingPolicy(1, "EVENT_DRIVEN", "TICK", 20),
                null,
                null,
                policy));
        return runs.create(scene.id()).id();
    }

    /** 固定只读外部数据和范围。 @author czhcc-trsdsj\codexsandboxoffline */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfig {
        @Bean
        KnowledgeGraphProvider bankFixture() {
            return new MockKnowledgeGraphProvider(
                    PROVIDER,
                    Map.of(
                            QUERY,
                            new ExternalSubgraph(
                                    null,
                                    "2026-01-01T00:00:00Z",
                                    new Coverage("accounts", "PARTIAL", false, false),
                                    List.of(new ExternalEntity(
                                            "C", "account", "External C", Map.of("amount", 5000L, "ccy", "CNY"))),
                                    List.of()),
                            MIXED,
                            new ExternalSubgraph(
                                    "mixed-v1",
                                    "2026-01-01T00:00:00Z",
                                    new Coverage("accounts", "PARTIAL", false, false),
                                    List.of(
                                            new ExternalEntity(
                                                    "C", "account", "External C", Map.of("amount", 5000, "ccy", "CNY")),
                                            new ExternalEntity(
                                                    "D",
                                                    "account",
                                                    "External D",
                                                    Map.of("amount", 1200, "ccy", "USD"))),
                                    List.of())));
        }
    }
}
