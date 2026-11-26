package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.EvaluationPolicy;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.evidence.ObjectiveEvidence;
import java.util.Objects;

/** Interprets the frozen event-mode clock and evaluation boundaries. @author czhcc-trsdsj\codexsandboxoffline */
final class EventRunPolicy {
    private static final EvaluationPolicy DEFAULT = new EvaluationPolicy(null, null, null, null, null, null, null);
    private final EventClockScheduler queue;
    private final RunStateRepository store;

    EventRunPolicy(EventClockScheduler queue, RunStateRepository store) {
        this.queue = queue;
        this.store = store;
    }

    long nextTime(Run run, Scenario scene, State current) {
        var policy = policy(scene);
        var due = queue.nextDue(run.id());
        long now = current.simulationTime();
        if (needsObjectiveRound(run, scene, current) || horizonReached(run.step(), now, policy)) return now;
        if ("OBJECTIVE".equals(policy.completionMode())
                && !policy.requireQuiescence()
                && answered(run.id(), current.version())) {
            long evaluationTime = Math.max(now, policy.earliestSimulationTime());
            if (due == null || evaluationTime < due) return evaluationTime;
        }
        if (due != null) {
            return Math.max(
                    now, policy.horizonSimulationTime() == null ? due : Math.min(due, policy.horizonSimulationTime()));
        }
        if ("HORIZON".equals(policy.completionMode()) && policy.horizonSimulationTime() != null) {
            return policy.horizonSimulationTime();
        }
        return Math.max(now, policy.earliestSimulationTime());
    }

    boolean needsObjectiveRound(Run run, Scenario scene, State current) {
        if (scene.objective() == null) return false;
        var policy = policy(scene);
        if (run.step() < policy.earliestRound()
                || ("HORIZON".equals(policy.completionMode())
                        && policy.horizonRound() != null
                        && run.step() < policy.horizonRound())) return true;
        if (answered(run.id(), current.version())) return false;
        return store.events(run.id(), 0).reversed().stream()
                .noneMatch(event -> "REASONING_ROUND_FINISHED".equals(event.eventType())
                        && event.simulationTime() == current.simulationTime()
                        && event.payload().get("inputVersion") instanceof Number version
                        && version.longValue() == current.version());
    }

    boolean answered(String id, long version) {
        return store.events(id, 0).reversed().stream()
                .anyMatch(event -> ObjectiveEvidence.COMPLETION_EVENT.equals(event.eventType())
                        && event.payload().get("version") instanceof Number value
                        && value.longValue() == version);
    }

    /** Captures the deterministic evaluation boundary and remaining work. @author czhcc-trsdsj\codexsandboxoffline */
    record Verdict(String queueState, long remaining, boolean answered, boolean ready) {}

    Verdict verdict(Run run, Scenario scene, State current) {
        var policy = policy(scene);
        long time = current.simulationTime();
        var due = queue.nextDue(run.id());
        long remaining = queue.unfinished(run.id());
        boolean answered = answered(run.id(), current.version());
        boolean ready = run.step() >= policy.earliestRound() && time >= policy.earliestSimulationTime();
        boolean horizon = horizonReached(run.step(), time, policy) && (due == null || due > time);
        if (horizon && (ready || !needsObjectiveRound(run, scene, current))) {
            return new Verdict("HORIZON_REACHED", remaining, answered, ready);
        }
        if (ready
                && answered
                && "OBJECTIVE".equals(policy.completionMode())
                && (!policy.requireQuiescence() || remaining == 0)) {
            return new Verdict(remaining == 0 ? "QUIESCENT" : "PENDING", remaining, true, true);
        }
        if (remaining == 0
                && time >= policy.earliestSimulationTime()
                && !needsObjectiveRound(run, scene, current)
                && (!"HORIZON".equals(policy.completionMode()) || horizon || policy.horizonSimulationTime() == null)) {
            return new Verdict(
                    "QUIESCENT", 0, answered, ready && (!"HORIZON".equals(policy.completionMode()) || horizon));
        }
        return null;
    }

    private boolean horizonReached(int round, long time, EvaluationPolicy policy) {
        return policy.horizonSimulationTime() != null && time >= policy.horizonSimulationTime()
                || policy.horizonRound() != null && round >= policy.horizonRound();
    }

    EvaluationPolicy policy(Scenario scene) {
        return Objects.requireNonNullElse(scene.evaluationPolicy(), DEFAULT);
    }
}
