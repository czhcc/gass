package com.trs.simulation.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 可信连接器返回的结构化只读批次。 @author czhcc-trsdsj\codexsandboxoffline */
public record ExternalSubgraph(
        String sourceVersion,
        String fetchedAt,
        Coverage coverage,
        List<ExternalEntity> entities,
        List<ExternalRelation> relations) {
    /** 保留未知源修订，并固定查询覆盖范围及响应内容。 @author czhcc-trsdsj\codexsandboxoffline */
    public ExternalSubgraph {
        Instant.parse(fetchedAt);
        if (coverage == null) {
            throw new IllegalArgumentException("Missing external query coverage");
        }
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
    }

    /** 完整性仅适用于所声明的已授权查询范围。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Coverage(String queryScope, String completeness, boolean truncated, boolean hasMore) {
        /** 防止把截断或未完成分页声明为完整。 @author czhcc-trsdsj\codexsandboxoffline */
        public Coverage {
            if (queryScope == null
                    || queryScope.isBlank()
                    || completeness == null
                    || !Set.of("COMPLETE", "PARTIAL", "UNKNOWN").contains(completeness)
                    || "COMPLETE".equals(completeness) && (truncated || hasMore)) {
                throw new IllegalArgumentException("Invalid external query coverage");
            }
        }
    }

    /** 外部实体身份以数据源本身的 ID 表示。 @author czhcc-trsdsj\codexsandboxoffline */
    public record ExternalEntity(String externalId, String type, String name, Map<String, Object> properties) {
        /** 检查实体的结构和标识。 @author czhcc-trsdsj\codexsandboxoffline */
        public ExternalEntity {
            requireIdentifier(externalId);
            requireIdentifier(type);
            requireIdentifier(name);
            properties = scalarProperties(properties);
        }
    }

    /** 关系保留源端点、目标端点及稳定 ID。 @author czhcc-trsdsj\codexsandboxoffline */
    public record ExternalRelation(
            String externalId, String type, String sourceId, String targetId, Map<String, Object> properties) {
        /** 检查关系的结构和标识。 @author czhcc-trsdsj\codexsandboxoffline */
        public ExternalRelation {
            requireIdentifier(externalId);
            requireIdentifier(type);
            requireIdentifier(sourceId);
            requireIdentifier(targetId);
            properties = scalarProperties(properties);
        }
    }

    static void requireIdentifier(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 300
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid external identifier");
        }
    }

    private static Map<String, Object> scalarProperties(Map<String, Object> properties) {
        if (properties == null) {
            return Map.of();
        }
        if (properties.size() > 100) {
            throw new IllegalArgumentException("External property budget exceeded");
        }
        properties.forEach((key, value) -> {
            requireIdentifier(key);
            if (!(value instanceof Number
                    || value instanceof Boolean
                    || value instanceof String text && text.length() <= 8192)) {
                throw new IllegalArgumentException("Invalid external property value");
            }
        });
        return Map.copyOf(properties);
    }
}
