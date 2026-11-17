package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Validates information-domain actions; inferred reachability is never a fact. @author agent */
@Component
public class InformationEnvironment implements DomainEnvironment {
  public static final Set<String> ACTIONS =
      Set.of(
          "SIMULATE_PROPAGATION",
          "ANALYZE_RELATIONS",
          "DISABLE_SYNC",
          "REVOKE_SHARING",
          "MARK_REVIEWED",
          "END");

  /** Identifies the plugin. @author agent */
  public String domainCode() {
    return "information";
  }

  /** Enforces schema, visibility, permissions and action preconditions. @author agent */
  public Outcome execute(AgentDefinition a, Decision d, State s) {
    if (d == null
        || d.actionType() == null
        || !ACTIONS.contains(d.actionType())
        || !a.allowedActions().contains(d.actionType()))
      return reject(s, "Unknown or forbidden action");
    if (d.parameters() == null
        || (!d.actionType().equals("SIMULATE_PROPAGATION") && !d.parameters().isEmpty())
        || d.reason() == null
        || d.reason().length() > 1000) return reject(s, "Invalid action schema");
    if (d.actionType().equals("SIMULATE_PROPAGATION")) {
      if (!d.parameters().keySet().equals(Set.of("sourceId"))
          || !(d.parameters().get("sourceId") instanceof String source))
        return reject(s, "sourceId parameter required");
      if (!a.visibleEntityIds()
          .containsAll(List.of(source, d.targetId() == null ? "" : d.targetId())))
        return reject(s, "Propagation outside visible scope");
      var origin = s.entities().stream().filter(e -> e.id().equals(source)).findFirst();
      var target = s.entities().stream().filter(e -> e.id().equals(d.targetId())).findFirst();
      if (origin.isEmpty()
          || target.isEmpty()
          || Boolean.TRUE.equals(target.get().properties().get("exposed")))
        return reject(s, "Invalid or already exposed target");
      if (!origin.get().type().equals("DOCUMENT")
          && !Boolean.TRUE.equals(origin.get().properties().get("exposed")))
        return reject(s, "Source is not a hypothetical document or simulated exposed object");
      if (s.relations().stream()
          .noneMatch(
              e -> e.enabled() && e.sourceId().equals(source) && e.targetId().equals(d.targetId())))
        return reject(s, "No enabled direct propagation path");
      var entities =
          s.entities().stream()
              .map(
                  e -> {
                    if (!e.id().equals(d.targetId())) return e;
                    var properties = new HashMap<>(e.properties());
                    properties.put("exposed", true);
                    properties.put("exposureEvidence", "SIMULATED");
                    properties.put("exposureSource", source);
                    return new Entity(
                        e.id(), e.type(), e.name(), Collections.unmodifiableMap(properties));
                  })
              .toList();
      return changed(
          s, entities, s.relations(), "Hypothetical propagation committed as SIMULATED, not FACT");
    }
    if (d.actionType().equals("END"))
      return new Outcome(true, "Agent completed its task", s, false);
    if (d.actionType().equals("DISABLE_SYNC") || d.actionType().equals("REVOKE_SHARING")) {
      var edge = s.relations().stream().filter(e -> e.id().equals(d.targetId())).findFirst();
      if (edge.isEmpty()) return reject(s, "Relation not found");
      var e = edge.get();
      if (!a.visibleEntityIds().containsAll(List.of(e.sourceId(), e.targetId())))
        return reject(s, "Target outside allowed observation scope");
      String required = d.actionType().equals("DISABLE_SYNC") ? "SYNCS_TO" : "SHARED_WITH";
      if (!e.enabled() || !e.relationType().equals(required))
        return reject(s, "Relation is disabled or has incompatible type");
      var edges =
          s.relations().stream()
              .map(
                  x ->
                      x.id().equals(e.id())
                          ? new Relation(
                              x.id(),
                              x.sourceId(),
                              x.targetId(),
                              x.relationType(),
                              x.properties(),
                              false,
                              "SIMULATED",
                              "world engine intervention")
                          : x)
              .toList();
      return changed(s, s.entities(), edges, "Simulated relation disabled");
    }
    if (!a.visibleEntityIds().contains(d.targetId()))
      return reject(s, "Target outside allowed observation scope");
    var entity = s.entities().stream().filter(e -> e.id().equals(d.targetId())).findFirst();
    if (entity.isEmpty()) return reject(s, "Entity not found");
    if (d.actionType().equals("ANALYZE_RELATIONS"))
      return new Outcome(
          true, "Read-only analysis; connectivity does not establish propagation", s, false);
    if (Boolean.TRUE.equals(entity.get().properties().get("reviewed")))
      return reject(s, "Entity already reviewed");
    var entities =
        s.entities().stream()
            .map(
                e -> {
                  if (!e.id().equals(d.targetId())) return e;
                  var p = new HashMap<>(e.properties());
                  p.put("reviewed", true);
                  p.put("reviewEvidence", "SIMULATED");
                  return new Entity(e.id(), e.type(), e.name(), Collections.unmodifiableMap(p));
                })
            .toList();
    return changed(s, entities, s.relations(), "Simulated review recorded");
  }

  private Outcome changed(State s, List<Entity> entities, List<Relation> edges, String message) {
    return new Outcome(
        true,
        message,
        new State(s.runId(), s.simulationTime(), s.version() + 1, entities, edges),
        true);
  }

  private Outcome reject(State s, String message) {
    return new Outcome(false, message, s, false);
  }

  /** Publishes the exact parameter schemas enforced by the domain arbiter. @author agent */
  public List<Map<String, Object>> actions() {
    return ACTIONS.stream()
        .sorted()
        .map(
            type -> {
              Map<String, Object> schema =
                  type.equals("SIMULATE_PROPAGATION")
                      ? Map.of(
                          "type",
                          "object",
                          "properties",
                          Map.of("sourceId", Map.of("type", "string")),
                          "required",
                          List.of("sourceId"),
                          "additionalProperties",
                          false)
                      : Map.of(
                          "type", "object", "properties", Map.of(), "additionalProperties", false);
              return Map.<String, Object>of(
                  "actionType", type, "parameters", schema, "targetRequired", !type.equals("END"));
            })
        .toList();
  }

  /** Separates confirmed contacts, potential graph reach and simulated exposure. @author agent */
  public Map<String, Object> evaluate(State state) {
    var documents =
        state.entities().stream().filter(e -> e.type().equals("DOCUMENT")).map(Entity::id).toList();
    var contacts =
        state.relations().stream()
            .filter(
                e ->
                    documents.contains(e.sourceId())
                        && e.relationType().equals("ACCESSED_BY")
                        && e.evidence().equals("FACT"))
            .map(Relation::targetId)
            .distinct()
            .sorted()
            .toList();
    var potential = new TreeSet<String>();
    documents.forEach(
        id -> potential.addAll(GraphQueries.reachable(state.entities(), state.relations(), id, 6)));
    return Map.of(
        "confirmedContactIds",
        contacts,
        "potentialReachableIds",
        potential,
        "potentialEvidence",
        "INFERRED",
        "simulatedExposureIds",
        state.entities().stream()
            .filter(e -> Boolean.TRUE.equals(e.properties().get("exposed")))
            .map(Entity::id)
            .toList(),
        "simulatedDisabledRelationIds",
        state.relations().stream()
            .filter(e -> !e.enabled() && e.evidence().equals("SIMULATED"))
            .map(Relation::id)
            .toList(),
        "warning",
        "Reachability and simulated exposure are not confirmed leakage facts");
  }
}
