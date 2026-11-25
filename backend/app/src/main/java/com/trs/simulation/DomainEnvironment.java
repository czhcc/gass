package com.trs.simulation;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;

/**
 * Pure domain action validation and effects, without persistence or SDK dependencies. @author agent
 */
public interface DomainEnvironment {
    /** Projects domain-generated objects into an authorized observation scope. @author agent */
    default java.util.Set<String> observationScope(SimulationTypes.AgentDefinition agent, SimulationTypes.State state) {
        return new java.util.HashSet<>(agent.visibleEntityIds());
    }

    /** Validates initial domain invariants before a template is stored. @author agent */
    default void validate(SimulationTypes.World world) {}

    /** Returns the plugin domain identifier. @author agent */
    String domainCode();

    /** Computes an outcome from a proposal without mutating the supplied state. @author agent */
    Outcome execute(AgentDefinition agent, Decision proposal, State state);

    /** Normalizes a legacy decision before execution and audit. @author czhcc-trsdsj\codexsandboxoffline */
    default Decision normalizeDecision(Decision decision, State state) {
        return decision;
    }

    /** Executes a decision using frozen world contracts where supported. @author czhcc-trsdsj\codexsandboxoffline */
    default Outcome execute(World world, AgentDefinition agent, Decision proposal, State state) {
        return execute(agent, proposal, state);
    }

    /** Returns visible object capabilities; legacy domains retain their original protocol. @author czhcc-trsdsj\codexsandboxoffline */
    default java.util.List<ActionCapability> capabilities(World world, AgentDefinition agent, State state) {
        return java.util.List.of();
    }

    /**
     * Lists registered action schemas; domains never provide executable user scripts. @author agent
     */
    java.util.List<java.util.Map<String, Object>> actions();

    /** Evaluates domain metrics without changing the state. @author agent */
    java.util.Map<String, Object> evaluate(State state);
}
