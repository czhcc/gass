package com.trs.simulation;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Interprets frozen CEL contracts using atomic, permission-checked graph primitives. @author czhcc-trsdsj\czhcc */
public final class DeclarativeActionEngine {
    /** Computes only visible object capabilities without side effects. @author czhcc-trsdsj\czhcc */
    public List<ActionCapability> capabilities(World world, AgentDefinition agent, State state) {
        var result = new ArrayList<ActionCapability>();
        for (var entity : state.entities()) {
            if (!scope(agent, state).contains(entity.id())) continue;
            for (var action : definitions(world, entity)) {
                if (!agent.allowedActions().contains(action.actionType())) continue;
                Map<String, List<String>> options = Map.of();
                String blocked = "";
                try {
                    options = options(action, variables(agent, entity, Map.of(), state));
                    for (var c : action.program().conditions()) {
                        if (java.util.regex.Pattern.compile("\\bparams\\b")
                                .matcher(c.expression())
                                .find()) continue;
                        if (!Boolean.TRUE.equals(
                                CelExpressions.evaluate(c.expression(), variables(agent, entity, Map.of(), state)))) {
                            blocked = c.messageEn();
                            break;
                        }
                    }
                    if (blocked.isEmpty())
                        for (var name : required(action)) {
                            if (reference(action, name)
                                    && options.getOrDefault(name, List.of()).isEmpty()) {
                                blocked = emptyOptions(action, name.toString());
                                break;
                            }
                        }
                } catch (IllegalArgumentException e) {
                    blocked = "CEL capability evaluation failed";
                }
                result.add(new ActionCapability(
                        "ENTITY",
                        entity.id(),
                        entity.name(),
                        entity.type(),
                        action,
                        blocked.isEmpty(),
                        blocked,
                        options.getOrDefault("sourceId", List.of()),
                        options));
            }
        }
        return List.copyOf(result);
    }

    /** Applies all declared operations to a private candidate; any failure preserves the input state. @author czhcc-trsdsj\czhcc */
    public Outcome execute(World world, AgentDefinition agent, Decision d, State original) {
        try {
            require(
                    d != null
                            && d.actionType() != null
                            && agent.allowedActions().contains(d.actionType()),
                    "ACTION_FORBIDDEN",
                    "Action is not permitted for this agent");
            require(
                    d.parameters() != null && d.reason() != null && d.reason().length() <= 1000,
                    "ACTION_PARAMETERS_INVALID",
                    "Malformed decision parameters or reason");
            if (d.actionType().equals("END")) {
                require(
                        (d.targetKind() == null || d.targetKind().equals("AGENT"))
                                && (d.targetId() == null || d.targetId().isEmpty())
                                && d.parameters().isEmpty(),
                        "ACTION_TARGET_INVALID",
                        "END requires an empty AGENT target and parameters");
                return new Outcome(true, "Agent completed its task", original, false);
            }
            require(
                    d.targetKind() == null || d.targetKind().equals("ENTITY"),
                    "ACTION_TARGET_INVALID",
                    "Business actions require an ENTITY target");
            var target = visible(agent, original, d.targetId());
            var a = definitions(world, target).stream()
                    .filter(x -> x.actionType().equals(d.actionType()))
                    .findFirst()
                    .orElseThrow(() ->
                            new Failure("ACTION_NOT_BOUND", "Action is not defined on object type " + target.type()));
            ActionPrograms.validate(a);
            validateParameters(a, d.parameters());
            for (var name : d.parameters().keySet())
                if (reference(a, name))
                    visible(agent, original, String.valueOf(d.parameters().get(name)));
            var context = variables(agent, target, d.parameters(), original);
            for (var c : a.program().conditions())
                require(
                        Boolean.TRUE.equals(CelExpressions.evaluate(c.expression(), context)),
                        "ACTION_PRECONDITION_FAILED",
                        c.messageEn());
            var options = options(a, context);
            for (var entry : options.entrySet())
                if (d.parameters().containsKey(entry.getKey()))
                    require(
                            entry.getValue().contains(d.parameters().get(entry.getKey())),
                            "ACTION_PRECONDITION_FAILED",
                            emptyOptions(a, entry.getKey()));
            var candidate = original;
            var affected = new TreeSet<String>();
            for (var op : a.program().operations()) {
                var currentTarget = candidate.entities().stream()
                        .filter(e -> e.id().equals(target.id()))
                        .findFirst()
                        .orElseThrow();
                var vars = variables(agent, currentTarget, d.parameters(), candidate);
                var args = new LinkedHashMap<String, Object>();
                op.arguments().forEach((key, expr) -> args.put(key, CelExpressions.evaluate(expr, vars)));
                candidate = switch (op.op()) {
                    case "SET_PROPERTY" -> setProperty(agent, candidate, args);
                    case "DISABLE_RELATIONS" -> disable(world, agent, candidate, args, affected);
                    case "TRANSFER_MONEY" -> transfer(world, agent, candidate, args);
                    default -> throw new Failure("ACTION_PROGRAM_INVALID", "Unsupported primitive");
                };
            }
            require(
                    candidate.entities().size() <= 200 && candidate.relations().size() <= 1000,
                    "ACTION_PRECONDITION_FAILED",
                    "Graph budget exceeded");
            boolean changed = !candidate.entities().equals(original.entities())
                    || !candidate.relations().equals(original.relations());
            var next = changed
                    ? new State(
                            original.runId(),
                            original.simulationTime(),
                            original.version() + 1,
                            candidate.entities(),
                            candidate.relations())
                    : original;
            return new Outcome(
                    true,
                    changed
                            ? "Declarative CEL action committed as SIMULATED"
                            : "Read-only analysis recorded; connectivity is not propagation evidence",
                    next,
                    changed,
                    "ACTION_ACCEPTED",
                    List.copyOf(affected));
        } catch (Failure e) {
            return new Outcome(false, e.getMessage(), original, false, e.code);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return new Outcome(false, e.getMessage(), original, false, "ACTION_PROGRAM_INVALID");
        }
    }

