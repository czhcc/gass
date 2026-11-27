package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.knowledge.ExternalSubgraph;
import com.trs.simulation.knowledge.ExternalSubgraph.Coverage;
import com.trs.simulation.knowledge.ExternalSubgraph.ExternalEntity;
import com.trs.simulation.knowledge.ExternalSubgraph.ExternalRelation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Operation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.MockKnowledgeGraphProvider;
import com.trs.simulation.knowledge.OntologyMapping;
import com.trs.simulation.knowledge.OntologyMapping.EdgeType;
import com.trs.simulation.knowledge.OntologyMapping.EntityType;
import com.trs.simulation.knowledge.OntologyMapping.Field;
import com.trs.simulation.knowledge.OntologyMapping.Unit;
import com.trs.simulation.knowledge.SubgraphImport;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 外部数据补充与模拟状态的回归夹具。 @author czhcc-trsdsj\codexsandboxoffline */
class KnowledgeExpansionTest {
    private static final Instant FETCHED = Instant.parse("2026-01-01T00:00:00Z");
    private static final OntologyMapping MAPPING = mapping("bank-kg");
    private static final World WORLD = bankWorld();
    private static final State BEFORE = new State(
            "run",
            9,
            3,
            List.of(account("a", 8000L), account("b", 2000L)),
            List.of(new Relation("gone", "a", "b", "CONTACT", Map.of(), false, "SIMULATED", "test")));
    private static final Identities KNOWN = new Identities(Map.of("A", "a", "B", "b"), Map.of("old-link", "gone"));
    private static final Query REQUEST = new Query(Operation.EXPAND, "A", 1, 10);

    @Test
    void mockExposesFixedScopedCandidatesWithoutModifyingTheWorld() {
        var response = source(List.of(external("A", 10000), external("C", 5000)), List.of());
        var provider = new MockKnowledgeGraphProvider("bank-kg", Map.of(REQUEST, response));
        assertEquals(response, provider.query(REQUEST));
        assertEquals("fixture:A", provider.query(REQUEST).coverage().queryScope());
        assertEquals(3, BEFORE.version());
        assertEquals(2, BEFORE.entities().size());
        assertThrows(
                IllegalArgumentException.class, () -> provider.query(new Query(Operation.GET_ENTITY, "missing", 0, 1)));
    }

    @Test
    void onlyFillsMissingFieldsAndPreservesSimulatedBalancesAndDisabledRelations() {
        var batch = source(
                List.of(external("A", 10000), external("C", 5000)),
                List.of(
                        new ExternalRelation("old-link", "contact", "A", "B", Map.of()),
                        new ExternalRelation("new-link", "contact", "A", "C", Map.of())));
        var first = SubgraphImport.preview(WORLD, BEFORE, MAPPING, KNOWN, batch);
        assertEquals(9, first.state().simulationTime());
        assertEquals(4, first.state().version());
        assertEquals(8000L, first.state().entities().getFirst().properties().get("balanceMinor"));
        assertEquals(2000L, first.state().entities().get(1).properties().get("balanceMinor"));
        assertEquals(5000L, first.state().entities().getLast().properties().get("balanceMinor"));
        assertFalse(first.state().relations().getFirst().enabled());
        assertTrue(first.state().relations().getLast().enabled());
        assertEquals("EXTERNAL", first.state().relations().getLast().evidence());
        assertEquals(Map.of("CNY", 5000L), first.importedBalanceMinor());
        assertTrue(first.conflicts().contains("gone"));
        assertFalse(first.changedFields().containsKey("a"));
        assertTrue(first.changedFields()
                .get(first.identities().entities().get("C"))
                .contains("balanceMinor"));
        var retry = SubgraphImport.preview(WORLD, first.state(), MAPPING, first.identities(), batch);
        assertEquals(first.state(), retry.state());
        assertTrue(retry.importedBalanceMinor().isEmpty());
        assertTrue(retry.changedFields().isEmpty());
    }

    @Test
    void identityUsesSourceAndExternalIdEvenWithTheSameName() {
        var batch = source(List.of(external("C", 5000), external("D", 200)), List.of());
        var first = SubgraphImport.preview(WORLD, BEFORE, MAPPING, KNOWN, batch);
        assertEquals(4, first.state().entities().size());
        var second = SubgraphImport.preview(WORLD, first.state(), mapping("second"), Identities.empty(), batch);
        assertEquals(6, second.state().entities().size());
        assertFalse(first.identities()
                .entities()
                .get("C")
                .equals(second.identities().entities().get("C")));
    }

    @Test
    void rejectsAnInvalidBatchWithoutChangingPreviousStateOrIdentityBindings() {
        var bad = new ExternalEntity("X", "unsupported", "Unknown", Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> SubgraphImport.preview(
                        WORLD, BEFORE, MAPPING, KNOWN, source(List.of(external("C", 5000), bad), List.of())));
        var dangling = new ExternalRelation("link", "contact", "A", "missing", Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> SubgraphImport.preview(
                        WORLD, BEFORE, MAPPING, KNOWN, source(List.of(external("C", 5000)), List.of(dangling))));
        var fractions = new ExternalEntity("C", "account", "Same name", Map.of("minor", 0.1, "ccy", "CNY"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SubgraphImport.preview(WORLD, BEFORE, MAPPING, KNOWN, source(List.of(fractions), List.of())));
        assertEquals(2, BEFORE.entities().size());
        assertEquals(Map.of("A", "a", "B", "b"), KNOWN.entities());
    }

    private static World bankWorld() {
        var example = Examples.bankTransfer();
        return new World(
                "test",
                "Test",
                "bank",
                example.entityTypes(),
                List.of("CONTACT"),
                List.of(),
                List.of(),
                1,
                example.ontology());
    }

    private static OntologyMapping mapping(String source) {
        return new OntologyMapping(
                source,
                1,
                Map.of(
                        "account",
                        new EntityType(
                                "BANK_ACCOUNT",
                                Map.of(
                                        "minor",
                                        new Field("balanceMinor", Unit.INTEGER),
                                        "ccy",
                                        new Field("currency", Unit.VALUE)))),
                Map.of("contact", new EdgeType("CONTACT", false, Map.of())));
    }

    private static Entity account(String id, long amount) {
        return new Entity(id, "BANK_ACCOUNT", "Same name", Map.of("balanceMinor", amount, "currency", "CNY"));
    }

    private static ExternalEntity external(String id, long amount) {
        return new ExternalEntity(id, "account", "Same name", Map.of("minor", amount, "ccy", "CNY"));
    }

    private static ExternalSubgraph source(List<ExternalEntity> entities, List<ExternalRelation> relations) {
        return new ExternalSubgraph(
                "r1", FETCHED.toString(), new Coverage("fixture:A", "PARTIAL", false, false), entities, relations);
    }
}
