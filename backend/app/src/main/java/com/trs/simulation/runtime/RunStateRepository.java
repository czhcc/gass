package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.common.Json;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads frozen run data and maintains the durable event log. @author czhcc-trsdsj\codexsandboxoffline */
final class RunStateRepository {
    private final JdbcTemplate db;

    RunStateRepository(JdbcTemplate db) {
        this.db = db;
    }

    State state(String id) {
        return Json.read(one("SELECT state_json FROM simulation_run WHERE id=?", id), State.class);
    }

    State replay(String id, long version) {
        return Json.read(
                one("SELECT state_json FROM simulation_snapshot WHERE run_id=? AND version=?", id, version),
                State.class);
    }

    List<Event> events(String id, long after) {
        state(id);
        return db.query(
                "SELECT * FROM simulation_event WHERE run_id=? AND sequence_no>? ORDER BY sequence_no LIMIT" + " 2000",
                (r, n) -> new Event(
                        id,
                        r.getLong("sequence_no"),
                        r.getString("event_type"),
                        r.getLong("simulation_time"),
                        r.getString("agent_id"),
                        Json.read(r.getString("payload_json"), Map.class)),
                id,
                after);
    }

    Event event(RuleWork.EventRef ref) {
        return db
                .query(
                        """
                SELECT simulation_time,event_type,agent_id,payload_json FROM simulation_event
                WHERE run_id=? AND sequence_no=?
                """,
                        (row, index) -> new Event(
                                ref.runId(),
                                ref.sequence(),
                                row.getString("event_type"),
                                row.getLong("simulation_time"),
                                row.getString("agent_id"),
                                Json.read(row.getString("payload_json"), Map.class)),
                        ref.runId(),
                        ref.sequence())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Rule source event is missing"));
    }

    State initialState(String id) {
        var world = frozenWorld(id);
        return new State(id, 0, 0, world.entities(), world.relations());
    }

    World frozenWorld(String id) {
        return Json.read(one("SELECT world_json FROM simulation_run WHERE id=?", id), World.class);
    }

    Scenario scenario(String id) {
        return Json.read(one("SELECT scenario_json FROM simulation_run WHERE id=?", id), Scenario.class);
    }

    int eventCount(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM simulation_event WHERE run_id=?", Integer.class, id);
    }

    void append(String id, String type, long time, String agent, Map<String, Object> payload) {
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

    private String one(String sql, Object... args) {
        return db.query(sql, (r, n) -> r.getString(1), args).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Run/checkpoint not found"));
    }

    int decisionCount(String id, String agent) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND agent_id=? AND"
                        + " event_type='AGENT_STARTED'",
                Integer.class,
                id,
                agent);
    }

    boolean hasEnded(String id, String agent) {
        return db.queryForObject(
                        "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND agent_id=? AND"
                                + " event_type='ACTION_ACCEPTED' AND"
                                + " JSON_EXTRACT(payload_json,'$.actionType')='END'",
                        Integer.class,
                        id,
                        agent)
                > 0;
    }

    Map<String, Object> usage(String id) {
        var rows = db.queryForList("SELECT requests,reserved_tokens FROM simulation_usage WHERE run_id=?", id);
        return rows.isEmpty() ? Map.of("requests", 0, "reserved_tokens", 0) : rows.getFirst();
    }
}
