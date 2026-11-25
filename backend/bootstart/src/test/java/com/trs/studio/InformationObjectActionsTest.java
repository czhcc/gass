package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.examples.Examples;
import com.trs.simulation.domain.information.InformationActionEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies type-owned contracts and independent engine enforcement.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
class InformationObjectActionsTest {
    private final World world = Examples.informationLeak();
    private final State state = new State("test", 0, 0, world.entities(), world.relations());
    private final InformationActionEngine engine = new InformationActionEngine();
    private final AgentDefinition agent = new AgentDefinition(
            "a",
            "Agent",
            "Investigate",
            "m",
            List.of(
                    "MARK_REVIEWED",
                    "ANALYZE_RELATIONS",
                    "SIMULATE_PROPAGATION",
                    "DISABLE_SYNC",
                    "REVOKE_SHARING",
                    "END"),
            world.entities().stream().map(Entity::id).toList(),
            10);

    @Test
    void ontologyOwnsActionsAndCapabilitiesFilterPermissionsAndVisibility() {
        assertNotNull(world.ontology());
        assertTrue(world.ontology().types().stream()
                .anyMatch(t -> t.type().equals("SYSTEM")
                        && t.targetKind().equals("ENTITY")
                        && t.actions().stream().anyMatch(a -> a.actionType().equals("DISABLE_SYNC"))));
        var limited = new AgentDefinition(
                "limited", "Limited", "Review", "m", List.of("MARK_REVIEWED"), List.of("document_f"), 10);
        var capabilities = engine.capabilities(world, limited, state);
        assertEquals(1, capabilities.size());
        assertEquals("document_f", capabilities.getFirst().targetId());
        assertEquals(false, capabilities.getFirst().action().parametersSchema().get("additionalProperties"));
        assertTrue(capabilities.getFirst().available());
    }

    @Test
    void rejectsExtraParametersWithSpecificFeedbackAndNeverAppliesThem() {
        var rejected = engine.execute(
                world, agent, decision("MARK_REVIEWED", "ENTITY", "document_f", Map.of("reviewed", true)), state);
        assertFalse(rejected.accepted());
        assertEquals("ACTION_PARAMETERS_INVALID", rejected.code());
        assertTrue(rejected.message().contains("parameters must be {}"));
        assertEquals(state, rejected.state());
        var accepted = engine.execute(world, agent, decision("MARK_REVIEWED", "ENTITY", "document_f", Map.of()), state);
        assertEquals(1, accepted.state().version());
        assertEquals(true, accepted.state().entities().getFirst().properties().get("reviewed"));
        assertEquals(
                "SIMULATED", accepted.state().entities().getFirst().properties().get("reviewEvidence"));
        var reviewed = engine.capabilities(world, agent, accepted.state()).stream()
                .filter(c -> c.targetId().equals("document_f")
                        && c.action().actionType().equals("MARK_REVIEWED"))
                .findFirst()
                .orElseThrow();
        assertFalse(reviewed.available());
        assertFalse(engine.execute(
                        world, agent, decision("MARK_REVIEWED", "ENTITY", "document_f", Map.of()), accepted.state())
                .accepted());
    }

    @Test
    void enforcesTargetKindTypeVisibilityPermissionAndCurrentRelationState() {
        assertEquals(
                "ACTION_TARGET_INVALID",
                engine.execute(world, agent, decision("DISABLE_SYNC", "ENTITY", "sync_st", Map.of()), state)
                        .code());
        assertEquals(
                "ACTION_NOT_BOUND",
                engine.execute(
                                world,
                                agent,
                                decision("DISABLE_SYNC", "ENTITY", "person_a", Map.of("destinationId", "system_t")),
                                state)
                        .code());
        var limited = new AgentDefinition("x", "X", "Review", "m", List.of("MARK_REVIEWED"), List.of("document_f"), 10);
        assertEquals(
                "ACTION_FORBIDDEN",
                engine.execute(
                                world,
                                limited,
                                decision("DISABLE_SYNC", "ENTITY", "system_s", Map.of("destinationId", "system_t")),
                                state)
                        .code());
        assertEquals(
                "ACTION_TARGET_HIDDEN",
                engine.execute(world, limited, decision("MARK_REVIEWED", "ENTITY", "person_a", Map.of()), state)
                        .code());
        var disabled = engine.execute(
                world,
                agent,
                decision("DISABLE_SYNC", "ENTITY", "system_s", Map.of("destinationId", "system_t")),
                state);
        assertTrue(disabled.changed());
        assertEquals(
                "ACTION_PRECONDITION_FAILED",
                engine.execute(
                                world,
                                agent,
                                decision("DISABLE_SYNC", "ENTITY", "system_s", Map.of("destinationId", "system_t")),
                                disabled.state())
                        .code());
        assertTrue(world.relations().stream().allMatch(Relation::enabled));
    }

