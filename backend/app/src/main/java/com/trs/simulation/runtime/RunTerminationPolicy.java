package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.BusinessOutcome;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.common.Json;
import com.trs.simulation.evaluation.BusinessEvaluation;
import com.trs.simulation.evidence.ObjectiveEvidence;
import com.trs.simulation.evidence.TaskEvidence;
import com.trs.simulation.graph.KnowledgeCoverage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies the existing completion rules and shared budgets under the run lifecycle monitor. @author czhcc-trsdsj\codexsandboxoffline */
final class RunTerminationPolicy {
    /** Budget settings read from the service at the point of each check. @author czhcc-trsdsj\codexsandboxoffline */
    record Limits(
            int decisionBudget,
            int requestBudget,
            boolean enforceOutputTokenBudget,
            long tokenBudget,
            int timeBudget,
            int changeBudget) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;
    private final RoundScheduler scheduler;
    private final Function<String, Run> readRun;

    RunTerminationPolicy(
            JdbcTemplate db,
            TransactionTemplate tx,
            RunStateRepository store,
            RoundScheduler scheduler,
            Function<String, Run> readRun) {
        this.db = db;
        this.tx = tx;
        this.store = store;
        this.scheduler = scheduler;
        this.readRun = readRun;
    }

    boolean check(String id, Scenario scenario, boolean checkSteps, Limits limits) {
        var run = readRun.apply(id);
        if (!List.of("PAUSED", "RUNNING").contains(run.status())) return true;
        if (!scenario.eventDriven()
                && scenario.team() == null
                && scenario.agents().stream().allMatch(a -> store.hasEnded(id, a.id())))
            return finish(
                    id,
                    "ALL_AGENTS_ENDED",
                    scenario.agents().size(),
                    scenario.agents().size(),
                    "All agents submitted accepted END decisions",
                    "");
        var coverage = store.knowledgeCoverage(id);
        if (!scenario.eventDriven()
                && checkSteps
                && scenario.objective() != null
                && scenario.team().conditions().isEmpty()
                && scheduler.pendingEvents(id) == 0
                && store.events(id, 0).reversed().stream()
                        .anyMatch(e -> ObjectiveEvidence.COMPLETION_EVENT.equals(e.eventType())
                                && e.payload().get("round") instanceof Number round
                                && round.intValue() == run.step()
                                && e.payload().get("version") instanceof Number version
                                && version.longValue() == run.version()
                                && KnowledgeCoverage.current(coverage, e.runId(), e.payload()))) {
            return evaluateHorizon(id, scenario, true);
        }
        if (!scenario.eventDriven() && checkSteps && run.step() >= scenario.maxSteps()) {
            if (scenario.objective() != null
                    && db.queryForObject(
                                    "SELECT COUNT(*) FROM simulation_task WHERE run_id=? AND round=? AND parent_task_id IS NULL AND status='SUCCEEDED'",
                                    Integer.class,
                                    id,
                                    run.step())
                            == 0) {
                tx.executeWithoutResult(t -> {
                    db.update(
                            "UPDATE simulation_run SET status='STOPPED',termination_reason='OBJECTIVE_HORIZON_INTERRUPTED' WHERE id=?",
                            id);
                    store.append(
                            id,
                            "RUN_STOPPED",
                            store.state(id).simulationTime(),
                            null,
                            Map.of("reasonCode", "OBJECTIVE_HORIZON_INTERRUPTED"));
                });
                return true;
            }
            if (scenario.team() != null) return evaluateHorizon(id, scenario, false);
            return finish(id, "MAX_STEPS", run.step(), scenario.maxSteps(), "Maximum simulation rounds reached", "");
        }
        int events = store.eventCount(id);
        if (events > scenario.maxEvents() - 10)
            return finish(
                    id,
                    "MAX_EVENTS",
                    events,
                    scenario.maxEvents(),
                    "Event budget reached (10 events reserved for audit)",
                    "");
        long timeLimit = Math.max(1, limits.timeBudget()) * 1000L;
        if (run.activeRuntimeMs() >= timeLimit)
            return finish(
                    id, "MAX_RUNTIME", run.activeRuntimeMs(), timeLimit, "Active computation time budget reached", "");
        if (!scenario.eventDriven()
                && scenario.team() == null
                && scenario.agents().stream()
                        .allMatch(a ->
                                store.hasEnded(id, a.id()) || store.decisionCount(id, a.id()) >= a.maxDecisions())) {
            var exhausted = scenario.agents().stream()
                    .filter(a -> !store.hasEnded(id, a.id()))
                    .findFirst()
                    .orElseThrow();
            return finish(
                    id,
                    "MAX_AGENT_DECISIONS",
                    store.decisionCount(id, exhausted.id()),
                    exhausted.maxDecisions(),
                    "All remaining agents exhausted their decision budgets",
                    exhausted.id());
        }
        int total = db.queryForObject(
                "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND event_type='AGENT_STARTED'",
                Integer.class,
                id);
        if (total >= Math.min(50, limits.decisionBudget()))
            return finish(
                    id,
                    "MAX_DECISIONS",
                    total,
                    Math.min(50, limits.decisionBudget()),
                    "Global decision budget reached",
                    "");
        if (!scenario.eventDriven() && scenario.team() == null && checkLegacyEstimate(id, limits, total)) return true;
        long changes = store.businessChangeCount(id);
        if (changes >= limits.changeBudget())
            return finish(id, "MAX_STATE_CHANGES", changes, limits.changeBudget(), "State change budget reached", "");
        return false;
    }

