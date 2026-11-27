package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.evidence.ObjectiveEvidence;
import com.trs.simulation.evidence.ObjectiveEvidence.CompletionCheck;
import com.trs.simulation.evidence.ObjectiveEvidence.GoalScope;
import com.trs.simulation.evidence.ObjectiveProof;
import com.trs.simulation.graph.KnowledgeCoverage;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Records text completion from authorized persisted evidence. @author czhcc-trsdsj\codexsandboxoffline */
final class CompleteObjective implements AgentTool {
    private final HierarchyHost host;
    private final TaskRecord root;
    private volatile boolean completed;

    CompleteObjective(HierarchyHost host, TaskRecord root) {
        this.host = host;
        this.root = root;
    }

    boolean completed() {
        return completed;
    }

    /** Identifies the text objective completion tool. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public String getName() {
        return "complete_objective";
    }

    /** Explains the boundary between model completion and configured engine conditions. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public String getDescription() {
        return "Finish the saved text objective immediately when evidence answers it. Requires the public answer and event sequences from current graph reads, accepted Action receipts or an approved external observation. Ends delegation for this objective.";
    }

    /** Accepts a bounded public answer and unique trace references. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public Map<String, Object> getParameters() {
        return ActionToolSchemas.object(
                Map.of(
                        "summary",
                                Map.of(
                                        "type",
                                        "string",
                                        "minLength",
                                        1,
                                        "maxLength",
                                        1500,
                                        "description",
                                        "Answer to the saved objective"),
                        "evidenceSequences",
                                Map.of(
                                        "type",
                                        "array",
                                        "minItems",
                                        1,
                                        "maxItems",
                                        8,
                                        "items",
                                        Map.of("type", "integer", "minimum", 1))),
                List.of("summary", "evidenceSequences"));
    }

    /** Verifies current-version and historical evidence before preventing any more delegations. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
        return Mono.fromSupplier(() -> {
            host.check();
            if (completed)
                return ToolResultBlock.text("Text objective completion already recorded. Return the round report.");
            var input = call.getInput();
            if (!input.keySet().equals(Set.of("summary", "evidenceSequences"))
                    || !(input.get("summary") instanceof String summary)
                    || summary.isBlank()
                    || summary.length() > 1500
                    || !(input.get("evidenceSequences") instanceof List<?> values)
                    || values.isEmpty()
                    || values.size() > 8)
                return ToolResultBlock.error("Completion requires a public answer and 1 to 8 evidence event sequences");
            var references = new LinkedHashSet<Long>();
            try {
                for (var value : values)
                    references.add(new java.math.BigDecimal(String.valueOf(value)).longValueExact());
            } catch (NumberFormatException | ArithmeticException invalid) {
                return ToolResultBlock.error("Evidence references must be integer event sequences");
            }
            var scene = host.scenario();
            var master = scene.agents().stream()
                    .filter(agent -> agent.id().equals(root.agentId()))
                    .findFirst()
                    .orElseThrow();
            var visible = host.visibleIds(master);
            var state = host.state();
            long version = state.version();
            var coverage = host.knowledgeCoverage();
            var goal = ObjectiveEvidence.goalScope(scene);
            var proof = ObjectiveProof.assess(
                    new ObjectiveProof.Context(scene, host.initial(), state, visible, master.id(), coverage),
                    host.history(),
                    references);
            if (proof.check() != CompletionCheck.SUPPORTED) {
                return defer(version, coverage, goal, proof.check());
            }
            // The submitted text is a proposal. Expansion objectives publish only the server's scoped answer.
            var completion = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "summary",
                    coverage == null ? summary : proof.summary(),
                    "evidenceSequences",
                    references,
                    "round",
                    host.round(),
                    "version",
                    version));
            if (coverage != null) {
                completion.put("goalScope", goal.name());
            }
            KnowledgeCoverage.annotate(coverage, completion);
            host.record(ObjectiveEvidence.COMPLETION_EVENT, root.agentId(), completion);
            completed = true;
            return ToolResultBlock.text(
                    "Text objective completion recorded. Return the round report with no further tasks.");
        });
    }

    private ToolResultBlock defer(long version, KnowledgeCoverage coverage, GoalScope goal, CompletionCheck check) {
        if (coverage != null || goal != GoalScope.RUN_GRAPH) {
            var details = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "reasonCode", check.name(), "goalScope", goal.name(), "round", host.round(), "version", version));
            KnowledgeCoverage.annotate(coverage, details);
            host.record(ObjectiveEvidence.COMPLETION_DEFERRED, root.agentId(), details);
        }
        return ToolResultBlock.error(
                switch (check) {
                    case GOAL_SCOPE_UNDETERMINED ->
                        "GOAL_SCOPE_UNDETERMINED: Specify whether the objective concerns the authorized run graph.";
                    case EXTERNAL_COVERAGE_PARTIAL, EXTERNAL_COVERAGE_UNKNOWN, EXTERNAL_SCOPE_UNVERIFIED ->
                        check.name() + ": DATA_REQUIRED. Local search cannot establish coverage of the external graph.";
                    case SEARCH_INCOMPLETE -> "SEARCH_INCOMPLETE: Refine or finish the authorized query.";
                    default ->
                        "Use only recorded, visible, complete current-version queries or accepted Action evidence";
                });
    }
}