    @Test
    void propagationUsesOnlyEnabledVisibleDirectSourcesAndLabelsEffects() {
        var capability = engine.capabilities(world, agent, state).stream()
                .filter(c -> c.targetId().equals("system_s")
                        && c.action().actionType().equals("SIMULATE_PROPAGATION"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("document_f"), capability.sourceIds());
        assertTrue(engine.capabilities(world, agent, state).stream()
                .filter(c -> c.targetId().equals("system_t")
                        && c.action().actionType().equals("SIMULATE_PROPAGATION"))
                .noneMatch(ActionCapability::available));
        assertFalse(engine.execute(
                        world,
                        agent,
                        decision("SIMULATE_PROPAGATION", "ENTITY", "system_t", Map.of("sourceId", "document_f")),
                        state)
                .accepted());
        var propagated = engine.execute(
                world,
                agent,
                decision("SIMULATE_PROPAGATION", "ENTITY", "system_s", Map.of("sourceId", "document_f")),
                state);
        assertTrue(propagated.accepted());
        var system = propagated.state().entities().stream()
                .filter(e -> e.id().equals("system_s"))
                .findFirst()
                .orElseThrow();
        assertEquals("SIMULATED", system.properties().get("exposureEvidence"));
        var disabled = engine.execute(
                world,
                agent,
                decision("DISABLE_SYNC", "ENTITY", "system_s", Map.of("destinationId", "system_t")),
                propagated.state());
        assertFalse(engine.execute(
                        world,
                        agent,
                        decision("SIMULATE_PROPAGATION", "ENTITY", "system_t", Map.of("sourceId", "system_s")),
                        disabled.state())
                .accepted());
    }

    @Test
    void legacyDecisionsRemainExecutableAndEndIsAnAgentLifecycleDecision() {
        assertTrue(engine.execute(world, agent, new Decision("MARK_REVIEWED", "document_f", Map.of(), "Legacy"), state)
                .accepted());
        assertTrue(engine.execute(world, agent, decision("END", "AGENT", "", Map.of()), state)
                .accepted());
        assertFalse(engine.execute(world, agent, decision("END", "ENTITY", "document_f", Map.of()), state)
                .accepted());
        var oldJson = Json.write(world).replaceAll(",\"ontology\":.*$", "}");
        assertNotNull(Json.read(oldJson, World.class).ontology());
    }

    @Test
    void hiddenEndpointsAndSourcesCannotBeUsedOrDiscoveredThroughCapabilities() {
        var limited = new AgentDefinition(
                "x", "X", "Contain", "m", List.of("DISABLE_SYNC", "SIMULATE_PROPAGATION"), List.of("system_s"), 10);
        var capabilities = engine.capabilities(world, limited, state);
        assertTrue(capabilities.stream().noneMatch(c -> c.targetKind().equals("RELATION")));
        assertTrue(capabilities.stream().allMatch(c -> c.sourceIds().isEmpty()));
        assertEquals(
                "ACTION_TARGET_HIDDEN",
                engine.execute(
                                world,
                                limited,
                                decision("DISABLE_SYNC", "ENTITY", "system_s", Map.of("destinationId", "system_t")),
                                state)
                        .code());
        assertFalse(engine.execute(
                        world,
                        limited,
                        decision("SIMULATE_PROPAGATION", "ENTITY", "system_s", Map.of("sourceId", "document_f")),
                        state)
                .accepted());
    }

    private Decision decision(String action, String kind, String target, Map<String, Object> parameters) {
        return new Decision(action, target, parameters, "Test decision", kind);
    }
}
