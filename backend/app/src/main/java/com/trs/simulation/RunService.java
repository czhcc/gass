package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controlled single-writer event-driven runtime. LLM calls never hold DB transactions. @author
 * agent
 */
@Service
public class RunService {
  @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-decisions:50}")
  private int decisionBudget;

  @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-model-requests:300}")
  private int requestBudget;

  @org.springframework.beans.factory.annotation.Value(
      "${studio.simulation.max-generated-tokens:307200}")
  private long tokenBudget;

  @org.springframework.beans.factory.annotation.Value(
      "${studio.simulation.max-runtime-seconds:600}")
  private int timeBudget;

  @org.springframework.beans.factory.annotation.Value("${studio.simulation.max-state-changes:50}")
  private int changeBudget;

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final WorldService worlds;
  private final DecisionProvider decisions;
  private final Map<String, DomainEnvironment> domains = new HashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, UUID> active = new ConcurrentHashMap<>();
  private java.util.function.LongSupplier nanoClock = System::nanoTime;
  private final Map<String, Long> activeStarts = new ConcurrentHashMap<>();

  /** Injects SDK-independent runtime boundaries and domain plugins. @author agent */
  public RunService(
      JdbcTemplate db,
      TransactionTemplate tx,
      WorldService worlds,
      DecisionProvider decisions,
      List<DomainEnvironment> plugins) {
    this.db = db;
    this.tx = tx;
    this.worlds = worlds;
    this.decisions = decisions;
    plugins.forEach(p -> domains.put(p.domainCode(), p));
  }

  /** Restores interrupted runs as paused after a process restart. @author agent */
  @jakarta.annotation.PostConstruct
  public void recover() {
    db.update("UPDATE simulation_run SET status='PAUSED' WHERE status='RUNNING'");
  }

  /**
   * Creates an isolated run from frozen scenario/world inputs without contacting a model. @author
   * agent
   */
  public synchronized Run create(String scenarioId) {
    var scenario = worlds.scenario(scenarioId);
    var world = worlds.get(scenario.worldId());
    String id = UUID.randomUUID().toString();
    var state = new State(id, 0, 0, world.entities(), world.relations());
    tx.executeWithoutResult(
        t -> {
          db.update(
              "INSERT INTO simulation_run (id,scenario_json,status,step,state_json,created_at,world_json)"
                  + " VALUES (?,?, 'PAUSED',0,?,?,?)",
              id,
              Json.write(scenario),
              Json.write(state),
              System.currentTimeMillis(), Json.write(world));
          db.update("INSERT INTO simulation_snapshot VALUES (?,?,?)", id, 0, Json.write(state));
          append(id, "RUN_STARTED", 0, null, Map.of("mode", "EVENT_DRIVEN", "status", "PAUSED"));
        });
    return get(id);
  }

  /** Lists persisted runs. @author agent */
  public List<Run> list() {
    return db.query(
        "SELECT id,status,step,JSON_EXTRACT(scenario_json,'$.id')"
            + " scenario_id,JSON_EXTRACT(scenario_json,'$.maxSteps')"
            + " max_steps,JSON_EXTRACT(state_json,'$.version') version,active_runtime_ms,termination_reason FROM simulation_run ORDER BY"
            + " created_at DESC",
        (r, n) ->
            new Run(
                r.getString("id"),
                r.getString("scenario_id"),
                r.getString("status"),
                r.getInt("step"),
                r.getInt("max_steps"),
                r.getLong("version"), r.getLong("active_runtime_ms") + currentIntervalMs(r.getString("id")),
                r.getString("termination_reason")));
  }

  /** Safe deletion conflict with a stable client code. @author czhcc-trsdsj\codexsandboxoffline */
  public static final class DeletionConflict extends IllegalStateException {
    private final String code;

    /** Creates a safe lifecycle conflict. @author czhcc-trsdsj\codexsandboxoffline */
    public DeletionConflict(String code, String message) {
      super(message);
      this.code = code;
    }

    /** Returns the client error code. @author czhcc-trsdsj\codexsandboxoffline */
    public String code() { return code; }
  }

  /**
   * Deletes an inactive leaf run and its owned history atomically, preserving templates and siblings.
   * Shares the runtime lock with stepping and forking to prevent writes during deletion.
   * @author czhcc-trsdsj\codexsandboxoffline
   */
  public synchronized void delete(String id) {
    var run = get(id);
    if (run.status().equals("RUNNING"))
      throw new DeletionConflict("RUN_ACTIVE", "Pause or stop this run before deleting it.");
    tx.executeWithoutResult(t -> {
      if (db.queryForObject("SELECT COUNT(*) FROM simulation_branch WHERE parent_run_id=?",
          Integer.class, id) > 0)
        throw new DeletionConflict("RUN_HAS_BRANCHES", "Delete child branches first.");
      db.update("DELETE FROM simulation_branch WHERE run_id=?", id);
      db.update("DELETE FROM simulation_scheduled_event WHERE run_id=?", id);
      db.update("DELETE FROM simulation_command WHERE run_id=?", id);
      db.update("DELETE FROM simulation_snapshot WHERE run_id=?", id);
      db.update("DELETE FROM simulation_event WHERE run_id=?", id);
      db.update("DELETE FROM simulation_run WHERE id=?", id);
    });
    active.remove(id);
  }

  /** Evaluates current domain metrics through its plugin. @author agent */
  public Map<String, Object> evaluate(String id) {
    var domain = frozenWorld(id).domainCode();
    return domains.get(domain).evaluate(state(id));
  }

  /**
   * Returns the frozen agent configuration for activity and intervention controls. @author agent
   */
  public List<AgentDefinition> agents(String id) {
    return scenario(id).agents();
  }

  /** Lists current object capabilities for manual decisions without invoking a model. @author czhcc-trsdsj\codexsandboxoffline */
  public ActionCatalog actionCapabilities(String id) {
    return tx.execute(transaction -> {
      var world = frozenWorld(id);
      var state = state(id);
      var plugin = domains.get(world.domainCode());
      return new ActionCatalog(world.domainCode(), state.version(), scenario(id).agents().stream()
          .map(agent -> {
            boolean ended = hasEnded(id, agent.id());
            return new AgentCapabilities(agent.id(), ended ? List.of() : plugin.capabilities(world, agent, state), ended);
          }).toList());
    });
  }

  /** Returns metadata for an isolated run. @author agent */
  public Run get(String id) {
    var s = scenario(id);
    var row = db.queryForMap("SELECT status,step,active_runtime_ms,termination_reason FROM simulation_run WHERE id=?", id);
    return new Run(
        id,
        s.id(),
        (String) row.get("status"),
        ((Number) row.get("step")).intValue(),
        s.maxSteps(),
        state(id).version(), ((Number) row.get("active_runtime_ms")).longValue() + currentIntervalMs(id),
        (String) row.get("termination_reason"));
  }

  /** Gets current immutable state. @author agent */
  public State state(String id) {
    return Json.read(one("SELECT state_json FROM simulation_run WHERE id=?", id), State.class);
  }

  /**
   * Reads an exact committed state version without re-executing an agent or action. @author agent
   */
  public State replay(String id, long version) {
    return Json.read(
        one("SELECT state_json FROM simulation_snapshot WHERE run_id=? AND version=?", id, version),
        State.class);
  }

  /** Reads durable ordered events for reconnect and audit. @author agent */
  public List<Event> events(String id, long after) {
    state(id);
    return db.query(
        "SELECT * FROM simulation_event WHERE run_id=? AND sequence_no>? ORDER BY sequence_no LIMIT"
            + " 2000",
        (r, n) ->
            new Event(
                id,
                r.getLong("sequence_no"),
                r.getString("event_type"),
                r.getLong("simulation_time"),
                r.getString("agent_id"),
                Json.read(r.getString("payload_json"), Map.class)),
        id,
        after);
  }

  /**
   * Schedules an idempotent event; no state changes occur outside domain validation. @author agent
   */
  public synchronized void inject(String id, String key, ScheduledInput event) {
    var run = get(id);
    if (!List.of("PAUSED", "RUNNING").contains(run.status()))
      throw new IllegalStateException("Run is terminal");
    if (key == null
        || key.isBlank()
        || key.length() > 100
        || event == null
        || event.eventType() == null
        || !List.of("POTENTIAL_LEAK", "USER_NOTE", "BANK_TRANSFER").contains(event.eventType())
        || event.parameters() == null
        || event.simulationTime() < state(id).simulationTime()
        || event.simulationTime()
            > db.queryForObject("SELECT base_time FROM simulation_run WHERE id=?", Long.class, id)
                + run.maxSteps()
        || Json.write(event).length() > 4000)
      throw new IllegalArgumentException("Invalid scheduled event");
    if (state(id).entities().stream().noneMatch(e -> e.id().equals(event.targetId())))
      throw new IllegalArgumentException("Event target not found");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=?", Integer.class, id)
        >= 100) throw new IllegalStateException("Event queue budget reached");
    String payload = Json.write(event);
    var existing =
        db.queryForList(
            "SELECT payload_json FROM simulation_scheduled_event WHERE run_id=? AND event_id=?",
            String.class,
            id,
            key);
    if (!existing.isEmpty()) {
      if (!existing.getFirst().equals(payload))
        throw new IllegalStateException("Idempotency key already used for another event");
      return;
    }
    if (eventCount(id) >= scenario(id).maxEvents() - 1)
      throw new IllegalStateException("Event budget exhausted");
    tx.executeWithoutResult(
        t -> {
          db.update(
              "INSERT INTO simulation_scheduled_event"
                  + " (run_id,event_id,simulation_time,payload_json,delivered,created_sequence)"
                  + " VALUES (?,?,?,?,0,0)",
              id,
              key,
              event.simulationTime(),
              payload);
          append(
              id,
              "SIMULATION_EVENT_SCHEDULED",
              state(id).simulationTime(),
              null,
              Map.of("scheduledEventId", key, "input", event));
          db.update(
              "UPDATE simulation_scheduled_event SET created_sequence=? WHERE run_id=? AND"
                  + " event_id=?",
              eventCount(id),
              id,
              key);
        });
  }

  /**
   * Executes one event-driven round with ordered fresh observations and bounded budgets. @author
   * agent
   */
  public synchronized State step(String id, String command) {
    if (command == null || command.isBlank() || command.length() > 100)
      throw new IllegalArgumentException("Idempotency-Key required, max 100 characters");
    if (db.queryForObject("SELECT COUNT(*) FROM simulation_command WHERE run_id=? AND command_id=?", Integer.class, id, command) > 0)
      return state(id);
    if (!List.of("PAUSED", "RUNNING").contains(get(id).status()))
      throw new IllegalStateException("Run is terminal");
    activeStarts.put(id, nanoClock.getAsLong());
    try {
      return stepRound(id, command);
    } finally {
      long elapsed = currentIntervalMs(id);
      activeStarts.remove(id);
      db.update("UPDATE simulation_run SET active_runtime_ms=active_runtime_ms+? WHERE id=?", elapsed, id);
    }
  }

  private State stepRound(String id, String command) {
    var run = get(id);
    var scenario = scenario(id);
    if (checkTermination(id, scenario, true)) return state(id);
    long clock =
        db.queryForObject("SELECT base_time FROM simulation_run WHERE id=?", Long.class, id)
            + run.step()
            + 1;
    tx.executeWithoutResult(
        t -> {
          db.update("INSERT INTO simulation_command VALUES (?,?)", id, command);
          db.update("UPDATE simulation_run SET step=step+1 WHERE id=?", id);
        });
    tx.executeWithoutResult(
        t -> {
          var due =
              db.queryForList(
                  "SELECT event_id,payload_json,simulation_time FROM simulation_scheduled_event"
                      + " WHERE run_id=? AND delivered=0 AND simulation_time<=? ORDER BY"
                      + " simulation_time,event_id LIMIT 10",
                  id,
                  clock);
          for (var event : due) {
            append(
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
    var world = frozenWorld(id);
    var plugin = domains.get(world.domainCode());
    if (plugin == null) throw new IllegalStateException("Domain plugin unavailable");
    for (var agent : scenario.agents()) {
      int count =
          db.queryForObject(
              "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND agent_id=? AND"
                  + " event_type='AGENT_STARTED'",
              Integer.class,
              id,
              agent.id());
      if (count >= agent.maxDecisions() || hasEnded(id, agent.id())) continue;
      // Each SDK decision has <= 3 iterations plus structured response, <= 6 requests / 1024 output
      // tokens each.
      // At most 50 decisions: <=300 model requests, <=307200 generated tokens per run.
      if (checkTermination(id, scenario, false)) break;
      var before = state(id);
      var visible = plugin.observationScope(agent, before);
      var history =
          events(id, Math.max(0, eventCount(id) - 30)).stream()
              .filter(
                  e ->
                      agent.id().equals(e.agentId())
                          && (e.eventType().equals("ACTION_ACCEPTED")
                              || e.eventType().equals("ACTION_REJECTED")
                              || e.eventType().equals("AGENT_MEMORY_RESTORED")))
              .map(e -> Json.write(e.payload()))
              .toList();
      var observation =
          new Observation(
              id,
              clock,
              before.version(),
              before.entities().stream().filter(e -> visible.contains(e.id())).toList(),
              before.relations().stream()
                  .filter(e -> visible.contains(e.sourceId()) && visible.contains(e.targetId()))
                  .toList(),
              history,
              events(id, 0).stream()
                  .filter(
                      e ->
                          e.eventType().equals("SIMULATION_EVENT_CREATED")
                              && visible.contains(String.valueOf(e.payload().get("targetId"))))
                  .map(Event::payload)
                  .limit(20)
                  .toList(),
              plugin.capabilities(world, agent, before), world.domainCode());
      append(
          id,
          "AGENT_STARTED",
          clock,
          agent.id(),
          Map.of("modelMode", Optional.ofNullable(decisions.mode(agent)).orElse("UNKNOWN")));
      append(id, "AGENT_OBSERVATION", clock, agent.id(), Map.of("observation", observation));
      Decision proposal;
      try {
        proposal = decisions.decide(agent, Json.read(Json.write(observation), Observation.class));
        if (proposal == null) throw new IllegalArgumentException();
      } catch (Exception ex) {
        decisions
            .drainToolCalls()
            .forEach(call -> append(id, "AGENT_TOOL_CALL", clock, agent.id(), call));
        append(
            id,
            "RUN_FAILED",
            clock,
            agent.id(),
            Map.of("reason", "Model decision failed or timed out; verify model configuration", "reasonCode", "MODEL_DECISION_FAILED", "activeRuntimeMs", get(id).activeRuntimeMs()));
        db.update("UPDATE simulation_run SET status='FAILED',termination_reason='MODEL_DECISION_FAILED' WHERE id=?", id);
        return state(id);
      }
      decisions
          .drainToolCalls()
          .forEach(call -> append(id, "AGENT_TOOL_CALL", clock, agent.id(), call));
      // Normalize through JSON to prevent a provider retaining references to mutable proposal maps.
      proposal = Json.read(Json.write(proposal), Decision.class);
      var current = new State(id, clock, before.version(), before.entities(), before.relations());
      var d = plugin.normalizeDecision(proposal, current);
      var outcome = plugin.execute(world, agent, d, current);
      commit(id, agent.id(), d, current, outcome, "AGENT");
    }
    checkTermination(id, scenario, true);
    return state(id);
  }

  /**
   * Executes a user intervention through the same permission, version and domain arbiter. @author
   * agent
   */
  public synchronized State intervene(
      String id, String key, String agentId, Decision decision, long expectedVersion) {
    if (key == null || key.isBlank() || key.length() > 100)
      throw new IllegalArgumentException("Idempotency key required");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM simulation_command WHERE run_id=? AND command_id=?",
            Integer.class,
            id,
            key)
        > 0) return state(id);
    if (decision == null) throw new IllegalArgumentException("Decision required");
    var run = get(id);
    if (!run.status().equals("PAUSED"))
      throw new IllegalStateException("Interventions require a paused run");
    var current = state(id);
    if (current.version() != expectedVersion)
      throw new IllegalStateException("World version conflict");
    if (current.version()
                - db.queryForObject(
                    "SELECT MIN(version) FROM simulation_snapshot WHERE run_id=?", Long.class, id)
            >= changeBudget
        || eventCount(id) > scenario(id).maxEvents() - 10)
      throw new IllegalStateException("Run budget exhausted");
    var scenario = scenario(id);
    var agent =
        scenario.agents().stream()
            .filter(a -> a.id().equals(agentId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
    if (hasEnded(id, agent.id())) throw new IllegalStateException("Agent has ended its task");
    var world = frozenWorld(id);
    var plugin = domains.get(world.domainCode());
    var normalized = plugin.normalizeDecision(decision, current);
    var outcome = plugin.execute(world, agent, normalized, current);
    tx.executeWithoutResult(
        t -> {
          db.update("INSERT INTO simulation_command VALUES (?,?)", id, key);
          commit(id, agent.id(), normalized, current, outcome, "USER");
        });
    return state(id);
  }

  private void commit(
      String id, String agent, Decision d, State current, Outcome outcome, String origin) {
    tx.executeWithoutResult(
        t -> {
          append(
              id,
              origin.equals("USER") ? "USER_INTERVENTION" : "AGENT_DECISION",
              current.simulationTime(),
              agent,
              Map.of("decision", d, "evidence", "HYPOTHESIS", "origin", origin));
          append(id, "ACTION_PROPOSED", current.simulationTime(), agent, Map.of("action", d));
          append(
              id,
              outcome.accepted() ? "ACTION_ACCEPTED" : "ACTION_REJECTED",
              current.simulationTime(),
              agent,
              Map.of(
                  "code", outcome.code(),
                  "affectedRelationIds", outcome.affectedRelationIds(),
                  "targetKind", d.targetKind() == null ? legacyTargetKind(d, current) : d.targetKind(),
                  "reason",
                  outcome.message(),
                  "actionType",
                  d.actionType() == null ? "" : d.actionType(),
                  "targetId",
                  d.targetId() == null ? "" : d.targetId()));
          var next = outcome.state();
          db.update("UPDATE simulation_run SET state_json=? WHERE id=?", Json.write(next), id);
          if (outcome.changed()) {
            db.update(
                "INSERT INTO simulation_snapshot VALUES (?,?,?)",
                id,
                next.version(),
                Json.write(next));
            append(
                id,
                "WORLD_STATE_CHANGED",
                current.simulationTime(),
                agent,
                Map.of(
                    "before",
                    current,
                    "after",
                    next,
                    "version",
                    next.version(),
                    "evidence",
                    "SIMULATED"));
          }
        });
  }

  /** Pauses automatic computation; historical replay is unaffected. @author agent */
  public synchronized Run pause(String id) {
    var r = get(id);
    if (r.status().equals("PAUSED")) return r;
    if (!r.status().equals("RUNNING"))
      throw new IllegalStateException("Only running simulations can pause");
    db.update("UPDATE simulation_run SET status='PAUSED' WHERE id=?", id);
    active.remove(id);
    append(id, "RUN_PAUSED", r.step(), null, Map.of());
    return get(id);
  }

  /** Starts bounded automatic advancement on a virtual thread. @author agent */
  public synchronized Run resume(String id) {
    var r = get(id);
    if (r.status().equals("RUNNING")) return r;
    if (!r.status().equals("PAUSED"))
      throw new IllegalStateException("Only paused simulations can resume");
    db.update("UPDATE simulation_run SET status='RUNNING' WHERE id=?", id);
    append(id, "RUN_RESUMED", r.step(), null, Map.of());
    UUID workerToken = UUID.randomUUID();
    if (active.putIfAbsent(id, workerToken) == null)
      workers.submit(
          () -> {
            try {
              while (true) {
                synchronized (RunService.this) {
                  if (!workerToken.equals(active.get(id))) break;
                  if (!get(id).status().equals("RUNNING")) {
                    active.remove(id, workerToken);
                    break;
                  }
                  step(id, UUID.randomUUID().toString());
                }
                try {
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            } finally {
              active.remove(id, workerToken);
            }
          });
    return get(id);
  }

  /** Stops the run permanently. @author agent */
  public synchronized Run stop(String id) {
    var r = get(id);
    if (r.status().equals("STOPPED")) return r;
    if (List.of("FAILED", "COMPLETED", "BUDGET_EXHAUSTED").contains(r.status()))
      throw new IllegalStateException("Run is terminal");
    db.update("UPDATE simulation_run SET status='STOPPED',termination_reason='USER_STOPPED' WHERE id=?", id);
    append(id, "RUN_STOPPED", r.step(), null, Map.of("reasonCode", "USER_STOPPED", "reason", "Stopped by user", "activeRuntimeMs", r.activeRuntimeMs()));
    return get(id);
  }

  private String legacyTargetKind(Decision decision, State state) {
    if ("END".equals(decision.actionType())) return "AGENT";
    return state.entities().stream().anyMatch(e -> e.id().equals(decision.targetId())) ? "ENTITY" : "RELATION";
  }

  private long currentIntervalMs(String id) {
    Long start = activeStarts.get(id);
    return start == null ? 0 : Math.max(0, (nanoClock.getAsLong() - start) / 1_000_000L);
  }

  private boolean checkTermination(String id, Scenario scenario, boolean checkSteps) {
    var run = get(id);
    if (!List.of("PAUSED", "RUNNING").contains(run.status())) return true;
    if (scenario.agents().stream().allMatch(a -> hasEnded(id, a.id())))
      return finish(id, "ALL_AGENTS_ENDED", scenario.agents().size(), scenario.agents().size(), "All agents submitted accepted END decisions", "");
    if (checkSteps && run.step() >= scenario.maxSteps())
      return finish(id, "MAX_STEPS", run.step(), scenario.maxSteps(), "Maximum simulation rounds reached", "");
    int events = eventCount(id);
    if (events > scenario.maxEvents() - 10)
      return finish(id, "MAX_EVENTS", events, scenario.maxEvents(), "Event budget reached (10 events reserved for audit)", "");
    long timeLimit = Math.max(1, timeBudget) * 1000L;
    if (run.activeRuntimeMs() >= timeLimit)
      return finish(id, "MAX_RUNTIME", run.activeRuntimeMs(), timeLimit, "Active computation time budget reached", "");
    if (scenario.agents().stream().allMatch(a -> hasEnded(id, a.id()) || decisionCount(id, a.id()) >= a.maxDecisions())) {
      var exhausted = scenario.agents().stream().filter(a -> !hasEnded(id, a.id())).findFirst().orElseThrow();
      return finish(id, "MAX_AGENT_DECISIONS", decisionCount(id, exhausted.id()), exhausted.maxDecisions(), "All remaining agents exhausted their decision budgets", exhausted.id());
    }
    int total = db.queryForObject("SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND event_type='AGENT_STARTED'", Integer.class, id);
    if (total >= Math.min(50, decisionBudget))
      return finish(id, "MAX_DECISIONS", total, Math.min(50, decisionBudget), "Global decision budget reached", "");
    if ((total + 1) * 6L > requestBudget)
      return finish(id, "MODEL_REQUEST_BUDGET", (total + 1) * 6L, requestBudget, "Insufficient model request budget for the next decision (reserved estimate)", "");
    if ((total + 1) * 6144L > tokenBudget)
      return finish(id, "TOKEN_BUDGET", (total + 1) * 6144L, tokenBudget, "Insufficient generated token budget for the next decision (reserved estimate)", "");
    long changes = state(id).version() - db.queryForObject("SELECT MIN(version) FROM simulation_snapshot WHERE run_id=?", Long.class, id);
    if (changes >= changeBudget)
      return finish(id, "MAX_STATE_CHANGES", changes, changeBudget, "State change budget reached", "");
    return false;
  }

  private boolean finish(String id, String reason, long actual, long limit, String message, String agentId) {
    var run = get(id);
    if (!List.of("PAUSED", "RUNNING").contains(run.status())) return true;
    boolean completed = reason.equals("ALL_AGENTS_ENDED");
    String status = completed ? "COMPLETED" : "BUDGET_EXHAUSTED";
    tx.executeWithoutResult(t -> {
      db.update("UPDATE simulation_run SET status=?,termination_reason=? WHERE id=?", status, reason, id);
      append(id, completed ? "RUN_COMPLETED" : "RUN_BUDGET_EXHAUSTED", run.step(), null,
          Map.of("reasonCode", reason, "reason", message, "actual", actual, "limit", limit,
              "activeRuntimeMs", run.activeRuntimeMs(), "agentId", agentId));
    });
    return true;
  }

  private int decisionCount(String id, String agent) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND agent_id=? AND"
            + " event_type='AGENT_STARTED'",
        Integer.class,
        id,
        agent);
  }

  private boolean hasEnded(String id, String agent) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND agent_id=? AND"
                + " event_type='ACTION_ACCEPTED' AND"
                + " JSON_EXTRACT(payload_json,'$.actionType')='END'",
            Integer.class,
            id,
            agent)
        > 0;
  }

  private int eventCount(String id) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM simulation_event WHERE run_id=?", Integer.class, id);
  }

  private World frozenWorld(String id) {
    return Json.read(one("SELECT world_json FROM simulation_run WHERE id=?", id), World.class);
  }

  private Scenario scenario(String id) {
    return Json.read(
        one("SELECT scenario_json FROM simulation_run WHERE id=?", id), Scenario.class);
  }

  private String one(String sql, Object... args) {
    return db.query(sql, (r, n) -> r.getString(1), args).stream()
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("Run/checkpoint not found"));
  }

  private Map<String, Object> eventPayload(String json, String eventId) {
    var payload = new HashMap<String, Object>(Json.read(json, Map.class));
    payload.put("scheduledEventId", eventId);
    return payload;
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

  /** Closes workers during application shutdown. @author agent */
  @PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}
