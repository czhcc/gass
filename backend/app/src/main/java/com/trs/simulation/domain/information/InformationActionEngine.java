package com.trs.simulation.domain.information;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.action.DeclarativeActionEngine;
import java.util.List;
import java.util.Map;

/** Compatibility boundary for information actions delegated to the shared CEL interpreter. @author czhcc-trsdsj\czhcc */
public final class InformationActionEngine {
    private final DeclarativeActionEngine engine = new DeclarativeActionEngine();

    /** Computes visible capabilities using frozen declarative definitions. @author czhcc-trsdsj\czhcc */
    public List<ActionCapability> capabilities(World world, AgentDefinition agent, State state) {
        return engine.capabilities(world, agent, state);
    }

    /** Adapts only historical untyped relation calls into object-owned decisions. @author czhcc-trsdsj\czhcc */
    public Decision normalizeLegacyDecision(Decision decision, State state) {
        if (decision == null
                || decision.targetKind() != null
                || decision.parameters() == null
                || !decision.parameters().isEmpty()
                || state.entities().stream().anyMatch(e -> e.id().equals(decision.targetId()))) return decision;
        String relationType = "DISABLE_SYNC".equals(decision.actionType())
                ? "SYNCS_TO"
                : "REVOKE_SHARING".equals(decision.actionType()) ? "SHARED_WITH" : "";
        var relation = state.relations().stream()
                .filter(r ->
                        r.id().equals(decision.targetId()) && r.relationType().equals(relationType))
                .findFirst();
        if (relation.isEmpty()) return decision;
        var edge = relation.get();
        return new Decision(
                decision.actionType(),
                edge.sourceId(),
                Map.of(relationType.equals("SYNCS_TO") ? "destinationId" : "recipientId", edge.targetId()),
                decision.reason(),
                "ENTITY");
    }

    /** Executes without dispatching on action names or descriptive effect codes. @author czhcc-trsdsj\czhcc */
    public Outcome execute(World world, AgentDefinition agent, Decision decision, State state) {
        return engine.execute(world, agent, normalizeLegacyDecision(decision, state), state);
    }
}
