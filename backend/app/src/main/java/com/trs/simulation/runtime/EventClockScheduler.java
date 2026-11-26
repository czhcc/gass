package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Handles strict time slices and durable delivery under the run's execution lease. @author czhcc-trsdsj\codexsandboxoffline */
final class EventClockScheduler {
    private static final int CLAIM_BATCH_SIZE = 10;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;

    EventClockScheduler(JdbcTemplate db, TransactionTemplate tx, RunStateRepository store) {
        this.db = db;
        this.tx = tx;
        this.store = store;
    }

    void recoverClaims() {
        db.update("""
                UPDATE simulation_scheduled_event SET work_status='PENDING'
                WHERE work_status='PROCESSING' AND delivered=0
                """);
    }

    void schedule(String id, String key, ScheduledInput input) {
        tx.executeWithoutResult(status -> enqueue(
                id,
                key,
                input.simulationTime(),
                input.priority() == null ? 0 : input.priority(),
                "EXTERNAL_INPUT",
                input));
    }

    // Invoked inside the successful Action commit. The Action receipt key is stable across retries.
    void enqueueDomainEvent(String id, long time, String actionRunId, long version) {
        enqueue(
                id,
                "domain:" + actionRunId,
                time,
                0,
                "DOMAIN_EVENT",
                Map.of("actionRunId", actionRunId, "stateVersion", version));
    }

    private void enqueue(String id, String key, long time, int priority, String kind, Object payload) {
        store.append(
                id,
                "EXTERNAL_INPUT".equals(kind) ? "SIMULATION_EVENT_SCHEDULED" : "DOMAIN_EVENT_SCHEDULED",
                store.state(id).simulationTime(),
                null,
                Map.of("scheduledEventId", key, "dueTime", time, "priority", priority, "input", payload));
        var sequence = store.eventCount(id);
        long enqueueSequence = db.queryForObject("""
                SELECT COALESCE(MAX(enqueue_sequence),0)+1 FROM simulation_scheduled_event WHERE run_id=?
                """, Long.class, id);
        db.update("""
                INSERT INTO simulation_scheduled_event
                    (run_id,event_id,simulation_time,payload_json,created_sequence,enqueue_sequence,priority,work_kind)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, key, time, Json.write(payload), sequence, enqueueSequence, priority, kind);
    }

    void acknowledge(String id, String key, int sequence) {
        db.update("""
                UPDATE simulation_scheduled_event SET work_status='COMPLETED',completed_sequence=?
                WHERE run_id=? AND event_id=? AND work_status='AWAITING_ACTION'
                """, sequence, id, key);
    }

    void cancel(String id) {
        db.update("""
                UPDATE simulation_scheduled_event SET work_status='CANCELLED',completed_sequence=?
                WHERE run_id=? AND work_status IN ('PENDING','PROCESSING','AWAITING_ACTION')
                """, store.eventCount(id), id);
    }

    Long nextDue(String id) {
        return db.queryForObject("""
                SELECT MIN(simulation_time) FROM simulation_scheduled_event
                WHERE run_id=? AND work_status IN ('PENDING','PROCESSING','AWAITING_ACTION')
                """, Long.class, id);
    }

    long unfinished(String id) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event
                WHERE run_id=? AND work_status IN ('PENDING','PROCESSING','AWAITING_ACTION')
                """, Long.class, id);
    }

