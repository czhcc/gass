package com.trs.simulation.state;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.PropertyDefinition;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.RelationDefinition;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TypeDefinition;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.action.expression.CelExpressions;
import com.trs.simulation.common.context.HierarchyData;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies optional frozen ontology constraints to complete graph candidates. @author czhcc-trsdsj\codexsandboxoffline */
public final class OntologyConstraints {
    private static final Set<String> DATA_TYPES =
            Set.of("STRING", "BOOLEAN", "INTEGER", "NUMBER", "OBJECT", "ARRAY", "JSON");
    private static final Set<String> PROVENANCE = Set.of("simulationEvidence", "balanceEvidence");

    private OntologyConstraints() {}

    /** Validates declarations and fills defaults on world-template writes only. @author czhcc-trsdsj\codexsandboxoffline */
    public static World prepareTemplate(World world) {
        var ontology = world.ontology();
        var entityRules = new HashMap<String, List<PropertyDefinition>>();
        for (var type : ontology.types()) {
            validateFields(type.properties());
            if (type.properties() != null) {
                require(world.entityTypes().contains(type.type()), "Property rules require a declared entity type");
                entityRules.put(type.type(), type.properties());
            }
        }
        var relationRules = new HashMap<String, List<PropertyDefinition>>();
        var relationTypes = new HashSet<String>();
        if (ontology.relations() != null) {
            for (var relation : ontology.relations()) {
                require(
                        relation != null
                                && world.relationTypes().contains(relation.type())
                                && relationTypes.add(relation.type())
                                && validEndpoints(relation.sourceTypes(), world.entityTypes())
                                && validEndpoints(relation.targetTypes(), world.entityTypes())
                                && validLimit(relation.maxOutgoing())
                                && validLimit(relation.maxIncoming()),
                        "Invalid relation constraint definition");
                validateFields(relation.properties());
                if (relation.properties() != null) {
                    relationRules.put(relation.type(), relation.properties());
                }
            }
        }
        if (ontology.stateConstraints() != null) {
            var ids = new HashSet<String>();
            for (var constraint : ontology.stateConstraints()) {
                require(
                        constraint != null && validCode(constraint.code()) && ids.add(constraint.code()),
                        "Invalid or duplicate state constraint code");
                CelExpressions.validateBoolean(constraint.expression());
            }
        }
        var entities = world.entities().stream()
                .map(e -> new Entity(
                        e.id(),
                        e.type(),
                        e.name(),
                        defaults(e.properties(), entityRules.getOrDefault(e.type(), List.of()))))
                .toList();
        var relations = world.relations().stream()
                .map(r -> new Relation(
                        r.id(),
                        r.sourceId(),
                        r.targetId(),
                        r.relationType(),
                        defaults(r.properties(), relationRules.getOrDefault(r.relationType(), List.of())),
                        r.enabled(),
                        r.evidence(),
                        r.source()))
                .toList();
        var prepared = new World(
                world.id(),
                world.name(),
                world.domainCode(),
                world.entityTypes(),
                world.relationTypes(),
                entities,
                relations,
                world.version(),
                ontology);
        validateCandidate(prepared, null, new State(world.id(), 0, 0, entities, relations));
        return prepared;
    }

    /** Validates the final state and compares immutable fields against the prior state. @author czhcc-trsdsj\codexsandboxoffline */
    public static void validateCandidate(World world, State before, State after) {
        require(after.entities().size() <= 200 && after.relations().size() <= 1000, "Graph budget exceeded");
        var oldEntities = entities(before == null ? List.of() : before.entities());
        var entities = entities(after.entities());
        var types = new HashMap<String, TypeDefinition>();
        world.ontology().types().forEach(type -> types.put(type.type(), type));
        for (var entity : after.entities()) {
            require(
                    world.entityTypes().contains(entity.type())
                            || before != null
                                    && "bank".equals(world.domainCode())
                                    && "TRANSACTION".equals(entity.type()),
                    "Unknown entity type");
            var type = types.get(entity.type());
            var old = oldEntities.get(entity.id());
            if (type != null) {
                validateFields(
                        type.properties(),
                        type.allowUnknownProperties(),
                        old == null ? null : old.properties(),
                        entity.properties());
            }
        }
        var constraints = new HashMap<String, RelationDefinition>();
        if (world.ontology().relations() != null) {
            world.ontology().relations().forEach(def -> constraints.put(def.type(), def));
        }
        var relationIds = new HashSet<String>();
        var oldRelations = new HashMap<String, Relation>();
        if (before != null) {
            before.relations().forEach(r -> oldRelations.put(r.id(), r));
        }
        var incoming = new HashMap<List<String>, Integer>();
        var outgoing = new HashMap<List<String>, Integer>();
        for (var relation : after.relations()) {
            require(validId(relation.id()) && relationIds.add(relation.id()), "Invalid or duplicate relation ID");
            var source = entities.get(relation.sourceId());
            var target = entities.get(relation.targetId());
            require(
                    source != null && target != null && world.relationTypes().contains(relation.relationType()),
                    "Invalid relation endpoints or type");
            var def = constraints.get(relation.relationType());
            if (def == null) {
                continue;
            }
            require(
                    def.sourceTypes().contains(source.type())
                            && def.targetTypes().contains(target.type()),
                    "Relation endpoint violates its type constraint");
            var old = oldRelations.get(relation.id());
            validateFields(
                    def.properties(),
                    def.allowUnknownProperties(),
                    old == null ? null : old.properties(),
                    relation.properties());
            if (relation.enabled()) {
                require(
                        increment(outgoing, relation.relationType(), source.id(), def.maxOutgoing())
                                && increment(incoming, relation.relationType(), target.id(), def.maxIncoming()),
                        "Relation cardinality exceeded");
            }
        }
        if ("bank".equals(world.domainCode())) {
            var totals = bankTotals(after);
            if (before != null) {
                require(totals.equals(bankTotals(before)), "Bank balances must be conserved by currency");
            }
        }
        if (world.ontology().stateConstraints() != null) {
            var vars = HierarchyData.variables(before == null ? after : before, after, Map.of(), 0);
            for (var constraint : world.ontology().stateConstraints()) {
                require(
                        Boolean.TRUE.equals(CelExpressions.evaluate(constraint.expression(), vars)),
                        "State constraint failed: " + constraint.code());
            }
        }
    }

