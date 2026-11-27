package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.common.Json;
import com.trs.simulation.graph.RunGraphReadService;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Operation;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.state.VisibilityPolicy;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import reactor.core.publisher.Mono;

/** Per-task tool scope; shared state is reachable only through a validated host. @author czhcc-trsdsj\codexsandboxoffline */
final class WorkerTools {
    private final HierarchyHost host;
    private final RunGraphReadService graph;
    private final String parentTaskId;
    private final IntSupplier nextActionAttempt;
    private final TaskRecord task;
    private final AgentDefinition agent;
    private final List<Map<String, Object>> queryResults = new ArrayList<>();
    private final List<Map<String, Object>> receipts = new ArrayList<>();
    private int queries;

    WorkerTools(
            HierarchyHost host,
            RunGraphReadService graph,
            String parentTaskId,
            TaskRecord task,
            AgentDefinition agent,
            IntSupplier nextActionAttempt) {
        this.host = host;
        this.graph = graph;
        this.parentTaskId = parentTaskId;
        this.nextActionAttempt = nextActionAttempt;
        this.task = task;
        this.agent = agent;
    }

    List<Map<String, Object>> queryResults() {
        return queryResults;
    }

    List<Map<String, Object>> receipts() {
        return receipts;
    }

    AgentTool submission(ActionToolSchemas.Submission submission) {
        return new SubmissionTool(submission);
    }

    /** Queries a fresh runtime projection with evidence and scope metadata. @author czhcc-trsdsj\codexsandboxoffline */
    @Tool(
            name = "graph_query",
            description =
                    "Read the authorized runtime graph. Allowed tools: graph_get_entity, graph_neighbors, graph_subgraph, graph_path, graph_metrics. For graph_path, pass {from,to} for a shortest route or {from,rankByType} to find the paths with the most distinct nodes of the selected type; the latter can optionally specify to, maxDepth and limit. Other arguments may include entityId, direction, relationType, depth, offset and limit. Results cite an eventSequence.",
            readOnly = true)
    public Map<String, Object> query(
            @ToolParam(name = "tool", description = "Authorized query tool name") String tool,
            @ToolParam(name = "arguments", description = "Query parameters; object IDs must be visible")
                    Map<String, Object> arguments) {
        host.check();
        if (++queries > 5) throw new IllegalArgumentException("Task query limit reached");
        var args = arguments == null ? Map.<String, Object>of() : arguments;
        var read = graph.read(agent, host.state(), tool, args, host.knowledgeCoverage());
        var result = read.result();
        if (Json.write(result).length() > 24000)
            throw new IllegalArgumentException("Query result too large; reduce limit");
        long sequence = host.record(
                "GRAPH_QUERY_RESULT",
                agent.id(),
                Map.of(
                        "taskId",
                        task.taskId(),
                        "parentTaskId",
                        parentTaskId,
                        "tool",
                        tool,
                        "arguments",
                        args,
                        "result",
                        result,
                        "scopeSize",
                        read.scopeIds().size(),
                        "scopeIds",
                        read.scopeIds(),
                        "reused",
                        read.reused()));
        var cited = new LinkedHashMap<>(result);
        cited.put("eventSequence", sequence);
        cited.put("reused", read.reused());
        queryResults.add(cited);
        return cited;
    }
    /** 查询冻结白名单中的外部图谱。 @author czhcc-trsdsj\codexsandboxoffline */
    @Tool(
            name = "external_graph_query",
            description = "Read an approved external graph selector for this task",
            readOnly = true)
    public Map<String, Object> externalQuery(
            @ToolParam(name = "provider") String provider,
            @ToolParam(name = "operation") String operation,
            @ToolParam(name = "selector") String selector,
            @ToolParam(name = "depth") int depth,
            @ToolParam(name = "limit") int limit) {
        return host.externalQuery(task, provider, new Query(Operation.valueOf(operation), selector, depth, limit));
    }

    /** 只申请导入工具查询返回的候选。 @author czhcc-trsdsj\codexsandboxoffline */
    @Tool(name = "world_request_import", description = "Request operator approval for a previously returned candidate")
    public Map<String, Object> requestExternalImport(@ToolParam(name = "candidateId") String candidateId) {
        host.requestExternalImport(task, candidateId);
        return Map.of("candidateId", candidateId, "status", "PENDING_APPROVAL");
    }

