package com.trs.simulation.knowledge;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 每个数据源独立发布并由运行冻结的本体映射。 @author czhcc-trsdsj\codexsandboxoffline */
public record OntologyMapping(
        String providerCode, int revision, Map<String, EntityType> entities, Map<String, EdgeType> relations) {
    /** 冻结给定修订及允许的类型。 @author czhcc-trsdsj\codexsandboxoffline */
    public OntologyMapping {
        ExternalSubgraph.requireIdentifier(providerCode);
        if (revision < 1) {
            throw new IllegalArgumentException("Invalid ontology mapping revision");
        }
        entities = Map.copyOf(entities);
        relations = Map.copyOf(relations);
    }

    /** 实体映射及字段清单。 @author czhcc-trsdsj\codexsandboxoffline */
    public record EntityType(String internalType, Map<String, Field> fields) {
        /** 固定实体映射。 @author czhcc-trsdsj\codexsandboxoffline */
        public EntityType {
            ExternalSubgraph.requireIdentifier(internalType);
            fields = Map.copyOf(fields);
        }
    }

    /** 关系映射使用显式方向。 @author czhcc-trsdsj\codexsandboxoffline */
    public record EdgeType(String internalType, boolean reverse, Map<String, Field> fields) {
        /** 固定关系映射。 @author czhcc-trsdsj\codexsandboxoffline */
        public EdgeType {
            ExternalSubgraph.requireIdentifier(internalType);
            fields = Map.copyOf(fields);
        }
    }

    /** 单位转换在校验图谱前进行。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Field(String internalName, Unit unit) {
        /** 固定内部字段名和单位转换。 @author czhcc-trsdsj\codexsandboxoffline */
        public Field {
            ExternalSubgraph.requireIdentifier(internalName);
            Objects.requireNonNull(unit);
        }
    }

    /** VALUE 保留标量，INTEGER 和 MAJOR_TO_MINOR 执行精确整数转换。 @author czhcc-trsdsj\codexsandboxoffline */
    public enum Unit {
        VALUE,
        INTEGER,
        MAJOR_TO_MINOR
    }

    static Map<String, Object> mapFields(Map<String, Field> definitions, Map<String, Object> external) {
        var fields = new LinkedHashMap<String, Object>();
        for (var entry : external.entrySet()) {
            var field = definitions.get(entry.getKey());
            if (field == null) {
                throw new IllegalArgumentException("Unmapped external property");
            }
            var converted = field.unit() == Unit.VALUE ? entry.getValue() : convert(field.unit(), entry.getValue());
            if (fields.putIfAbsent(field.internalName(), converted) != null) {
                throw new IllegalArgumentException("Multiple external properties map to the same field");
            }
        }
        return fields;
    }

    private static long convert(Unit unit, Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("External numeric property must be a number");
        }
        try {
            var decimal = new BigDecimal(number.toString());
            return (unit == Unit.MAJOR_TO_MINOR ? decimal.movePointRight(2) : decimal).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("External numeric property is out of range or imprecise");
        }
    }
}
