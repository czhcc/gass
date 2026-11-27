package com.trs.simulation.rule;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.action.expression.CelExpressions;
import com.trs.simulation.common.Json;
import com.trs.simulation.common.context.HierarchyData;
import com.trs.simulation.state.VisibilityPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Evaluates rules over a scoped snapshot without mutating it. @author czhcc-trsdsj\codexsandboxoffline */
public final class RuleEvaluation {
    private RuleEvaluation() {}

    /** A prospective Action and its relative logical delay. @author czhcc-trsdsj\codexsandboxoffline */
    public record Plan(Decision decision, String targetType, long delay) {}

    /** Creates a system identity restricted to the rule's explicit grant. @author czhcc-trsdsj\codexsandboxoffline */
    public static AgentDefinition actor(TriggerRule rule) {
        var grant = rule.grant();
        var actor = new AgentDefinition(
                "rule:" + rule.id(),
                rule.name(),
                "Apply frozen trigger",
                "",
                grant.allowedActions(),
                grant.visibleEntityIds(),
                1);
        return VisibilityPolicy.restrict(actor, grant.visibleEntityIds());
    }

    /** Projects entities, edges and values through the same visibility policy as other runtime readers. @author czhcc-trsdsj\codexsandboxoffline */
    public static State project(TriggerRule rule, State state) {
        return VisibilityPolicy.project(state, VisibilityPolicy.readableIds(actor(rule), state));
    }

    /** Evaluates the scoped condition and selects targets in deterministic object order. @author czhcc-trsdsj\codexsandboxoffline */
    public static List<Plan> plan(TriggerRule rule, State current, Map<String, Object> event) {
        return plan(rule, current, current, event);
    }

    /** Checks the recorded change before resolving targets against the current state. @author czhcc-trsdsj\codexsandboxoffline */
    public static List<Plan> plan(TriggerRule rule, State observed, State current, Map<String, Object> event) {
        var encodedEvent = HierarchyData.json(event);
        var satisfied = CelExpressions.evaluateRule(rule.conditionExpression(), variables(observed, encodedEvent));
        if (!(satisfied instanceof Boolean allowed))
            throw new IllegalArgumentException("Rule condition must return boolean");
        if (!allowed) return List.of();
        var vars = variables(current, encodedEvent);
        var result = new ArrayList<Plan>();
        for (var action : rule.actions()) {
            var ids = CelExpressions.evaluateRule(action.targetExpression(), vars);
            var targets = new TreeSet<String>();
            if (ids instanceof String id) targets.add(id);
            else if (ids instanceof List<?> list) {
                for (var candidate : list) {
                    if (!(candidate instanceof String id))
                        throw new IllegalArgumentException("Rule targets must be object IDs");
                    targets.add(id);
                }
            } else throw new IllegalArgumentException("Rule target expression must return an ID or list");
            if (targets.isEmpty()) continue;
            var params = new TreeMap<String, Object>();
            action.parameterExpressions()
                    .forEach((key, expr) -> params.put(key, CelExpressions.evaluateRule(expr, vars)));
            long delay = delay(CelExpressions.evaluateRule(action.delayExpression(), vars));
            if (Json.write(params).length() > 4000) throw new IllegalArgumentException("Rule parameters exceed budget");
            for (var id : targets) {
                if (current.entities().stream()
                        .noneMatch(e -> e.id().equals(id) && action.targetType().equals(e.type())))
                    throw new IllegalArgumentException("Rule target not authorized or of the declared type");
                result.add(new Plan(
                        new Decision(action.actionType(), id, params, "Triggered by rule", "ENTITY"),
                        action.targetType(),
                        delay));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> variables(State state, Object event) {
        return Map.of(
                "event",
                event,
                "entities",
                HierarchyData.json(state.entities()),
                "relations",
                HierarchyData.json(state.relations()),
                "version",
                state.version());
    }

    /** Resolves a hypothetical event in the same projected world used for target selection. @author czhcc-trsdsj\codexsandboxoffline */
    public static List<Plan> preview(TriggerRule rule, State state, Map<String, Object> values) {
        if (values == null || Json.write(values).length() > 4000)
            throw new IllegalArgumentException("Invalid preview event");
        var scope = VisibilityPolicy.readableIds(actor(rule), state);
        var current = VisibilityPolicy.project(state, scope);
        Object objectId = values.get("objectId");
        if (!current.entities().stream()
                        .anyMatch(e -> e.id().equals(objectId)
                                && e.type().equals(rule.trigger().objectType()))
                && !current.relations().stream()
                        .anyMatch(r -> r.id().equals(objectId)
                                && r.relationType().equals(rule.trigger().objectType())))
            throw new IllegalArgumentException("Preview event is outside the trigger scope");
        if (!VisibilityPolicy.referencesVisible(values, VisibilityPolicy.hiddenIds(state, scope)))
            throw new IllegalArgumentException("Preview contains a hidden object");
        var event = new LinkedHashMap<>(values);
        event.put("eventType", rule.trigger().eventType());
        event.put("objectType", rule.trigger().objectType());
        event.put(
                "property",
                rule.trigger().property() == null ? "" : rule.trigger().property());
        return plan(rule, current, event);
    }

    private static long delay(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Rule delay must be an integer");
        try {
            long delay = new BigDecimal(number.toString()).longValueExact();
            if (delay >= 0 && delay <= 1_000_000_000L) return delay;
        } catch (ArithmeticException ignored) {
            // The same safe validation message applies to fractional/overflowed results.
        }
        throw new IllegalArgumentException("Rule delay must be between 0 and 1000000000 ticks");
    }
}