    boolean checkLegacyEstimate(String id, Limits limits, long decisions) {
        long next = decisions + 1;
        if (next * 6L > limits.requestBudget()) {
            return finish(
                    id,
                    "MODEL_REQUEST_BUDGET",
                    next * 6L,
                    limits.requestBudget(),
                    "Insufficient model request budget for the next decision (reserved estimate)",
                    "");
        }
        return limits.enforceOutputTokenBudget()
                && next * 6144L > limits.tokenBudget()
                && finish(
                        id,
                        "TOKEN_BUDGET",
                        next * 6144L,
                        limits.tokenBudget(),
                        "Insufficient generated token budget for the next decision (reserved estimate)",
                        "");
    }

    boolean finish(String id, String reason, long actual, long limit, String message, String agentId) {
        var run = readRun.apply(id);
        if (!List.of("PAUSED", "RUNNING").contains(run.status())) return true;
        boolean completed = reason.equals("ALL_AGENTS_ENDED");
        String status = completed ? "COMPLETED" : "BUDGET_EXHAUSTED";
        tx.executeWithoutResult(t -> {
            db.update("UPDATE simulation_run SET status=?,termination_reason=? WHERE id=?", status, reason, id);
            store.append(
                    id,
                    completed ? "RUN_COMPLETED" : "RUN_BUDGET_EXHAUSTED",
                    auditTime(id),
                    null,
                    Map.of(
                            "reasonCode",
                            reason,
                            "reason",
                            message,
                            "actual",
                            actual,
                            "limit",
                            limit,
                            "activeRuntimeMs",
                            run.activeRuntimeMs(),
                            "agentId",
                            agentId));
        });
        return true;
    }

    long auditTime(String id) {
        return store.scenario(id).eventDriven()
                ? store.state(id).simulationTime()
                : readRun.apply(id).step();
    }

    void concludeEventRun(String id, Scenario scene, String queueState, long pending, boolean answered, boolean ready) {
        var run = readRun.apply(id);
        var current = store.state(id);
        var result = !ready
                ? new BusinessOutcome("NOT_EVALUATED", run.step(), current.version(), List.of())
                : scene.team() == null
                        ? new BusinessOutcome("NO_CONDITIONS", run.step(), current.version(), List.of())
                        : BusinessEvaluation.evaluate(
                                scene,
                                store.initialState(id),
                                current,
                                run.step(),
                                pending,
                                unhandledInputs(id, scene));
        String objectiveStatus = switch (result.result()) {
            case "SUCCEEDED" -> "MET";
            case "FAILED" -> "NOT_MET";
            case "NO_CONDITIONS" -> answered ? "ANSWERED" : "UNVERIFIABLE";
            default -> "UNVERIFIABLE";
        };
        String reason = switch (result.result()) {
            case "ERROR" -> "OBJECTIVE_EVALUATION_FAILED";
            case "SUCCEEDED" -> "OBJECTIVE_MET";
            case "FAILED" -> "OBJECTIVE_NOT_MET";
            case "NO_CONDITIONS" ->
                scene.objective() == null
                        ? queueState
                        : answered ? "OBJECTIVE_REVIEW_COMPLETED" : "OBJECTIVE_NOT_VERIFIABLE";
            default -> "EVALUATION_NOT_READY";
        };
        tx.executeWithoutResult(t -> {
            db.update("UPDATE simulation_usage SET outcome_json=? WHERE run_id=?", Json.write(result), id);
            db.update(
                    "UPDATE simulation_run SET status=?,termination_reason=? WHERE id=?",
                    result.result().equals("ERROR") ? "FAILED" : "COMPLETED",
                    reason,
                    id);
            var evaluation = Map.of(
                    "outcome",
                    result,
                    "objectiveStatus",
                    objectiveStatus,
                    "queueState",
                    queueState,
                    "pendingWork",
                    pending);
            store.append(id, "SCENARIO_EVALUATED", current.simulationTime(), null, evaluation);
            store.append(
                    id,
                    result.result().equals("ERROR") ? "RUN_FAILED" : "RUN_COMPLETED",
                    current.simulationTime(),
                    null,
                    Map.of(
                            "reasonCode",
                            reason,
                            "queueState",
                            queueState,
                            "objectiveStatus",
                            objectiveStatus,
                            "pendingWork",
                            pending));
        });
    }