    private List<ActionDefinition> definitions(World world, Entity entity) {
        return world.ontology().types().stream()
                .filter(t -> t.targetKind().equals("ENTITY") && t.type().equals(entity.type()))
                .flatMap(t -> t.actions().stream())
                .toList();
    }

    private Set<String> scope(AgentDefinition a, State s) {
        var ids = new HashSet<>(a.visibleEntityIds());
        s.entities().stream()
                .filter(e -> e.type().equals("TRANSACTION")
                        && ids.contains(e.properties().get("sourceId"))
                        && ids.contains(e.properties().get("targetId")))
                .forEach(e -> ids.add(e.id()));
        return ids;
    }

    private Entity visible(AgentDefinition a, State s, String id) {
        var entity = s.entities().stream().filter(e -> e.id().equals(id)).findFirst();
        require(entity.isPresent(), "ACTION_TARGET_INVALID", "Select an existing owner or related object");
        require(scope(a, s).contains(id), "ACTION_TARGET_HIDDEN", "Object is outside the agent's visible scope");
        return entity.orElseThrow();
    }

    private Map<String, Object> variables(
            AgentDefinition a, Entity target, Map<String, Object> parameters, State state) {
        var ids = scope(a, state);
        return Map.of(
                "target",
                entity(target),
                "params",
                jsonValue(parameters),
                "version",
                state.version(),
                "entities",
                state.entities().stream()
                        .filter(e -> ids.contains(e.id()))
                        .map(this::entity)
                        .toList(),
                "relations",
                state.relations().stream()
                        .filter(r -> ids.contains(r.sourceId()) && ids.contains(r.targetId()))
                        .map(r -> Map.<String, Object>of(
                                "id",
                                r.id(),
                                "sourceId",
                                r.sourceId(),
                                "targetId",
                                r.targetId(),
                                "relationType",
                                r.relationType(),
                                "enabled",
                                r.enabled(),
                                "properties",
                                jsonValue(r.properties()),
                                "evidence",
                                r.evidence()))
                        .toList());
    }

    private Map<String, Object> entity(Entity e) {
        return Map.of("id", e.id(), "type", e.type(), "name", e.name(), "properties", jsonValue(e.properties()));
    }

    private Object jsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> out.put(k.toString(), jsonValue(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(this::jsonValue).toList();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer)
            return ((Number) value).longValue();
        return value;
    }

