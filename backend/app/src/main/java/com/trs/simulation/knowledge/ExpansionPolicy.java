package com.trs.simulation.knowledge;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.knowledge.SubgraphImport.Identities;
import java.util.Map;
import java.util.Set;

/** 运行冻结的管理员数据源配置、显式身份关联和扩展配额。 @author czhcc-trsdsj\codexsandboxoffline */
public record ExpansionPolicy(
        Set<String> operators,
        Map<String, Source> sources,
        int maxCalls,
        int maxDepth,
        int maxEntitiesPerCall,
        int maxRelationsPerCall,
        int maxTotalImported) {
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

    /** 绑定本体修订与已有世界对象。 @author czhcc-trsdsj\codexsandboxoffline */
    public record Source(OntologyMapping mapping, Identities initialIdentities) {}

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