    private static Map<String, Entity> entities(List<Entity> rows) {
        var entities = new HashMap<String, Entity>();
        for (var entity : rows) {
            require(
                    validId(entity.id()) && entities.putIfAbsent(entity.id(), entity) == null,
                    "Invalid or duplicate entity ID");
        }
        return entities;
    }

    private static Map<String, Long> bankTotals(State state) {
        var totals = new HashMap<String, Long>();
        for (var entity : state.entities()) {
            if (!"BANK_ACCOUNT".equals(entity.type())) {
                continue;
            }
            var raw = entity.properties().get("balanceMinor");
            var currency = entity.properties().get("currency");
            require(
                    raw instanceof Number && currency instanceof String code && code.matches("[A-Z]{3}"),
                    "Invalid bank account currency or balance");
            long value = new BigDecimal(raw.toString()).longValueExact();
            require(value >= 0 && value <= 1_000_000_000_000L, "Bank account balance outside allowed range");
            totals.merge(currency.toString(), value, Math::addExact);
        }
        return totals;
    }

    private static boolean increment(Map<List<String>, Integer> counters, String type, String id, Integer max) {
        return max == null || counters.merge(List.of(type, id), 1, Integer::sum) <= max;
    }

    private static Map<String, Object> defaults(Map<String, Object> properties, List<PropertyDefinition> definitions) {
        if (definitions.isEmpty()) {
            return properties;
        }
        var result = new LinkedHashMap<>(properties);
        for (var rule : definitions) {
            if (rule.defaultValue() != null && !result.containsKey(rule.code())) {
                result.put(rule.code(), rule.defaultValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateFields(List<PropertyDefinition> fields) {
        if (fields == null) {
            return;
        }
        require(fields.size() <= 100, "Too many property definitions");
        var codes = new HashSet<String>();
        for (var field : fields) {
            require(
                    field != null
                            && validCode(field.code())
                            && codes.add(field.code())
                            && field.dataType() != null
                            && DATA_TYPES.contains(field.dataType()),
                    "Invalid or duplicate property definition");
            if (field.constraintExpression() != null) {
                CelExpressions.validateBoolean(field.constraintExpression());
            }
            if (field.defaultValue() != null) {
                validateValue(field, field.defaultValue());
            }
        }
    }

    private static void validateFields(
            List<PropertyDefinition> definitions,
            Boolean allowUnknown,
            Map<String, Object> before,
            Map<String, Object> properties) {
        if (definitions == null && allowUnknown == null) {
            return;
        }
        var fields = definitions == null ? List.<PropertyDefinition>of() : definitions;
        var declared = new HashSet<String>();
        for (var field : fields) {
            var code = field.code();
            declared.add(code);
            require(!field.required() || properties.get(code) != null, "Missing required property: " + code);
            if (properties.containsKey(code)) {
                validateValue(field, properties.get(code));
            }
            if (before != null && !field.mutable()) {
                require(
                        before.containsKey(code) == properties.containsKey(code)
                                && StateDiff.sameValue(before.get(code), properties.get(code)),
                        "Immutable property: " + code);
            }
        }
        if (!Boolean.TRUE.equals(allowUnknown)) {
            for (var entry : properties.entrySet()) {
                require(
                        declared.contains(entry.getKey())
                                || PROVENANCE.contains(entry.getKey()) && Objects.equals(entry.getValue(), "SIMULATED"),
                        "Unknown property: " + entry.getKey());
            }
        }
    }

    private static void validateValue(PropertyDefinition field, Object value) {
        boolean valid = switch (field.dataType()) {
            case "STRING" -> value instanceof String;
            case "BOOLEAN" -> value instanceof Boolean;
            case "INTEGER" ->
                value instanceof Number n
                        && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
            case "NUMBER" -> value instanceof Number;
            case "OBJECT" -> value instanceof Map<?, ?>;
            case "ARRAY" -> value instanceof List<?>;
            case "JSON" -> true;
            default -> false;
        };
        require(valid, "Invalid property type: " + field.code());
        if (field.constraintExpression() != null) {
            require(
                    Boolean.TRUE.equals(CelExpressions.evaluate(
                            field.constraintExpression(),
                            Collections.singletonMap("value", HierarchyData.json(value)))),
                    "Invalid property value: " + field.code());
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,100}");
    }

    private static boolean validCode(String code) {
        return code != null && code.matches("[A-Za-z][A-Za-z0-9_]{0,63}");
    }

    private static boolean validEndpoints(List<String> types, List<String> domainTypes) {
        return types != null && !types.isEmpty() && domainTypes.containsAll(types);
    }

    private static boolean validLimit(Integer limit) {
        return limit == null || limit >= 0 && limit <= 1000;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
