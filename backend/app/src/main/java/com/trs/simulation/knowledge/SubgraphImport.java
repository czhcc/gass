package com.trs.simulation.knowledge;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.common.Json;
import com.trs.simulation.state.OntologyConstraints;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 构造事务内可提交的 FILL_MISSING 图谱，并保留新增字段的来源。 @author czhcc-trsdsj\codexsandboxoffline */
public final class SubgraphImport {
    private SubgraphImport() {}

    /** 单一数据源已登记的内外部身份关联。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Identities(Map<String, String> entities, Map<String, String> relations) {
        /** 固定身份关系。 @author czhcc-trsdsj\codexsandboxoffline */
        public Identities {
            entities = Map.copyOf(entities);
            relations = Map.copyOf(relations);
        }

        /** 尚无已知身份时返回空映射。 @author czhcc-trsdsj\codexsandboxoffline */
        public static Identities empty() {
            return new Identities(Map.of(), Map.of());
        }
    }

    /** 导入候选包含世界状态、来源字段、冲突报告和独立的导入余额。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Preview(
            State state,
            Identities identities,
            Map<String, Set<String>> changedFields,
            List<String> conflicts,
            Map<String, Long> importedBalanceMinor) {}

    /** 对比当前世界并校验整个批次。此步骤不写入状态。 @author czhcc-trsdsj\codexsandboxoffline */
    public static Preview preview(
            World world, State before, OntologyMapping mapping, Identities known, ExternalSubgraph batch) {
        var entities = new LinkedHashMap<String, Entity>();
        var relations = new LinkedHashMap<String, Relation>();
        before.entities().forEach(entity -> entities.put(entity.id(), entity));
        before.relations().forEach(relation -> relations.put(relation.id(), relation));
        var entityIds = new LinkedHashMap<>(known.entities());
        var relationIds = new LinkedHashMap<>(known.relations());
        var importedFields = new LinkedHashMap<String, Set<String>>();
        var conflicts = new LinkedHashSet<String>();
        addEntities(world, mapping, batch, entities, entityIds, importedFields, conflicts);
        addRelations(mapping, batch, entities, relations, entityIds, relationIds, importedFields, conflicts);
        var afterEntities = List.copyOf(entities.values());
        var afterRelations = List.copyOf(relations.values());
        boolean changed = !afterEntities.equals(before.entities()) || !afterRelations.equals(before.relations());
        var next = changed
                ? new State(
                        before.runId(),
                        before.simulationTime(),
                        Math.incrementExact(before.version()),
                        afterEntities,
                        afterRelations)
                : before;
        var bankImport = OntologyConstraints.validateExternalCandidate(world, before, next);
        return new Preview(
                next,
                new Identities(entityIds, relationIds),
                Map.copyOf(importedFields),
                new ArrayList<>(conflicts),
                bankImport);
    }

    private static void addEntities(
            World world,
            OntologyMapping mapping,
            ExternalSubgraph batch,
            Map<String, Entity> entities,
            Map<String, String> ids,
            Map<String, Set<String>> importedFields,
            Set<String> conflicts) {
        var seen = new LinkedHashSet<String>();
        for (var external : batch.entities()) {
            var type = mapping.entities().get(external.type());
            if (type == null
                    || !world.entityTypes().contains(type.internalType())
                    || "bank".equals(world.domainCode()) && "TRANSACTION".equals(type.internalType())
                    || !seen.add(external.externalId())) {
                throw new IllegalArgumentException("Invalid or unmapped external entity");
            }
            var attributes = OntologyMapping.mapFields(type.fields(), external.properties());
            var bound = ids.get(external.externalId());
            var id = bound == null ? identity("node", mapping.providerCode(), external.externalId()) : bound;
            var existing = entities.get(id);
            if (bound != null && existing == null) {
                conflicts.add(id);
                continue;
            }
            if (existing != null && (bound == null || !existing.type().equals(type.internalType()))) {
                throw new IllegalArgumentException("External entity identity conflicts with the world");
            }
            var properties = merge(
                    id, existing == null ? Map.of() : existing.properties(), attributes, importedFields, conflicts);
            if (existing == null) {
                importedFields.putIfAbsent(id, Set.of());
                ids.put(external.externalId(), id);
            }
            entities.put(
                    id,
                    new Entity(
                            id, type.internalType(), existing == null ? external.name() : existing.name(), properties));
        }
    }

    private static void addRelations(
            OntologyMapping mapping,
            ExternalSubgraph batch,
            Map<String, Entity> entities,
            Map<String, Relation> relations,
            Map<String, String> entityIds,
            Map<String, String> ids,
            Map<String, Set<String>> importedFields,
            Set<String> conflicts) {
        var seen = new LinkedHashSet<String>();
        for (var external : batch.relations()) {
            var type = mapping.relations().get(external.type());
            if (type == null || !seen.add(external.externalId())) {
                throw new IllegalArgumentException("Invalid or unmapped external relation");
            }
            var source = entityIds.get(type.reverse() ? external.targetId() : external.sourceId());
            var target = entityIds.get(type.reverse() ? external.sourceId() : external.targetId());
            var bound = ids.get(external.externalId());
            var id = bound == null ? identity("edge", mapping.providerCode(), external.externalId()) : bound;
            var existing = relations.get(id);
            if (bound != null && (existing == null || !existing.enabled())) {
                conflicts.add(id);
                continue;
            }
            if (source == null || target == null || !entities.containsKey(source) || !entities.containsKey(target)) {
                throw new IllegalArgumentException("External relation endpoint is missing");
            }
            if (existing != null
                    && (bound == null
                            || !existing.relationType().equals(type.internalType())
                            || !existing.sourceId().equals(source)
                            || !existing.targetId().equals(target))) {
                throw new IllegalArgumentException("External relation identity conflicts with the world");
            }
            var fields = OntologyMapping.mapFields(type.fields(), external.properties());
            var properties = merge(
                    "relation:" + id,
                    existing == null ? Map.of() : existing.properties(),
                    fields,
                    importedFields,
                    conflicts);
            if (existing == null) {
                importedFields.putIfAbsent("relation:" + id, Set.of());
                ids.put(external.externalId(), id);
            }
            relations.put(
                    id,
                    new Relation(
                            id,
                            source,
                            target,
                            type.internalType(),
                            properties,
                            true,
                            existing == null ? "EXTERNAL" : existing.evidence(),
                            existing == null ? mapping.providerCode() : existing.source()));
        }
    }

    private static Map<String, Object> merge(
            String id,
            Map<String, Object> existing,
            Map<String, Object> external,
            Map<String, Set<String>> importedFields,
            Set<String> conflicts) {
        var result = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        var fields = new LinkedHashSet<String>();
        external.forEach((key, value) -> {
            if (!result.containsKey(key)) {
                result.put(key, value);
                fields.add(key);
            } else if (!equalProperty(result.get(key), value)) {
                conflicts.add(id);
            }
        });
        if (!fields.isEmpty()) {
            importedFields.put(id, Set.copyOf(fields));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static boolean equalProperty(Object existing, Object external) {
        if (existing instanceof Number a && external instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(existing, external);
    }

    private static String identity(String kind, String providerCode, String externalId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var key = Json.write(List.of(kind, providerCode, externalId)).getBytes(StandardCharsets.UTF_8);
            return "kg_" + HexFormat.of().formatHex(digest.digest(key));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
