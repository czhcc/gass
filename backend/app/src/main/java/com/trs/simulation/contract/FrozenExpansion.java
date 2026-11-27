package com.trs.simulation.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Data-only expansion contract shared by scenarios and knowledge providers. @author czhcc-trsdsj\codexsandboxoffline */
public record FrozenExpansion(
        Set<String> operators,
        Map<String, Source> sources,
        int maxCalls,
        int maxDepth,
        int maxEntitiesPerCall,
        int maxRelationsPerCall,
        int maxTotalImported)
        implements ExpansionInput {
    /** Copies the scenario's frozen source registry. @author czhcc-trsdsj\codexsandboxoffline */
    public FrozenExpansion {
        operators = Set.copyOf(operators);
        sources = Map.copyOf(sources);
    }

    /** Already a standalone scenario snapshot. @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public FrozenExpansion expansionSnapshot() {
        return this;
    }

    /** Provider mapping, explicit identities and approved role settings. @author czhcc-trsdsj\codexsandboxoffline */
    public record Source(
            Mapping mapping,
            Identities initialIdentities,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Set<String>> readersByExternalId,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Set<String>> actionRolesByExternalId,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Set<Query>> workerQueries) {
        /** Defensively copies all frozen permissions and queries. @author czhcc-trsdsj\codexsandboxoffline */
        public Source {
            readersByExternalId = copySets(readersByExternalId);
            actionRolesByExternalId = copySets(actionRolesByExternalId);
            workerQueries = copySets(workerQueries);
        }
    }

    /** Serialized source vocabulary and revision. @author czhcc-trsdsj\codexsandboxoffline */
    public record Mapping(
            String providerCode, int revision, Map<String, EntityType> entities, Map<String, EdgeType> relations) {
        /** Defensively copies the vocabulary. @author czhcc-trsdsj\codexsandboxoffline */
        public Mapping {
            entities = Map.copyOf(entities);
            relations = Map.copyOf(relations);
        }
    }

    /** Names the accepted internal entity type and fields. @author czhcc-trsdsj\codexsandboxoffline */
    public record EntityType(String internalType, Map<String, Field> fields) {
        /** Copies the field mappings. @author czhcc-trsdsj\codexsandboxoffline */
        public EntityType {
            fields = Map.copyOf(fields);
        }
    }

    /** Names the internal relation type and endpoint orientation. @author czhcc-trsdsj\codexsandboxoffline */
    public record EdgeType(String internalType, boolean reverse, Map<String, Field> fields) {
        /** Copies the field mappings. @author czhcc-trsdsj\codexsandboxoffline */
        public EdgeType {
            fields = Map.copyOf(fields);
        }
    }

    /** Field configuration; the knowledge layer interprets the unit. @author czhcc-trsdsj\codexsandboxoffline */
    public record Field(String internalName, String unit) {}

    /** Explicit local identities kept in the scenario. @author czhcc-trsdsj\codexsandboxoffline */
    public record Identities(Map<String, String> entities, Map<String, String> relations) {
        /** Copies both identity mappings. @author czhcc-trsdsj\codexsandboxoffline */
        public Identities {
            entities = Map.copyOf(entities);
            relations = Map.copyOf(relations);
        }
    }

    /** Exact external request authorized for a role. @author czhcc-trsdsj\codexsandboxoffline */
    public record Query(String operation, String selector, int depth, int limit) {}

    private static <T> Map<String, Set<T>> copySets(Map<String, Set<T>> values) {
        return values == null
                ? Map.of()
                : values.entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }
}
