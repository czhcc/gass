package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.AgentRuntime;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.graph.RunGraphReadService;
import java.util.List;
import java.util.Map;
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