    private long unhandledInputs(String id, Scenario scene) {
        var types = scene.team().inputActions().keySet();
        var handled = TaskEvidence.handledInputs(store.events(id, 0));
        return store.events(id, 0).stream()
                .filter(e -> e.eventType().equals("SIMULATION_EVENT_CREATED")
                        && types.contains(e.payload().get("eventType"))
                        && !handled.contains(String.valueOf(e.payload().get("scheduledEventId"))))
                .count();
    }

    private boolean evaluateHorizon(String id, Scenario scene, boolean reviewedEarly) {
        var result = BusinessEvaluation.evaluate(
                scene,
                store.initialState(id),
                store.state(id),
                readRun.apply(id).step(),
                scheduler.pendingEvents(id),
                unhandledInputs(id, scene));
        String reason = switch (result.result()) {
            case "ERROR" -> "OBJECTIVE_EVALUATION_FAILED";
            case "SUCCEEDED" -> "OBJECTIVE_MET";
            case "NO_CONDITIONS" -> reviewedEarly ? "OBJECTIVE_REVIEW_COMPLETED" : "OBJECTIVE_NOT_VERIFIABLE";
            default -> "OBJECTIVE_NOT_MET";
        };
        tx.executeWithoutResult(t -> {
            db.update(
                    "INSERT INTO simulation_usage(run_id,outcome_json) VALUES (?,?) ON CONFLICT(run_id) DO UPDATE SET outcome_json=excluded.outcome_json",
                    id,
                    Json.write(result));
            db.update(
                    "UPDATE simulation_run SET status=?,termination_reason=? WHERE id=?",
                    result.result().equals("ERROR") ? "FAILED" : "COMPLETED",
                    reason,
                    id);
            store.append(id, "SCENARIO_EVALUATED", store.state(id).simulationTime(), null, Map.of("outcome", result));
            store.append(
                    id,
                    result.result().equals("ERROR") ? "RUN_FAILED" : "RUN_COMPLETED",
                    store.state(id).simulationTime(),
                    null,
                    Map.of(
                            "reasonCode",
                            reason,
                            "reason",
                            reviewedEarly
                                    ? "Master finished the text objective with recorded evidence"
                                    : "Configured evaluation horizon reached",
                            "activeRuntimeMs",
                            readRun.apply(id).activeRuntimeMs()));
        });
        return true;
    }

    long reserveModelRequest(String id, String agentId, int tokens, boolean reviewOnly, Limits limits) {
        db.update("INSERT OR IGNORE INTO simulation_usage(run_id) VALUES (?)", id);
        readRun.apply(id);
        var usage = store.usage(id);
        long requests = ((Number) usage.get("requests")).longValue(),
                reserved = ((Number) usage.get("reserved_tokens")).longValue();
        if (requests + 1 > limits.requestBudget()) {
            if (!reviewOnly)
                finish(
                        id,
                        "MODEL_REQUEST_BUDGET",
                        requests,
                        limits.requestBudget(),
                        "Shared model request budget exhausted",
                        agentId);
            throw new CancellationException("Request budget exhausted");
        }
        if (limits.enforceOutputTokenBudget() && reserved + tokens > limits.tokenBudget()) {
            if (!reviewOnly)
                finish(
                        id,
                        "TOKEN_BUDGET",
                        reserved,
                        limits.tokenBudget(),
                        "Shared output-token reservation budget exhausted",
                        agentId);
            throw new CancellationException("Token budget exhausted");
        }
        db.update(
                "UPDATE simulation_usage SET requests=requests+1,reserved_tokens=reserved_tokens+? WHERE run_id=?",
                tokens,
                id);
        return requests + 1;
    }
}