    private Map<String, List<String>> options(ActionDefinition a, Map<String, Object> vars) {
        var result = new LinkedHashMap<String, List<String>>();
        var visibleIds = ((List<?>) vars.get("entities"))
                .stream()
                        .map(e -> String.valueOf(((Map<?, ?>) e).get("id")))
                        .sorted()
                        .toList();
        ((Map<?, ?>) a.parametersSchema().get("properties")).forEach((name, field) -> {
            if (field instanceof Map<?, ?> schema && "entity-id".equals(schema.get("format")))
                result.put(name.toString(), visibleIds);
        });
        for (var entry : a.program().parameterOptions().entrySet()) {
            Object value = CelExpressions.evaluate(entry.getValue(), vars);
            require(
                    value instanceof List<?> list
                            && list.size() <= 200
                            && list.stream().allMatch(String.class::isInstance),
                    "ACTION_PROGRAM_INVALID",
                    "CEL parameter options must be a bounded string list");
            var candidates = ((List<?>) value)
                    .stream().map(String.class::cast).distinct().sorted().toList();
            require(
                    visibleIds.containsAll(candidates),
                    "ACTION_PROGRAM_INVALID",
                    "Options must reference visible existing objects");
            result.put(entry.getKey(), candidates);
        }
        return result;
    }

    private String emptyOptions(ActionDefinition a, String name) {
        if (a.effect().equals("REVOKE_SHARING_WITH")) return "No visible recipient with enabled outgoing sharing";
        if (a.effect().equals("DISABLE_SYNC_TO")) return "No visible destination with enabled outgoing synchronization";
        if (a.effect().equals("EXPOSE_ENTITY"))
            return "No visible eligible source with an enabled direct propagation path";
        return "No visible eligible object for parameter " + name;
    }

    private List<?> required(ActionDefinition a) {
        return (List<?>) a.parametersSchema().getOrDefault("required", List.of());
    }

    private boolean reference(ActionDefinition a, Object name) {
        Object field = ((Map<?, ?>) a.parametersSchema().get("properties")).get(name);
        return field instanceof Map<?, ?> map
                && ("entity-id".equals(map.get("format"))
                        || a.program().parameterOptions().containsKey(name));
    }

    private void validateParameters(ActionDefinition a, Map<String, Object> params) {
        var fields = (Map<?, ?>) a.parametersSchema().get("properties");
        require(
                fields.keySet().containsAll(params.keySet()) && params.keySet().containsAll(required(a)),
                "ACTION_PARAMETERS_INVALID",
                fields.isEmpty()
                        ? "parameters must be {}; put findings in reason"
                        : "Parameters must match the declared schema");
        for (var e : params.entrySet()) {
            var schema = (Map<?, ?>) fields.get(e.getKey());
            Object value = e.getValue();
            boolean valid = switch (String.valueOf(schema.get("type"))) {
                case "string" -> value instanceof String s && !s.isBlank() && s.length() <= 1000;
                case "boolean" -> value instanceof Boolean;
                case "integer" ->
                    value instanceof Number n
                            && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
                case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
                default -> false;
            };
            require(valid, "ACTION_PARAMETERS_INVALID", "Invalid parameter " + e.getKey());
            if (schema.get("enum") instanceof List<?> values)
                require(
                        values.stream()
                                .anyMatch(v -> v instanceof Number n && value instanceof Number m
                                        ? new BigDecimal(n.toString()).compareTo(new BigDecimal(m.toString())) == 0
                                        : Objects.equals(v, value)),
                        "ACTION_PARAMETERS_INVALID",
                        "Invalid enum parameter " + e.getKey());
        }
    }

    private State setProperty(AgentDefinition agent, State s, Map<String, Object> args) {
        var e = visible(agent, s, text(args, "object"));
        String property = text(args, "property");
        require(
                property.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                        && !Set.of("balanceMinor", "currency").contains(property)
                        && !e.type().equals("TRANSACTION"),
                "ACTION_PRECONDITION_FAILED",
                "Protected financial fields cannot be patched");
        Object value = args.get("value");
        require(
                value != null
                        && Json.write(value).length() <= 2000
                        && !(property.toLowerCase(Locale.ROOT).contains("evidence") && !value.equals("SIMULATED")),
                "ACTION_PROGRAM_INVALID",
                "Invalid simulated property value");
        var properties = new LinkedHashMap<>(e.properties());
        properties.put(property, value);
        properties.put("simulationEvidence", "SIMULATED");
        var entities = s.entities().stream()
                .map(x -> x.id().equals(e.id())
                        ? new Entity(x.id(), x.type(), x.name(), Collections.unmodifiableMap(properties))
                        : x)
                .toList();
        return new State(s.runId(), s.simulationTime(), s.version(), entities, s.relations());
    }

