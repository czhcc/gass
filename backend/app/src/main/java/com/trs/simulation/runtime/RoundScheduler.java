package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies the legacy round clock and persists scheduled inputs in their original order. @author czhcc-trsdsj\codexsandboxoffline */
final class RoundScheduler {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RunStateRepository store;

    RoundScheduler(JdbcTemplate db, TransactionTemplate tx, RunStateRepository store) {
        this.db = db;
        this.tx = tx;
        this.store = store;
    }

    long baseTime(String id) {
        return db.queryForObject("SELECT base_time FROM simulation_run WHERE id=?", Long.class, id);
    }

    int size(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=?", Integer.class, id);
    }

    List<String> existing(String id, String key) {
        return db.queryForList(
                "SELECT payload_json FROM simulation_scheduled_event WHERE run_id=? AND event_id=?",
                String.class,
                id,
                key);
    }

    void schedule(String id, String key, ScheduledInput event, String payload) {
        tx.executeWithoutResult(t -> {
            db.update(
                    "INSERT INTO simulation_scheduled_event"
                            + " (run_id,event_id,simulation_time,payload_json,delivered,created_sequence)"
                            + " VALUES (?,?,?,?,0,0)",
                    id,
                    key,
                    event.simulationTime(),
                    payload);
            store.append(
                    id,
                    "SIMULATION_EVENT_SCHEDULED",
                    store.state(id).simulationTime(),
                    null,
                    Map.of("scheduledEventId", key, "input", event));
            db.update(
                    "UPDATE simulation_scheduled_event SET created_sequence=? WHERE run_id=? AND" + " event_id=?",
                    store.eventCount(id),
                    id,
                    key);
        });
    }

    void advance(String id, String command, int step) {
        long clock = baseTime(id) + step + 1;
        tx.executeWithoutResult(t -> {
            db.update("INSERT INTO simulation_command VALUES (?,?)", id, command);
            db.update("UPDATE simulation_run SET step=step+1 WHERE id=?", id);
        });
        tx.executeWithoutResult(t -> {
            var due = db.queryForList(
                    "SELECT event_id,payload_json,simulation_time FROM simulation_scheduled_event"
                            + " WHERE run_id=? AND delivered=0 AND simulation_time<=? ORDER BY"
                            + " simulation_time,event_id LIMIT 10",
                    id,
                    clock);
            for (var event : due) {
                store.append(
                        id,
                        "SIMULATION_EVENT_CREATED",
                        ((Number) event.get("simulation_time")).longValue(),
                        null,
                        eventPayload((String) event.get("payload_json"), (String) event.get("event_id")));
                db.update(
                        "UPDATE simulation_scheduled_event SET delivered=1 WHERE run_id=? AND event_id=?",
                        id,
                        event.get("event_id"));
            }
        });
        var current = store.state(id);
        db.update(
                "UPDATE simulation_run SET state_json=? WHERE id=?",
                Json.write(new State(id, clock, current.version(), current.entities(), current.relations())),
                id);
    }

    long pendingEvents(String id) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=? AND delivered=0", Long.class, id);
    }

    private Map<String, Object> eventPayload(String json, String eventId) {
        var payload = new HashMap<String, Object>(Json.read(json, Map.class));
        payload.put("scheduledEventId", eventId);
        return payload;
    }
}
