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

  @org.springframework.beans.factory.annotation.Value("${studio.simulation.enforce-output-token-budget:false}")
  private boolean enforceOutputTokenBudget;

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
  private final HierarchicalCoordinator hierarchy;
  private final ModelService models;
  private final Map<String, Lease> invocations = new HashMap<>();
  /** Cancellation ownership for one round, shared by every nested task. @author czhcc-trsdsj\codexsandboxoffline */
  private static final class Lease {
    private final reactor.core.publisher.Sinks.One<Void> cancellation = reactor.core.publisher.Sinks.one();
    private volatile boolean cancelled;
    private void cancel() { cancelled = true; cancellation.tryEmitEmpty(); }
  }
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
      List<DomainEnvironment> plugins, HierarchicalCoordinator hierarchy, ModelService models) {
    this.db = db;
    this.tx = tx;
    this.worlds = worlds;
    this.decisions = decisions;
    this.hierarchy = hierarchy;
    this.models = models;
    plugins.forEach(p -> domains.put(p.domainCode(), p));
  }

  /** Restores interrupted runs and closes their task audit records after restart. @author czhcc-trsdsj\codexsandboxoffline */
  @jakarta.annotation.PostConstruct
  public synchronized void recover() {
    db.update("UPDATE simulation_run SET status='PAUSED' WHERE status='RUNNING'");
    var interrupted = db.queryForList("SELECT DISTINCT run_id FROM simulation_task WHERE status='RUNNING'", String.class);
    for (var id : interrupted) for (var task : tasks(id)) if (task.status().equals("RUNNING")) {
      var receipts = events(id, 0).stream().filter(e -> e.eventType().equals("ACTION_TASK_RECEIPT") && task.taskId().equals(e.payload().get("taskId"))).map(Event::payload).toList();
      String originalInput = task.request().inputEventId() == null ? "" : task.request().inputEventId();
      var inputId = receipts.isEmpty() ? originalInput : receipts.getFirst().getOrDefault("inputEventId", originalInput);
      var result = Map.<String,Object>of("message", "Task cancelled after application restart", "receipts", receipts, "inputEventId", inputId);
      tx.executeWithoutResult(t -> {
        append(id, task.parentTaskId() == null ? "MASTER_TASK_COMPLETED" : "SUBTASK_COMPLETED", state(id).simulationTime(), task.agentId(), Map.of("taskId", task.taskId(), "parentTaskId", task.parentTaskId() == null ? "" : task.parentTaskId(), "status", "CANCELLED", "result", result));
        db.update("UPDATE simulation_task SET status='CANCELLED',output_version=?,result_json=?,completed_sequence=? WHERE run_id=? AND task_id=?", state(id).version(), Json.write(result), eventCount(id), id, task.taskId());
      });
    }
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
          db.update("INSERT INTO simulation_usage(run_id) VALUES (?)", id);
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
    if (run.status().equals("RUNNING") || invocations.containsKey(id))
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
      db.update("DELETE FROM simulation_task WHERE run_id=?", id);
      db.update("DELETE FROM simulation_usage WHERE run_id=?", id);
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

  /** Seeds a configured example input using this run's frozen world and an idempotent key. @author czhcc-trsdsj\codexsandboxoffline */
  public synchronized void seedInput(String id, ScenarioProfiles profiles) { inject(id, "demo-initial", profiles.initialInput(frozenWorld(id))); }

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
        (String) row.get("termination_reason"), s.objective());
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
        || !(List.of("POTENTIAL_LEAK", "USER_NOTE", "BANK_TRANSFER").contains(event.eventType()) || scenario(id).team() != null && scenario(id).team().inputActions().containsKey(event.eventType()))
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
  public State step(String id, String command) {
    return step(id, command, null);
  }

  private State step(String id, String command, UUID workerToken) {
    if (command == null || command.isBlank() || command.length() > 100)
      throw new IllegalArgumentException("Idempotency-Key required, max 100 characters");
    var lease = new Lease();
    synchronized (this) {
      if (workerToken != null && (!workerToken.equals(active.get(id)) || !get(id).status().equals("RUNNING"))) return state(id);
      if (db.queryForObject("SELECT COUNT(*) FROM simulation_command WHERE run_id=? AND command_id=?", Integer.class, id, command) > 0) return state(id);
      if (!List.of("PAUSED", "RUNNING").contains(get(id).status())) throw new IllegalStateException("Run is terminal");
      if (invocations.containsKey(id)) throw new IllegalStateException("Run is already computing a round");
      invocations.put(id, lease);
      activeStarts.put(id, nanoClock.getAsLong());
    }
    try {
      if (scenario(id).team() == null) { synchronized (this) { return stepRound(id, command); } }
      return stepRound(id, command);
    } finally {
      synchronized (this) {
        long elapsed = currentIntervalMs(id);
        activeStarts.remove(id);
        db.update("UPDATE simulation_run SET active_runtime_ms=active_runtime_ms+? WHERE id=?", elapsed, id);
        invocations.remove(id, lease);
      }
    }
  }

  private State stepRound(String id, String command) {
    Lease lease;
    synchronized (this) { prepareRound(id, command); lease = invocations.get(id); }
    var scenario = scenario(id);
    if (!List.of("PAUSED", "RUNNING").contains(get(id).status())) {
      reviewAtHorizon(id, scenario, lease);
      return state(id);
    }
    if (scenario.team() != null) {
      var host = new RuntimeHost(id, lease);
      try { hierarchy.run(host); }
      catch (CancellationException ex) { /* Pause, stop and shared budgets preserve their authoritative lifecycle status. */ }
      catch (RuntimeException ex) {
        synchronized (this) {
          if (List.of("PAUSED", "RUNNING").contains(get(id).status()) && !host.lease.cancelled) {
            db.update("UPDATE simulation_run SET status='FAILED',termination_reason='MODEL_DECISION_FAILED' WHERE id=?", id);
            append(id, "RUN_FAILED", state(id).simulationTime(), null, hierarchyFailure(ex));
          }
        }
      }
      synchronized (this) { if (!host.lease.cancelled) checkTermination(id, scenario, true); }
      reviewAtHorizon(id, scenario, host.lease);
      return state(id);
    }
    long clock = db.queryForObject("SELECT base_time FROM simulation_run WHERE id=?", Long.class, id) + get(id).step();
    return legacyRound(id, scenario, clock);
  }

  private Map<String, Object> hierarchyFailure(RuntimeException failure) {
    Throwable detail = failure;
    String diagnostic = "HIERARCHICAL_EXECUTION_ERROR";
    for (int depth = 0; depth < 12; depth++) {
      if (detail instanceof TimeoutException || detail instanceof java.net.SocketTimeoutException
          || detail instanceof java.net.http.HttpTimeoutException) {
        diagnostic = "MODEL_REQUEST_TIMEOUT";
        break;
      }
      if (detail.getCause() == null) break;
      detail = detail.getCause();
    }
    return Map.of("reasonCode", "MODEL_DECISION_FAILED", "reason", "Model planning or child task failed; inspect task receipts",
        "diagnosticCode", diagnostic, "exceptionType", detail.getClass().getSimpleName());
  }

  private void prepareRound(String id, String command) {
    var run = get(id);
    var scenario = scenario(id);
    if (checkTermination(id, scenario, true)) return;
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
    var current = state(id);
    db.update("UPDATE simulation_run SET state_json=? WHERE id=?", Json.write(new State(id, clock, current.version(), current.entities(), current.relations())), id);
  }

  private State legacyRound(String id, Scenario scenario, long clock) {
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
      // Legacy budget estimates reserve six requests and 6144 output tokens per decision.
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
    if (invocations.containsKey(id)) throw new IllegalStateException("Wait for the active round to finish cancelling");
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
    commit(id, agent, d, current, outcome, origin, Map.of());
  }

  private void commit(String id, String agent, Decision d, State current, Outcome outcome, String origin, Map<String,Object> taskContext) {
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
            var changes = new LinkedHashMap<String,Object>(Map.of("before", current, "after", next, "version", next.version(), "evidence", "SIMULATED"));
            changes.putAll(taskContext);
            append(
                id,
                "WORLD_STATE_CHANGED",
                current.simulationTime(),
                agent,
                changes);
          }
        });
  }

  /** Pauses automatic computation; historical replay is unaffected. @author agent */
  public synchronized Run pause(String id) {
    var r = get(id);
    if (!List.of("PAUSED", "RUNNING").contains(r.status()))
      throw new IllegalStateException("Only running simulations can pause");
    var lease = invocations.get(id);
    if (lease != null) { lease.cancel(); accumulateInterval(id); }
    if (r.status().equals("PAUSED")) return r;
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
    if (invocations.containsKey(id)) throw new IllegalStateException("A round is still active; wait for it to finish");
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
                }
                step(id, UUID.randomUUID().toString(), workerToken);
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
    active.remove(id);
    var lease = invocations.get(id); if (lease != null) { lease.cancel(); accumulateInterval(id); }
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

  private void accumulateInterval(String id) {
    long elapsed = currentIntervalMs(id); activeStarts.remove(id);
    db.update("UPDATE simulation_run SET active_runtime_ms=active_runtime_ms+? WHERE id=?", elapsed, id);
  }

  private boolean checkTermination(String id, Scenario scenario, boolean checkSteps) {
    var run = get(id);
    if (!List.of("PAUSED", "RUNNING").contains(run.status())) return true;
    if (scenario.team() == null && scenario.agents().stream().allMatch(a -> hasEnded(id, a.id())))
      return finish(id, "ALL_AGENTS_ENDED", scenario.agents().size(), scenario.agents().size(), "All agents submitted accepted END decisions", "");
    if (checkSteps && scenario.objective() != null && scenario.team().conditions().isEmpty() && pendingEvents(id) == 0
        && events(id, 0).reversed().stream().anyMatch(e -> ObjectiveEvidence.COMPLETION_EVENT.equals(e.eventType())
            && e.payload().get("round") instanceof Number round && round.intValue() == run.step()
            && e.payload().get("version") instanceof Number version && version.longValue() == run.version()))
      return evaluateHorizon(id, scenario, true);
    if (checkSteps && run.step() >= scenario.maxSteps()) {
      if (scenario.objective() != null && db.queryForObject(
          "SELECT COUNT(*) FROM simulation_task WHERE run_id=? AND round=? AND parent_task_id IS NULL AND status='SUCCEEDED'",
          Integer.class, id, run.step()) == 0) {
        tx.executeWithoutResult(t -> {
          db.update("UPDATE simulation_run SET status='STOPPED',termination_reason='OBJECTIVE_HORIZON_INTERRUPTED' WHERE id=?", id);
          append(id, "RUN_STOPPED", state(id).simulationTime(), null, Map.of("reasonCode", "OBJECTIVE_HORIZON_INTERRUPTED"));
        });
        return true;
      }
      if (scenario.team() != null) return evaluateHorizon(id, scenario, false);
      return finish(id, "MAX_STEPS", run.step(), scenario.maxSteps(), "Maximum simulation rounds reached", "");
    }
    int events = eventCount(id);
    if (events > scenario.maxEvents() - 10)
      return finish(id, "MAX_EVENTS", events, scenario.maxEvents(), "Event budget reached (10 events reserved for audit)", "");
    long timeLimit = Math.max(1, timeBudget) * 1000L;
    if (run.activeRuntimeMs() >= timeLimit)
      return finish(id, "MAX_RUNTIME", run.activeRuntimeMs(), timeLimit, "Active computation time budget reached", "");
    if (scenario.team() == null && scenario.agents().stream().allMatch(a -> hasEnded(id, a.id()) || decisionCount(id, a.id()) >= a.maxDecisions())) {
      var exhausted = scenario.agents().stream().filter(a -> !hasEnded(id, a.id())).findFirst().orElseThrow();
      return finish(id, "MAX_AGENT_DECISIONS", decisionCount(id, exhausted.id()), exhausted.maxDecisions(), "All remaining agents exhausted their decision budgets", exhausted.id());
    }
    int total = db.queryForObject("SELECT COUNT(*) FROM simulation_event WHERE run_id=? AND event_type='AGENT_STARTED'", Integer.class, id);
    if (total >= Math.min(50, decisionBudget))
      return finish(id, "MAX_DECISIONS", total, Math.min(50, decisionBudget), "Global decision budget reached", "");
    if (scenario.team() == null && (total + 1) * 6L > requestBudget)
      return finish(id, "MODEL_REQUEST_BUDGET", (total + 1) * 6L, requestBudget, "Insufficient model request budget for the next decision (reserved estimate)", "");
    if (enforceOutputTokenBudget && scenario.team() == null && (total + 1) * 6144L > tokenBudget)
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

  /** Lists durable tasks in creation order, retaining parent/child relationships. @author czhcc-trsdsj\codexsandboxoffline */
  public List<TaskRecord> tasks(String id) {
    get(id);
    return db.query("SELECT * FROM simulation_task WHERE run_id=? ORDER BY created_sequence", (r,n) -> new TaskRecord(id, r.getString("task_id"), r.getString("parent_task_id"), r.getString("agent_id"), r.getInt("round"), r.getString("status"), r.getLong("input_version"), r.getLong("output_version"), Json.read(r.getString("request_json"), TaskRequest.class), Json.read(r.getString("result_json"), Map.class), r.getLong("created_sequence"), r.getObject("completed_sequence") == null ? null : r.getLong("completed_sequence")), id);
  }

  /** Returns the independent business outcome; ongoing and legacy runs remain unevaluated. @author czhcc-trsdsj\codexsandboxoffline */
  public BusinessOutcome outcome(String id) {
    get(id);
    var values = db.queryForList("SELECT outcome_json FROM simulation_usage WHERE run_id=?", String.class, id);
    return values.isEmpty() || values.getFirst() == null ? new BusinessOutcome("NOT_EVALUATED", get(id).step(), state(id).version(), List.of()) : Json.read(values.getFirst(), BusinessOutcome.class);
  }

  /** Returns actual shared request and conservative output-token reservations. @author czhcc-trsdsj\codexsandboxoffline */
  public Map<String,Object> usage(String id) {
    get(id); var rows = db.queryForList("SELECT requests,reserved_tokens FROM simulation_usage WHERE run_id=?", id);
    return rows.isEmpty() ? Map.of("requests", 0, "reserved_tokens", 0) : rows.getFirst();
  }

  private State initialState(String id) { var world = frozenWorld(id); return new State(id, 0, 0, world.entities(), world.relations()); }
  private long pendingEvents(String id) { return db.queryForObject("SELECT COUNT(*) FROM simulation_scheduled_event WHERE run_id=? AND delivered=0", Long.class, id); }
  private long unhandledInputs(String id, Scenario scene) {
    var types = scene.team().inputActions().keySet();
    var handled = TaskEvidence.handledInputs(events(id, 0));
    return events(id, 0).stream().filter(e -> e.eventType().equals("SIMULATION_EVENT_CREATED") && types.contains(e.payload().get("eventType")) && !handled.contains(String.valueOf(e.payload().get("scheduledEventId")))).count();
  }
  private boolean evaluateHorizon(String id, Scenario scene, boolean reviewedEarly) {
    var result = BusinessEvaluation.evaluate(scene, initialState(id), state(id), get(id).step(), pendingEvents(id), unhandledInputs(id, scene));
    String reason = switch (result.result()) {
      case "ERROR" -> "OBJECTIVE_EVALUATION_FAILED";
      case "SUCCEEDED" -> "OBJECTIVE_MET";
      case "NO_CONDITIONS" -> reviewedEarly ? "OBJECTIVE_REVIEW_COMPLETED" : "OBJECTIVE_NOT_VERIFIABLE";
      default -> "OBJECTIVE_NOT_MET";
    };
    tx.executeWithoutResult(t -> {
      db.update("INSERT INTO simulation_usage(run_id,outcome_json) VALUES (?,?) ON CONFLICT(run_id) DO UPDATE SET outcome_json=excluded.outcome_json", id, Json.write(result));
      db.update("UPDATE simulation_run SET status=?,termination_reason=? WHERE id=?", result.result().equals("ERROR") ? "FAILED" : "COMPLETED", reason, id);
      append(id, "SCENARIO_EVALUATED", state(id).simulationTime(), null, Map.of("outcome", result));
      append(id, result.result().equals("ERROR") ? "RUN_FAILED" : "RUN_COMPLETED", state(id).simulationTime(), null, Map.of("reasonCode", reason,
          "reason", reviewedEarly ? "Master finished the text objective with recorded evidence" : "Configured evaluation horizon reached", "activeRuntimeMs", get(id).activeRuntimeMs()));
    }); return true;
  }

  private void reviewAtHorizon(String id, Scenario scene, Lease lease) {
    if (scene.objective() == null || lease.cancelled) return;
    var run = get(id);
    if (run.step() != scene.maxSteps() && !"OBJECTIVE_REVIEW_COMPLETED".equals(run.terminationReason())) return;
    var outcome = outcome(id);
    if ("NOT_EVALUATED".equals(outcome.result())) return;
    var root = scene.agents().stream().filter(a -> a.id().equals(scene.team().rootAgentId())).findFirst().orElseThrow();
    String mode = "UNKNOWN";
    Map<String, Object> payload = new LinkedHashMap<>();
    try {
      mode = models.get(root.modelId()).baseUrl().startsWith("mock:") ? "MOCK" : "LLM";
      var review = hierarchy.conclude(new RuntimeHost(id, lease, true), root, outcome, "MOCK".equals(mode));
      payload.put("status", "AVAILABLE");
      payload.put("review", review);
    } catch (RuntimeException ex) {
      payload.put("status", "UNAVAILABLE");
      payload.put("reason", "MASTER_REVIEW_UNAVAILABLE");
    }
    payload.put("mode", mode);
    payload.put("round", outcome.evaluatedRound());
    payload.put("version", outcome.version());
    synchronized (this) { append(id, "OBJECTIVE_REVIEWED", state(id).simulationTime(), root.id(), payload); }
  }

  /** One invocation's short-lock gateway; no network call holds the runtime monitor or a transaction. @author czhcc-trsdsj\codexsandboxoffline */
  private final class RuntimeHost implements HierarchyHost {
    private final String id;
    private final Lease lease;
    private final boolean reviewOnly;
    private final Map<String,Map<String,Object>> receipts = new HashMap<>();
    private RuntimeHost(String id, Lease lease) { this(id, lease, false); }
    private RuntimeHost(String id, Lease lease, boolean reviewOnly) { this.id = id; this.lease = lease; this.reviewOnly = reviewOnly; }
    /** Frozen configuration. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public Scenario scenario() { return RunService.this.scenario(id); }
    /** Latest committed state. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public State state() { return RunService.this.state(id); }
    /** Initial template snapshot. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public State initial() { return initialState(id); }
    /** Current planning round. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public int round() { return get(id).step(); }
    /** Fresh object-bound capabilities. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public List<ActionCapability> capabilities(AgentDefinition agent) { synchronized (RunService.this) { check(); var world = frozenWorld(id); return domains.get(world.domainCode()).capabilities(world, agent, state()); } }
    /** Uses the same scope policy as observations and capabilities. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public Set<String> visibleIds(AgentDefinition agent) { var world = frozenWorld(id); return domains.get(world.domainCode()).observationScope(agent, state()); }
    /** Durable receipts and delivered inputs. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public List<Event> history() { return events(id, 0); }
    /** Enforces cancellation and shared run budgets before every external or stateful operation. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public void check() { synchronized (RunService.this) {
      if (lease.cancelled || invocations.get(id) != lease) throw new CancellationException("Invocation is no longer active");
      if (reviewOnly) {
        if (get(id).activeRuntimeMs() >= Math.max(1, timeBudget) * 1000L || eventCount(id) >= scenario().maxEvents() - 1)
          throw new CancellationException("Review budget exhausted");
        return;
      }
      if (!List.of("PAUSED", "RUNNING").contains(get(id).status())) throw new CancellationException("Invocation is no longer active");
      if (checkTermination(id, scenario(), false)) throw new CancellationException("Shared run budget exhausted");
    } }
    /** Propagates pause/stop to the SDK subscription. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public reactor.core.publisher.Mono<Void> cancelled() { return lease.cancellation.asMono(); }
    /** Reserves each model request across master and all workers. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public void beforeModel(AgentDefinition agent, String taskId, int tokens) { synchronized (RunService.this) {
      check(); db.update("INSERT OR IGNORE INTO simulation_usage(run_id) VALUES (?)", id);
      var usage = usage(id); long requests = ((Number) usage.get("requests")).longValue(), reserved = ((Number) usage.get("reserved_tokens")).longValue();
      if (requests + 1 > requestBudget) { if (!reviewOnly) finish(id, "MODEL_REQUEST_BUDGET", requests, requestBudget, "Shared model request budget exhausted", agent.id()); throw new CancellationException("Request budget exhausted"); }
      if (enforceOutputTokenBudget && reserved + tokens > tokenBudget) {
        if (!reviewOnly) finish(id, "TOKEN_BUDGET", reserved, tokenBudget, "Shared output-token reservation budget exhausted", agent.id());
        throw new CancellationException("Token budget exhausted");
      }
      db.update("UPDATE simulation_usage SET requests=requests+1,reserved_tokens=reserved_tokens+? WHERE run_id=?", tokens, id);
      record("MODEL_REQUESTED", agent.id(), Map.of("taskId", taskId, "modelMode", models.get(agent.modelId()).baseUrl().startsWith("mock:") ? "SIMULATED" : "LLM", "modelId", agent.modelId(), "requestNumber", requests + 1, "reservedOutputTokens", tokens));
    } }
    /** Creates an authorized task once; child retries reuse its durable identity. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public TaskRecord startTask(String taskId, String parentId, AgentDefinition agent, TaskRequest request) { synchronized (RunService.this) {
      check(); var existing = tasks(id).stream().filter(t -> t.taskId().equals(taskId)).findFirst(); if (existing.isPresent()) return existing.get();
      if (decisionCount(id, agent.id()) >= agent.maxDecisions()) throw new IllegalStateException("Agent task budget exhausted");
      if (parentId != null && tasks(id).stream().noneMatch(t -> t.taskId().equals(parentId) && t.parentTaskId() == null && t.status().equals("RUNNING"))) throw new IllegalArgumentException("Active parent task required");
      if (request.inputEventId() != null && history().stream().noneMatch(e -> e.eventType().equals("SIMULATION_EVENT_CREATED") && request.inputEventId().equals(e.payload().get("scheduledEventId")) && agent.visibleEntityIds().contains(e.payload().get("targetId")))) throw new IllegalArgumentException("Task input event is outside the authorized scope");
      tx.executeWithoutResult(t -> {
        append(id, "AGENT_STARTED", state().simulationTime(), agent.id(), Map.of("taskId", taskId, "role", agent.runtime().role(), "modelMode", models.get(agent.modelId()).baseUrl().startsWith("mock:") ? "SIMULATED" : "LLM"));
        append(id, parentId == null ? "MASTER_TASK_STARTED" : "SUBTASK_DISPATCHED", state().simulationTime(), agent.id(), Map.of("taskId", taskId, "parentTaskId", parentId == null ? "" : parentId, "request", request, "inputVersion", state().version()));
        db.update("INSERT INTO simulation_task VALUES (?,?,?,?,?,'RUNNING',?,?,?,'{}',?,NULL)", id, taskId, parentId, agent.id(), round(), state().version(), state().version(), Json.write(request), eventCount(id));
      }); return tasks(id).stream().filter(t -> t.taskId().equals(taskId)).findFirst().orElseThrow();
    } }
    /** Persists terminal task results even during cancellation, without changing graph state. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public void completeTask(String taskId, String status, Map<String,Object> result) { synchronized (RunService.this) {
      var task = tasks(id).stream().filter(t -> t.taskId().equals(taskId)).findFirst().orElse(null);
      if (task == null || !task.status().equals("RUNNING")) return;
      var actual = new LinkedHashMap<>(result);
      if (receipts.containsKey(taskId)) {
        var receipt = receipts.get(taskId);
        actual.put("receipts", List.of(receipt));
        actual.put("inputEventId", receipt.getOrDefault("inputEventId", ""));
      }
      tx.executeWithoutResult(t -> {
        append(id, task.parentTaskId() == null ? "MASTER_TASK_COMPLETED" : "SUBTASK_COMPLETED", state().simulationTime(), task.agentId(), Map.of("taskId", taskId, "parentTaskId", task.parentTaskId() == null ? "" : task.parentTaskId(), "status", status, "result", actual));
        db.update("UPDATE simulation_task SET status=?,output_version=?,result_json=?,completed_sequence=? WHERE run_id=? AND task_id=?", status, state().version(), Json.write(actual), eventCount(id), id, taskId);
      });
    } }
    /** Serializes engine validation, optimistic concurrency and idempotent task receipts. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public Map<String,Object> execute(TaskRecord task, AgentDefinition agent, Decision decision, long expectedVersion, String inputEventId) { synchronized (RunService.this) {
      check(); if (receipts.containsKey(task.taskId())) return receipts.get(task.taskId());
      var current = state();
      if (!agent.runtime().role().equals("ACTION") || !task.agentId().equals(agent.id()) || !task.runId().equals(id) || tasks(id).stream().noneMatch(t -> t.taskId().equals(task.taskId()) && t.status().equals("RUNNING")) || "END".equals(decision.actionType())) throw new IllegalArgumentException("Task lacks mutation authority");
      if (decision.reason() == null || decision.reason().length() > 1000 || Json.write(decision).length() > 6000) throw new IllegalArgumentException("Invalid Action request");
      var world = frozenWorld(id); var plugin = domains.get(world.domainCode());
      var team = scenario().team();
      Outcome result;
      if (expectedVersion != current.version()) result = new Outcome(false, "State changed; query current capabilities and create a new task", current, false, "VERSION_CONFLICT", List.of());
      else if (inputEventId == null && TaskEvidence.requiresInput(team, decision.actionType())) result = new Outcome(false, "Input-bound Action requires an inputEventId", current, false, "INPUT_REQUIRED", List.of());
      else if ((task.request().inputEventId() != null && !task.request().inputEventId().equals(inputEventId))
          || (inputEventId != null && !inputMatches(inputEventId, decision, team))) result = new Outcome(false, "Action does not match the bound input request", current, false, "INPUT_ACTION_MISMATCH", List.of());
      else if (inputEventId != null && TaskEvidence.handledInputs(history()).contains(inputEventId)) result = new Outcome(false, "Input already has an accepted Action receipt", current, false, "INPUT_ALREADY_HANDLED", List.of());
      else if (receipts.size() >= team.maxActionsPerRound()) result = new Outcome(false, "Round Action attempt limit reached", current, false, "ROUND_ACTION_LIMIT", List.of());
      else result = plugin.execute(world, agent, plugin.normalizeDecision(decision, current), current);
      var details = new LinkedHashMap<>(Map.<String,Object>of("taskId", task.taskId(), "accepted", result.accepted(), "code", result.code(), "message", result.message(), "actionType", decision.actionType(), "targetId", decision.targetId(), "parameters", decision.parameters() == null ? Map.of() : decision.parameters(), "inputVersion", current.version(), "outputVersion", result.state().version(), "affectedRelationIds", result.affectedRelationIds()));
      if (inputEventId != null) details.put("inputEventId", inputEventId);
      var receipt = Map.copyOf(details);
      Outcome outcome = result;
      tx.executeWithoutResult(t -> {
        commit(id, agent.id(), decision, current, outcome, "AGENT", Map.of("taskId", task.taskId(), "inputEventId", inputEventId == null ? "" : inputEventId));
        append(id, "ACTION_TASK_RECEIPT", current.simulationTime(), agent.id(), receipt);
        if (outcome.accepted() && inputEventId != null) append(id, "ACTION_INPUT_HANDLED", current.simulationTime(), agent.id(), Map.of("taskId", task.taskId(), "inputEventId", inputEventId, "outputVersion", outcome.state().version()));
      }); receipts.put(task.taskId(), receipt); return receipt;
    } }
    private boolean inputMatches(String inputId, Decision decision, TeamConfig team) {
      return history().stream().anyMatch(e -> e.eventType().equals("SIMULATION_EVENT_CREATED") && inputId.equals(e.payload().get("scheduledEventId"))
          && TaskEvidence.matchesInputType(team, decision.actionType(), e)
          && Objects.equals(e.payload().get("targetId"), decision.targetId()) && Objects.equals(e.payload().get("parameters"), decision.parameters()));
    }
    /** Writes public evidence with stable ordering and returns its sequence. @author czhcc-trsdsj\codexsandboxoffline */
    @Override public long record(String type, String agentId, Map<String,Object> payload) { synchronized (RunService.this) {
      check();
      if (Json.write(payload).length() > 40000) throw new IllegalArgumentException("Event payload too large");
      append(id, type, state().simulationTime(), agentId, payload);
      return db.queryForObject("SELECT MAX(sequence_no) FROM simulation_event WHERE run_id=?", Long.class, id);
    } }
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
