package com.trs.simulation.rule;

import java.util.List;
import java.util.Map;

/** Declarative rule, versioned independently from the world's Action definitions. @author czhcc-trsdsj\codexsandboxoffline */
public record TriggerRule(
        String id,
        Integer revision,
        String worldId,
        String name,
        String scope,
        String scopeId,
        Boolean enabled,
        Integer priority,
        Trigger trigger,
        String conditionExpression,
        List<TriggerAction> actions,
        List<String> allowedOrigins,
        Grant grant) {
    /** Provides input defaults while keeping published revisions explicit. @author czhcc-trsdsj\codexsandboxoffline */
    public TriggerRule {
        revision = revision == null ? 0 : revision;
        enabled = enabled == null || enabled;
        priority = priority == null ? 0 : priority;
    }
    /** Selects one documented kind of simulation change. @author czhcc-trsdsj\codexsandboxoffline */
    public record Trigger(String eventType, String objectType, String property) {}

    /** Plans a delayed invocation of an existing, type-owned Action. @author czhcc-trsdsj\codexsandboxoffline */
    public record TriggerAction(
            String actionType,
            String targetType,
            String targetExpression,
            Map<String, String> parameterExpressions,
            String delayExpression) {}

    /** Caps reads and target selection independently from the rule expressions. @author czhcc-trsdsj\codexsandboxoffline */
    public record Grant(List<String> visibleEntityIds, List<String> targetTypes, List<String> allowedActions) {}
}
