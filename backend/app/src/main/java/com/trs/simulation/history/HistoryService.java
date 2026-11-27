package com.trs.simulation.history;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import com.trs.simulation.runtime.RunService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable replay, exact-checkpoint branching and structural comparison; never invokes a
 * model. @author agent
 */
@Service
public class HistoryService {
    /** Replay point mapped to the event protocol. @author agent */
    public record Checkpoint(long sequence, long version, long simulationTime) {}

    /** A field/object difference with both exact persisted values. @author agent */
    public record Difference(String id, Object before, Object after) {}

    /** Structural result comparison, not a generated narrative. @author agent */
    public record Comparison(
            String leftId,
            String rightId,
            List<Difference> entityDifferences,
            List<Difference> relationDifferences,
            Map<String, Object> leftMetrics,
            Map<String, Object> rightMetrics,
            String summary,

            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Boolean comparableDataBaseline) {}

    private final RunService runs;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    /** Injects immutable history readers and transactional persistence. @author agent */
    public HistoryService(RunService runs, JdbcTemplate db, TransactionTemplate tx) {
        this.runs = runs;
        this.db = db;
        this.tx = tx;
    }

    /** Returns persisted state checkpoints and their original event sequence. @author agent */
    public List<Checkpoint> checkpoints(String id) {
        var initial = baseline(id);
        var result = new ArrayList<Checkpoint>();
        result.add(new Checkpoint(0, initial.version(), initial.simulationTime()));
        for (var event : runs.events(id, 0))
            if (event.eventType().equals("WORLD_STATE_CHANGED")
                    || event.eventType().equals("WORLD_EXPANDED")
                            && Boolean.TRUE.equals(event.payload().get("changed")))
                result.add(new Checkpoint(
                        event.sequence(),
                        ((Number) event.payload().get("version")).longValue(),
                        event.simulationTime()));
            else if (event.eventType().equals("SIMULATION_CLOCK_ADVANCED"))
                result.add(new Checkpoint(
                        event.sequence(),
                        ((Number) event.payload().get("stateVersion")).longValue(),
                        event.simulationTime()));
        return result;
    }

    /**
     * Restores any valid event position using its last committed snapshot, with zero model
     * calls. @author agent
     */
    public State stateAt(String id, long sequence) {
        var events = runs.events(id, 0);
        long max = events.isEmpty() ? 0 : events.getLast().sequence();
        if (sequence < 0 || sequence > max) throw new IllegalArgumentException("Event position out of range");
        var state = baseline(id);
        long time = state.simulationTime();
        for (var event : events) {
            if (event.sequence() > sequence) break;
            time = event.simulationTime();
            if (event.eventType().equals("WORLD_STATE_CHANGED")
                    || event.eventType().equals("WORLD_EXPANDED"))
                state = runs.replay(id, ((Number) event.payload().get("version")).longValue());
        }
        return new State(id, time, state.version(), state.entities(), state.relations());
    }

    /** Lists independent branches with their source checkpoints. @author agent */
    public List<Map<String, Object>> branches() {
        return db.queryForList(
                "SELECT run_id,parent_run_id,parent_sequence,label FROM simulation_branch ORDER BY run_id");
    }