    /** Lists current executable schemas; a query role cannot obtain mutation authority. @author czhcc-trsdsj\codexsandboxoffline */
    @Tool(
            name = "action_capabilities",
            description =
                    "Read currently available object Actions, required parameter schemas and allowed related object IDs.",
            readOnly = true)
    public List<Map<String, Object>> capabilities() {
        host.check();
        if (!agent.runtime().role().equals("ACTION"))
            throw new IllegalArgumentException("Query role has no business Actions");
        return host.capabilities(agent).stream()
                .filter(ActionCapability::available)
                .limit(50)
                .map(c -> Map.<String, Object>of(
                        "targetId",
                        c.targetId(),
                        "targetName",
                        c.targetName(),
                        "actionType",
                        c.action().actionType(),
                        "parametersSchema",
                        c.action().parametersSchema(),
                        "parameterOptions",
                        c.parameterOptions(),
                        "expectedVersion",
                        host.state().version()))
                .toList();
    }

    private synchronized Map<String, Object> execute(
            String action,
            String target,
            Map<String, Object> parameters,
            long version,
            String reason,
            String inputEventId) {
        host.check();
        if (!agent.runtime().role().equals("ACTION")
                || !agent.allowedActions().contains(action)
                || "END".equals(action)) throw new IllegalArgumentException("Task role cannot perform this Action");
        if (receipts.isEmpty()
                && nextActionAttempt.getAsInt() > host.scenario().team().maxActionsPerRound())
            throw new IllegalArgumentException("Round Action limit reached");
        var receipt = host.execute(
                task, agent, new Decision(action, target, parameters, reason, "ENTITY"), version, inputEventId);
        if (!receipts.isEmpty()) return receipts.getFirst();
        var scope = host.visibleIds(agent);
        boolean visible = receipt.get("relatedIds") instanceof List<?> related
                && scope.containsAll(related)
                && VisibilityPolicy.referencesVisible(
                        receipt.get("parameters"), VisibilityPolicy.hiddenIds(host.state(), scope));
        var cited = new LinkedHashMap<String, Object>();
        if (visible) {
            cited.putAll(receipt);
        } else {
            for (String field : List.of("taskId", "accepted", "code", "inputVersion", "outputVersion")) {
                cited.put(field, receipt.get(field));
            }
        }
        host.history().reversed().stream()
                .filter(event -> visible
                        && event.eventType().equals("ACTION_TASK_RECEIPT")
                        && task.taskId().equals(event.payload().get("taskId"))
                        && receipt.get("actionRunId").equals(event.payload().get("actionRunId")))
                .findFirst()
                .ifPresent(event -> cited.put("eventSequence", event.sequence()));
        receipts.add(cited);
        return cited;
    }

    /** Maps one generated schema to the permission-checked Action gateway. @author czhcc-trsdsj\codexsandboxoffline */
    private final class SubmissionTool implements AgentTool {
        private final ActionToolSchemas.Submission submission;

        private SubmissionTool(ActionToolSchemas.Submission submission) {
            this.submission = submission;
        }

        /** Stable SDK-safe name for this Action. @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public String getName() {
            return ActionToolSchemas.toolName(submission.actionType());
        }
        /** Describes the bound Action and any event binding. @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public String getDescription() {
            return submission.description();
        }
        /** Publishes the selected Action's current graph schema. @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public Map<String, Object> getParameters() {
            return submission.schema();
        }
        /** Parses the envelope and leaves business validation to the run gateway. @author czhcc-trsdsj\codexsandboxoffline */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
            return Mono.fromSupplier(() -> {
                var fields = (Map<?, ?>) submission.schema().get("properties");
                var args = call.getInput();
                if (!fields.keySet().equals(args.keySet())
                        || !(args.get("targetId") instanceof String target)
                        || !(args.get("parameters") instanceof Map<?, ?> parameters)
                        || !(args.get("reason") instanceof String reason)
                        || !(args.get("expectedVersion") instanceof Number version)
                        || submission.inputBound() && !(args.get("inputEventId") instanceof String))
                    return ToolResultBlock.error("Action arguments must match the generated tool schema");
                long expected;
                try {
                    expected = new java.math.BigDecimal(version.toString()).longValueExact();
                } catch (NumberFormatException | ArithmeticException ex) {
                    return ToolResultBlock.error("expectedVersion must be an integer");
                }
                var actionParameters = new LinkedHashMap<String, Object>();
                parameters.forEach((key, value) -> actionParameters.put(String.valueOf(key), value));
                return ToolResultBlock.text(
                        Json.write(execute(submission.actionType(), target, actionParameters, expected, reason, (String)
                                args.get("inputEventId"))));
            });
        }
    }
}
