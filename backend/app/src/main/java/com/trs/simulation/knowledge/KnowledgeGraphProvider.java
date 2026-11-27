package com.trs.simulation.knowledge;

/** 外部图谱只读入口。 @author czhcc-trsdsj\codexsandboxoffline */
public interface KnowledgeGraphProvider {
    /** 返回管理员登记的数据源代号。 @author czhcc-trsdsj\codexsandboxoffline */
    String providerCode();

    /** 查询结构化批次，不执行任何外部写操作。 @author czhcc-trsdsj\codexsandboxoffline */
    ExternalSubgraph query(Query query);

    /** 服务端认可的只读操作。 @author czhcc-trsdsj\codexsandboxoffline */
    enum Operation {
        SEARCH,
        GET_ENTITY,
        EXPAND
    }

    /** 结构化参数由调用方的策略进一步校验。 @author czhcc-trsdsj\codexsandboxoffline */
    record Query(Operation operation, String selector, int depth, int limit) {
        /** 固定请求边界，避免转发任意工具参数。 @author czhcc-trsdsj\codexsandboxoffline */
        public Query {
            ExternalSubgraph.requireIdentifier(selector);
            if (operation == null
                    || depth < 0
                    || depth > 10
                    || limit < 1
                    || limit > 200
                    || operation != Operation.EXPAND && depth != 0) {
                throw new IllegalArgumentException("Invalid external query parameters");
            }
        }
    }
}
