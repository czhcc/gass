package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.SchedulingPolicy;
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
                Map.of(PROVIDER, new ExpansionPolicy.Source(mapping, Identities.empty())),
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
                List.of(agent),
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
                                    List.of())));
        }
    }
}
