package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits the engine outcome, audit events and snapshot in the caller's transaction. @author czhcc-trsdsj\codexsandboxoffline */
final class ActionCommitService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;

    ActionCommitService(JdbcTemplate db, TransactionTemplate tx, RunStateRepository store) {
        this.db = db;
        this.tx = tx;
        this.store = store;
    }

    void commit(String id, String agent, Decision d, State current, Outcome outcome, String origin) {
        commit(id, agent, d, current, outcome, origin, Map.of());
    }

    void commit(
            String id,
            String agent,
            Decision d,
            State current,
            Outcome outcome,
            String origin,
            Map<String, Object> taskContext) {
        tx.executeWithoutResult(t -> {
            store.append(
                    id,
                    origin.equals("USER") ? "USER_INTERVENTION" : "AGENT_DECISION",
                    current.simulationTime(),
                    agent,
                    Map.of("decision", d, "evidence", "HYPOTHESIS", "origin", origin));
            store.append(id, "ACTION_PROPOSED", current.simulationTime(), agent, Map.of("action", d));
            store.append(
                    id,
                    outcome.accepted() ? "ACTION_ACCEPTED" : "ACTION_REJECTED",
                    current.simulationTime(),
                    agent,
                    Map.of(
                            "code",
                            outcome.code(),
                            "affectedRelationIds",
                            outcome.affectedRelationIds(),
                            "targetKind",
                            d.targetKind() == null ? legacyTargetKind(d, current) : d.targetKind(),
                            "reason",
                            outcome.message(),
                            "actionType",
                            d.actionType() == null ? "" : d.actionType(),
                            "targetId",
                            d.targetId() == null ? "" : d.targetId()));
            var next = outcome.state();
            db.update("UPDATE simulation_run SET state_json=? WHERE id=?", Json.write(next), id);
            if (outcome.changed()) {
                db.update("INSERT INTO simulation_snapshot VALUES (?,?,?)", id, next.version(), Json.write(next));
                var changes = new LinkedHashMap<String, Object>(
                        Map.of("before", current, "after", next, "version", next.version(), "evidence", "SIMULATED"));
                changes.putAll(taskContext);
                store.append(id, "WORLD_STATE_CHANGED", current.simulationTime(), agent, changes);
            }
        });
    }

    private String legacyTargetKind(Decision decision, State state) {
        if ("END".equals(decision.actionType())) return "AGENT";
        return state.entities().stream().anyMatch(e -> e.id().equals(decision.targetId())) ? "ENTITY" : "RELATION";
    }
}
