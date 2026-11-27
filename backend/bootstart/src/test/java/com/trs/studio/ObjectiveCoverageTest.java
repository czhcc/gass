package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.evidence.ObjectiveEvidence;
import com.trs.simulation.evidence.ObjectiveEvidence.CompletionCheck;
import com.trs.simulation.evidence.ObjectiveEvidence.GoalScope;
import com.trs.simulation.graph.KnowledgeCoverage;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies completion against server-side scope and bounded query evidence. @author czhcc-trsdsj\codexsandboxoffline */
class ObjectiveCoverageTest {
    private static final Set<String> SCOPE = Set.of("a", "b");
    private static final KnowledgeCoverage REVISION = new KnowledgeCoverage("run", 5);

    @Test
    void explicitLocalGoalsCanUseCompleteLocalPathsEvenWhenExternalCoverageIsUnknown() {
        assertEquals(
                GoalScope.RUN_GRAPH, ObjectiveEvidence.goalScope(null, "Rank paths in the current local run", true));
        assertEquals(GoalScope.RUN_GRAPH, ObjectiveEvidence.goalScope("RUN_GRAPH", "Rank paths", true));
        assertEquals(
                GoalScope.RUN_GRAPH,
                ObjectiveEvidence.goalScope(null, "Reassess the document if it changes and wait until tick 9", true));
        assertEquals(
                GoalScope.EXTERNAL_GRAPH,
                ObjectiveEvidence.goalScope(null, "Rank the entire external graph and wait until tick 9", true));
        assertEquals(
                GoalScope.UNDETERMINED,
                ObjectiveEvidence.goalScope(null, "Rank the whole graph and wait until tick 9", true));
        assertEquals(CompletionCheck.SUPPORTED, check(GoalScope.RUN_GRAPH, "UNKNOWN", true, false, false));
        assertEquals(CompletionCheck.SUPPORTED, check(GoalScope.RUN_GRAPH, "PARTIAL", true, false, false));
    }

    @Test
    void externalAndAmbiguousGoalsCannotUseLocalPathsAsGlobalProof() {
        assertEquals(
                GoalScope.EXTERNAL_GRAPH,
                ObjectiveEvidence.goalScope(
                        null,
                        "Find the closest person across the entire external graph, including knowledge beyond this local run",
                        true));
        assertEquals(GoalScope.EXTERNAL_GRAPH, ObjectiveEvidence.goalScope("RUN_GRAPH", "查找外部全图的最短路径", true));
        assertEquals(GoalScope.UNDETERMINED, ObjectiveEvidence.goalScope(null, "Find the closest person", true));
        assertEquals(
                CompletionCheck.EXTERNAL_COVERAGE_UNKNOWN,
                check(GoalScope.EXTERNAL_GRAPH, "UNKNOWN", true, false, false));
        assertEquals(
                CompletionCheck.EXTERNAL_COVERAGE_PARTIAL,
                check(GoalScope.EXTERNAL_GRAPH, "PARTIAL", true, false, false));
        assertEquals(
                CompletionCheck.EXTERNAL_SCOPE_UNVERIFIED,
                check(GoalScope.EXTERNAL_GRAPH, "COMPLETE", true, false, false));
        assertEquals(
                CompletionCheck.GOAL_SCOPE_UNDETERMINED, check(GoalScope.UNDETERMINED, "COMPLETE", true, false, false));
    }

    @Test
    void coveredSourceCannotMakeATruncatedOrStaleSearchSufficient() {
        assertEquals(CompletionCheck.SEARCH_INCOMPLETE, check(GoalScope.RUN_GRAPH, "COMPLETE", false, false, false));
        assertEquals(CompletionCheck.SEARCH_INCOMPLETE, check(GoalScope.RUN_GRAPH, "COMPLETE", true, true, false));
        assertEquals(CompletionCheck.SEARCH_INCOMPLETE, check(GoalScope.RUN_GRAPH, "COMPLETE", true, false, true));
        assertEquals(
                CompletionCheck.EVIDENCE_UNAVAILABLE,
                ObjectiveEvidence.completionCheck(
                        evidence("COMPLETE", true, false, false),
                        Set.of("a"),
                        3,
                        false,
                        REVISION,
                        GoalScope.RUN_GRAPH));
        assertEquals(
                CompletionCheck.EVIDENCE_UNAVAILABLE,
                ObjectiveEvidence.completionCheck(
                        evidence("COMPLETE", true, false, false),
                        SCOPE,
                        3,
                        true,
                        new KnowledgeCoverage("run", 6),
                        GoalScope.RUN_GRAPH));
    }

    @Test
    void timeDoesNotAuthorizeAnUnboundedComparisonOrNegativeConclusion() {
        for (var goal : java.util.List.of(
                "At tick 0, compare every item held by the registered data provider",
                "Before simulation tick 3, use the complete collection to determine the best match",
                "At tick 7 determine that a more relevant result never existed in any data feed",
                "涵盖所有记录进行比较，在模拟时刻 0 给出总体第一名")) {
            assertEquals(GoalScope.UNDETERMINED, ObjectiveEvidence.goalScope(null, goal, true), goal);
        }
    }

    private CompletionCheck check(GoalScope scope, String coverage, boolean complete, boolean truncated, boolean ties) {
        return ObjectiveEvidence.completionCheck(
                evidence(coverage, complete, truncated, ties), SCOPE, 3, true, REVISION, scope);
    }

    private Event evidence(String coverage, boolean complete, boolean truncated, boolean ties) {
        return new Event(
                "run",
                7,
                "GRAPH_QUERY_RESULT",
                0,
                "query",
                Map.of(
                        "scopeIds",
                        SCOPE.stream().toList(),
                        "result",
                        Map.of(
                                "externalCoverage",
                                coverage,
                                "version",
                                3,
                                "coverageRevision",
                                5,
                                "truncated",
                                truncated,
                                "pathRanking",
                                Map.of("complete", complete, "tiesTruncated", ties))));
    }
}