    private State disable(World w, AgentDefinition agent, State s, Map<String, Object> args, Set<String> affected) {
        String from = text(args, "source"), to = text(args, "target"), type = text(args, "relationType");
        visible(agent, s, from);
        visible(agent, s, to);
        require(w.relationTypes().contains(type), "ACTION_PROGRAM_INVALID", "Unknown relation type");
        var ids = s.relations().stream()
                .filter(r -> r.enabled()
                        && r.sourceId().equals(from)
                        && r.targetId().equals(to)
                        && r.relationType().equals(type))
                .map(Relation::id)
                .toList();
        require(!ids.isEmpty(), "ACTION_PRECONDITION_FAILED", "No matching enabled outgoing relations");
        affected.addAll(ids);
        var relations = s.relations().stream()
                .map(r -> ids.contains(r.id())
                        ? new Relation(
                                r.id(),
                                r.sourceId(),
                                r.targetId(),
                                r.relationType(),
                                r.properties(),
                                false,
                                "SIMULATED",
                                "declarative CEL object action")
                        : r)
                .toList();
        return new State(s.runId(), s.simulationTime(), s.version(), s.entities(), relations);
    }

    private State transfer(World w, AgentDefinition agent, State s, Map<String, Object> args) {
        require(w.domainCode().equals("bank"), "ACTION_PRECONDITION_FAILED", "Money transfers require a bank world");
        var source = visible(agent, s, text(args, "source"));
        var target = visible(agent, s, text(args, "target"));
        require(
                source.type().equals("BANK_ACCOUNT")
                        && target.type().equals("BANK_ACCOUNT")
                        && !source.id().equals(target.id()),
                "ACTION_PRECONDITION_FAILED",
                "Distinct bank accounts required");
        String currency = text(args, "currency");
        require(
                currency.matches("[A-Z]{3}")
                        && currency.equals(source.properties().get("currency"))
                        && currency.equals(target.properties().get("currency")),
                "ACTION_PRECONDITION_FAILED",
                "Currency mismatch");
        long amount;
        try {
            amount = new BigDecimal(text(args, "amount")).movePointRight(2).longValueExact();
        } catch (Exception e) {
            throw new Failure("ACTION_PARAMETERS_INVALID", "Invalid amount precision or range");
        }
        long debit = new BigDecimal(source.properties().get("balanceMinor").toString()).longValueExact(),
                credit = new BigDecimal(target.properties().get("balanceMinor").toString()).longValueExact();
        require(
                amount > 0 && amount <= 1_000_000_000_000L && debit >= amount && credit <= Long.MAX_VALUE - amount,
                "ACTION_PRECONDITION_FAILED",
                "Invalid amount, insufficient funds or balance overflow");
        require(s.entities().size() < 200, "ACTION_PRECONDITION_FAILED", "Entity budget reached");
        var entities = new ArrayList<Entity>();
        for (var e : s.entities()) {
            if (e.id().equals(source.id()) || e.id().equals(target.id())) {
                var properties = new LinkedHashMap<>(e.properties());
                properties.put("balanceMinor", e.id().equals(source.id()) ? debit - amount : credit + amount);
                properties.put("balanceEvidence", "SIMULATED");
                entities.add(new Entity(e.id(), e.type(), e.name(), Collections.unmodifiableMap(properties)));
            } else entities.add(e);
        }
        String id = "transaction_" + (s.version() + 1);
        int suffix = 0;
        while (contains(entities, id)) id = "transaction_" + (s.version() + 1) + "_" + (++suffix);
        entities.add(new Entity(
                id,
                "TRANSACTION",
                "模拟转账 / Simulated transfer",
                Map.of(
                        "sourceId",
                        source.id(),
                        "targetId",
                        target.id(),
                        "amountMinor",
                        amount,
                        "currency",
                        currency,
                        "evidence",
                        "SIMULATED")));
        return new State(s.runId(), s.simulationTime(), s.version(), List.copyOf(entities), s.relations());
    }

    private boolean contains(List<Entity> entities, String id) {
        return entities.stream().anyMatch(e -> e.id().equals(id));
    }

    private String text(Map<String, Object> args, String name) {
        Object value = args.get(name);
        require(
                value instanceof String && !((String) value).isBlank(),
                "ACTION_PARAMETERS_INVALID",
                "Expected text argument " + name);
        return (String) value;
    }

    private static void require(boolean value, String code, String message) {
        if (!value) throw new Failure(code, message);
    }
    /** Internal checked outcome classification; no state is committed on failure. @author czhcc-trsdsj\czhcc */
    private static final class Failure extends IllegalArgumentException {
        private final String code;

        private Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