    /**
     * Atomically forks exact state, pending events and bounded memories into an isolated paused
     * run. @author agent
     */
    public Run fork(String parent, long sequence, String label, String key) {
        if (key == null
                || key.isBlank()
                || key.length() > 100
                || label == null
                || label.isBlank()
                || label.length() > 100) throw new IllegalArgumentException("Fork requires label and idempotency key");
        synchronized (runs) {
            var existing = db.queryForList(
                    "SELECT run_id FROM simulation_branch WHERE parent_run_id=? AND command_key=?",
                    String.class,
                    parent,
                    key);
            if (!existing.isEmpty()) return runs.get(existing.getFirst());
            var snapshot = stateAt(parent, sequence);
            return tx.execute(t -> {
                var scenario = Json.read(
                        db.queryForObject("SELECT scenario_json FROM simulation_run WHERE id=?", String.class, parent),
                        Scenario.class);
                // The editable scenario may have changed since the ancestor began; always preserve its
                // frozen copy.
                String child = UUID.randomUUID().toString();
                var state = new State(
                        child,
                        snapshot.simulationTime(),
                        snapshot.version(),
                        snapshot.entities(),
                        snapshot.relations());
                db.update(
                        "INSERT INTO simulation_run (id,scenario_json,status,step,state_json,created_at,world_json)"
                                + " VALUES (?,?,'PAUSED',?,?,?,(SELECT world_json FROM simulation_run WHERE id=?))",
                        child,
                        Json.write(scenario),
                        snapshot.simulationTime(),
                        Json.write(state),
                        System.currentTimeMillis(),
                        parent);
                db.update("UPDATE simulation_run SET base_time=?,step=0 WHERE id=?", snapshot.simulationTime(), child);
                db.update("""
                        UPDATE simulation_run SET trigger_rules_json=(
                            SELECT trigger_rules_json FROM simulation_run WHERE id=?
                        ) WHERE id=?
                        """, parent, child);
                db.update("INSERT INTO simulation_snapshot VALUES (?,?,?)", child, state.version(), Json.write(state));
                db.update("INSERT INTO simulation_usage(run_id) VALUES (?)", child);
                db.update("INSERT INTO simulation_branch VALUES (?,?,?,?,?)", child, parent, sequence, label, key);
                db.update("""
                        INSERT INTO simulation_knowledge_import
                        SELECT ?, candidate_id,provider_code,import_order,0,entity_count,identities_json,receipt_json
                        FROM simulation_knowledge_import WHERE run_id=? AND event_sequence<=?
                        """, child, parent, sequence);
                append(
                        child,
                        "BRANCH_CREATED",
                        state.simulationTime(),
                        null,
                        Map.of("parentRunId", parent, "parentSequence", sequence, "label", label));
                var past = runs.events(parent, 0).stream()
                        .filter(e -> e.sequence() <= sequence)
                        .toList();
                if (scenario.eventDriven()) {
                    int round = past.stream()
                            .filter(e -> "REASONING_ROUND_STARTED".equals(e.eventType())
                                    || "BRANCH_CLOCK_RESTORED".equals(e.eventType()))
                            .map(e -> e.payload().get("round"))
                            .filter(Number.class::isInstance)
                            .mapToInt(value -> ((Number) value).intValue())
                            .max()
                            .orElse(0);
                    db.update("UPDATE simulation_run SET step=? WHERE id=?", round, child);
                    append(child, "BRANCH_CLOCK_RESTORED", state.simulationTime(), null, Map.of("round", round));
                }
                for (var event : past) {
                    if (event.eventType().equals("WORLD_STATE_CHANGED")
                            && event.payload().get("inputEventId") instanceof String input
                            && !input.isBlank())
                        append(
                                child,
                                "ACTION_INPUT_HANDLED",
                                state.simulationTime(),
                                event.agentId(),
                                Map.of("inputEventId", input));
                    if (event.eventType().equals("SIMULATION_EVENT_CREATED")
                            && (!scenario.eventDriven() || isUnfinishedDelivery(parent, sequence, event.payload()))) {
                        var payload = new HashMap<>(event.payload());
                        payload.put("inherited", true);
                        append(child, "SIMULATION_EVENT_CREATED", state.simulationTime(), null, payload);
                    }
                    if (List.of("SUBTASK_COMPLETED", "MASTER_REVIEW", "TASK_MEMORY_RESTORED", "ACTION_INPUT_HANDLED")
                            .contains(event.eventType())) {
                        append(
                                child,
                                event.eventType().equals("ACTION_INPUT_HANDLED")
                                        ? "ACTION_INPUT_HANDLED"
                                        : "TASK_MEMORY_RESTORED",
                                state.simulationTime(),
                                event.agentId(),
                                event.payload());
                    }
                }
                for (var event : past.stream()
                        .filter(e -> List.of("ACTION_ACCEPTED", "ACTION_REJECTED", "AGENT_MEMORY_RESTORED")
                                .contains(e.eventType()))
                        .skip(Math.max(
                                0,
                                past.stream()
                                                .filter(e -> List.of(
                                                                "ACTION_ACCEPTED",
                                                                "ACTION_REJECTED",
                                                                "AGENT_MEMORY_RESTORED")
                                                        .contains(e.eventType()))
                                                .count()
                                        - 30))
                        .toList())
                    append(child, "AGENT_MEMORY_RESTORED", state.simulationTime(), event.agentId(), event.payload());
                if (scenario.eventDriven()) {
                    copyPendingWork(parent, child, sequence, scenario.team() == null);
                    copyPendingReasoning(parent, child, sequence);
                    return runs.get(child);
                }
                var pending = db.queryForList(
                        "SELECT event_id,simulation_time,payload_json FROM simulation_scheduled_event"
                                + " WHERE run_id=? AND created_sequence<=? ORDER BY"
                                + " simulation_time,event_id",
                        parent,
                        sequence);
                for (var event : pending) {
                    boolean delivered = past.stream()
                            .anyMatch(e -> e.eventType().equals("SIMULATION_EVENT_CREATED")
                                    && Objects.equals(e.payload().get("scheduledEventId"), event.get("event_id")));
                    if (!delivered)
                        db.update(
                                "INSERT INTO simulation_scheduled_event"
                                        + " (run_id,event_id,simulation_time,payload_json,delivered,created_sequence)"
                                        + " VALUES (?,?,?,?,0,0)",
                                child,
                                event.get("event_id"),
                                event.get("simulation_time"),
                                event.get("payload_json"));
                }
                return runs.get(child);
            });
        }
    }

