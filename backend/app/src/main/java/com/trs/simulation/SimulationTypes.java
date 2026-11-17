package com.trs.simulation;

import java.util.*;

/** Immutable boundary types; agents receive only filtered observations. @author agent */
public final class SimulationTypes {
  private SimulationTypes() {}

  /** Dynamic entity; types are not restricted to Java enums. @author agent */
  public record Entity(String id, String type, String name, Map<String, Object> properties) {}

  /** Directed property edge with provenance and evidence class. @author agent */
  public record Relation(
      String id,
      String sourceId,
      String targetId,
      String relationType,
      Map<String, Object> properties,
      boolean enabled,
      String evidence,
      String source) {}

  /** Reusable, versioned world template. @author agent */
  public record World(
      String id,
      String name,
      String domainCode,
      List<String> entityTypes,
      List<String> relationTypes,
      List<Entity> entities,
      List<Relation> relations,
      int version) {}

  /** An agent's goal, permissions, visibility and budget. @author agent */
  public record AgentDefinition(
      String id,
      String name,
      String goal,
      String modelId,
      List<String> allowedActions,
      List<String> visibleEntityIds,
      int maxDecisions) {}

  /** Scenario configuration; independent from mutable execution state. @author agent */
  public record Scenario(
      String id,
      String name,
      String worldId,
      List<AgentDefinition> agents,
      int maxSteps,
      int maxEvents) {}

  /** An action proposal is untrusted until the world engine validates it. @author agent */
  public record Decision(
      String actionType, String targetId, Map<String, Object> parameters, String reason) {}

  /** Agent-visible state and recent public action feedback. @author agent */
  public record Observation(
      String runId,
      long simulationTime,
      long version,
      List<Entity> entities,
      List<Relation> relations,
      List<String> feedback,
      List<Map<String, Object>> visibleEvents) {
    /**
     * Creates an observation without injected events for connection/protocol tests. @author agent
     */
    public Observation(
        String runId,
        long simulationTime,
        long version,
        List<Entity> entities,
        List<Relation> relations,
        List<String> feedback) {
      this(runId, simulationTime, version, entities, relations, feedback, List.of());
    }
  }

  /** Validated, delayed user event; effects remain subject to the World Engine. @author agent */
  public record ScheduledInput(
      String eventType, String targetId, long simulationTime, Map<String, Object> parameters) {}

  /** Isolated runtime state. @author agent */
  public record State(
      String runId,
      long simulationTime,
      long version,
      List<Entity> entities,
      List<Relation> relations) {}

  /** Persisted lifecycle metadata. @author agent */
  public record Run(
      String id, String scenarioId, String status, int step, int maxSteps, long version) {}

  /** Append-only event with a per-run sequence number. @author agent */
  public record Event(
      String runId,
      long sequence,
      String eventType,
      long simulationTime,
      String agentId,
      Map<String, Object> payload) {}

  /** Validated execution result, never an unvalidated LLM state patch. @author agent */
  public record Outcome(boolean accepted, String message, State state, boolean changed) {}
}
