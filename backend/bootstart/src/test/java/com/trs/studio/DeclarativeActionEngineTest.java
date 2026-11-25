package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trs.simulation.ActionPrograms;
import com.trs.simulation.CelExpressions;
import com.trs.simulation.DeclarativeActionEngine;
import com.trs.simulation.Examples;
import com.trs.simulation.SimulationTypes.ActionCondition;
import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.ActionOperation;
import com.trs.simulation.SimulationTypes.ActionProgram;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TypeDefinition;
import com.trs.simulation.SimulationTypes.World;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises CEL interpretation and atomic graph/financial primitives. @author czhcc-trsdsj\czhcc */
class DeclarativeActionEngineTest {
    private final DeclarativeActionEngine engine = new DeclarativeActionEngine();

    private AgentDefinition agent(World w, String... actions) {
        return new AgentDefinition(
                "a",
                "A",
                "Test",
                "m",
                List.of(actions),
                w.entities().stream().map(Entity::id).toList(),
                10);
    }

    private State state(World w) {
        return new State("test", 0, 0, w.entities(), w.relations());
    }

    private Decision decision(String action, String id, Map<String, Object> parameters) {
        return new Decision(action, id, parameters, "Test", "ENTITY");
    }

    @Test
    void celRejectsNonBooleanConditionsAndStopsExcessiveComprehensionWork() {
        var a = ActionPrograms.ontology("bank").types().getFirst().actions().getLast();
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionPrograms.validate(new ActionDefinition(
                        a.actionType(),
                        a.nameZh(),
                        a.nameEn(),
                        a.description(),
                        a.parametersSchema(),
                        List.of(),
                        a.effect(),
                        new ActionProgram(
                                1,
                                List.of(new ActionCondition("42", "需要布尔值", "Boolean required")),
                                a.program().operations(),
                                Map.of()))));
        assertThrows(IllegalArgumentException.class, () -> CelExpressions.validate("x".repeat(2049)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CelExpressions.evaluate(
                        "entities.map(e, relations.map(r, true)).size()",
                        Map.of(
                                "entities",
                                Collections.nCopies(200, Map.of("id", "a")),
                                "relations",
                                Collections.nCopies(200, Map.of("id", "r")))));
    }

    @Test
    void allBuiltinsHaveRealCelProgramsAndBankRiskCalculationExecutes() {
        for (var w : List.of(Examples.informationLeak(), Examples.bankTransfer())) {
            assertEquals(3, w.ontology().version());
            w.ontology()
                    .types()
                    .forEach(t -> t.actions().forEach(a -> {
                        assertNotNull(a.program());
                        ActionPrograms.validate(a);
                    }));
        }
        var w = Examples.bankTransfer();
        var result = engine.execute(
                w, agent(w, "ASSESS_RISK_CEL"), decision("ASSESS_RISK_CEL", "account_a", Map.of()), state(w));
        assertTrue(result.accepted(), result.message());
        assertEquals(
                80L, ((Number) result.state().entities().getFirst().properties().get("riskScore")).longValue());
        assertEquals(
                1000000000L,
                ((Number) result.state().entities().getFirst().properties().get("balanceMinor")).longValue());
    }

    @Test
    void transferUsesExactMoneyPrimitiveAndRejectsMalformedAmountsAndHiddenSources() {
        var w = Examples.bankTransfer();
        var a = agent(w, "TRANSFER");
        var result = engine.execute(
                w,
                a,
                decision(
                        "TRANSFER",
                        "account_b",
                        Map.of("sourceId", "account_a", "amount", "5000000.00", "currency", "CNY")),
                state(w));
        assertTrue(result.accepted(), result.message());
        assertEquals(
                1200000000L,
                result.state().entities().stream()
                        .filter(e -> e.type().equals("BANK_ACCOUNT"))
                        .mapToLong(e -> ((Number) e.properties().get("balanceMinor")).longValue())
                        .sum());
        assertEquals(
                1,
                result.state().entities().stream()
                        .filter(e -> e.type().equals("TRANSACTION"))
                        .count());
        for (var amount : List.of("1.001", "-1", "999999999999999999", "NaN", "10000000.01")) {
            var denied = engine.execute(
                    w,
                    a,
                    decision(
                            "TRANSFER",
                            "account_b",
                            Map.of("sourceId", "account_a", "amount", amount, "currency", "CNY")),
                    state(w));
            assertFalse(denied.accepted(), amount);
            assertEquals(state(w), denied.state());
        }
        var hidden = new AgentDefinition("a", "A", "T", "m", List.of("TRANSFER"), List.of("account_b"), 10);
        assertEquals(
                "ACTION_TARGET_HIDDEN",
                engine.execute(
                                w,
                                hidden,
                                decision(
                                        "TRANSFER",
                                        "account_b",
                                        Map.of("sourceId", "account_a", "amount", "1", "currency", "CNY")),
                                state(w))
                        .code());
    }