    private boolean isUnfinishedDelivery(String parent, long sequence, Map<String, Object> event) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM simulation_scheduled_event
                WHERE run_id=? AND event_id=? AND (completed_sequence IS NULL OR completed_sequence>?)
                """, Integer.class, parent, event.get("scheduledEventId"), sequence) > 0;
    }

    private void copyPendingWork(String parent, String child, long sequence, boolean plain) {
        db.update("""
                INSERT INTO simulation_scheduled_event
                    (run_id,event_id,simulation_time,payload_json,delivered,created_sequence,
                     enqueue_sequence,priority,work_kind,work_status,auto_action)
                SELECT ?,work.event_id,work.simulation_time,work.payload_json,
                    CASE WHEN delivered.sequence_no IS NULL THEN 0 ELSE 1 END,0,
                    work.enqueue_sequence,work.priority,work.work_kind,
                    CASE WHEN delivered.sequence_no IS NULL THEN 'PENDING' ELSE ? END,work.auto_action
                FROM simulation_scheduled_event work
                LEFT JOIN simulation_event delivered ON delivered.run_id=work.run_id
                    AND delivered.event_type='SIMULATION_EVENT_CREATED' AND delivered.sequence_no<=?
                    AND JSON_EXTRACT(delivered.payload_json,'$.scheduledEventId')=work.event_id
                WHERE work.run_id=? AND work.created_sequence<=?
                    AND (work.completed_sequence IS NULL OR work.completed_sequence>?)
                """, child, plain ? "PROCESSING" : "AWAITING_ACTION", sequence, parent, sequence, sequence);
    }

    private void copyPendingReasoning(String parent, String child, long sequence) {
        db.update("""
                INSERT INTO simulation_reasoning_round
                    (run_id,round,simulation_time,input_version,created_sequence,started_sequence)
                SELECT ?,round,simulation_time,input_version,0,
                    CASE WHEN started_sequence<=? THEN 0 END
                FROM simulation_reasoning_round
                WHERE run_id=? AND created_sequence<=? AND (completed_sequence IS NULL OR completed_sequence>?)
                """, child, sequence, parent, sequence, sequence);
        db.update("""
                INSERT INTO simulation_reasoning_action
                    (run_id,round,sequence_no,agent_id,command_id,selector_json,receipt_json)
                SELECT ?,action.round,0,action.agent_id,action.command_id,action.selector_json,action.receipt_json
                FROM simulation_reasoning_action action
                JOIN simulation_reasoning_round fork ON fork.run_id=? AND fork.round=action.round
                WHERE action.run_id=? AND action.sequence_no<=?
                """, child, child, parent, sequence);
    }

    /**
     * Compares real current state objects and plugin metrics across compatible worlds. @author agent
     */
    public Comparison compare(String left, String right) {
        var a = runs.state(left);
        var b = runs.state(right);
        String
                worldA =
                        Json.read(
                                        db.queryForObject(
                                                "SELECT scenario_json FROM simulation_run WHERE id=?",
                                                String.class,
                                                left),
                                        Scenario.class)
                                .worldId(),
                worldB =
                        Json.read(
                                        db.queryForObject(
                                                "SELECT scenario_json FROM simulation_run WHERE id=?",
                                                String.class,
                                                right),
                                        Scenario.class)
                                .worldId();
        if (!worldA.equals(worldB)) throw new IllegalArgumentException("Compare requires the same world template");
        var entities = diff(
                a.entities().stream().collect(java.util.stream.Collectors.toMap(Entity::id, e -> (Object) e)),
                b.entities().stream().collect(java.util.stream.Collectors.toMap(Entity::id, e -> (Object) e)));
        var relations = diff(
                a.relations().stream().collect(java.util.stream.Collectors.toMap(Relation::id, e -> (Object) e)),
                b.relations().stream().collect(java.util.stream.Collectors.toMap(Relation::id, e -> (Object) e)));
        var leftData = dataBaseline(left);
        var rightData = dataBaseline(right);
        Boolean comparable = leftData.isEmpty() && rightData.isEmpty() ? null : leftData.equals(rightData);
        return new Comparison(
                left,
                right,
                entities,
                relations,
                runs.evaluate(left),
                runs.evaluate(right),
                entities.size()
                        + " changed entities; "
                        + relations.size()
                        + " changed relations. SIMULATED results, not real-world facts."
                        + (Boolean.FALSE.equals(comparable) ? " External data baselines differ." : ""),
                comparable);
    }

    private List<String> dataBaseline(String runId) {
        return db.queryForList(
                "SELECT candidate_id FROM simulation_knowledge_import WHERE run_id=? ORDER BY import_order",
                String.class,
                runId);
    }

    private List<Difference> diff(Map<String, Object> left, Map<String, Object> right) {
        var ids = new TreeSet<String>(left.keySet());
        ids.addAll(right.keySet());
        return ids.stream()
                .filter(id -> !Objects.equals(left.get(id), right.get(id)))
                .map(id -> new Difference(id, left.get(id), right.get(id)))
                .toList();
    }

    private State baseline(String id) {
        runs.get(id);
        return Json.read(
                db.queryForObject(
                        "SELECT state_json FROM simulation_snapshot WHERE run_id=? ORDER BY version LIMIT 1",
                        String.class,
                        id),
                State.class);
    }

    private void append(String id, String type, long time, String agent, Map<String, Object> payload) {
        db.update(
                "INSERT INTO simulation_event VALUES (?,(SELECT COALESCE(MAX(sequence_no),0)+1 FROM"
                        + " simulation_event WHERE run_id=?),?,?,?,?)",
                id,
                id,
                type,
                time,
                agent,
                Json.write(payload));
    }
}
