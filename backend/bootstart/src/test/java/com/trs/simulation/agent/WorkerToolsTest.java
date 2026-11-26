package com.trs.simulation.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.AgentRuntime;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.graph.RunGraphReadService;
import com.trs.simulation.state.VisibilityPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Covers the task scope across SDK tool adapters and persisted query evidence. @author czhcc-trsdsj\codexsandboxoffline */
class WorkerToolsTest {
    @Test
    void workerAuditsTheExactLimitedScopeAndCannotReexpandTransactions() {
        var host = mock(HierarchyHost.class);
        var state = new State(
                "run",
                0,
                2,
                List.of(
                        new Entity("debit", "BANK_ACCOUNT", "Debit", Map.of()),
                        new Entity("credit", "BANK_ACCOUNT", "Credit", Map.of()),
                        new Entity(
                                "tx",
                                "TRANSACTION",
                                "Receipt",
                                Map.of("sourceId", "debit", "targetId", "credit", "amountMinor", 42))),
                List.of());
        var role = new AgentDefinition(
                "query",
                "Query",
                "Observe",
                "mock",
                List.of(),
                List.of("debit", "credit"),
                5,
                new AgentRuntime("QUERY", "query", "Observe", List.of("graph_get_entity")));
        var scope = List.of("debit", "credit");
        var agent = VisibilityPolicy.restrict(role, scope);
        var task = new TaskRecord(
                "run",
                "child",
                "master",
                role.id(),
                1,
                "RUNNING",
                2,
                2,
                new TaskRequest("Observe", scope, List.of(), null),
                Map.of(),
                1,
                null);
        when(host.state()).thenReturn(state);
        when(host.record(eq("GRAPH_QUERY_RESULT"), eq(agent.id()), anyMap())).thenReturn(10L);
        var tools = new WorkerTools(host, new RunGraphReadService(), "master", task, agent, () -> 0);

        assertThrows(IllegalArgumentException.class, () -> tools.query("graph_get_entity", Map.of("entityId", "tx")));
        assertEquals(
                10L,
                tools.query("graph_get_entity", Map.of("entityId", "debit")).get("eventSequence"));
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.captor();
        verify(host).record(eq("GRAPH_QUERY_RESULT"), eq(agent.id()), audit.capture());
        assertEquals(List.of("credit", "debit"), audit.getValue().get("scopeIds"));
        assertEquals(2, audit.getValue().get("scopeSize"));
    }
}
