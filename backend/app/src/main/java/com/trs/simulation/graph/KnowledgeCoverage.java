package com.trs.simulation.graph;

import java.util.Map;

/** 运行内导入覆盖及授权的修订。 @author czhcc-trsdsj\codexsandboxoffline */
public record KnowledgeCoverage(String runId, long revision) {
    /** 在运行内匹配持久查询、完成记录和研判边界。 @author czhcc-trsdsj\codexsandboxoffline */
    public static boolean current(KnowledgeCoverage coverage, String sourceRunId, Map<?, ?> evidence) {
        if (coverage == null) {
            return !evidence.containsKey("coverageRevision");
        }
        return coverage.runId().equals(sourceRunId)
                && evidence.get("coverageRevision") instanceof Number observed
                && observed.longValue() == coverage.revision();
    }

    /** 为已启用扩展的运行标注覆盖修订。 @author czhcc-trsdsj\codexsandboxoffline */
    public static void annotate(KnowledgeCoverage coverage, Map<String, Object> evidence) {
        if (coverage != null) {
            evidence.put("coverageRevision", coverage.revision());
        }
    }
}
