package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
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
      String summary) {}

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
      if (event.eventType().equals("WORLD_STATE_CHANGED"))
        result.add(
            new Checkpoint(
                event.sequence(),
                ((Number) event.payload().get("version")).longValue(),
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
    if (sequence < 0 || sequence > max)
      throw new IllegalArgumentException("Event position out of range");
    var state = baseline(id);
    long time = state.simulationTime();
    for (var event : events) {
      if (event.sequence() > sequence) break;
      time = event.simulationTime();
      if (event.eventType().equals("WORLD_STATE_CHANGED"))
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
        || label.length() > 100)
      throw new IllegalArgumentException("Fork requires label and idempotency key");
    synchronized (runs) {
      var existing =
          db.queryForList(
              "SELECT run_id FROM simulation_branch WHERE parent_run_id=? AND command_key=?",
              String.class,
              parent,
              key);
      if (!existing.isEmpty()) return runs.get(existing.getFirst());
      var snapshot = stateAt(parent, sequence);
      return tx.execute(
          t -> {
            var scenario =
                Json.read(
                    db.queryForObject(
                        "SELECT scenario_json FROM simulation_run WHERE id=?",
                        String.class,
                        parent),
                    Scenario.class);
            // The editable scenario may have changed since the ancestor began; always preserve its
            // frozen copy.
            String child = UUID.randomUUID().toString();
            var state =
                new State(
                    child,
                    snapshot.simulationTime(),
                    snapshot.version(),
                    snapshot.entities(),
                    snapshot.relations());
            db.update(
                "INSERT INTO simulation_run (id,scenario_json,status,step,state_json,created_at)"
                    + " VALUES (?,?,'PAUSED',?,?,?)",
                child,
                Json.write(scenario),
                snapshot.simulationTime(),
                Json.write(state),
                System.currentTimeMillis());
            db.update(
                "UPDATE simulation_run SET base_time=?,step=0 WHERE id=?",
                snapshot.simulationTime(),
                child);
            db.update(
                "INSERT INTO simulation_snapshot VALUES (?,?,?)",
                child,
                state.version(),
                Json.write(state));
            db.update(
                "INSERT INTO simulation_branch VALUES (?,?,?,?,?)",
                child,
                parent,
                sequence,
                label,
                key);
            append(
                child,
                "BRANCH_CREATED",
                state.simulationTime(),
                null,
                Map.of("parentRunId", parent, "parentSequence", sequence, "label", label));
            var past =
                runs.events(parent, 0).stream().filter(e -> e.sequence() <= sequence).toList();
            for (var event : past) {
              if (event.eventType().equals("SIMULATION_EVENT_CREATED")) {
                var payload = new HashMap<>(event.payload());
                payload.put("inherited", true);
                append(child, "SIMULATION_EVENT_CREATED", state.simulationTime(), null, payload);
              }
            }
            for (var event :
                past.stream()
                    .filter(
                        e ->
                            List.of("ACTION_ACCEPTED", "ACTION_REJECTED", "AGENT_MEMORY_RESTORED")
                                .contains(e.eventType()))
                    .skip(
                        Math.max(
                            0,
                            past.stream()
                                    .filter(
                                        e ->
                                            List.of(
                                                    "ACTION_ACCEPTED",
                                                    "ACTION_REJECTED",
                                                    "AGENT_MEMORY_RESTORED")
                                                .contains(e.eventType()))
                                    .count()
                                - 30))
                    .toList())
              append(
                  child,
                  "AGENT_MEMORY_RESTORED",
                  state.simulationTime(),
                  event.agentId(),
                  event.payload());
            var pending =
                db.queryForList(
                    "SELECT event_id,simulation_time,payload_json FROM simulation_scheduled_event"
                        + " WHERE run_id=? AND created_sequence<=? ORDER BY"
                        + " simulation_time,event_id",
                    parent,
                    sequence);
            for (var event : pending) {
              boolean delivered =
                  past.stream()
                      .anyMatch(
                          e ->
                              e.eventType().equals("SIMULATION_EVENT_CREATED")
                                  && Objects.equals(
                                      e.payload().get("scheduledEventId"), event.get("event_id")));
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
                        "SELECT scenario_json FROM simulation_run WHERE id=?", String.class, left),
                    Scenario.class)
                .worldId(),
        worldB =
            Json.read(
                    db.queryForObject(
                        "SELECT scenario_json FROM simulation_run WHERE id=?", String.class, right),
                    Scenario.class)
                .worldId();
    if (!worldA.equals(worldB))
      throw new IllegalArgumentException("Compare requires the same world template");
    var entities =
        diff(
            a.entities().stream()
                .collect(java.util.stream.Collectors.toMap(Entity::id, e -> (Object) e)),
            b.entities().stream()
                .collect(java.util.stream.Collectors.toMap(Entity::id, e -> (Object) e)));
    var relations =
        diff(
            a.relations().stream()
                .collect(java.util.stream.Collectors.toMap(Relation::id, e -> (Object) e)),
            b.relations().stream()
                .collect(java.util.stream.Collectors.toMap(Relation::id, e -> (Object) e)));
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
            + " changed relations. SIMULATED results, not real-world facts.");
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

  private void append(
      String id, String type, long time, String agent, Map<String, Object> payload) {
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
