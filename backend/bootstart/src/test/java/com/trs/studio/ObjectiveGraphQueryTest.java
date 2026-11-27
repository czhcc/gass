package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.AgentRuntime;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.common.Json;
import com.trs.simulation.evidence.ObjectiveEvidence;
import com.trs.simulation.graph.KnowledgeCoverage;
import com.trs.simulation.graph.RunGraphReadService;
import com.trs.simulation.state.VisibilityPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks graph ranking and cache isolation with arbitrary domain types. @author czhcc-trsdsj\codexsandboxoffline */
class ObjectiveGraphQueryTest {
    private final RunGraphReadService graph = new RunGraphReadService();
    private final List<Entity> entities = List.of(
            entity("root", "START"),
            entity("a", "TEAM"),
            entity("b", "TEAM"),
            entity("other", "SYSTEM"),
            entity("end", "END"));
    private final List<Relation> relations = List.of(
            edge("root", "a", true),
            edge("a", "b", true),
            edge("b", "a", true),
            edge("b", "end", true),
            edge("root", "other", true),
            edge("other", "b", false));
    private final Map<String, Object> ranking = Map.of("from", "root", "rankByType", "TEAM");

    @Test
    void importedCoverageSeparatesSameVersionCachesAndCompletionEvidence() {
        var state = new State("coverage-run", 2, 3, entities, relations);
        var scope = Set.of("root", "a", "b", "end", "other");
        var worker = new AgentDefinition(
                "query",
                "Query",
                "Path",
                "mock",
                List.of(),
                List.copyOf(scope),
                3,
                new AgentRuntime("QUERY", "query", "graph", List.of("graph_path")));
        var oldCoverage = new KnowledgeCoverage(state.runId(), 4);
        var newCoverage = new KnowledgeCoverage(state.runId(), 5);
        var initial = graph.read(worker, state, "graph_path", ranking, oldCoverage);
        assertTrue(graph.read(worker, state, "graph_path", ranking, oldCoverage).reused());
        var refreshed = graph.read(worker, state, "graph_path", ranking, newCoverage);
        assertFalse(refreshed.reused());
        assertEquals("UNKNOWN", refreshed.result().get("externalCoverage"));
        assertEquals(true, ((Map<?, ?>) refreshed.result().get("pathRanking")).get("complete"));
        var event = new Event(
                state.runId(),
                10,
                "GRAPH_QUERY_RESULT",
                2,
                worker.id(),
                Map.of("scopeIds", initial.scopeIds(), "result", initial.result()));
        assertTrue(ObjectiveEvidence.usableForCompletion(event, scope, 3, true, oldCoverage));
        assertFalse(ObjectiveEvidence.usableForCompletion(event, scope, 3, true, newCoverage));
        assertFalse(ObjectiveEvidence.usableForCompletion(event, scope, 3, true, new KnowledgeCoverage("branch", 4)));
        assertTrue(ObjectiveEvidence.recentGraphEvidence(List.of(event), 3, scope, true, newCoverage)
                .isEmpty());
    }

    @Test
    void legacyQueryWithoutScopeNeedsProvenFullVisibility() {
        var legacy =
                new Event("run", 9, "GRAPH_QUERY_RESULT", 2, "query-worker", Map.of("result", Map.of("version", 3)));
        assertFalse(ObjectiveEvidence.usableForCompletion(legacy, Set.of("root"), 3, false));
        assertTrue(ObjectiveEvidence.usableForCompletion(legacy, Set.of("root", "a"), 3, true));
        assertFalse(ObjectiveEvidence.usableForCompletion(legacy, Set.of("root", "a"), 4, true));
    }

