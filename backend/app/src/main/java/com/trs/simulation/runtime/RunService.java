package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.ActionCatalog;
import com.trs.simulation.SimulationTypes.AgentCapabilities;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.BusinessOutcome;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Observation;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.agent.DecisionProvider;
import com.trs.simulation.agent.HierarchicalCoordinator;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.DomainEnvironment;
import com.trs.simulation.evidence.TaskEvidence;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.scenario.ScenarioProfiles;
import com.trs.simulation.state.VisibilityPolicy;
import com.trs.simulation.world.WorldService;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controlled single-writer event-driven runtime. LLM calls never hold DB transactions. @author
 * agent
 */
@Service
public class RunService {
    @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-decisions:50}")
    private int decisionBudget;

    @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-model-requests:300}")
    private int requestBudget;

    @org.springframework.beans.factory.annotation.Value("${studio.simulation.enforce-output-token-budget:false}")
    private boolean enforceOutputTokenBudget;

    @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-generated-tokens:307200}")
    private long tokenBudget;

    @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-runtime-seconds:600}")
    private int timeBudget;

    @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-state-changes:50}")
    private int changeBudget;

    private final JdbcTemplate db;
    private final RunStateRepository store;
    private final ActionCommitService commits;
    private final RoundScheduler scheduler;
    private final EventClockScheduler eventClock;
    private final DurableReasoning reasoning;
    private final EventRunPolicy eventPolicy;
    private final RunTerminationPolicy termination;
    private final TransactionTemplate tx;
    private final WorldService worlds;
    private final DecisionProvider decisions;
    private final HierarchicalCoordinator hierarchy;
    private final ModelService models;
    private final Map<String, Lease> invocations = new HashMap<>();
    /** Cancellation ownership for one round, shared by every nested task. @author czhcc-trsdsj\codexsandboxoffline */
    static final class Lease {
        long recoveryThrough = -1;
        private final reactor.core.publisher.Sinks.One<Void> cancellation = reactor.core.publisher.Sinks.one();
        private volatile boolean cancelled;

        reactor.core.publisher.Mono<Void> cancelledSignal() {
            return cancellation.asMono();
        }

        private void cancel() {
            cancelled = true;
            cancellation.tryEmitEmpty();
        }
    }

    private final Map<String, DomainEnvironment> domains = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, UUID> active = new ConcurrentHashMap<>();
    private java.util.function.LongSupplier nanoClock = System::nanoTime;
    private final Map<String, Long> activeStarts = new ConcurrentHashMap<>();

    /** Injects SDK-independent runtime boundaries and domain plugins. @author agent */
    public RunService(
            JdbcTemplate db,
            TransactionTemplate tx,
            WorldService worlds,
            DecisionProvider decisions,
            List<DomainEnvironment> plugins,
            HierarchicalCoordinator hierarchy,
            ModelService models) {
        this.db = db;
        this.store = new RunStateRepository(db);
        this.eventClock = new EventClockScheduler(db, tx, store);
        this.reasoning = new DurableReasoning(db, tx, store);
        this.eventPolicy = new EventRunPolicy(eventClock, store, reasoning);
        this.commits = new ActionCommitService(db, tx, store, eventClock, reasoning);
        this.scheduler = new RoundScheduler(db, tx, store);
        this.termination = new RunTerminationPolicy(db, tx, store, scheduler, this::get);
        this.tx = tx;
        this.worlds = worlds;
        this.decisions = decisions;
        this.hierarchy = hierarchy;
        this.models = models;
        plugins.forEach(p -> domains.put(p.domainCode(), p));
    }

    /** Restores interrupted runs and closes their task audit records after restart. @author czhcc-trsdsj\codexsandboxoffline */
    @jakarta.annotation.PostConstruct
    public synchronized void recover() {
        db.update("UPDATE simulation_run SET status='PAUSED' WHERE status='RUNNING'");
        eventClock.recoverClaims();
        var interrupted =
                db.queryForList("SELECT DISTINCT run_id FROM simulation_task WHERE status='RUNNING'", String.class);
        for (var id : interrupted)
            for (var task : tasks(id))
                if (task.status().equals("RUNNING")) {
                    var receipts = events(id, 0).stream()
                            .filter(e -> e.eventType().equals("ACTION_TASK_RECEIPT")
                                    && task.taskId().equals(e.payload().get("taskId")))
                            .map(Event::payload)
                            .toList();
                    String originalInput = task.request().inputEventId() == null
                            ? ""
                            : task.request().inputEventId();
                    var inputId = receipts.isEmpty()
                            ? originalInput
                            : receipts.getFirst().getOrDefault("inputEventId", originalInput);
                    var result = Map.<String, Object>of(
                            "message",
                            "Task cancelled after application restart",
                            "receipts",
                            receipts,
                            "inputEventId",
                            inputId);
                    tx.executeWithoutResult(t -> {
                        store.append(
                                id,
                                task.parentTaskId() == null ? "MASTER_TASK_COMPLETED" : "SUBTASK_COMPLETED",
                                state(id).simulationTime(),
                                task.agentId(),
                                Map.of(
                                        "taskId",
                                        task.taskId(),
                                        "parentTaskId",
                                        task.parentTaskId() == null ? "" : task.parentTaskId(),
                                        "status",
                                        "CANCELLED",
                                        "result",
                                        result));
                        db.update(
                                "UPDATE simulation_task SET status='CANCELLED',output_version=?,result_json=?,completed_sequence=? WHERE run_id=? AND task_id=?",
                                state(id).version(),
                                Json.write(result),
                                store.eventCount(id),
                                id,
                                task.taskId());
                    });
                }
    }

    /**
     * Creates an isolated run from frozen scenario/world inputs without contacting a model. @author
     * agent
     */
    public synchronized Run create(String scenarioId) {
        var scenario = worlds.scenario(scenarioId);
        var world = worlds.get(scenario.worldId());
        String id = UUID.randomUUID().toString();
        var state = new State(id, 0, 0, world.entities(), world.relations());
        tx.executeWithoutResult(t -> {
            db.update(
                    "INSERT INTO simulation_run (id,scenario_json,status,step,state_json,created_at,world_json)"
                            + " VALUES (?,?, 'PAUSED',0,?,?,?)",
                    id,
                    Json.write(scenario),
                    Json.write(state),
                    System.currentTimeMillis(),
                    Json.write(world));
            db.update("INSERT INTO simulation_snapshot VALUES (?,?,?)", id, 0, Json.write(state));
            db.update("INSERT INTO simulation_usage(run_id) VALUES (?)", id);
            store.append(
                    id,
                    "RUN_STARTED",
                    0,
                    null,
                    Map.of(
                            "mode",
                            scenario.scheduling() == null
                                    ? "EVENT_DRIVEN"
                                    : scenario.scheduling().mode(),
                            "status",
                            "PAUSED",
                            "actionOrigin",
                            "INITIALIZATION",
                            "outputVersion",
                            0));
        });
        return get(id);
    }

    /** Lists persisted runs. @author agent */
    public List<Run> list() {
        return db.query(
                "SELECT id,status,step,JSON_EXTRACT(scenario_json,'$.id')"
                        + " scenario_id,JSON_EXTRACT(scenario_json,'$.maxSteps')"
                        + " max_steps,JSON_EXTRACT(state_json,'$.version') version,active_runtime_ms,termination_reason FROM simulation_run ORDER BY"
                        + " created_at DESC",
                (r, n) -> new Run(
                        r.getString("id"),
                        r.getString("scenario_id"),
                        r.getString("status"),
                        r.getInt("step"),
                        r.getInt("max_steps"),
                        r.getLong("version"),
                        r.getLong("active_runtime_ms") + currentIntervalMs(r.getString("id")),
                        r.getString("termination_reason")));
    }

    /** Safe deletion conflict with a stable client code. @author czhcc-trsdsj\codexsandboxoffline */
    public static final class DeletionConflict extends IllegalStateException {
        private final String code;

        /** Creates a safe lifecycle conflict. @author czhcc-trsdsj\codexsandboxoffline */
        public DeletionConflict(String code, String message) {
            super(message);
            this.code = code;
        }

        /** Returns the client error code. @author czhcc-trsdsj\codexsandboxoffline */
        public String code() {
            return code;
        }
    }

    /**
     * Deletes an inactive leaf run and its owned history atomically, preserving templates and siblings.
     * Shares the runtime lock with stepping and forking to prevent writes during deletion.
     * @author czhcc-trsdsj\codexsandboxoffline
     */
    public synchronized void delete(String id) {
        var run = get(id);
        if (run.status().equals("RUNNING") || invocations.containsKey(id))
            throw new DeletionConflict("RUN_ACTIVE", "Pause or stop this run before deleting it.");
        tx.executeWithoutResult(t -> {
            if (db.queryForObject("SELECT COUNT(*) FROM simulation_branch WHERE parent_run_id=?", Integer.class, id)
                    > 0) throw new DeletionConflict("RUN_HAS_BRANCHES", "Delete child branches first.");
            db.update("DELETE FROM simulation_branch WHERE run_id=?", id);
            db.update("DELETE FROM simulation_scheduled_event WHERE run_id=?", id);
            db.update("DELETE FROM simulation_command WHERE run_id=?", id);
            db.update("DELETE FROM simulation_reasoning_action WHERE run_id=?", id);
            db.update("DELETE FROM simulation_reasoning_round WHERE run_id=?", id);
            db.update("DELETE FROM simulation_action_receipt WHERE run_id=?", id);
            db.update("DELETE FROM simulation_snapshot WHERE run_id=?", id);
            db.update("DELETE FROM simulation_event WHERE run_id=?", id);
            db.update("DELETE FROM simulation_task WHERE run_id=?", id);
            db.update("DELETE FROM simulation_usage WHERE run_id=?", id);
            db.update("DELETE FROM simulation_run WHERE id=?", id);
        });
        active.remove(id);
    }

    /** Evaluates current domain metrics through its plugin. @author agent */
    public Map<String, Object> evaluate(String id) {
        var domain = store.frozenWorld(id).domainCode();
        return domains.get(domain).evaluate(state(id));
    }

    /**
     * Returns the frozen agent configuration for activity and intervention controls. @author agent
     */
    public List<AgentDefinition> agents(String id) {
        return store.scenario(id).agents();
    }

    /** Seeds a configured example input using this run's frozen world and an idempotent key. @author czhcc-trsdsj\codexsandboxoffline */
    public synchronized void seedInput(String id, ScenarioProfiles profiles) {
        inject(id, "demo-initial", profiles.initialInput(store.frozenWorld(id)));
    }

    /** Lists current object capabilities for manual decisions without invoking a model. @author czhcc-trsdsj\codexsandboxoffline */
    public ActionCatalog actionCapabilities(String id) {
        return tx.execute(transaction -> {
            var world = store.frozenWorld(id);
            var state = state(id);
            var plugin = domains.get(world.domainCode());
            return new ActionCatalog(
                    world.domainCode(),
                    state.version(),
                    store.scenario(id).agents().stream()
                            .map(agent -> {
                                boolean ended = store.hasEnded(id, agent.id());
                                return new AgentCapabilities(
                                        agent.id(),
                                        ended ? List.of() : plugin.capabilities(world, agent, state),
                                        ended);
                            })
                            .toList());
        });
    }

    /** Returns metadata for an isolated run. @author agent */
    public Run get(String id) {
        var s = store.scenario(id);
        var row = db.queryForMap(
                "SELECT status,step,active_runtime_ms,termination_reason FROM simulation_run WHERE id=?", id);
        return new Run(
                id,
                s.id(),
                (String) row.get("status"),
                ((Number) row.get("step")).intValue(),
                s.maxSteps(),
                state(id).version(),
                ((Number) row.get("active_runtime_ms")).longValue() + currentIntervalMs(id),
                (String) row.get("termination_reason"),
                s.objective());
    }

    /** Gets current immutable state. @author agent */
    public State state(String id) {
        return store.state(id);
    }

    /**
     * Reads an exact committed state version without re-executing an agent or action. @author agent
     */
    public State replay(String id, long version) {
        return store.replay(id, version);
    }

    /** Reads durable ordered events for reconnect and audit. @author agent */
    public List<Event> events(String id, long after) {
        return store.events(id, after);
    }

    /** Reports strict scheduling metadata and work status without executing the queue. @author czhcc-trsdsj\codexsandboxoffline */
    public synchronized Map<String, Object> scheduling(String id) {
        var scene = store.scenario(id);
        var current = state(id);
        if (!scene.eventDriven()) {
            return Map.of("mode", "TASK_DRIVEN", "simulationTime", current.simulationTime());
        }
        String queueState = events(id, 0).reversed().stream()
                .filter(event -> "SCENARIO_EVALUATED".equals(event.eventType()))
                .map(event -> (String) event.payload().get("queueState"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(() ->
                        eventClock.unfinished(id) == 0 && reasoning.pending(id) == null ? "QUIESCENT" : "PENDING");
        return Map.of(
                "mode",
                "EVENT_DRIVEN",
                "policy",
                scene.scheduling(),
                "evaluationPolicy",
                eventPolicy.policy(scene),
                "simulationTime",
                current.simulationTime(),
                "round",
                get(id).step(),
                "stateVersion",
                current.version(),
                "queueState",
                queueState,
                "work",
                eventClock.queue(id));
    }

    /**
     * Schedules an idempotent event; no state changes occur outside domain validation. @author agent
     */
    public synchronized void inject(String id, String key, ScheduledInput event) {
        var run = get(id);
        var scenario = store.scenario(id);
        if (!scenario.eventDriven() && !List.of("PAUSED", "RUNNING").contains(run.status()))
            throw new IllegalStateException("Run is terminal");
        if (key == null
                || key.isBlank()
                || key.length() > 100
                || event == null
                || event.eventType() == null
                || !(List.of("POTENTIAL_LEAK", "USER_NOTE", "BANK_TRANSFER").contains(event.eventType())
                        || store.scenario(id).team() != null
                                && store.scenario(id).team().inputActions().containsKey(event.eventType()))
                || event.parameters() == null
                || !scenario.eventDriven() && event.simulationTime() < state(id).simulationTime()
                || !scenario.eventDriven() && event.simulationTime() > scheduler.baseTime(id) + run.maxSteps()
                || scenario.eventDriven() && key.startsWith("domain:")
                || event.priority() != null
                        && (!scenario.eventDriven() || event.priority() < -1000 || event.priority() > 1000)
                || Json.write(event).length() > 4000) throw new IllegalArgumentException("Invalid scheduled event");
        if (!scenario.eventDriven() && state(id).entities().stream().noneMatch(e -> e.id().equals(event.targetId())))
            throw new IllegalArgumentException("Event target not found");
        if (!scenario.eventDriven() && scheduler.size(id) >= 100)
            throw new IllegalStateException("Event queue budget reached");
        String payload = Json.write(event);
        var existing = scheduler.existing(id, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(payload))
                throw new IllegalStateException("Idempotency key already used for another event");
            return;
        }
        if (scenario.eventDriven()) {
            if (!List.of("PAUSED", "RUNNING").contains(run.status()))
                throw new IllegalStateException("Run is terminal");
            var current = state(id);
            if (event.simulationTime() < current.simulationTime())
                throw new IllegalArgumentException("Invalid scheduled event");
            if (current.entities().stream().noneMatch(e -> e.id().equals(event.targetId())))
                throw new IllegalArgumentException("Event target not found");
        }
        if (scenario.eventDriven() && scheduler.size(id) >= 100)
            throw new IllegalStateException("Event queue budget reached");
        if (store.eventCount(id) >= store.scenario(id).maxEvents() - 1)
            throw new IllegalStateException("Event budget exhausted");
        if (scenario.eventDriven()) eventClock.schedule(id, key, event);
        else scheduler.schedule(id, key, event, payload);
    }

    /**
     * Executes one event-driven round with ordered fresh observations and bounded budgets. @author
     * agent
     */
    public State step(String id, String command) {
        return step(id, command, null);
    }

    private State step(String id, String command, UUID workerToken) {
        if (command == null || command.isBlank() || command.length() > 100)
            throw new IllegalArgumentException("Idempotency-Key required, max 100 characters");
        var lease = new Lease();
        synchronized (this) {
            if (workerToken != null
                    && (!workerToken.equals(active.get(id)) || !get(id).status().equals("RUNNING"))) return state(id);
            if (reasoning.finishedCommand(id, command)) return state(id);
            if (!List.of("PAUSED", "RUNNING").contains(get(id).status()))
                throw new IllegalStateException("Run is terminal");
            if (invocations.containsKey(id)) throw new IllegalStateException("Run is already computing a round");
            invocations.put(id, lease);
            activeStarts.put(id, nanoClock.getAsLong());
        }
        try {
            var scenario = store.scenario(id);
            if (scenario.eventDriven()) return stepEventClock(id, command, scenario, lease);
            if (scenario.team() == null) {
                synchronized (this) {
                    return stepRound(id, command);
                }
            }
            return stepRound(id, command);
        } finally {
            synchronized (this) {
                long elapsed = currentIntervalMs(id);
                activeStarts.remove(id);
                db.update("UPDATE simulation_run SET active_runtime_ms=active_runtime_ms+? WHERE id=?", elapsed, id);
                invocations.remove(id, lease);
            }
        }
    }

    private State stepEventClock(String id, String command, Scenario scene, Lease lease) {
        DurableReasoning.Pending round;
        synchronized (this) {
            if (lease.cancelled || checkTermination(id, scene, false)) return state(id);
            round = reasoning.pending(id);
            if (round == null) {
                long time = eventPolicy.nextTime(get(id), scene, state(id));
                var slice = eventClock.advance(
                        id, time, scene, false, delivered -> queueReasoning(id, command, scene, time, delivered));
                if (stopOnWorkBudget(id, scene, slice)) return state(id);
                round = reasoning.pending(id);
            }
            if (round != null) {
                if (round.started()) lease.recoveryThrough = store.eventCount(id);
                reasoning.start(id, command, round);
            }
        }
        if (round != null) {
            try {
                if (scene.team() == null) {
                    legacyRound(id, scene, round.time(), lease);
                } else if (!reasoning.succeededMaster(id, round.number())) {
                    runHierarchy(id, lease);
                }
            } catch (CancellationException ex) {
                return state(id);
            }
        }
        synchronized (this) {
            if (lease.cancelled || checkTermination(id, scene, false)) return state(id);
            if (round != null) {
                var slice = eventClock.advance(id, round.time(), scene, true, delivered -> {});
                reasoning.finish(id, round, eventClock);
                if (stopOnWorkBudget(id, scene, slice)) return state(id);
            }
            if (checkTermination(id, scene, false)) return state(id);
            var verdict = eventPolicy.verdict(get(id), scene, state(id));
            if (verdict != null) {
                termination.concludeEventRun(
                        id, scene, verdict.queueState(), verdict.remaining(), verdict.answered(), verdict.ready());
            }
        }
        reviewAtHorizon(id, scene, lease);
        return state(id);
    }

    // The scheduler calls this within the same transaction as clock advancement and delivery.
    private void queueReasoning(String id, String command, Scenario scene, long time, EventClockScheduler.Slice slice) {
        var run = get(id);
        int nextRound = run.step() + 1;
        boolean plainInput = scene.team() == null
                && slice.firstExternalSequence() != null
                && scene.agents().stream()
                        .anyMatch(a -> !store.hasEnded(id, a.id())
                                && reasoning.priorLegacyRounds(id, a.id(), nextRound) < a.maxDecisions());
        boolean needsRound = !slice.interrupted()
                && (plainInput
                        || eventClock.awaitingAction(id) > 0
                        || eventPolicy.needsObjectiveRound(run, scene, state(id)));
        if (!needsRound) {
            reasoning.trackCommand(id, command, null);
            return;
        }
        if (run.step() >= scene.maxSteps()) {
            reasoning.trackCommand(id, command, null);
            termination.finish(id, "MAX_STEPS", run.step(), scene.maxSteps(), "Maximum reasoning rounds reached", "");
            return;
        }
        reasoning.queue(id, nextRound, time, state(id).version(), slice.firstExternalSequence());
        if (plainInput) eventClock.waitForReasoning(id, slice.firstExternalSequence());
        reasoning.trackCommand(id, command, nextRound);
    }

    private boolean stopOnWorkBudget(String id, Scenario scene, EventClockScheduler.Slice slice) {
        if (slice.workLimit()) {
            int limit = scene.scheduling().maxWorkPerTime();
            return termination.finish(
                    id,
                    "MAX_WORK_PER_TIME",
                    limit,
                    limit,
                    "Same-time work budget reached; remaining entries were preserved",
                    "");
        }
        return slice.eventLimit()
                && termination.finish(
                        id,
                        "MAX_EVENTS",
                        store.eventCount(id),
                        scene.maxEvents(),
                        "Event budget reached; remaining entries were preserved",
                        "");
    }

    private State stepRound(String id, String command) {
        Lease lease;
        synchronized (this) {
            prepareRound(id, command);
            lease = invocations.get(id);
        }
        var scenario = store.scenario(id);
        if (!List.of("PAUSED", "RUNNING").contains(get(id).status())) {
            reviewAtHorizon(id, scenario, lease);
            return state(id);
        }
        if (scenario.team() != null) {
            runHierarchy(id, lease);
            synchronized (this) {
                if (!lease.cancelled) checkTermination(id, scenario, true);
            }
            reviewAtHorizon(id, scenario, lease);
            return state(id);
        }
        long clock = scheduler.baseTime(id) + get(id).step();
        return legacyRound(id, scenario, clock, lease);
    }

    private void runHierarchy(String id, Lease lease) {
        try {
            hierarchy.run(hierarchyHost(id, lease, false));
        } catch (CancellationException ex) {
            /* Pause, stop and shared budgets preserve their authoritative lifecycle status. */
        } catch (RuntimeException ex) {
            if (store.scenario(id).eventDriven() && ex instanceof org.springframework.dao.DataAccessException) throw ex;
            synchronized (this) {
                if (List.of("PAUSED", "RUNNING").contains(get(id).status()) && !lease.cancelled) {
                    db.update(
                            "UPDATE simulation_run SET status='FAILED',termination_reason='MODEL_DECISION_FAILED' WHERE id=?",
                            id);
                    store.append(id, "RUN_FAILED", state(id).simulationTime(), null, hierarchyFailure(ex));
                }
            }
        }
    }

    private Map<String, Object> hierarchyFailure(RuntimeException failure) {
        Throwable detail = failure;
        String diagnostic = "HIERARCHICAL_EXECUTION_ERROR";
        for (int depth = 0; depth < 12; depth++) {
            if (detail instanceof TimeoutException
                    || detail instanceof java.net.SocketTimeoutException
                    || detail instanceof java.net.http.HttpTimeoutException) {
                diagnostic = "MODEL_REQUEST_TIMEOUT";
                break;
            }
            if (detail.getCause() == null) break;
            detail = detail.getCause();
        }
        return Map.of(
                "reasonCode",
                "MODEL_DECISION_FAILED",
                "reason",
                "Model planning or child task failed; inspect task receipts",
                "diagnosticCode",
                diagnostic,
                "exceptionType",
                detail.getClass().getSimpleName());
    }

    private void prepareRound(String id, String command) {
        var run = get(id);
        var scenario = store.scenario(id);
        if (checkTermination(id, scenario, true)) return;
        scheduler.advance(id, command, run.step());
    }

    private State legacyRound(String id, Scenario scenario, long clock, Lease lease) {
        var world = store.frozenWorld(id);
        var plugin = domains.get(world.domainCode());
        if (plugin == null) throw new IllegalStateException("Domain plugin unavailable");
        int round = get(id).step();
        for (var agent : scenario.agents()) {
            String commandId = "legacy:" + clock + ":" + (scenario.eventDriven() ? round + ":" : "") + agent.id();
            if (scenario.eventDriven() && reasoning.hasDecision(id, commandId, round)) continue;
            int count = scenario.eventDriven()
                    ? reasoning.priorLegacyRounds(id, agent.id(), round)
                    : db.queryForObject(
                            "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND agent_id=? AND"
                                    + " event_type='AGENT_STARTED'",
                            Integer.class,
                            id,
                            agent.id());
            if (count >= agent.maxDecisions() || store.hasEnded(id, agent.id())) continue;
            // Legacy budget estimates reserve six requests and 6144 output tokens per decision.
            if (!scenario.eventDriven() && checkTermination(id, scenario, false)) break;
            var before = state(id);
            var visible = plugin.observationScope(agent, before);
            var projected = VisibilityPolicy.project(before, visible);
            var hidden = VisibilityPolicy.hiddenIds(before, visible);
            var history = events(id, Math.max(0, store.eventCount(id) - 30)).stream()
                    .filter(e -> agent.id().equals(e.agentId())
                            && (e.eventType().equals("ACTION_ACCEPTED")
                                    || e.eventType().equals("ACTION_REJECTED")
                                    || e.eventType().equals("AGENT_MEMORY_RESTORED")))
                    .filter(e -> e.payload().get("relatedIds") instanceof List<?> ids
                            ? visible.containsAll(ids)
                            : hidden.isEmpty())
                    .map(e -> Json.write(e.payload()))
                    .toList();
            var observation = new Observation(
                    id,
                    clock,
                    before.version(),
                    projected.entities(),
                    projected.relations(),
                    history,
                    events(id, 0).stream()
                            .filter(e -> TaskEvidence.visibleInput(e, visible, hidden))
                            .map(Event::payload)
                            .limit(20)
                            .toList(),
                    plugin.capabilities(world, agent, before),
                    world.domainCode());
            synchronized (this) {
                if (scenario.eventDriven()) {
                    checkInvocation(id, lease, false);
                    long total = db.queryForObject("""
                            SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND event_type='AGENT_STARTED'
                            """, Long.class, id);
                    if (termination.checkLegacyEstimate(id, limits(), total)) return state(id);
                }
                var started = new LinkedHashMap<String, Object>();
                started.put(
                        "modelMode", Optional.ofNullable(decisions.mode(agent)).orElse("UNKNOWN"));
                if (scenario.eventDriven()) started.put("round", round);
                store.append(id, "AGENT_STARTED", clock, agent.id(), started);
                store.append(id, "AGENT_OBSERVATION", clock, agent.id(), Map.of("observation", observation));
            }
            Decision proposal;
            try {
                proposal = decisions.decide(agent, Json.read(Json.write(observation), Observation.class));
                if (proposal == null) throw new IllegalArgumentException();
            } catch (Exception ex) {
                synchronized (this) {
                    var calls = decisions.drainToolCalls();
                    if (scenario.eventDriven()) checkInvocation(id, lease, false);
                    calls.forEach(call -> store.append(id, "AGENT_TOOL_CALL", clock, agent.id(), call));
                    store.append(
                            id,
                            "RUN_FAILED",
                            clock,
                            agent.id(),
                            Map.of(
                                    "reason",
                                    "Model decision failed or timed out; verify model configuration",
                                    "reasonCode",
                                    "MODEL_DECISION_FAILED",
                                    "activeRuntimeMs",
                                    get(id).activeRuntimeMs()));
                    db.update(
                            "UPDATE simulation_run SET status='FAILED',termination_reason='MODEL_DECISION_FAILED' WHERE id=?",
                            id);
                }
                return state(id);
            }
            synchronized (this) {
                var calls = decisions.drainToolCalls();
                if (scenario.eventDriven()) checkInvocation(id, lease, false);
                calls.forEach(call -> store.append(id, "AGENT_TOOL_CALL", clock, agent.id(), call));
                // Normalize through JSON to prevent a provider retaining references to mutable proposal maps.
                proposal = Json.read(Json.write(proposal), Decision.class);
                var current = new State(id, clock, before.version(), before.entities(), before.relations());
                var d = plugin.normalizeDecision(proposal, current);
                var outcome = plugin.execute(world, agent, d, current);
                var action = new ActionCommitService.Command(
                        id, commandId, agent.id(), current.version(), proposal, "AGENT_ACTION", null, null);
                commits.commit(action, d, current, outcome);
            }
        }
        if (!scenario.eventDriven()) checkTermination(id, scenario, true);
        return state(id);
    }

    /**
     * Executes a user intervention through the same permission, version and domain arbiter. @author
     * agent
     */
    public synchronized State intervene(
            String id, String key, String agentId, Decision decision, long expectedVersion) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new IllegalArgumentException("Idempotency key required");
        if (decision == null) {
            throw new IllegalArgumentException("Decision required");
        }
        var action = new ActionCommitService.Command(
                id, key, agentId, expectedVersion, decision, "USER_INTERVENTION", null, null);
        var prior = commits.retry(action);
        if (prior != null) {
            return prior.state();
        }
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM simulation_command WHERE run_id=? AND command_id=?",
                        Integer.class,
                        id,
                        key)
                > 0) return state(id);
        var run = get(id);
        if (!run.status().equals("PAUSED")) throw new IllegalStateException("Interventions require a paused run");
        if (invocations.containsKey(id))
            throw new IllegalStateException("Wait for the active round to finish cancelling");
        var current = state(id);
        if (current.version() != expectedVersion) throw new IllegalStateException("World version conflict");
        if (current.version()
                                - db.queryForObject(
                                        "SELECT MIN(version) FROM simulation_snapshot WHERE run_id=?", Long.class, id)
                        >= changeBudget
                || store.eventCount(id) > store.scenario(id).maxEvents() - 10)
            throw new IllegalStateException("Run budget exhausted");
        var scenario = store.scenario(id);
        var agent = scenario.agents().stream()
                .filter(a -> a.id().equals(agentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
        if (store.hasEnded(id, agent.id())) throw new IllegalStateException("Agent has ended its task");
        var world = store.frozenWorld(id);
        var plugin = domains.get(world.domainCode());
        var normalized = plugin.normalizeDecision(decision, current);
        var outcome = plugin.execute(world, agent, normalized, current);
        return tx.execute(t -> {
            db.update("INSERT INTO simulation_command (run_id,command_id) VALUES (?,?)", id, key);
            return commits.commit(action, normalized, current, outcome).state();
        });
    }

    /** Pauses automatic computation; historical replay is unaffected. @author agent */
    public synchronized Run pause(String id) {
        var r = get(id);
        if (!List.of("PAUSED", "RUNNING").contains(r.status()))
            throw new IllegalStateException("Only running simulations can pause");
        var lease = invocations.get(id);
        if (lease != null) {
            lease.cancel();
            accumulateInterval(id);
        }
        if (r.status().equals("PAUSED")) return r;
        db.update("UPDATE simulation_run SET status='PAUSED' WHERE id=?", id);
        active.remove(id);
        store.append(id, "RUN_PAUSED", termination.auditTime(id), null, Map.of());
        return get(id);
    }

    /** Starts bounded automatic advancement on a virtual thread. @author agent */
    public synchronized Run resume(String id) {
        var r = get(id);
        if (r.status().equals("RUNNING")) return r;
        if (!r.status().equals("PAUSED")) throw new IllegalStateException("Only paused simulations can resume");
        if (invocations.containsKey(id))
            throw new IllegalStateException("A round is still active; wait for it to finish");
        db.update("UPDATE simulation_run SET status='RUNNING' WHERE id=?", id);
        store.append(id, "RUN_RESUMED", termination.auditTime(id), null, Map.of());
        UUID workerToken = UUID.randomUUID();
        if (active.putIfAbsent(id, workerToken) == null)
            workers.submit(() -> {
                try {
                    while (true) {
                        synchronized (RunService.this) {
                            if (!workerToken.equals(active.get(id))) break;
                            if (!get(id).status().equals("RUNNING")) {
                                active.remove(id, workerToken);
                                break;
                            }
                        }
                        step(id, UUID.randomUUID().toString(), workerToken);
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (RuntimeException failure) {
                    synchronized (RunService.this) {
                        if (workerToken.equals(active.get(id))
                                && get(id).status().equals("RUNNING")
                                && store.scenario(id).eventDriven()) {
                            tx.executeWithoutResult(status -> {
                                db.update("UPDATE simulation_run SET status='PAUSED' WHERE id=?", id);
                                store.append(
                                        id,
                                        "RUN_PAUSED",
                                        state(id).simulationTime(),
                                        null,
                                        Map.of(
                                                "reasonCode",
                                                "SCHEDULED_WORK_INTERRUPTED",
                                                "exceptionType",
                                                failure.getClass().getSimpleName()));
                            });
                            active.remove(id, workerToken);
                        } else {
                            throw failure;
                        }
                    }
                } finally {
                    active.remove(id, workerToken);
                }
            });
        return get(id);
    }

    /** Stops the run permanently. @author agent */
    public synchronized Run stop(String id) {
        var r = get(id);
        if (r.status().equals("STOPPED")) return r;
        if (List.of("FAILED", "COMPLETED", "BUDGET_EXHAUSTED").contains(r.status()))
            throw new IllegalStateException("Run is terminal");
        active.remove(id);
        var lease = invocations.get(id);
        if (lease != null) {
            lease.cancel();
            accumulateInterval(id);
        }
        tx.executeWithoutResult(status -> {
            db.update("UPDATE simulation_run SET status='STOPPED',termination_reason='USER_STOPPED' WHERE id=?", id);
            store.append(
                    id,
                    "RUN_STOPPED",
                    termination.auditTime(id),
                    null,
                    Map.of(
                            "reasonCode",
                            "USER_STOPPED",
                            "reason",
                            "Stopped by user",
                            "activeRuntimeMs",
                            r.activeRuntimeMs()));
            if (store.scenario(id).eventDriven()) {
                eventClock.cancel(id);
                reasoning.closePending(id);
            }
        });
        return get(id);
    }

    private long currentIntervalMs(String id) {
        Long start = activeStarts.get(id);
        return start == null ? 0 : Math.max(0, (nanoClock.getAsLong() - start) / 1_000_000L);
    }

    private void accumulateInterval(String id) {
        long elapsed = currentIntervalMs(id);
        activeStarts.remove(id);
        db.update("UPDATE simulation_run SET active_runtime_ms=active_runtime_ms+? WHERE id=?", elapsed, id);
    }

    /** Lists durable tasks in creation order, retaining parent/child relationships. @author czhcc-trsdsj\codexsandboxoffline */
    public List<TaskRecord> tasks(String id) {
        get(id);
        return db.query(
                "SELECT * FROM simulation_task WHERE run_id=? ORDER BY created_sequence",
                (r, n) -> new TaskRecord(
                        id,
                        r.getString("task_id"),
                        r.getString("parent_task_id"),
                        r.getString("agent_id"),
                        r.getInt("round"),
                        r.getString("status"),
                        r.getLong("input_version"),
                        r.getLong("output_version"),
                        Json.read(r.getString("request_json"), TaskRequest.class),
                        Json.read(r.getString("result_json"), Map.class),
                        r.getLong("created_sequence"),
                        r.getObject("completed_sequence") == null ? null : r.getLong("completed_sequence")),
                id);
    }

    /** Returns the independent business outcome; ongoing and legacy runs remain unevaluated. @author czhcc-trsdsj\codexsandboxoffline */
    public BusinessOutcome outcome(String id) {
        get(id);
        var values = db.queryForList("SELECT outcome_json FROM simulation_usage WHERE run_id=?", String.class, id);
        return values.isEmpty() || values.getFirst() == null
                ? new BusinessOutcome("NOT_EVALUATED", get(id).step(), state(id).version(), List.of())
                : Json.read(values.getFirst(), BusinessOutcome.class);
    }

    /** Returns actual shared request and conservative output-token reservations. @author czhcc-trsdsj\codexsandboxoffline */
    public Map<String, Object> usage(String id) {
        get(id);
        return store.usage(id);
    }

    private boolean checkTermination(String id, Scenario scenario, boolean checkSteps) {
        return termination.check(id, scenario, checkSteps, limits());
    }

    long reserveModelRequest(String id, String agentId, int tokens, boolean reviewOnly) {
        return termination.reserveModelRequest(id, agentId, tokens, reviewOnly, limits());
    }

    private RunTerminationPolicy.Limits limits() {
        return new RunTerminationPolicy.Limits(
                decisionBudget, requestBudget, enforceOutputTokenBudget, tokenBudget, timeBudget, changeBudget);
    }

    private void reviewAtHorizon(String id, Scenario scene, Lease lease) {
        if (scene.objective() == null || lease.cancelled) return;
        var run = get(id);
        if (scene.eventDriven()) {
            if (!List.of("COMPLETED", "FAILED").contains(run.status())) return;
        } else if (run.step() != scene.maxSteps() && !"OBJECTIVE_REVIEW_COMPLETED".equals(run.terminationReason())) {
            return;
        }
        var outcome = outcome(id);
        if ("NOT_EVALUATED".equals(outcome.result())) return;
        var root = scene.agents().stream()
                .filter(a -> a.id().equals(scene.team().rootAgentId()))
                .findFirst()
                .orElseThrow();
        String mode = "UNKNOWN";
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            mode = models.get(root.modelId()).baseUrl().startsWith("mock:") ? "MOCK" : "LLM";
            var review = hierarchy.conclude(hierarchyHost(id, lease, true), root, outcome, "MOCK".equals(mode));
            payload.put("status", "AVAILABLE");
            payload.put("review", review);
        } catch (RuntimeException ex) {
            payload.put("status", "UNAVAILABLE");
            payload.put("reason", "MASTER_REVIEW_UNAVAILABLE");
        }
        payload.put("mode", mode);
        payload.put("round", outcome.evaluatedRound());
        payload.put("version", outcome.version());
        synchronized (this) {
            store.append(id, "OBJECTIVE_REVIEWED", state(id).simulationTime(), root.id(), payload);
        }
    }

    private HierarchyRuntimeHost hierarchyHost(String id, Lease lease, boolean reviewOnly) {
        return new HierarchyRuntimeHost(this, db, tx, models, store, commits, reasoning, id, lease, reviewOnly);
    }

    void checkInvocation(String id, Lease lease, boolean reviewOnly) {
        if (lease.cancelled || invocations.get(id) != lease)
            throw new CancellationException("Invocation is no longer active");
        if (reviewOnly) {
            if (get(id).activeRuntimeMs() >= Math.max(1, timeBudget) * 1000L
                    || store.eventCount(id) >= store.scenario(id).maxEvents() - 1)
                throw new CancellationException("Review budget exhausted");
            return;
        }
        if (!List.of("PAUSED", "RUNNING").contains(get(id).status()))
            throw new CancellationException("Invocation is no longer active");
        if (checkTermination(id, store.scenario(id), false))
            throw new CancellationException("Shared run budget exhausted");
    }

    DomainEnvironment domain(String code) {
        return domains.get(code);
    }

    /** Closes workers during application shutdown. @author agent */
    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
