package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Observation;

/**
 * SDK boundary; receives immutable observations, never connections or state stores. @author agent
 */
public interface DecisionProvider {
    /** Identifies simulated or live model output for durable provenance. @author agent */
    default String mode(SimulationTypes.AgentDefinition agent) {
        return "LLM";
    }

    /** Produces a candidate action from the current permitted observation. @author agent */
    Decision decide(AgentDefinition agent, Observation observation);

    /** Returns public read-only tool calls from the just-completed decision. @author agent */
    default java.util.List<java.util.Map<String, Object>> drainToolCalls() {
        return java.util.List.of();
    }
}