    long awaitingAction(String id) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=? AND work_status='AWAITING_ACTION'
                """, Long.class, id);
    }

    /** Summarizes the committed delivery and which constraint interrupted it. @author czhcc-trsdsj\codexsandboxoffline */
    record Slice(Long firstExternalSequence, boolean workLimit, boolean eventLimit) {
        boolean interrupted() {
            return workLimit || eventLimit;
        }
    }

    Slice advance(String id, long time, Scenario scenario, boolean domainOnly, Consumer<Slice> afterDelivery) {
        var rows = db.queryForList("""
                SELECT event_id,work_kind,payload_json,simulation_time,priority,enqueue_sequence
                FROM simulation_scheduled_event
                WHERE run_id=? AND work_status IN ('PENDING','PROCESSING') AND delivered=0 AND simulation_time<=?
                    AND (?=0 OR work_kind='DOMAIN_EVENT')
                ORDER BY simulation_time,priority DESC,enqueue_sequence,event_id
                """, id, time, domainOnly ? 1 : 0);
        int delivered = db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event
                WHERE run_id=? AND simulation_time=? AND delivered=1
                """, Integer.class, id, time);
        int workRoom = Math.max(0, scenario.scheduling().maxWorkPerTime() - delivered);
        int eventRoom = Math.max(
                0,
                scenario.maxEvents()
                        - 10
                        - store.eventCount(id)
                        - (time > store.state(id).simulationTime() ? 1 : 0));
        int limit = Math.min(rows.size(), Math.min(workRoom, eventRoom));
        var claimed = rows.subList(0, limit);
        for (int start = 0; start < claimed.size(); start += CLAIM_BATCH_SIZE) {
            var batch = claimed.subList(start, Math.min(start + CLAIM_BATCH_SIZE, claimed.size()));
            tx.executeWithoutResult(status -> db.batchUpdate(
                    """
                    UPDATE simulation_scheduled_event SET work_status='PROCESSING',attempts=attempts+1
                    WHERE run_id=? AND event_id=?
                    """,
                    batch.stream()
                            .map(row -> new Object[] {id, row.get("event_id")})
                            .toList()));
        }
        return tx.execute(status -> {
            var current = store.state(id);
            if (time > current.simulationTime()) {
                var next = new State(id, time, current.version(), current.entities(), current.relations());
                db.update("UPDATE simulation_run SET state_json=? WHERE id=?", Json.write(next), id);
                store.append(
                        id,
                        "SIMULATION_CLOCK_ADVANCED",
                        time,
                        null,
                        Map.of("previousTime", current.simulationTime(), "stateVersion", current.version()));
            }
            Long firstExternal = null;
            for (var row : claimed) {
                if (deliver(id, scenario, time, row) && firstExternal == null)
                    firstExternal = (long) store.eventCount(id);
            }
            var slice = new Slice(
                    firstExternal,
                    rows.size() > workRoom && workRoom <= eventRoom,
                    rows.size() > eventRoom && eventRoom < workRoom);
            afterDelivery.accept(slice);
            return slice;
        });
    }

    void waitForReasoning(String id, long firstSequence) {
        db.update("""
                UPDATE simulation_scheduled_event SET work_status='PROCESSING',completed_sequence=NULL
                WHERE run_id=? AND work_kind='EXTERNAL_INPUT' AND completed_sequence>=?
                """, id, firstSequence);
    }

    void completeReasoningInputs(String id) {
        db.update("""
                UPDATE simulation_scheduled_event SET work_status='COMPLETED',completed_sequence=?
                WHERE run_id=? AND work_status='PROCESSING' AND delivered=1
                """, store.eventCount(id), id);
    }

    private boolean deliver(String id, Scenario scenario, long time, Map<String, Object> row) {
        boolean external = "EXTERNAL_INPUT".equals(row.get("work_kind"));
        var key = (String) row.get("event_id");
        var payload = new LinkedHashMap<String, Object>(Json.read((String) row.get("payload_json"), Map.class));
        payload.put("scheduledEventId", key);
        payload.put("dueTime", row.get("simulation_time"));
        payload.put("enqueueSequence", row.get("enqueue_sequence"));
        payload.put("priority", row.get("priority"));
        boolean awaiting = external
                && scenario.team() != null
                && scenario.team().inputActions().containsKey(payload.get("eventType"));
        store.append(id, external ? "SIMULATION_EVENT_CREATED" : "DOMAIN_EVENT_PROCESSED", time, null, payload);
        db.update("""
                UPDATE simulation_scheduled_event SET delivered=1,work_status=?,completed_sequence=?
                WHERE run_id=? AND event_id=?
                """, awaiting ? "AWAITING_ACTION" : "COMPLETED", awaiting ? null : store.eventCount(id), id, key);
        return external;
    }

    List<Map<String, Object>> queue(String id) {
        return db.queryForList("""
                SELECT event_id AS id,work_kind AS type,simulation_time AS dueTime,priority,
                    enqueue_sequence AS enqueueSequence,work_status AS status,attempts
                FROM simulation_scheduled_event WHERE run_id=?
                ORDER BY simulation_time,priority DESC,enqueue_sequence,event_id
                """, id);
    }
}
