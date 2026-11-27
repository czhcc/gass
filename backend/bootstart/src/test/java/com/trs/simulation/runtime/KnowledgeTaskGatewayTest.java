package com.trs.simulation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.knowledge.ExpansionPolicy;
import com.trs.simulation.knowledge.ExternalSubgraph;
import com.trs.simulation.knowledge.ExternalSubgraph.Coverage;
import com.trs.simulation.knowledge.ExternalSubgraph.ExternalEntity;
import com.trs.simulation.knowledge.KnowledgeGraphProvider;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Operation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.OntologyMapping;
import com.trs.simulation.knowledge.OntologyMapping.EntityType;
import com.trs.simulation.knowledge.OntologyMapping.Field;
import com.trs.simulation.knowledge.OntologyMapping.Unit;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.runtime.KnowledgeExpansionService.TaskScope;
import com.trs.simulation.world.WorldService;
import com.trs.studio.StudioApplication;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 委派任务返回范围、审批、I/O 和租约复验回归。 @author czhcc-trsdsj\codexsandboxoffline */
@SpringBootTest(
        classes = StudioApplication.class,
        properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1"})
@Import(KnowledgeTaskGatewayTest.SourceConfig.class)
class KnowledgeTaskGatewayTest {
    private static final Query QUERY = new Query(Operation.SEARCH, "approved selector", 0, 5);

    @Autowired
    WorldService worlds;

    @Autowired
    RunService runs;

    @Autowired
    ModelService models;

    @Autowired
    KnowledgeExpansionService expansions;

    @Autowired
    Source source;

    @Test
    void workerSeesOnlyItsApprovedBatchAndRequiresOperatorApproval() {
        String run = run();
        var scope = new TaskScope("task", "worker", "parent", Set.of("account_a"), false, () -> {});
        var result = expansions.queryForTask(run, scope, "fixture", QUERY);
        assertEquals(
                List.of("C"),
                result.batch().entities().stream()
                        .map(ExternalEntity::externalId)
                        .toList());
        assertEquals("PARTIAL", result.batch().coverage().completeness());
        assertThrows(IllegalArgumentException.class, () -> expansions.commit(run, "owner", result.id()));
        assertThrows(
                IllegalArgumentException.class,
                () -> expansions.queryForTask(run, scope, "fixture", new Query(Operation.SEARCH, "unapproved", 0, 5)));
        var other = new TaskScope("other", "worker", "parent", Set.of("account_a"), false, () -> {});
        assertThrows(IllegalArgumentException.class, () -> expansions.requestForTask(run, other, result.id()));
        expansions.requestForTask(run, scope, result.id());
        expansions.requestForTask(run, scope, result.id());
        assertThrows(IllegalArgumentException.class, () -> expansions.commit(run, "worker", result.id()));
        assertEquals(0, runs.step(run, "wait-for-approval").version());
        assertThrows(IllegalStateException.class, () -> runs.resume(run));
        assertEquals(result, expansions.inspect(run, "owner", result.id()));
        var imported = expansions.commit(run, "owner", result.id());
        assertEquals(1, imported.stateVersion());
        assertEquals(
                1,
                runs.events(run, 0).stream()
                        .filter(event -> "EXTERNAL_IMPORT_REQUESTED".equals(event.eventType()))
                        .count());
    }

    @Test
    void explicitTaskScopeDoesNotAllowNewObjectsAndNoWorldWriteOccursDuringBlockedQuery() throws Exception {
        String run = run();
        var valid = new AtomicBoolean(true);
        var scope = new TaskScope("narrow", "worker", "parent", Set.of("account_a"), true, () -> {
            if (!valid.get()) {
                throw new IllegalStateException("Fixture cancelled");
            }
        });
        var isolated = expansions.queryForTask(run, scope, "fixture", QUERY);
        assertTrue(isolated.batch().entities().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> expansions.requestForTask(run, scope, isolated.id()));
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        source.arrived = arrived;
        source.release = release;
        try (var operations = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var async = operations.submit(() -> expansions.queryForTask(run, scope, "fixture", QUERY));
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                var write = operations.submit(this::run);
                assertEquals(0, runs.state(write.get(5, TimeUnit.SECONDS)).version());
                valid.set(false);
                release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> async.get(5, TimeUnit.SECONDS));
                assertEquals(0, runs.state(run).version());
            } finally {
                release.countDown();
                source.arrived = null;
                source.release = null;
            }
        }
    }

    private String run() {
        var world = worlds.create(Examples.bankTransfer());
        var model = models.mock().id();
        var agents = List.of(
                new AgentDefinition("parent", "Parent", "Goal", model, List.of(), List.of("account_a"), 3),
                new AgentDefinition("worker", "Worker", "Goal", model, List.of(), List.of("account_a"), 3));
        var mapping = new OntologyMapping(
                "fixture",
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
        var settings = new ExpansionPolicy.Source(
                mapping,
                Identities.empty(),
                Map.of("C", Set.of("worker", "parent")),
                Map.of(),
                Map.of("worker", Set.of(QUERY)));
        var scene = worlds.scenario(new Scenario(
                null,
                "Worker fixture",
                world.id(),
                agents,
                5,
                100,
                null,
                null,
                null,
                null,
                null,
                new ExpansionPolicy(Set.of("owner"), Map.of("fixture", settings), 5, 2, 5, 10, 10)));
        return runs.create(scene.id()).id();
    }

    /** 隔离配置，只加载一个合成只读源。 @author czhcc-trsdsj\codexsandboxoffline */
    @TestConfiguration(proxyBeanMethods = false)
    static class SourceConfig {
        @Bean
        Source fixtureSource() {
            return new Source();
        }
    }

    /** 可控连接点，可独立暂停外部 I/O。 @author czhcc-trsdsj\codexsandboxoffline */
    static class Source implements KnowledgeGraphProvider {
        volatile CountDownLatch arrived;
        volatile CountDownLatch release;

        /** 固定测试数据源。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public String providerCode() {
            return "fixture";
        }

        /** 公开响应还含一个越权对象，服务端必须先投影。 @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public ExternalSubgraph query(Query query) {
            if (arrived != null) {
                arrived.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Fixture timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture interrupted");
                }
            }
            return new ExternalSubgraph(
                    "fixed",
                    "2026-01-01T00:00:00Z",
                    new Coverage("fixed", "COMPLETE", false, false),
                    List.of(
                            new ExternalEntity("C", "account", "Visible", Map.of("amount", 5000, "ccy", "CNY")),
                            new ExternalEntity("hidden", "account", "Hidden", Map.of("amount", 9000, "ccy", "CNY"))),
                    List.of());
        }
    }
}
