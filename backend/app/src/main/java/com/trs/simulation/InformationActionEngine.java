package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;

/** Executes entity-owned actions and maintains relationships internally.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
public final class InformationActionEngine {
  /** Projects object capabilities through permissions, visibility and current state. @author czhcc-trsdsj\codexsandboxoffline */
  public List<ActionCapability> capabilities(World world, AgentDefinition agent, State state) {
    var result = new ArrayList<ActionCapability>();
    for (var entity : state.entities()) {
      if (!agent.visibleEntityIds().contains(entity.id())) continue;
      for (var action : definitions(world, entity)) {
        if (!agent.allowedActions().contains(action.actionType())) continue;
        var options = options(action, entity, agent, state);
        String blocked = precondition(action, entity, options);
        result.add(new ActionCapability("ENTITY", entity.id(), entity.name(), entity.type(), action,
            blocked.isEmpty(), blocked, options.getOrDefault("sourceId", List.of()), options));
      }
    }
    return List.copyOf(result);
  }

  /** Normalizes only the original untyped relation-call protocol at the boundary. @author czhcc-trsdsj\codexsandboxoffline */
  public Decision normalizeLegacyDecision(Decision decision, State state) {
    if (decision == null || decision.targetKind() != null || decision.parameters() == null
        || !decision.parameters().isEmpty() || state.entities().stream().anyMatch(e -> e.id().equals(decision.targetId()))) return decision;
    String relationType = "DISABLE_SYNC".equals(decision.actionType()) ? "SYNCS_TO"
        : "REVOKE_SHARING".equals(decision.actionType()) ? "SHARED_WITH" : "";
    var relation = state.relations().stream().filter(r -> r.id().equals(decision.targetId()) && r.relationType().equals(relationType)).findFirst();
    if (relation.isEmpty()) return decision;
    var edge = relation.get();
    String parameter = relationType.equals("SYNCS_TO") ? "destinationId" : "recipientId";
    return new Decision(decision.actionType(), edge.sourceId(), Map.of(parameter, edge.targetId()), decision.reason(), "ENTITY");
  }

  /** Validates an object decision against its frozen type contract before applying effects. @author czhcc-trsdsj\codexsandboxoffline */
  public Outcome execute(World world, AgentDefinition agent, Decision input, State state) {
    var decision = normalizeLegacyDecision(input, state);
    if (decision == null || decision.actionType() == null || !agent.allowedActions().contains(decision.actionType()))
      return reject(state, "ACTION_FORBIDDEN", "Action is not permitted for this agent");
    if (decision.reason() == null || decision.reason().length() > 1000)
      return reject(state, "ACTION_PARAMETERS_INVALID", "reason is required and must be at most 1000 characters");
    if (decision.actionType().equals("END")) {
      if ((decision.targetKind() != null && !decision.targetKind().equals("AGENT"))
          || (decision.targetId() != null && !decision.targetId().isEmpty())
          || decision.parameters() == null || !decision.parameters().isEmpty())
        return reject(state, "ACTION_TARGET_INVALID", "END is an agent lifecycle decision; use targetKind=AGENT, targetId='', parameters={}");
      return new Outcome(true, "Agent completed its task", state, false);
    }
    if (decision.targetKind() != null && !decision.targetKind().equals("ENTITY"))
      return reject(state, "ACTION_TARGET_INVALID", "Business actions require an ENTITY target; specify related objects in parameters");
    var target = state.entities().stream().filter(e -> e.id().equals(decision.targetId())).findFirst();
    if (target.isEmpty()) return reject(state, "ACTION_TARGET_INVALID", "Select an existing owner object as targetId");
    var entity = target.get();
    if (!agent.visibleEntityIds().contains(entity.id())) return reject(state, "ACTION_TARGET_HIDDEN", "Target is outside the agent's visible scope");
    var definition = definitions(world, entity).stream().filter(a -> a.actionType().equals(decision.actionType())).findFirst();
    if (definition.isEmpty()) return reject(state, "ACTION_NOT_BOUND", "Action is not defined on object type " + entity.type());
    var action = definition.get();
    String error = parametersError(action, decision.parameters());
    if (!error.isEmpty()) return reject(state, "ACTION_PARAMETERS_INVALID", error);
    for (var related : decision.parameters().values()) {
      if (!agent.visibleEntityIds().contains(related)) return reject(state, "ACTION_TARGET_HIDDEN", "Related object is outside the agent's visible scope");
      if (state.entities().stream().noneMatch(e -> e.id().equals(related))) return reject(state, "ACTION_TARGET_INVALID", "Related object does not exist");
    }
    var options = options(action, entity, agent, state);
    String blocked = precondition(action, entity, options);
    if (!blocked.isEmpty()) return reject(state, "ACTION_PRECONDITION_FAILED", blocked);
    for (var parameter : decision.parameters().entrySet())
      if (!options.getOrDefault(parameter.getKey(), List.of()).contains(parameter.getValue()))
        return reject(state, "ACTION_PRECONDITION_FAILED", "Related object must be one of the current parameterOptions for " + parameter.getKey());
    return apply(action, entity, decision, state);
  }

  private List<ActionDefinition> definitions(World world, Entity entity) {
    return world.ontology().types().stream().filter(t -> t.targetKind().equals("ENTITY") && t.type().equals(entity.type()))
        .flatMap(t -> t.actions().stream()).toList();
  }

  private Map<String, List<String>> options(ActionDefinition action, Entity entity, AgentDefinition agent, State state) {
    if (action.effect().equals("EXPOSE_ENTITY")) {
      var sources = state.entities().stream().filter(e -> agent.visibleEntityIds().contains(e.id())
          && (e.type().equals("DOCUMENT") || Boolean.TRUE.equals(e.properties().get("exposed")))
          && state.relations().stream().anyMatch(r -> r.enabled() && r.sourceId().equals(e.id()) && r.targetId().equals(entity.id())))
          .map(Entity::id).sorted().toList();
      return Map.of("sourceId", sources);
    }
    String type = relationType(action);
    if (type.isEmpty()) return Map.of();
    String parameter = type.equals("SYNCS_TO") ? "destinationId" : "recipientId";
    return Map.of(parameter, state.relations().stream().filter(r -> r.enabled() && r.sourceId().equals(entity.id())
        && r.relationType().equals(type) && agent.visibleEntityIds().contains(r.targetId())
        && state.entities().stream().anyMatch(e -> e.id().equals(r.targetId())))
        .map(Relation::targetId).distinct().sorted().toList());
  }

  private String precondition(ActionDefinition action, Entity entity, Map<String, List<String>> options) {
    for (var condition : action.preconditions()) {
      if (condition.equals("NOT_REVIEWED") && Boolean.TRUE.equals(entity.properties().get("reviewed"))) return "Object is already reviewed";
      if (condition.equals("NOT_EXPOSED") && Boolean.TRUE.equals(entity.properties().get("exposed"))) return "Object is already simulated exposed";
      if (condition.equals("ENABLED_DIRECT_SOURCE") && options.getOrDefault("sourceId", List.of()).isEmpty()) return "No visible eligible source with an enabled direct propagation path";
      if (condition.equals("ENABLED_SHARING_RECIPIENT") && options.getOrDefault("recipientId", List.of()).isEmpty()) return "No visible recipient with enabled outgoing sharing";
      if (condition.equals("ENABLED_SYNC_DESTINATION") && options.getOrDefault("destinationId", List.of()).isEmpty()) return "No visible destination with enabled outgoing synchronization";
    }
    return "";
  }

  private String parametersError(ActionDefinition action, Map<String, Object> parameters) {
    var properties = (Map<?, ?>) action.parametersSchema().get("properties");
    if (parameters == null) return action.actionType() + ": parameters must be an object";
    if (properties.isEmpty()) return parameters.isEmpty() ? "" : action.actionType() + ": parameters must be {}; put analysis in reason, not parameters";
    if (!parameters.keySet().equals(properties.keySet()) || parameters.values().stream().anyMatch(v -> !(v instanceof String text) || text.isBlank()))
      return action.actionType() + ": parameters must contain only non-empty string fields " + properties.keySet();
    return "";
  }

  private String relationType(ActionDefinition action) {
    return action.effect().equals("DISABLE_SYNC_TO") ? "SYNCS_TO" : action.effect().equals("REVOKE_SHARING_WITH") ? "SHARED_WITH" : "";
  }

  private Outcome apply(ActionDefinition action, Entity target, Decision decision, State state) {
    if (action.effect().equals("READ_ONLY")) return new Outcome(true, "Read-only analysis recorded; connectivity is not propagation evidence", state, false);
    var entities = state.entities();
    var relations = state.relations();
    List<String> affected = List.of();
    String message;
    String type = relationType(action);
    if (!type.isEmpty()) {
      String parameter = type.equals("SYNCS_TO") ? "destinationId" : "recipientId";
      var relatedId = decision.parameters().get(parameter);
      affected = relations.stream().filter(r -> r.enabled() && r.relationType().equals(type)
          && r.sourceId().equals(target.id()) && r.targetId().equals(relatedId)).map(Relation::id).sorted().toList();
      var affectedIds = affected;
      relations = relations.stream().map(r -> affectedIds.contains(r.id())
          ? new Relation(r.id(), r.sourceId(), r.targetId(), r.relationType(), r.properties(), false, "SIMULATED", "world engine object action") : r).toList();
      message = "Object action disabled matching outgoing " + type + " relations";
    } else {
      var properties = new HashMap<>(target.properties());
      if (action.effect().equals("SET_REVIEWED")) {
        properties.put("reviewed", true);
        properties.put("reviewEvidence", "SIMULATED");
        message = "Simulated review recorded";
      } else if (action.effect().equals("EXPOSE_ENTITY")) {
        properties.put("exposed", true);
        properties.put("exposureEvidence", "SIMULATED");
        properties.put("exposureSource", decision.parameters().get("sourceId"));
        message = "Hypothetical propagation committed as SIMULATED, not FACT";
      } else return reject(state, "ACTION_NOT_BOUND", "Unsupported action effect");
      entities = entities.stream().map(e -> e.id().equals(target.id())
          ? new Entity(e.id(), e.type(), e.name(), Collections.unmodifiableMap(properties)) : e).toList();
    }
    return new Outcome(true, message, new State(state.runId(), state.simulationTime(), state.version() + 1, entities, relations), true, "ACTION_ACCEPTED", affected);
  }

  private Outcome reject(State state, String code, String message) {
    return new Outcome(false, message, state, false, code);
  }
}
