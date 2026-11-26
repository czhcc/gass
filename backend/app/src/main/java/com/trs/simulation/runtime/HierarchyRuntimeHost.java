package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.SimulationTypes.TeamConfig;
import com.trs.simulation.agent.HierarchyHost;
import com.trs.simulation.common.Json;
import com.trs.simulation.evidence.TaskEvidence;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.runtime.RunService.Lease;
import com.trs.simulation.state.VisibilityPolicy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** One invocation's short-lock gateway; no network call holds the runtime monitor or a transaction. @author czhcc-trsdsj\codexsandboxoffline */
final class HierarchyRuntimeHost implements HierarchyHost {
    private final String id;
    private final Lease lease;
    private final boolean reviewOnly;
    private final Map<String, Map<String, Object>> receipts = new HashMap<>();

    private final RunService runs;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ModelService models;
    private final RunStateRepository store;
    private final ActionCommitService commits;

    HierarchyRuntimeHost(
            RunService runs,
            JdbcTemplate db,
            TransactionTemplate tx,
            ModelService models,
            RunStateRepository store,
            ActionCommitService commits,
            String id,
            Lease lease,
            boolean reviewOnly) {
        this.runs = runs;
        this.db = db;
        this.tx = tx;
        this.models = models;
        this.store = store;
        this.commits = commits;
        this.id = id;
        this.lease = lease;
        this.reviewOnly = reviewOnly;
    }

