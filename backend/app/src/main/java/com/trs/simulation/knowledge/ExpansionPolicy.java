package com.trs.simulation.knowledge;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.contract.ExpansionInput;
import com.trs.simulation.contract.FrozenExpansion;
import com.trs.simulation.knowledge.KnowledgeGraphProvider.Query;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 运行冻结的管理员数据源配置、显式身份关联和扩展配额。 @author czhcc-trsdsj\codexsandboxoffline */
public record ExpansionPolicy(
        Set<String> operators,
        Map<String, Source> sources,
        int maxCalls,
        int maxDepth,
        int maxEntitiesPerCall,
        int maxRelationsPerCall,
        int maxTotalImported)
        implements ExpansionInput {
    /** 校验服务器上限内的场景配额。 @author czhcc-trsdsj\codexsandboxoffline */
    public ExpansionPolicy {
        operators = Set.copyOf(operators);
        sources = Map.copyOf(sources);
        if (operators.isEmpty()
                || sources.isEmpty()
                || maxCalls < 1
                || maxCalls > 50
                || maxDepth < 0
                || maxDepth > 10
                || maxEntitiesPerCall < 1
                || maxEntitiesPerCall > 200
                || maxRelationsPerCall < 0
                || maxRelationsPerCall > 1000
                || maxTotalImported < 1
                || maxTotalImported > 200) {
            throw new IllegalArgumentException("Invalid external expansion policy");
        }
        operators.forEach(ExternalSubgraph::requireIdentifier);
        sources.forEach((code, source) -> {
            if (!code.equals(source.mapping().providerCode())) {
                throw new IllegalArgumentException("External provider mapping does not match its source");
            }
        });
    }

    /** Converts the data-only snapshot at the knowledge validation boundary. @author czhcc-trsdsj\codexsandboxoffline */
    public static ExpansionPolicy from(FrozenExpansion snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new ExpansionPolicy(
                snapshot.operators(),
                mapValues(snapshot.sources(), ExpansionPolicy::thawSource),
                snapshot.maxCalls(),
                snapshot.maxDepth(),
                snapshot.maxEntitiesPerCall(),
                snapshot.maxRelationsPerCall(),
                snapshot.maxTotalImported());
    }

    /** Keeps the persisted policy shape independent of validation and import code. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public FrozenExpansion expansionSnapshot() {
        return new FrozenExpansion(
                operators,
                mapValues(sources, ExpansionPolicy::freezeSource),
                maxCalls,
                maxDepth,
                maxEntitiesPerCall,
                maxRelationsPerCall,
                maxTotalImported);
    }

    private static Source thawSource(FrozenExpansion.Source source) {
        var mapping = source.mapping();
        var model = new OntologyMapping(
                mapping.providerCode(),
                mapping.revision(),
                mapValues(
                        mapping.entities(),
                        type -> new OntologyMapping.EntityType(type.internalType(), thawFields(type.fields()))),
                mapValues(
                        mapping.relations(),
                        type -> new OntologyMapping.EdgeType(
                                type.internalType(), type.reverse(), thawFields(type.fields()))));
        var ids = source.initialIdentities();
        return new Source(
                model,
                new Identities(ids.entities(), ids.relations()),
                source.readersByExternalId(),
                source.actionRolesByExternalId(),
                mapValues(
                        source.workerQueries(),
                        queries -> queries.stream()
                                .map(query -> new Query(
                                        KnowledgeGraphProvider.Operation.valueOf(query.operation()),
                                        query.selector(),
                                        query.depth(),
                                        query.limit()))
                                .collect(Collectors.toUnmodifiableSet())));
    }

    private static FrozenExpansion.Source freezeSource(Source source) {
        var model = source.mapping();
        var mapping = new FrozenExpansion.Mapping(
                model.providerCode(),
                model.revision(),
                mapValues(
                        model.entities(),
                        type -> new FrozenExpansion.EntityType(type.internalType(), freezeFields(type.fields()))),
                mapValues(
                        model.relations(),
                        type -> new FrozenExpansion.EdgeType(
                                type.internalType(), type.reverse(), freezeFields(type.fields()))));
        var ids = source.initialIdentities();
        return new FrozenExpansion.Source(
                mapping,
                new FrozenExpansion.Identities(ids.entities(), ids.relations()),
                source.readersByExternalId(),
                source.actionRolesByExternalId(),
                mapValues(
                        source.workerQueries(),
                        queries -> queries.stream()
                                .map(query -> new FrozenExpansion.Query(
                                        query.operation().name(), query.selector(), query.depth(), query.limit()))
                                .collect(Collectors.toUnmodifiableSet())));
    }

    private static Map<String, OntologyMapping.Field> thawFields(Map<String, FrozenExpansion.Field> fields) {
        return mapValues(
                fields,
                field -> new OntologyMapping.Field(field.internalName(), OntologyMapping.Unit.valueOf(field.unit())));
    }

    private static Map<String, FrozenExpansion.Field> freezeFields(Map<String, OntologyMapping.Field> fields) {
        return mapValues(
                fields,
                field -> new FrozenExpansion.Field(
                        field.internalName(), field.unit().name()));
    }

    private static <A, B> Map<String, B> mapValues(Map<String, A> source, Function<A, B> convert) {
        return source.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> convert.apply(entry.getValue())));
    }

    /** 绑定本体修订与已有世界对象。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Source(
            OntologyMapping mapping,
            Identities initialIdentities,

            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, Set<String>> readersByExternalId,

            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, Set<String>> actionRolesByExternalId,

            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, Set<Query>> workerQueries) {
        /** 冻结外部对象的读取授权及独立的本地 Action 访问授权。 @author czhcc-trsdsj\codexsandboxoffline */
        public Source {
            readersByExternalId = freeze(readersByExternalId);
            actionRolesByExternalId = freeze(actionRolesByExternalId);
            workerQueries = workerQueries == null
                    ? Map.of()
                    : workerQueries.entrySet().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
            if (workerQueries.values().stream().anyMatch(queries -> queries.isEmpty() || queries.size() > 20)) {
                throw new IllegalArgumentException("Invalid external worker query allowlist");
            }

            for (var grant : actionRolesByExternalId.entrySet()) {
                if (!readersByExternalId.getOrDefault(grant.getKey(), Set.of()).containsAll(grant.getValue())) {
                    throw new IllegalArgumentException("External object Action access requires an explicit reader");
                }
            }
        }

        /** 兼容仅配置操作人和导入授权的场景。 @author czhcc-trsdsj\codexsandboxoffline */
        public Source(
                OntologyMapping mapping,
                Identities initialIdentities,
                Map<String, Set<String>> readersByExternalId,
                Map<String, Set<String>> actionRolesByExternalId) {
            this(mapping, initialIdentities, readersByExternalId, actionRolesByExternalId, Map.of());
        }

        /** 兼容仅配置动态读取授权的场景。 @author czhcc-trsdsj\codexsandboxoffline */
        public Source(
                OntologyMapping mapping, Identities initialIdentities, Map<String, Set<String>> readersByExternalId) {
            this(mapping, initialIdentities, readersByExternalId, Map.of());
        }

        /** 兼容未配置动态授权的场景。 @author czhcc-trsdsj\codexsandboxoffline */
        public Source(OntologyMapping mapping, Identities initialIdentities) {
            this(mapping, initialIdentities, Map.of(), Map.of());
        }

        private static Map<String, Set<String>> freeze(Map<String, Set<String>> grants) {
            if (grants == null) {
                return Map.of();
            }
            grants.keySet().forEach(ExternalSubgraph::requireIdentifier);
            return grants.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }

    /** 仅允许对当前冻结场景中的角色发放授权。 @author czhcc-trsdsj\codexsandboxoffline */
    public void validateReaders(Set<String> sceneAgentIds) {
        if (sources.values().stream()
                .flatMap(source -> source.readersByExternalId().values().stream())
                .anyMatch(readers -> readers.isEmpty() || !sceneAgentIds.containsAll(readers))) {
            throw new IllegalArgumentException("Unknown or empty reader set for external entity");
        }
    }

    /** 校验已绑定目标及允许的内部类型均来自当前本体。 @author czhcc-trsdsj\codexsandboxoffline */
    public void validate(World world) {
        var entities = world.entities().stream().map(Entity::id).toList();
        var relations = world.relations().stream().map(Relation::id).toList();
        for (var source : sources.values()) {
            if (!entities.containsAll(source.initialIdentities().entities().values())
                    || !relations.containsAll(
                            source.initialIdentities().relations().values())
                    || source.mapping().entities().values().stream()
                            .anyMatch(type -> !world.entityTypes().contains(type.internalType()))
                    || source.mapping().relations().values().stream()
                            .anyMatch(type -> !world.relationTypes().contains(type.internalType()))) {
                throw new IllegalArgumentException("External mapping refers to unknown world types or objects");
            }
        }
    }
}