    @Test
    void explicitActionIdentitySurvivesInterleavingAndRequiresAllRelatedObjects() {
        var identity = Map.<String, Object>of(
                "runId",
                "run",
                "actionRunId",
                "action-1",
                "inputVersion",
                2,
                "outputVersion",
                3,
                "relatedIds",
                List.of("root", "a"),
                "targetId",
                "root",
                "actionType",
                "MARK");
        var proposal = new java.util.LinkedHashMap<>(identity);
        proposal.put("action", Map.of("parameters", Map.of("sourceId", "a")));
        var changed = new java.util.LinkedHashMap<>(identity);
        changed.put("before", Map.of("version", 2, "entities", List.of(Map.of("id", "root", "properties", Map.of()))));
        changed.put(
                "after",
                Map.of("version", 3, "entities", List.of(Map.of("id", "root", "properties", Map.of("score", 7)))));
        var verdict = new Event("run", 3, "ACTION_ACCEPTED", 2, "worker", identity);
        var history = List.of(
                new Event("run", 1, "ACTION_PROPOSED", 2, "worker", proposal),
                new Event("run", 2, "MODEL_REQUESTED", 2, "worker", Map.of()),
                verdict,
                new Event("run", 4, "MODEL_REQUESTED", 2, "worker", Map.of()),
                new Event("run", 5, "WORLD_STATE_CHANGED", 2, "worker", changed));
        assertTrue(ObjectiveEvidence.historicalActions(history, Set.of("root"), false, Set.of())
                .isEmpty());
        assertFalse(ObjectiveEvidence.usableForCompletion(verdict, Set.of("root"), 3, false));
        var visible = ObjectiveEvidence.historicalActions(history, Set.of("root", "a"), true, Set.of())
                .get(3L);
        assertEquals(Map.of("score", 7), visible.get("targetPropertiesWritten"));
        assertEquals(Map.of("sourceId", "a"), visible.get("parameters"));
    }

    @Test
    void cachedProjectionCannotBeMutatedAndOmitsFieldsReferencingHiddenObjects() {
        var source = new java.util.LinkedHashMap<String, Object>();
        source.put("ownerId", "secret");
        source.put("sub", new java.util.LinkedHashMap<>(Map.of("value", 3)));
        var state = new State(
                "isolated",
                0,
                1,
                List.of(new Entity("root", "START", "Root", source), entity("secret", "TEAM")),
                List.of());
        var queryWorker = new AgentDefinition(
                "query",
                "Query",
                "Analyze",
                "model",
                List.of(),
                List.of("root"),
                5,
                new AgentRuntime("QUERY", "query", "Analyze", List.of("graph_get_entity")));
        var params = Map.<String, Object>of("entityId", "root");
        var first = graph.read(queryWorker, state, "graph_get_entity", params);
        var entity = (Entity) ((List<?>) first.result().get("entities")).getFirst();
        assertFalse(entity.properties().containsKey("ownerId"));
        assertThrows(
                UnsupportedOperationException.class, () -> entity.properties().put("other", "unsafe"));
        assertEquals(Map.of("sub", Map.of("value", 3)), entity.properties());
        source.clear();
        var again = graph.read(queryWorker, state, "graph_get_entity", params);
        assertTrue(again.reused());
        assertEquals(first.result(), again.result());
    }

    @Test
    void taskScopeCapsDerivedTransactionsInQueriesCacheAndCitations() {
        var state = new State(
                "bank",
                1,
                2,
                List.of(
                        new Entity("debit", "BANK_ACCOUNT", "Debit", Map.of("lastReceipt", "tx")),
                        entity("credit", "BANK_ACCOUNT"),
                        new Entity(
                                "tx",
                                "TRANSACTION",
                                "Receipt",
                                Map.of("sourceId", "debit", "targetId", "credit", "amountMinor", 42))),
                List.of(edge("debit", "tx", true)));
        var role = new AgentDefinition(
                "query",
                "Query",
                "Observe",
                "mock",
                List.of(),
                List.of("debit", "credit"),
                5,
                new AgentRuntime("QUERY", "query", "Observe", List.of("graph_get_entity", "graph_subgraph")));
        var task = VisibilityPolicy.restrict(role, List.of("debit", "credit"));
        var graphArgs = Map.<String, Object>of();
        var transactionArgs = Map.<String, Object>of("entityId", "tx");
        assertFalse(graph.read(role, state, "graph_get_entity", transactionArgs).reused());
        assertThrows(
                IllegalArgumentException.class, () -> graph.read(task, state, "graph_get_entity", transactionArgs));

        var full = graph.read(role, state, "graph_subgraph", graphArgs);
        var limited = graph.read(task, state, "graph_subgraph", graphArgs);
        assertFalse(limited.reused());
        var objects = (List<?>) limited.result().get("entities");
        assertEquals(
                List.of("debit", "credit"),
                objects.stream().map(Entity.class::cast).map(Entity::id).toList());
        assertFalse(((Entity) objects.getFirst()).properties().containsKey("lastReceipt"));
        assertEquals(List.of(), limited.result().get("relations"));
        assertTrue(graph.read(task, state, "graph_subgraph", graphArgs).reused());
        assertEquals(
                full.result(),
                graph.read(role, state, "graph_subgraph", graphArgs).result());

        var evidence = new Event(
                state.runId(),
                1,
                "GRAPH_QUERY_RESULT",
                state.simulationTime(),
                role.id(),
                Map.of("scopeIds", List.copyOf(VisibilityPolicy.readableIds(role, state)), "result", full.result()));
        assertFalse(ObjectiveEvidence.usableForCompletion(evidence, VisibilityPolicy.readableIds(task, state), 2));
        assertTrue(ObjectiveEvidence.usableForCompletion(evidence, VisibilityPolicy.readableIds(role, state), 2));
        var explicitlyAllowed = VisibilityPolicy.restrict(role, List.of("debit", "credit", "tx"));
        assertTrue(graph.read(explicitlyAllowed, state, "graph_get_entity", transactionArgs)
                .reused());
        var composed = VisibilityPolicy.restrict(task, List.of("debit", "credit", "tx"));
        assertThrows(
                IllegalArgumentException.class, () -> graph.read(composed, state, "graph_get_entity", transactionArgs));
        var withoutCounterparty = VisibilityPolicy.restrict(role, List.of("debit", "tx"));
        assertThrows(
                IllegalArgumentException.class,
                () -> graph.read(withoutCounterparty, state, "graph_get_entity", transactionArgs));
        var inherited = VisibilityPolicy.delegated(role, task, new TaskRequest("Observe", List.of(), List.of(), null));
        assertEquals(Set.of("debit", "credit"), VisibilityPolicy.readableIds(inherited, state));
        var implicit = VisibilityPolicy.delegated(role, role, new TaskRequest("Observe", List.of(), List.of(), null));
        assertEquals(Set.of("debit", "credit", "tx"), VisibilityPolicy.readableIds(implicit, state));
        assertEquals(Json.write(role), Json.write(task));
    }

