package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;

/** Versioned information ontology: business actions belong only to entity types.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
public final class InformationOntology {
  private InformationOntology() {}

  /** Returns immutable, serializable type contracts for new and legacy worlds. @author czhcc-trsdsj\codexsandboxoffline */
  public static Ontology definition() {
    var types = new ArrayList<TypeDefinition>();
    for (var type : legacyDefinition().types()) {
      if (!type.targetKind().equals("ENTITY")) continue;
      var actions = new ArrayList<>(type.actions());
      if (type.type().equals("PERSON")) actions.add(action("REVOKE_SHARING", "撤销共享", "Revoke sharing",
          "This person revokes outgoing sharing with recipientId. The engine disables matching enabled SHARED_WITH edges.",
          relatedParameters("recipientId", "Visible recipient object ID"), List.of("ENABLED_SHARING_RECIPIENT"), "REVOKE_SHARING_WITH"));
      if (type.type().equals("SYSTEM")) actions.add(action("DISABLE_SYNC", "禁用同步", "Disable sync",
          "This system disables outgoing synchronization to destinationId. The engine disables matching enabled SYNCS_TO edges.",
          relatedParameters("destinationId", "Visible synchronization destination object ID"), List.of("ENABLED_SYNC_DESTINATION"), "DISABLE_SYNC_TO"));
      types.add(new TypeDefinition("ENTITY", type.type(), List.copyOf(actions)));
    }
    return new Ontology(2, List.copyOf(types));
  }

  /** Returns the fixed version-one contract solely for legacy input validation and conversion. @author czhcc-trsdsj\codexsandboxoffline */
  public static Ontology legacyDefinition() {
    var review = action("MARK_REVIEWED", "标记审查", "Mark reviewed",
        "Record a simulated review. The engine sets reviewed=true; put findings in reason, not parameters.",
        emptyParameters(), List.of("NOT_REVIEWED"), "SET_REVIEWED");
    var analyze = action("ANALYZE_RELATIONS", "分析关系", "Analyze relations",
        "Record a read-only analysis decision about this target. Reachability is not proof of leakage.",
        emptyParameters(), List.of(), "READ_ONLY");
    var propagate = action("SIMULATE_PROPAGATION", "模拟接收传播", "Simulate exposure",
        "Simulate exposure of this target from sourceId over an enabled direct edge. Never asserts real leakage.",
        Map.of("type", "object", "properties", Map.of("sourceId", Map.of("type", "string", "description", "Visible document or simulated exposed source ID")),
            "required", List.of("sourceId"), "additionalProperties", false),
        List.of("NOT_EXPOSED", "ENABLED_DIRECT_SOURCE"), "EXPOSE_ENTITY");
    var disable = action("DISABLE_SYNC", "禁用同步", "Disable sync",
        "Disable this synchronization relation in the simulation only.", emptyParameters(), List.of("ENABLED"), "DISABLE_RELATION");
    var revoke = action("REVOKE_SHARING", "撤销共享", "Revoke sharing",
        "Disable this sharing relation in the simulation only.", emptyParameters(), List.of("ENABLED"), "DISABLE_RELATION");
    var types = new ArrayList<TypeDefinition>();
    for (var type : List.of("DOCUMENT", "PERSON", "SYSTEM", "ORGANIZATION"))
      types.add(new TypeDefinition("ENTITY", type, List.of(review, analyze, propagate)));
    for (var type : List.of("ACCESSED_BY", "STORED_IN", "SHARED_WITH", "SYNCS_TO", "CONTACTS")) {
      var actions = new ArrayList<ActionDefinition>();
      actions.add(analyze);
      if (type.equals("SYNCS_TO")) actions.add(disable);
      if (type.equals("SHARED_WITH")) actions.add(revoke);
      types.add(new TypeDefinition("RELATION", type, List.copyOf(actions)));
    }
    return new Ontology(1, List.copyOf(types));
  }

  /** Rejects unsupported or altered executable contracts; bindings may be narrowed. @author czhcc-trsdsj\codexsandboxoffline */
  public static void validate(Ontology ontology) {
    validateAgainst(ontology, definition(), false);
  }

  /** Converts legacy relation bindings into owner object bindings without changing graph state. @author czhcc-trsdsj\codexsandboxoffline */
  public static Ontology upgrade(Ontology ontology) {
    if (ontology.version() != 1) return ontology;
    validateAgainst(ontology, legacyDefinition(), true);
    var current = definition();
    var bindings = new LinkedHashMap<String, List<ActionDefinition>>();
    for (var type : ontology.types()) {
      if (type.targetKind().equals("ENTITY")) bindings.put(type.type(), new ArrayList<>(type.actions()));
    }
    for (var type : ontology.types()) {
      if (!type.targetKind().equals("RELATION")) continue;
      String owner = type.type().equals("SYNCS_TO") ? "SYSTEM" : type.type().equals("SHARED_WITH") ? "PERSON" : "";
      String actionId = type.type().equals("SYNCS_TO") ? "DISABLE_SYNC" : "REVOKE_SHARING";
      if (!owner.isEmpty() && type.actions().stream().anyMatch(a -> a.actionType().equals(actionId))) {
        var action = current.types().stream().filter(t -> t.type().equals(owner)).flatMap(t -> t.actions().stream())
            .filter(a -> a.actionType().equals(actionId)).findFirst().orElseThrow();
        bindings.computeIfAbsent(owner, key -> new ArrayList<>()).add(action);
      }
    }
    return new Ontology(2, bindings.entrySet().stream().map(e -> new TypeDefinition("ENTITY", e.getKey(), List.copyOf(e.getValue()))).toList());
  }

  private static void validateAgainst(Ontology ontology, Ontology reference, boolean legacy) {
    if (ontology == null || ontology.version() != reference.version() || ontology.types() == null || ontology.types().size() > 100)
      throw new IllegalArgumentException("Unsupported information ontology");
    var seen = new HashSet<String>();
    for (var type : ontology.types()) {
      if (type == null || type.type() == null || type.targetKind() == null || !List.of("ENTITY", "RELATION").contains(type.targetKind())
          || (!legacy && !type.targetKind().equals("ENTITY"))
          || !seen.add(type.targetKind() + ":" + type.type()) || type.actions() == null)
        throw new IllegalArgumentException("Invalid or duplicate ontology type");
      var canonical = reference.types().stream().filter(t -> t.type().equals(type.type())
          && t.targetKind().equals(type.targetKind())).findFirst();
      var actionIds = new HashSet<String>();
      for (var action : type.actions()) {
        if (action == null || !actionIds.add(action.actionType()) || canonical.isEmpty()
            || !canonical.get().actions().contains(action))
          throw new IllegalArgumentException("Unsupported action contract for type " + type.type());
      }
    }
  }

  private static Map<String, Object> relatedParameters(String name, String description) {
    return Map.of("type", "object", "properties", Map.of(name, Map.of("type", "string", "description", description)),
        "required", List.of(name), "additionalProperties", false);
  }

  private static Map<String, Object> emptyParameters() {
    return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
  }

  private static ActionDefinition action(String id, String zh, String en, String description,
      Map<String, Object> schema, List<String> conditions, String effect) {
    return new ActionDefinition(id, zh, en, description, schema, conditions, effect);
  }
}
