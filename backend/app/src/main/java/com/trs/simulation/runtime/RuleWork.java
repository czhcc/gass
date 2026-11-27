package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Decision;

/** The persisted invocation of one frozen rule response. @author czhcc-trsdsj\codexsandboxoffline */
record RuleWork(Cause cause, String targetType, Decision decision) {
    /** A stable source coordinate across historical forks. @author czhcc-trsdsj\codexsandboxoffline */
    record EventRef(String runId, long sequence) {}

    /** Identifies a rule revision and the original event that produced its work. @author czhcc-trsdsj\codexsandboxoffline */
    record Cause(EventRef causeEventRef, String ruleId, int ruleRevision, int causeDepth, String correlationId) {}
}