    @Test
    void customCelCanCalculatePropertiesWithoutWritingJavaAndFailuresRollBackAllOperations() {
        var w = Examples.informationLeak();
        var action = new ActionDefinition(
                "SCORE",
                "计算分数",
                "Score",
                "Compute score",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                List.of(),
                "CUSTOM",
                new ActionProgram(
                        1,
                        List.of(new ActionCondition("target.type == 'DOCUMENT'", "需要文档", "Document required")),
                        List.of(new ActionOperation(
                                "SET_PROPERTY",
                                Map.of(
                                        "object",
                                        "target.id",
                                        "property",
                                        "'score'",
                                        "value",
                                        "relations.filter(r, r.sourceId == target.id && r.enabled).size() * 10"))),
                        Map.of()));
        var custom = new World(
                "custom",
                "Custom",
                "information",
                w.entityTypes(),
                w.relationTypes(),
                w.entities(),
                w.relations(),
                1,
                new Ontology(3, List.of(new TypeDefinition("ENTITY", "DOCUMENT", List.of(action)))));
        var result = engine.execute(
                custom, agent(custom, "SCORE"), decision("SCORE", "document_f", Map.of()), state(custom));
        assertTrue(result.accepted(), result.message());
        assertEquals(30L, result.state().entities().getFirst().properties().get("score"));
        var bad = new ActionDefinition(
                "SCORE",
                "计算分数",
                "Score",
                "Bad second operation",
                action.parametersSchema(),
                List.of(),
                "CUSTOM",
                new ActionProgram(
                        1,
                        List.of(),
                        List.of(
                                action.program().operations().getFirst(),
                                new ActionOperation(
                                        "SET_PROPERTY",
                                        Map.of("object", "'missing'", "property", "'score'", "value", "1"))),
                        Map.of()));
        var badWorld = new World(
                "bad",
                "Bad",
                "information",
                w.entityTypes(),
                w.relationTypes(),
                w.entities(),
                w.relations(),
                1,
                new Ontology(3, List.of(new TypeDefinition("ENTITY", "DOCUMENT", List.of(bad)))));
        var denied = engine.execute(
                badWorld, agent(badWorld, "SCORE"), decision("SCORE", "document_f", Map.of()), state(badWorld));
        assertFalse(denied.accepted());
        assertEquals(state(badWorld), denied.state());
        assertFalse(w.entities().getFirst().properties().containsKey("score"));
    }

    @Test
    void validatesCelBeforePublishingAndNeverAllowsHostCallsOrBalancePropertyPatches() {
        var base = ActionPrograms.ontology("bank").types().getFirst().actions().getFirst();
        var invalid = new ActionDefinition(
                "BAD",
                "错误",
                "Bad",
                "Bad",
                base.parametersSchema(),
                List.of(),
                "CUSTOM",
                new ActionProgram(
                        1,
                        List.of(new ActionCondition("java.lang.Runtime.getRuntime()", "条件", "Condition")),
                        List.of(),
                        Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ActionPrograms.validate(invalid));
        var patch = new ActionDefinition(
                "BAD",
                "错误",
                "Bad",
                "Bad",
                Map.of("properties", Map.of()),
                List.of(),
                "CUSTOM",
                new ActionProgram(
                        1,
                        List.of(),
                        List.of(new ActionOperation(
                                "SET_PROPERTY",
                                Map.of("object", "target.id", "property", "'balanceMinor'", "value", "999999"))),
                        Map.of()));
        var w = Examples.bankTransfer();
        var custom = new World(
                "bad",
                "Bad",
                "bank",
                w.entityTypes(),
                w.relationTypes(),
                w.entities(),
                w.relations(),
                1,
                new Ontology(3, List.of(new TypeDefinition("ENTITY", "BANK_ACCOUNT", List.of(patch)))));
        var denied =
                engine.execute(custom, agent(custom, "BAD"), decision("BAD", "account_a", Map.of()), state(custom));
        assertFalse(denied.accepted());
        assertEquals(state(custom), denied.state());
    }
}
