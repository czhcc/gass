package com.trs.simulation.knowledge;

import java.util.Map;

/** 由测试装配的确定性只读外部源。 @author czhcc-trsdsj\codexsandboxoffline */
public final class MockKnowledgeGraphProvider implements KnowledgeGraphProvider {
    private final String providerCode;
    private final Map<Query, ExternalSubgraph> fixtures;

    /** 固定完整查询参数和对应结果。 @author czhcc-trsdsj\codexsandboxoffline */
    public MockKnowledgeGraphProvider(String providerCode, Map<Query, ExternalSubgraph> fixtures) {
        ExternalSubgraph.requireIdentifier(providerCode);
        this.providerCode = providerCode;
        this.fixtures = Map.copyOf(fixtures);
    }

    /** 返回固定数据源代号。 @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public String providerCode() {
        return providerCode;
    }

    /** 仅响应已配置的查询，便于发现测试中的误请求。 @author czhcc-trsdsj\codexsandboxoffline */
    @Override
    public ExternalSubgraph query(Query query) {
        var result = fixtures.get(query);
        if (result == null) {
            throw new IllegalArgumentException("External query has no fixture");
        }
        return result;
    }
}
