package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import com.trs.simulation.rule.TriggerRule;
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
    void enqueueDomainEvent(String id, long time, String actionRunId, long version, long changeSequence) {
        var payload = new LinkedHashMap<String, Object>(Map.of("actionRunId", actionRunId, "stateVersion", version));
        if (hasRules(id)) payload.put("sourceEventRef", new RuleWork.EventRef(id, changeSequence));
        enqueue(
                id,
                "domain:" + actionRunId,
                time,
                0,
                "DOMAIN_EVENT",
                payload,
                false,
                hasRules(id) ? changeSequence : null);
    }

    private void enqueue(String id, String key, long time, int priority, String kind, Object payload) {
        enqueue(id, key, time, priority, kind, payload, false, null);
    }

    void scheduleRuleWork(String id, String key, long time, int priority, RuleWork work) {
        enqueue(id, key, time, priority, "DOMAIN_EVENT", work, true, null);
    }

    private void enqueue(
            String id,
            String key,
            long time,
            int priority,
            String kind,
            Object payload,
            boolean automatic,
            Long createdSequence) {
        var audit = new LinkedHashMap<String, Object>(
                Map.of("scheduledEventId", key, "dueTime", time, "priority", priority, "input", payload));
        String type = "EXTERNAL_INPUT".equals(kind) ? "SIMULATION_EVENT_SCHEDULED" : "DOMAIN_EVENT_SCHEDULED";
        if (automatic && payload instanceof RuleWork work) {
            type = "RULE_ACTION_SCHEDULED";
            audit.put("causeEventRef", work.cause().causeEventRef());
            audit.put("ruleId", work.cause().ruleId());
            audit.put("ruleRevision", work.cause().ruleRevision());
            audit.put("actionOrigin", "SYSTEM_RULE");
        }
        store.append(id, type, store.state(id).simulationTime(), null, audit);
        long sequence = createdSequence == null ? store.eventCount(id) : createdSequence;
        long enqueueSequence = db.queryForObject("""
                SELECT COALESCE(MAX(enqueue_sequence),0)+1 FROM simulation_scheduled_event WHERE run_id=?
                """, Long.class, id);
        db.update(
                """
                INSERT INTO simulation_scheduled_event
                    (run_id,event_id,simulation_time,payload_json,created_sequence,enqueue_sequence,priority,work_kind,auto_action)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, key, time, Json.write(payload), sequence, enqueueSequence, priority, kind, automatic ? 1 : 0);
    }

    boolean hasRules(String id) {
        return db.queryForObject(
                "SELECT trigger_rules_json IS NOT NULL FROM simulation_run WHERE id=?", Boolean.class, id);
    }

    List<TriggerRule> frozenRules(String id) {
        var json = db.queryForObject("SELECT trigger_rules_json FROM simulation_run WHERE id=?", String.class, id);
        return json == null ? List.of() : List.of(Json.read(json, TriggerRule[].class));
    }

    boolean workExists(String id, String key) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=? AND event_id=?
                """, Integer.class, id, key) > 0;
    }

    int ruleWorkCount(String id) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=? AND auto_action=1
                """, Integer.class, id);
    }

    long pendingRuleWork(String id) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event
                WHERE run_id=? AND delivered=0 AND work_status IN ('PENDING','PROCESSING')
                    AND (auto_action=1 OR JSON_TYPE(payload_json,'$.sourceEventRef')='object')
                """, Long.class, id);
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
    record Slice(Long firstExternalSequence, boolean workLimit, boolean eventLimit, String propagationLimit) {
        Slice(Long firstExternalSequence, boolean workLimit, boolean eventLimit) {
            this(firstExternalSequence, workLimit, eventLimit, null);
        }

        boolean interrupted() {
            return workLimit || eventLimit || propagationLimit != null;
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
            advanceClock(id, time);
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

    Slice advanceRules(
            String id, long time, Scenario scene, boolean domainOnly, EcaRuntime rules, Consumer<Slice> afterDelivery) {
        return tx.execute(status -> {
            advanceClock(id, time);
            Long firstExternal = null;
            int delivered = db.queryForObject("""
                    SELECT COUNT(*) FROM simulation_scheduled_event
                    WHERE run_id=? AND simulation_time=? AND delivered=1
                    """, Integer.class, id, time);
            boolean workLimit = false;
            String limit = null;
            while (true) {
                var items = db.queryForList("""
                        SELECT event_id,work_kind,payload_json,simulation_time,priority,enqueue_sequence,auto_action
                        FROM simulation_scheduled_event WHERE run_id=? AND work_status IN ('PENDING','PROCESSING')
                          AND delivered=0 AND simulation_time<=? AND (?=0 OR work_kind='DOMAIN_EVENT')
                        ORDER BY simulation_time,priority DESC,enqueue_sequence,event_id LIMIT 1
                        """, id, time, domainOnly ? 1 : 0);
                if (items.isEmpty()) break;
                if (delivered >= scene.scheduling().maxWorkPerTime()) {
                    workLimit = true;
                    break;
                }
                if (store.eventCount(id) >= scene.maxEvents() - 10) {
                    limit = "MAX_EVENTS";
                    break;
                }
                var row = items.getFirst();
                String key = (String) row.get("event_id");
                var payload = (String) row.get("payload_json");
                boolean automatic = ((Number) row.get("auto_action")).intValue() == 1;
                var outcome = automatic
                        ? rules.execute(id, key, time, scene, Json.read(payload, RuleWork.class))
                        : "DOMAIN_EVENT".equals(row.get("work_kind"))
                                ? rules.consume(id, time, scene, Json.read(payload, Map.class))
                                : EcaRuntime.Delivery.completed();
                if (outcome.limit() != null) {
                    limit = outcome.limit();
                    break;
                }
                db.update("""
                        UPDATE simulation_scheduled_event SET attempts=attempts+1 WHERE run_id=? AND event_id=?
                        """, id, key);
                if (automatic) {
                    db.update("""
                            UPDATE simulation_scheduled_event SET delivered=1,work_status=?,completed_sequence=?
                            WHERE run_id=? AND event_id=?
                            """, outcome.workStatus(), outcome.completedSequence(), id, key);
                } else if (deliver(id, scene, time, row) && firstExternal == null) {
                    firstExternal = (long) store.eventCount(id);
                }
                delivered++;
            }
            var slice = new Slice(firstExternal, workLimit, false, limit);
            afterDelivery.accept(slice);
            return slice;
        });
    }

    private void advanceClock(String id, long time) {
        var current = store.state(id);
        if (time <= current.simulationTime()) return;
        var next = new State(id, time, current.version(), current.entities(), current.relations());
        db.update("UPDATE simulation_run SET state_json=? WHERE id=?", Json.write(next), id);
        store.append(
                id,
                "SIMULATION_CLOCK_ADVANCED",
                time,
                null,
                Map.of("previousTime", current.simulationTime(), "stateVersion", current.version()));
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
                SELECT event_id AS id,CASE WHEN auto_action=1 THEN 'AUTO_ACTION' ELSE work_kind END AS type,
                    simulation_time AS dueTime,priority,
                    enqueue_sequence AS enqueueSequence,work_status AS status,attempts
                FROM simulation_scheduled_event WHERE run_id=?
                ORDER BY simulation_time,priority DESC,enqueue_sequence,event_id
                """, id);
    }
}
