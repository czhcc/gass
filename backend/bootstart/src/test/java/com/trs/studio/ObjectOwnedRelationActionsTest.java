package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.Examples;
import com.trs.simulation.InformationActionEngine;
import com.trs.simulation.InformationOntology;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks object-owned operations that maintain related graph edges.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
class ObjectOwnedRelationActionsTest {
    private final World world = Examples.informationLeak();
    private final State state = new State("test", 0, 0, world.entities(), world.relations());
    private final InformationActionEngine engine = new InformationActionEngine();
    private final AgentDefinition agent = new AgentDefinition(
            "response",
            "Response",
            "Contain",
            "m",
            List.of("REVOKE_SHARING", "DISABLE_SYNC", "ANALYZE_RELATIONS", "END"),
            world.entities().stream().map(Entity::id).toList(),
            10);

    @Test
    void allBusinessActionsAreOwnedByEntityTypesWithRelatedObjectParameters() {
        assertEquals(3, world.ontology().version());
        assertTrue(
                world.ontology().types().stream().allMatch(t -> t.targetKind().equals("ENTITY")));
        var capabilities = engine.capabilities(world, agent, state);
        assertTrue(capabilities.stream().allMatch(c -> c.targetKind().equals("ENTITY")));
        var revoke = capabilities.stream()
                .filter(c -> c.targetId().equals("person_a")
                        && c.action().actionType().equals("REVOKE_SHARING"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("person_c"), revoke.parameterOptions().get("recipientId"));
        assertEquals(List.of("recipientId"), revoke.action().parametersSchema().get("required"));
        var sync = capabilities.stream()
                .filter(c -> c.targetId().equals("system_s")
                        && c.action().actionType().equals("DISABLE_SYNC"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("system_t"), sync.parameterOptions().get("destinationId"));
    }

    @Test
    void personRevokesOutgoingSharingAndSystemDisablesOutgoingSync() {
        var revoked = execute("REVOKE_SHARING", "person_a", Map.of("recipientId", "person_c"), state);
        assertTrue(revoked.accepted());
        assertEquals(List.of("share_ac"), revoked.affectedRelationIds());
        assertFalse(relation(revoked.state(), "share_ac").enabled());
        assertTrue(relation(revoked.state(), "sync_st").enabled());
        var disabled = execute("DISABLE_SYNC", "system_s", Map.of("destinationId", "system_t"), revoked.state());
        assertTrue(disabled.accepted());
        assertEquals(List.of("sync_st"), disabled.affectedRelationIds());
        assertEquals("SIMULATED", relation(disabled.state(), "sync_st").evidence());
        assertEquals(2, disabled.state().version());
        assertFalse(execute("DISABLE_SYNC", "system_s", Map.of("destinationId", "system_t"), disabled.state())
                .accepted());
        assertTrue(world.relations().stream().allMatch(Relation::enabled));
    }

    @Test
    void rejectsRelationTargetsWrongOwnersAndMalformedRelatedObjectParameters() {
        assertEquals(
                "ACTION_TARGET_INVALID",
                engine.execute(
                                world,
                                agent,
                                new Decision("REVOKE_SHARING", "share_ac", Map.of(), "Bad target", "RELATION"),
                                state)
                        .code());
        assertEquals(
                "ACTION_NOT_BOUND",
                execute("REVOKE_SHARING", "system_s", Map.of("recipientId", "person_c"), state)
                        .code());
        for (var parameters : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("recipientId", 1),
                Map.of("recipientId", ""),
                Map.of("destinationId", "person_c"),
                Map.of("recipientId", "person_c", "enabled", false)))
            assertEquals(
                    "ACTION_PARAMETERS_INVALID",
                    execute("REVOKE_SHARING", "person_a", parameters, state).code());
        assertFalse(execute("REVOKE_SHARING", "person_a", Map.of("recipientId", "person_b"), state)
                .accepted());
        assertFalse(execute("REVOKE_SHARING", "person_c", Map.of("recipientId", "person_a"), state)
                .accepted());
    }

    @Test
    void relatedObjectsMustBeVisibleAndCandidateListsDoNotExposeHiddenRecipients() {
        var limited = new AgentDefinition("x", "X", "Contain", "m", List.of("REVOKE_SHARING"), List.of("person_a"), 10);
        var capability = engine.capabilities(world, limited, state).getFirst();
        assertFalse(capability.available());
        assertTrue(capability.parameterOptions().get("recipientId").isEmpty());
        var denied = engine.execute(
                world,
                limited,
                new Decision("REVOKE_SHARING", "person_a", Map.of("recipientId", "person_c"), "Hidden", "ENTITY"),
                state);
        assertEquals("ACTION_TARGET_HIDDEN", denied.code());
        assertEquals(state, denied.state());
    }

    @Test
    void disablesAllMatchingOutgoingEdgesButPreservesOtherRecipientsAndIncomingEdges() {
        var relations = new ArrayList<>(state.relations());
        relations.add(new Relation("parallel", "person_a", "person_c", "SHARED_WITH", Map.of(), true, "FACT", "test"));
        relations.add(new Relation("other", "person_a", "person_b", "SHARED_WITH", Map.of(), true, "FACT", "test"));
        relations.add(new Relation("incoming", "person_c", "person_a", "SHARED_WITH", Map.of(), true, "FACT", "test"));
        var result = execute(
                "REVOKE_SHARING",
                "person_a",
                Map.of("recipientId", "person_c"),
                new State("test", 0, 0, state.entities(), relations));
        assertTrue(result.accepted());
        assertEquals(Set.of("parallel", "share_ac"), new HashSet<>(result.affectedRelationIds()));
        assertFalse(relation(result.state(), "parallel").enabled());
        assertTrue(relation(result.state(), "other").enabled());
        assertTrue(relation(result.state(), "incoming").enabled());
    }

    @Test
    void upgradesLegacyTypeBindingsWithoutChangingTheirHistoricalGraph() {
        var legacy = InformationOntology.legacyDefinition();
        var old = new World(
                "old",
                "Old",
                "information",
                world.entityTypes(),
                world.relationTypes(),
                world.entities(),
                world.relations(),
                1,
                legacy);
        assertEquals(3, old.ontology().version());
        assertEquals(world.relations(), old.relations());
        assertTrue(engine.capabilities(old, agent, state).stream()
                .allMatch(c -> c.targetKind().equals("ENTITY")));
        var disabled =
                engine.execute(old, agent, new Decision("DISABLE_SYNC", "sync_st", Map.of(), "Legacy request"), state);
        assertTrue(disabled.accepted());
        var narrowed = new Ontology(
                1,
                legacy.types().stream()
                        .filter(t -> !t.type().equals("SYNCS_TO"))
                        .toList());
        var restricted = new World(
                "old",
                "Old",
                "information",
                world.entityTypes(),
                world.relationTypes(),
                world.entities(),
                world.relations(),
                1,
                narrowed);
        assertTrue(engine.capabilities(restricted, agent, state).stream()
                .noneMatch(c -> c.action().actionType().equals("DISABLE_SYNC")));
    }

    private Outcome execute(String action, String owner, Map<String, Object> parameters, State input) {
        return engine.execute(
                world, agent, new Decision(action, owner, parameters, "Object decision", "ENTITY"), input);
    }

    private Relation relation(State value, String id) {
        return value.relations().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
