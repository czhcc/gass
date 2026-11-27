package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.common.Json;
import com.trs.simulation.graph.KnowledgeCoverage;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores logical rounds and settled Action attempts independently of the model session. @author czhcc-trsdsj\codexsandboxoffline */
final class DurableReasoning {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;

    DurableReasoning(JdbcTemplate db, TransactionTemplate tx, RunStateRepository store) {
        this.db = db;
        this.tx = tx;
        this.store = store;
    }

    /** An unfinished round retains its original time, input version and round number. @author czhcc-trsdsj\codexsandboxoffline */
    record Pending(int number, long time, long inputVersion, boolean started) {}

    Pending pending(String id) {
        return db
                .query(
                        """
                SELECT round,simulation_time,input_version,started_sequence FROM simulation_reasoning_round
                WHERE run_id=? AND completed_sequence IS NULL
                """,
                        (row, index) ->
                                new Pending(row.getInt(1), row.getLong(2), row.getLong(3), row.getObject(4) != null),
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    void queue(String id, int round, long time, long version, Long deliverySequence) {
        if (deliverySequence == null) {
            store.append(id, "REASONING_ROUND_QUEUED", time, null, Map.of("round", round, "inputVersion", version));
        }
        db.update("""
                INSERT INTO simulation_reasoning_round(run_id,round,simulation_time,input_version,created_sequence)
                VALUES (?,?,?,?,?)
                """, id, round, time, version, deliverySequence == null ? store.eventCount(id) : deliverySequence);
    }

    boolean finishedCommand(String id, String key) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_command command
                WHERE command.run_id=? AND command.command_id=? AND NOT EXISTS (
                    SELECT 1 FROM simulation_reasoning_round checkpoint
                    JOIN simulation_run run ON run.id=checkpoint.run_id
                    WHERE checkpoint.run_id=command.run_id AND checkpoint.round=command.reasoning_round
                        AND checkpoint.completed_sequence IS NULL AND run.status IN ('PAUSED','RUNNING')
                )
                """, Integer.class, id, key) > 0;
    }

    void trackCommand(String id, String key, Integer round) {
        db.update("""
                INSERT OR IGNORE INTO simulation_command (run_id,command_id,reasoning_round) VALUES (?,?,?)
                """, id, key, round);
    }

    void start(String id, String key, Pending round) {
        tx.executeWithoutResult(status -> {
            trackCommand(id, key, round.number());
            if (round.started()) return;
            db.update("UPDATE simulation_run SET step=? WHERE id=?", round.number(), id);
            store.append(
                    id,
                    "REASONING_ROUND_STARTED",
                    round.time(),
                    null,
                    Map.of("round", round.number(), "inputVersion", round.inputVersion()));
            db.update(
                    "UPDATE simulation_reasoning_round SET started_sequence=? WHERE run_id=? AND round=?",
                    store.eventCount(id),
                    id,
                    round.number());
        });
    }

    void finish(String id, Pending round, EventClockScheduler queue) {
        tx.executeWithoutResult(status -> {
            var finished = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "round",
                    round.number(),
                    "inputVersion",
                    round.inputVersion(),
                    "stateVersion",
                    store.state(id).version()));
            KnowledgeCoverage.annotate(store.knowledgeCoverage(id), finished);
            store.append(id, "REASONING_ROUND_FINISHED", round.time(), null, finished);
            closePending(id);
            queue.completeReasoningInputs(id);
        });
    }

    void closePending(String id) {
        db.update("""
                UPDATE simulation_reasoning_round SET completed_sequence=?
                WHERE run_id=? AND completed_sequence IS NULL
                """, store.eventCount(id), id);
    }

    void recordAction(
            ActionCommitService.Command command,
            Decision decision,
            String targetKind,
            int sequence,
            Map<String, Object> receipt) {
        var round = pending(command.runId());
        if (round == null || !"AGENT_ACTION".equals(command.origin())) return;
        db.update(
                """
                INSERT INTO simulation_reasoning_action
                    (run_id,round,sequence_no,agent_id,command_id,selector_json,receipt_json)
                VALUES (?,?,?,?,?,?,?)
                """,
                command.runId(),
                round.number(),
                sequence,
                command.actorId(),
                command.commandId(),
                Json.write(selector(decision, targetKind, command.inputEventId())),
                Json.write(receipt));
    }

    boolean hasDecision(String id, String key, int round) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_reasoning_action WHERE run_id=? AND round=? AND command_id=?
                """, Integer.class, id, round, key) > 0;
    }

    int priorLegacyRounds(String id, String agent, int round) {
        return db.queryForObject("""
                SELECT COUNT(DISTINCT COALESCE(JSON_EXTRACT(payload_json,'$.round'),-sequence_no))
                FROM simulation_event WHERE run_id=? AND agent_id=? AND event_type='AGENT_STARTED'
                    AND COALESCE(JSON_EXTRACT(payload_json,'$.round'),0) < ?
                """, Integer.class, id, agent, round);
    }

    int actionAttempts(String id, int round) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_reasoning_action WHERE run_id=? AND round=?
                """, Integer.class, id, round);
    }

    boolean succeededMaster(String id, int round) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_task
                WHERE run_id=? AND round=? AND parent_task_id IS NULL AND status='SUCCEEDED'
                """, Integer.class, id, round) > 0;
    }

    Map<String, Object> priorAction(
            ActionCommitService.Command command,
            Decision decision,
            String targetKind,
            int round,
            long through,
            Set<String> scope) {
        var requested = Json.read(Json.write(selector(decision, targetKind, command.inputEventId())), Map.class);
        return db
                .query(
                        """
                SELECT selector_json,receipt_json FROM simulation_reasoning_action
                WHERE run_id=? AND round=? AND agent_id=? AND sequence_no<=? AND
                    JSON_EXTRACT(receipt_json,'$.accepted')=1 ORDER BY sequence_no,command_id
                """,
                        (row, index) -> {
                            if (!requested.equals(Json.read(row.getString(1), Map.class))) return null;
                            Map<String, Object> receipt = Json.read(row.getString(2), Map.class);
                            if (!command.runId().equals(receipt.get("runId"))) {
                                return Map.<String, Object>of(
                                        "accepted",
                                        false,
                                        "code",
                                        "ACTION_INHERITED",
                                        "message",
                                        "The branch already includes this Action's outcome");
                            }
                            if (receipt.get("relatedIds") instanceof java.util.List<?> ids && scope.containsAll(ids)) {
                                return receipt;
                            }
                            // An earlier attempt can have had a wider scope. Never return its details in this case.
                            return Map.<String, Object>of(
                                    "accepted",
                                    false,
                                    "code",
                                    "ACTION_SCOPE_CHANGED",
                                    "message",
                                    "No scoped receipt can be returned for this Action");
                        },
                        command.runId(),
                        round,
                        command.actorId(),
                        through)
                .stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> selector(Decision decision, String targetKind, String input) {
        return Map.of(
                "action",
                Objects.toString(decision.actionType(), ""),
                "target",
                Objects.toString(decision.targetId(), ""),
                "kind",
                targetKind,
                "parameters",
                decision.parameters() == null ? Map.of() : decision.parameters(),
                "input",
                Objects.toString(input, ""));
    }
}