    @Test
    void ranksUniqueNodesOnEnabledDirectedPathsAndReportsTies() {
        var result =
                graph.read(agent("root", "a", "b", "other", "end"), state("run", 0, relations), "graph_path", ranking);
        var ranked = (Map<?, ?>) result.result().get("pathRanking");
        assertEquals(2, ranked.get("maxCount"));
        assertEquals(List.of(List.of("root", "a", "b"), List.of("root", "a", "b", "end")), ranked.get("paths"));
        assertEquals(true, ranked.get("complete"));
        var limited = graph.read(
                        agent("root", "a", "b", "other", "end"),
                        state("run", 0, relations),
                        "graph_path",
                        Map.of("from", "root", "rankByType", "TEAM", "maxDepth", 1))
                .result();
        assertEquals(false, ((Map<?, ?>) limited.get("pathRanking")).get("complete"));
    }

    @Test
    void reusesEquivalentRequestsOnlyWithinTheSameRunVersionAndExactScope() {
        var agent = agent("root", "a", "b", "other", "end");
        var state = state("run", 0, relations);
        var params = Map.<String, Object>of("entityId", "root");
        assertFalse(graph.read(agent, state, "graph_neighbors", params).reused());
        assertTrue(graph.read(
                        agent("end", "a", "b", "root", "other"),
                        state,
                        "graph_neighbors",
                        Map.of("entityId", "root", "direction", "both", "offset", 0, "limit", 50))
                .reused());
        assertFalse(
                graph.read(agent("root", "a"), state, "graph_neighbors", params).reused());
        assertFalse(
                graph.read(agent("root", "b"), state, "graph_neighbors", params).reused());
        assertFalse(graph.read(agent, state("fork", 0, relations), "graph_neighbors", params)
                .reused());
        assertFalse(graph.read(agent, state("run", 1, List.of()), "graph_neighbors", params)
                .reused());
        assertThrows(
                IllegalArgumentException.class,
                () -> graph.read(agent("root"), state, "graph_neighbors", Map.of("entityId", "a")));
    }

    private AgentDefinition agent(String... scope) {
        return new AgentDefinition(
                "query",
                "Query",
                "Analyze",
                "model",
                List.of(),
                List.of(scope),
                5,
                new AgentRuntime("QUERY", "query", "Analyze", List.of("graph_path", "graph_neighbors")));
    }

    private Entity entity(String id, String type) {
        return new Entity(id, type, id, Map.of());
    }

    private Relation edge(String from, String to, boolean enabled) {
        return new Relation(from + "_" + to, from, to, "LINK", Map.of(), enabled, "FACT", "input");
    }

    private State state(String runId, long version, List<Relation> edges) {
        return new State(runId, 0, version, entities, edges);
    }
}
