package com.trs.simulation.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.EvaluationPolicy;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.contract.FrozenExpansion;
import com.trs.simulation.evidence.ObjectiveEvidence;
import com.trs.simulation.evidence.ObjectiveProof;
import com.trs.simulation.graph.KnowledgeCoverage;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises completion audit and final publication with the same scope proof. @author czhcc-trsdsj\codexsandboxoffline */
class ScopedCompletionTest {
    private static final String CLAIM = "We found the global optimum and nothing in the provider records can beat it.";
    private static final Entity SUBJECT = new Entity("document_f", "DOCUMENT", "Document F", Map.of());
    private static final State CURRENT = new State("run", 0, 2, List.of(SUBJECT), List.of());
    private static final KnowledgeCoverage COVERAGE = new KnowledgeCoverage("run", 8);
    private static final AgentDefinition MASTER =
            new AgentDefinition("master", "Master", "Goal", "mock", List.of(), List.of(SUBJECT.id()), 5);
    private static final TaskRecord TASK = new TaskRecord(
            "run",
            "task",
            null,
            MASTER.id(),
            1,
            "RUNNING",
            2,
            2,
            new TaskRequest("Goal", List.of(), List.of(), null),
            Map.of(),
            1,
            null);

    @Test
    void timedUnboundedRequestDoesNotWriteCompletedEvent() {
        var scene = scene("At tick 0, rank every archived record by proximity", null);
        var host = host(scene, List.of(ranking()));
        var tool = new CompleteObjective(host, TASK);
        tool.callAsync(request()).block();
        assertFalse(tool.completed());
        verify(host, never()).record(eq(ObjectiveEvidence.COMPLETION_EVENT), any(), any());
        verify(host).record(eq(ObjectiveEvidence.COMPLETION_DEFERRED), eq(MASTER.id()), any());
    }

    @Test
    void boundedRankingPublishesServerScopeInCompletionAndFinalReport() {
        var scene = scene("Rank paths in the current local run", "RUN_GRAPH");
        var history = List.of(ranking());
        var host = host(scene, history);
        var tool = new CompleteObjective(host, TASK);
        tool.callAsync(request()).block();
        assertTrue(tool.completed());
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(host).record(eq(ObjectiveEvidence.COMPLETION_EVENT), eq(MASTER.id()), captor.capture());
        assertFalse(captor.getValue().get("summary").toString().contains(CLAIM));
        var context = new ObjectiveProof.Context(scene, CURRENT, CURRENT, Set.of(SUBJECT.id()), MASTER.id(), COVERAGE);
        var finalReview = ObjectiveReviewService.boundedFinalAnswer(context, history, List.of(7L));
        assertTrue(finalReview.conclusion().contains("当前授权运行图谱"));
        assertTrue(finalReview.conclusion().contains("最高计数为 1"));
        assertTrue(finalReview.limitations().contains("外部全量数据未验证"));
        assertFalse(finalReview.conclusion().contains(CLAIM));
    }

    @Test
    void implicitSingleObjectObservationRequiresAuthorizedPointEvidence() {
        var scene = scene("Reassess the document if it changes and wait until tick 9", null);
        var unsupported = host(scene, List.of(ranking()));
        var wrong = new CompleteObjective(unsupported, TASK);
        wrong.callAsync(request()).block();
        assertFalse(wrong.completed());
        verify(unsupported, never()).record(eq(ObjectiveEvidence.COMPLETION_EVENT), any(), any());

        var read = new Event(
                "run",
                7,
                "GRAPH_QUERY_RESULT",
                0,
                "worker",
                Map.of(
                        "tool",
                        "graph_get_entity",
                        "arguments",
                        Map.of("entityId", SUBJECT.id()),
                        "scopeIds",
                        List.of(SUBJECT.id()),
                        "result",
                        Map.of("version", 2, "coverageRevision", 8, "entities", List.of(SUBJECT))));
        var supportedHost = host(scene, List.of(read));
        var supported = new CompleteObjective(supportedHost, TASK);
        supported.callAsync(request()).block();
        assertTrue(supported.completed());
        var captured = ArgumentCaptor.forClass(Map.class);
        verify(supportedHost).record(eq(ObjectiveEvidence.COMPLETION_EVENT), eq(MASTER.id()), captured.capture());
        assertTrue(captured.getValue().get("summary").toString().contains(SUBJECT.id()));
    }

    private Event ranking() {
        return new Event(
                "run",
                7,
                "GRAPH_QUERY_RESULT",
                0,
                "worker",
                Map.of(
                        "tool",
                        "graph_path",
                        "scopeIds",
                        List.of(SUBJECT.id()),
                        "result",
                        Map.of(
                                "version",
                                2,
                                "coverageRevision",
                                8,
                                "externalCoverage",
                                "UNKNOWN",
                                "pathRanking",
                                Map.of("complete", true, "maxCount", 1, "paths", List.of(List.of(SUBJECT.id()))))));
    }

    private ToolCallParam request() {
        var call = mock(ToolCallParam.class);
        when(call.getInput()).thenReturn(Map.of("summary", CLAIM, "evidenceSequences", List.of(7)));
        return call;
    }

    private HierarchyHost host(Scenario scene, List<Event> history) {
        var host = mock(HierarchyHost.class);
        when(host.scenario()).thenReturn(scene);
        when(host.state()).thenReturn(CURRENT);
        when(host.initial()).thenReturn(CURRENT);
        when(host.knowledgeCoverage()).thenReturn(COVERAGE);
        when(host.visibleIds(MASTER)).thenReturn(Set.of(SUBJECT.id()));
        when(host.history()).thenReturn(history);
        return host;
    }

    private Scenario scene(String goal, String scope) {
        return new Scenario(
                null,
                "Fixture",
                "world",
                List.of(MASTER),
                5,
                100,
                null,
                goal,
                null,
                new EvaluationPolicy("TEXT_ONLY", 1, 0L, null, null, false, "OBJECTIVE", scope),
                null,
                new FrozenExpansion(Set.of(), Map.of(), 1, 0, 1, 0, 1));
    }
}