    /** Frozen configuration. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public Scenario scenario() {
        return store.scenario(id);
    }
    /** Latest committed state. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public State state() {
        return runs.state(id);
    }
    /** Initial template snapshot. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public State initial() {
        return store.initialState(id);
    }
    /** Current planning round. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public int round() {
        return runs.get(id).step();
    }
    /** Fresh object-bound capabilities. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public List<ActionCapability> capabilities(AgentDefinition agent) {
        synchronized (runs) {
            check();
            var world = store.frozenWorld(id);
            return runs.domain(world.domainCode()).capabilities(world, agent, state());
        }
    }
    /** Uses the same scope policy as observations and capabilities. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public Set<String> visibleIds(AgentDefinition agent) {
        var world = store.frozenWorld(id);
        return runs.domain(world.domainCode()).observationScope(agent, state());
    }
    /** Durable receipts and delivered inputs. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public List<Event> history() {
        return runs.events(id, 0);
    }
    /** Enforces cancellation and shared run budgets before every external or stateful operation. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public void check() {
        synchronized (runs) {
            runs.checkInvocation(id, lease, reviewOnly);
        }
    }
    /** Propagates pause/stop to the SDK subscription. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public reactor.core.publisher.Mono<Void> cancelled() {
        return lease.cancelledSignal();
    }
    /** Reserves each model request across master and all workers. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public void beforeModel(AgentDefinition agent, String taskId, int tokens) {
        synchronized (runs) {
            check();
            long request = runs.reserveModelRequest(id, agent.id(), tokens, reviewOnly);
            record(
                    "MODEL_REQUESTED",
                    agent.id(),
                    Map.of(
                            "taskId",
                            taskId,
                            "modelMode",
                            models.get(agent.modelId()).baseUrl().startsWith("mock:") ? "SIMULATED" : "LLM",
                            "modelId",
                            agent.modelId(),
                            "requestNumber",
                            request,
                            "reservedOutputTokens",
                            tokens));
        }
    }
    /** Creates an authorized task once; child retries reuse its durable identity. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public TaskRecord startTask(String taskId, String parentId, AgentDefinition agent, TaskRequest request) {
        synchronized (runs) {
            check();
            var existing = runs.tasks(id).stream()
                    .filter(t -> t.taskId().equals(taskId))
                    .findFirst();
            if (existing.isPresent()) {
                var previous = existing.get();
                if (!previous.agentId().equals(agent.id())
                        || !Objects.equals(previous.parentTaskId(), parentId)
                        || !previous.request().equals(request)) {
                    throw new IllegalArgumentException("Task identifier already belongs to another request");
                }
                return previous;
            }
            if (store.decisionCount(id, agent.id()) >= agent.maxDecisions())
                throw new IllegalStateException("Agent task budget exhausted");
            if (parentId != null
                    && runs.tasks(id).stream()
                            .noneMatch(t -> t.taskId().equals(parentId)
                                    && t.parentTaskId() == null
                                    && t.status().equals("RUNNING")))
                throw new IllegalArgumentException("Active parent task required");
            var effective = parentId == null ? agent : taskAgent(scenario(), agent.id(), request);
            if (parentId != null
                    && (!visibleIds(effective).containsAll(request.scopeIds())
                            || !effective.allowedActions().containsAll(request.allowedActions()))) {
                throw new IllegalArgumentException("Task exceeds the child role scope or permissions");
            }
            if (request.inputEventId() != null
                    && history().stream()
                            .noneMatch(e -> visibleInput(effective, e)
                                    && request.inputEventId().equals(e.payload().get("scheduledEventId"))))
                throw new IllegalArgumentException("Task input event is outside the authorized scope");
            tx.executeWithoutResult(t -> {
                store.append(
                        id,
                        "AGENT_STARTED",
                        state().simulationTime(),
                        agent.id(),
                        Map.of(
                                "taskId",
                                taskId,
                                "role",
                                agent.runtime().role(),
                                "modelMode",
                                models.get(agent.modelId()).baseUrl().startsWith("mock:") ? "SIMULATED" : "LLM"));
                store.append(
                        id,
                        parentId == null ? "MASTER_TASK_STARTED" : "SUBTASK_DISPATCHED",
                        state().simulationTime(),
                        agent.id(),
                        Map.of(
                                "taskId",
                                taskId,
                                "parentTaskId",
                                parentId == null ? "" : parentId,
                                "request",
                                request,
                                "inputVersion",
                                state().version()));
                db.update(
                        "INSERT INTO simulation_task VALUES (?,?,?,?,?,'RUNNING',?,?,?,'{}',?,NULL)",
                        id,
                        taskId,
                        parentId,
                        agent.id(),
                        round(),
                        state().version(),
                        state().version(),
                        Json.write(request),
                        store.eventCount(id));
            });
            return runs.tasks(id).stream()
                    .filter(t -> t.taskId().equals(taskId))
                    .findFirst()
                    .orElseThrow();
        }
    }
    /** Persists terminal task results even during cancellation, without changing graph state. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public void completeTask(String taskId, String status, Map<String, Object> result) {
        synchronized (runs) {
            var task = runs.tasks(id).stream()
                    .filter(t -> t.taskId().equals(taskId))
                    .findFirst()
                    .orElse(null);
            if (task == null || !task.status().equals("RUNNING")) return;
            var actual = new LinkedHashMap<>(result);
            if (receipts.containsKey(taskId)) {
                var receipt = receipts.get(taskId);
                actual.put("receipts", List.of(receipt));
                actual.put("inputEventId", receipt.getOrDefault("inputEventId", ""));
            }
            tx.executeWithoutResult(t -> {
                store.append(
                        id,
                        task.parentTaskId() == null ? "MASTER_TASK_COMPLETED" : "SUBTASK_COMPLETED",
                        state().simulationTime(),
                        task.agentId(),
                        Map.of(
                                "taskId",
                                taskId,
                                "parentTaskId",
                                task.parentTaskId() == null ? "" : task.parentTaskId(),
                                "status",
                                status,
                                "result",
                                actual));
                db.update(
                        "UPDATE simulation_task SET status=?,output_version=?,result_json=?,completed_sequence=? WHERE run_id=? AND task_id=?",
                        status,
                        state().version(),
                        Json.write(actual),
                        store.eventCount(id),
                        id,
                        taskId);
            });
        }
    }
    /** Serializes engine validation, optimistic concurrency and idempotent task receipts. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public Map<String, Object> execute(
            TaskRecord task, AgentDefinition agent, Decision decision, long expectedVersion, String inputEventId) {
        synchronized (runs) {
            check();
            var activeTask = runs.tasks(id).stream()
                    .filter(t -> t.taskId().equals(task.taskId()) && t.status().equals("RUNNING"))
                    .findFirst()
                    .orElse(null);
            if (activeTask == null
                    || !activeTask.agentId().equals(agent.id())
                    || !task.runId().equals(id)
                    || decision == null
                    || "END".equals(decision.actionType())) {
                throw new IllegalArgumentException("Task lacks mutation authority");
            }
            var current = state();
            var scene = scenario();
            var team = scene.team();
            var effective = taskAgent(scene, activeTask.agentId(), activeTask.request());
            if (!effective.runtime().role().equals("ACTION") || activeTask.parentTaskId() == null) {
                throw new IllegalArgumentException("Task lacks mutation authority");
            }
            if (decision.reason() == null
                    || decision.reason().length() > 1000
                    || Json.write(decision).length() > 6000) {
                throw new IllegalArgumentException("Invalid Action request");
            }
            var command = new ActionCommitService.Command(
                    id,
                    "task:" + task.taskId(),
                    effective.id(),
                    expectedVersion,
                    decision,
                    "AGENT_ACTION",
                    task.taskId(),
                    inputEventId);
            var prior = commits.retry(command);
            if (prior != null) {
                return prior.details();
            }
            var world = store.frozenWorld(id);
            var plugin = runs.domain(world.domainCode());
            Outcome result;
            if (expectedVersion != current.version())
                result = new Outcome(
                        false,
                        "State changed; query current capabilities and create a new task",
                        current,
                        false,
                        "VERSION_CONFLICT",
                        List.of());
            else if (inputEventId == null && TaskEvidence.requiresInput(team, decision.actionType()))
                result = new Outcome(
                        false,
                        "Input-bound Action requires an inputEventId",
                        current,
                        false,
                        "INPUT_REQUIRED",
                        List.of());
            else if ((activeTask.request().inputEventId() != null
                            && !activeTask.request().inputEventId().equals(inputEventId))
                    || (inputEventId != null && !inputMatches(inputEventId, decision, team, effective)))
                result = new Outcome(
                        false,
                        "Action does not match the bound input request",
                        current,
                        false,
                        "INPUT_ACTION_MISMATCH",
                        List.of());
            else if (inputEventId != null
                    && TaskEvidence.handledInputs(history()).contains(inputEventId))
                result = new Outcome(
                        false,
                        "Input already has an accepted Action receipt",
                        current,
                        false,
                        "INPUT_ALREADY_HANDLED",
                        List.of());
            else if (receipts.size() >= team.maxActionsPerRound())
                result = new Outcome(
                        false, "Round Action attempt limit reached", current, false, "ROUND_ACTION_LIMIT", List.of());
            else result = plugin.execute(world, effective, plugin.normalizeDecision(decision, current), current);
            var receipt = commits.commit(command, decision, current, result).details();
            receipts.put(task.taskId(), receipt);
            return receipt;
        }
    }

    private AgentDefinition taskAgent(Scenario scene, String agentId, TaskRequest request) {
        var role = scene.agents().stream()
                .filter(member -> member.id().equals(agentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task agent"));
        var root = scene.agents().stream()
                .filter(member -> member.id().equals(scene.team().rootAgentId()))
                .findFirst()
                .orElseThrow();
        return VisibilityPolicy.delegated(role, root, request);
    }

    private boolean visibleInput(AgentDefinition agent, Event event) {
        var scope = visibleIds(agent);
        return TaskEvidence.visibleInput(event, scope, VisibilityPolicy.hiddenIds(state(), scope));
    }

    private boolean inputMatches(String inputId, Decision decision, TeamConfig team, AgentDefinition agent) {
        return history().stream()
                .anyMatch(e -> inputId.equals(e.payload().get("scheduledEventId"))
                        && visibleInput(agent, e)
                        && TaskEvidence.matchesInputType(team, decision.actionType(), e)
                        && Objects.equals(e.payload().get("targetId"), decision.targetId())
                        && Objects.equals(e.payload().get("parameters"), decision.parameters()));
    }
    /** Writes public evidence with stable ordering and returns its sequence. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public long record(String type, String agentId, Map<String, Object> payload) {
        synchronized (runs) {
            check();
            if (Json.write(payload).length() > 40000) throw new IllegalArgumentException("Event payload too large");
            store.append(id, type, state().simulationTime(), agentId, payload);
            return db.queryForObject("SELECT MAX(sequence_no) FROM simulation_event WHERE run_id=?", Long.class, id);
        }
    }
}
