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
      int version,
      Ontology ontology) {
    /** Resolves absent or legacy contracts to object-owned actions without changing graph state. @author czhcc-trsdsj\codexsandboxoffline */
    public World {
      if (ontology == null && "information".equals(domainCode)) ontology = InformationOntology.definition();
      else if (ontology != null && "information".equals(domainCode)) ontology = InformationOntology.upgrade(ontology);
    }

    /** Preserves the original world input protocol. @author czhcc-trsdsj\codexsandboxoffline */
    public World(String id, String name, String domainCode, List<String> entityTypes,
        List<String> relationTypes, List<Entity> entities, List<Relation> relations, int version) {
      this(id, name, domainCode, entityTypes, relationTypes, entities, relations, version, null);
    }
  }

  /** Versioned ontology stored with the world and frozen run. @author czhcc-trsdsj\codexsandboxoffline */
  public record Ontology(int version, List<TypeDefinition> types) {}

  /** Actions owned by entity types; targetKind remains for serialized legacy compatibility. @author czhcc-trsdsj\codexsandboxoffline */
  public record TypeDefinition(String targetKind, String type, List<ActionDefinition> actions) {}

  /** Declarative engine contract; no arbitrary code or state patches. @author czhcc-trsdsj\codexsandboxoffline */
  public record ActionDefinition(String actionType, String nameZh, String nameEn, String description,
      Map<String, Object> parametersSchema, List<String> preconditions, String effect) {}

  /** Object capability and allowed related-object IDs evaluated against current state. @author czhcc-trsdsj\codexsandboxoffline */
  public record ActionCapability(String targetKind, String targetId, String targetName, String type,
      ActionDefinition action, boolean available, String unavailableReason, List<String> sourceIds,
      Map<String, List<String>> parameterOptions) {}

  /** Object actions visible to one agent. @author czhcc-trsdsj\codexsandboxoffline */
  public record AgentCapabilities(String agentId, List<ActionCapability> capabilities, boolean ended) {}

  /** Versioned instance capabilities for the run's intervention UI. @author czhcc-trsdsj\codexsandboxoffline */
  public record ActionCatalog(String domainCode, long version, List<AgentCapabilities> agents) {}

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
      String actionType, String targetId, Map<String, Object> parameters, String reason, String targetKind) {
    /** Accepts persisted legacy decisions without a target kind. @author czhcc-trsdsj\codexsandboxoffline */
    public Decision(String actionType, String targetId, Map<String, Object> parameters, String reason) {
      this(actionType, targetId, parameters, reason, null);
    }
  }

  /** Agent-visible state and recent public action feedback. @author agent */
  public record Observation(
      String runId,
      long simulationTime,
      long version,
      List<Entity> entities,
      List<Relation> relations,
      List<String> feedback,
      List<Map<String, Object>> visibleEvents,
      List<ActionCapability> availableActions,
      String domainCode) {
    /** Accepts observations constructed before domain-specific decision protocols. @author czhcc-trsdsj\codexsandboxoffline */
    public Observation(String runId, long simulationTime, long version, List<Entity> entities,
        List<Relation> relations, List<String> feedback, List<Map<String, Object>> visibleEvents,
        List<ActionCapability> availableActions) {
      this(runId, simulationTime, version, entities, relations, feedback, visibleEvents, availableActions, "");
    }
    /** Accepts legacy observations and non-object-action domains. @author czhcc-trsdsj\codexsandboxoffline */
    public Observation(String runId, long simulationTime, long version, List<Entity> entities,
        List<Relation> relations, List<String> feedback, List<Map<String, Object>> visibleEvents) {
      this(runId, simulationTime, version, entities, relations, feedback, visibleEvents, List.of());
    }
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
  public record Outcome(boolean accepted, String message, State state, boolean changed, String code, List<String> affectedRelationIds) {
    /** Preserves domain results without relation changes. @author czhcc-trsdsj\codexsandboxoffline */
    public Outcome(boolean accepted, String message, State state, boolean changed, String code) {
      this(accepted, message, state, changed, code, List.of());
    }
    /** Preserves other domain plugins' original result contract. @author czhcc-trsdsj\codexsandboxoffline */
    public Outcome(boolean accepted, String message, State state, boolean changed) {
      this(accepted, message, state, changed, accepted ? "ACTION_ACCEPTED" : "ACTION_REJECTED");
    }
  }
}
