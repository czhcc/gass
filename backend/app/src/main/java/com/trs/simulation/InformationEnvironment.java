package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Validates information-domain actions; inferred reachability is never a fact. @author agent */
@Component
public class InformationEnvironment implements DomainEnvironment {
  private final InformationActionEngine engine = new InformationActionEngine();

  /** Identifies the information domain. @author czhcc-trsdsj\codexsandboxoffline */
  public String domainCode() { return "information"; }

  /** Adapts original untyped relation requests to object calls. @author czhcc-trsdsj\codexsandboxoffline */
  @Override
  public Decision normalizeDecision(Decision decision, State state) { return engine.normalizeLegacyDecision(decision, state); }

  /** Validates persisted type contracts. @author czhcc-trsdsj\codexsandboxoffline */
  @Override
  public void validate(World world) { ActionPrograms.validate(world.ontology()); }

  /** Executes legacy direct domain calls through the object action engine. @author czhcc-trsdsj\codexsandboxoffline */
  public Outcome execute(AgentDefinition agent, Decision decision, State state) {
    return engine.execute(Examples.informationLeak(), agent, decision, state);
  }

  /** Executes against the run's frozen ontology. @author czhcc-trsdsj\codexsandboxoffline */
  @Override
  public Outcome execute(World world, AgentDefinition agent, Decision decision, State state) {
    return engine.execute(world, agent, decision, state);
  }

  /** Publishes scoped instance capabilities using the same engine contracts. @author czhcc-trsdsj\codexsandboxoffline */
  @Override
  public List<ActionCapability> capabilities(World world, AgentDefinition agent, State state) {
    return engine.capabilities(world, agent, state);
  }

  /** Legacy permission catalog derived from type-owned definitions; END is lifecycle control. @author czhcc-trsdsj\codexsandboxoffline */
  public List<Map<String, Object>> actions() {
    var definitions = new TreeMap<String, ActionDefinition>();
    ActionPrograms.ontology("information").types().forEach(t -> t.actions().forEach(a -> definitions.put(a.actionType(), a)));
    var result = new ArrayList<Map<String, Object>>();
    definitions.values().forEach(a -> result.add(Map.of("actionType", a.actionType(), "parameters", a.parametersSchema(), "targetRequired", true)));
    result.add(Map.of("actionType", "END", "parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false), "targetRequired", false));
    return List.copyOf(result);
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
