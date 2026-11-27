package com.trs.simulation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.trs.simulation.knowledge.ExpansionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable boundary types; agents receive only filtered observations. @author agent */
public final class SimulationTypes {
    private SimulationTypes() {}

    /** Dynamic entity; types are not restricted to Java enums. @author agent */
    public record Entity(String id, String type, String name, Map<String, Object> properties) {}

    /** Directed property edge with provenance and evidence class. @author agent */
    public record Relation(
            String id,
            String sourceId,
            String targetId,
            String relationType,
            Map<String, Object> properties,
            boolean enabled,
            String evidence,
            String source) {}

    /** Reusable, versioned world template. @author agent */
    public record World(
            String id,
            String name,
            String domainCode,
            List<String> entityTypes,
            List<String> relationTypes,
            List<Entity> entities,
            List<Relation> relations,
            int version,
            Ontology ontology) {
        /** Resolves absent or legacy contracts to object-owned actions without changing graph state. @author czhcc-trsdsj\codexsandboxoffline */
        public World {
            if (Set.of("information", "bank").contains(domainCode == null ? "" : domainCode))
                ontology = ActionPrograms.upgrade(domainCode, ontology);
        }

        /** Preserves the original world input protocol. @author czhcc-trsdsj\codexsandboxoffline */
        public World(
                String id,
                String name,
                String domainCode,
                List<String> entityTypes,
                List<String> relationTypes,
                List<Entity> entities,
                List<Relation> relations,
                int version) {
            this(id, name, domainCode, entityTypes, relationTypes, entities, relations, version, null);
        }
    }

    /** Versioned ontology stored with the world and frozen run. @author czhcc-trsdsj\codexsandboxoffline */
    public record Ontology(
            int version,
            List<TypeDefinition> types,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<RelationDefinition> relations,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<StateConstraint> stateConstraints) {
        /** Retains the legacy action-only ontology. @author czhcc-trsdsj\codexsandboxoffline */
        public Ontology(int version, List<TypeDefinition> types) {
            this(version, types, null, null);
        }
    }

    /** Actions owned by entity types; targetKind remains for serialized legacy compatibility. @author czhcc-trsdsj\codexsandboxoffline */
    public record TypeDefinition(
            String targetKind,
            String type,
            List<ActionDefinition> actions,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<PropertyDefinition> properties,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean allowUnknownProperties) {
        /** Retains a legacy binding without strict property rules. @author czhcc-trsdsj\codexsandboxoffline */
        public TypeDefinition(String targetKind, String type, List<ActionDefinition> actions) {
            this(targetKind, type, actions, null, null);
        }
    }

    /** A declared field with an optional default and CEL predicate over its value. @author czhcc-trsdsj\codexsandboxoffline */
    public record PropertyDefinition(
            String code,
            String dataType,
            boolean required,
            boolean mutable,
            Object defaultValue,
            String constraintExpression) {}

    /** Restricts endpoints, enabled-edge cardinality and properties of one relation type. @author czhcc-trsdsj\codexsandboxoffline */
    public record RelationDefinition(
            String type,
            List<String> sourceTypes,
            List<String> targetTypes,
            Integer maxOutgoing,
            Integer maxIncoming,
            List<PropertyDefinition> properties,
            Boolean allowUnknownProperties) {}

    /** A CEL invariant evaluated on a complete graph candidate. @author czhcc-trsdsj\codexsandboxoffline */
    public record StateConstraint(String code, String expression) {}

    /** Declarative engine contract; no arbitrary code or state patches. @author czhcc-trsdsj\codexsandboxoffline */
    public record ActionDefinition(
            String actionType,
            String nameZh,
            String nameEn,
            String description,
            Map<String, Object> parametersSchema,
            List<String> preconditions,
            String effect,
            ActionProgram program) {
        /** Accepts historical contracts; world loading upgrades registered legacy effects. @author czhcc-trsdsj\czhcc */
        public ActionDefinition(
                String actionType,
                String nameZh,
                String nameEn,
                String description,
                Map<String, Object> parametersSchema,
                List<String> preconditions,
                String effect) {
            this(actionType, nameZh, nameEn, description, parametersSchema, preconditions, effect, null);
        }
    }

    /** Bounded CEL conditions and allow-listed graph operations, frozen with each world. @author czhcc-trsdsj\czhcc */
    public record ActionProgram(
            int version,
            List<ActionCondition> conditions,
            List<ActionOperation> operations,
            Map<String, String> parameterOptions) {}

    /** Pure boolean CEL expression with user-readable failure explanations. @author czhcc-trsdsj\czhcc */
    public record ActionCondition(String expression, String messageZh, String messageEn) {}

    /** Primitive operation whose arguments are pure CEL expressions. @author czhcc-trsdsj\czhcc */
    public record ActionOperation(String op, Map<String, String> arguments) {}

    /** Object capability and allowed related-object IDs evaluated against current state. @author czhcc-trsdsj\codexsandboxoffline */
    public record ActionCapability(
            String targetKind,
            String targetId,
            String targetName,
            String type,
            ActionDefinition action,
            boolean available,
            String unavailableReason,
            List<String> sourceIds,
            Map<String, List<String>> parameterOptions) {}

    /** Object actions visible to one agent. @author czhcc-trsdsj\codexsandboxoffline */
    public record AgentCapabilities(String agentId, List<ActionCapability> capabilities, boolean ended) {}

    /** Versioned instance capabilities for the run's intervention UI. @author czhcc-trsdsj\codexsandboxoffline */
    public record ActionCatalog(String domainCode, long version, List<AgentCapabilities> agents) {}

    /** An agent's goal, permissions, visibility and budget. @author agent */
    public record AgentDefinition(
            String id,
            String name,
            String goal,
            String modelId,
            List<String> allowedActions,
            List<String> visibleEntityIds,
            int maxDecisions,
            AgentRuntime runtime,
            @JsonIgnore Set<String> taskScopeLimit) {
        /** Copies the internal scope limit so a delegated grant cannot change after creation. @author czhcc-trsdsj\codexsandboxoffline */
        public AgentDefinition {
            taskScopeLimit = taskScopeLimit == null ? null : Set.copyOf(taskScopeLimit);
        }

        /** Creates a frozen role with its normal derived visibility. @author czhcc-trsdsj\codexsandboxoffline */
        public AgentDefinition(
                String id,
                String name,
                String goal,
                String modelId,
                List<String> allowedActions,
                List<String> visibleEntityIds,
                int maxDecisions,
                AgentRuntime runtime) {
            this(id, name, goal, modelId, allowedActions, visibleEntityIds, maxDecisions, runtime, null);
        }

        /** Preserves frozen legacy agent definitions. @author czhcc-trsdsj\codexsandboxoffline */
        public AgentDefinition(
                String id,
                String name,
                String goal,
                String modelId,
                List<String> allowedActions,
                List<String> visibleEntityIds,
                int maxDecisions) {
            this(id, name, goal, modelId, allowedActions, visibleEntityIds, maxDecisions, null);
        }
    }

    /** Configurable SDK role, delegation tool description and allowed query tools. @author czhcc-trsdsj\codexsandboxoffline */
    public record AgentRuntime(String role, String toolName, String description, List<String> tools) {}

    /** Bounded hierarchy and declarative outcome rules, frozen in each run. @author czhcc-trsdsj\codexsandboxoffline */
    public record TeamConfig(
            String rootAgentId,
            int maxDelegationsPerRound,
            int maxActionsPerRound,
            List<MockActionRule> mockRules,
            List<GoalCondition> conditions,
            Map<String, List<String>> inputActions) {
        /** Supports profiles without action-bound external inputs. @author czhcc-trsdsj\codexsandboxoffline */
        public TeamConfig(
                String rootAgentId,
                int maxDelegationsPerRound,
                int maxActionsPerRound,
                List<MockActionRule> mockRules,
                List<GoalCondition> conditions) {
            this(rootAgentId, maxDelegationsPerRound, maxActionsPerRound, mockRules, conditions, Map.of());
        }
    }

    /** Explicit mock-only policy; real models choose tasks from prompts and capabilities. @author czhcc-trsdsj\codexsandboxoffline */
    public record MockActionRule(String actionType, String eventType, String whenExpression) {}

    /** Pure CEL success condition and user-visible explanation. @author czhcc-trsdsj\codexsandboxoffline */
    public record GoalCondition(String id, String nameZh, String nameEn, String expression, String evidenceExpression) {
        /** Keeps existing conditions valid without an evidence expression. @author czhcc-trsdsj\codexsandboxoffline */
        public GoalCondition(String id, String nameZh, String nameEn, String expression) {
            this(id, nameZh, nameEn, expression, null);
        }
    }

    /** Editable condition draft and requirements that need clarification or data. @author czhcc-trsdsj\codexsandboxoffline */
    public record ObjectiveDraft(List<GoalCondition> conditions, List<String> unresolved, String mode) {}

    /** Master conclusion with server-validated references to persisted evidence. @author czhcc-trsdsj\codexsandboxoffline */
    public record ObjectiveReview(
            String conclusion, List<Long> evidenceSequences, String limitations, String history, String finalState) {
        /** Preserves reports written before historical and final-state sections were introduced. @author czhcc-trsdsj\codexsandboxoffline */
        public ObjectiveReview(String conclusion, List<Long> evidenceSequences, String limitations) {
            this(conclusion, evidenceSequences, limitations, null, null);
        }
    }

    /** Structured delegation request; authorization is independently checked by the runtime. @author czhcc-trsdsj\codexsandboxoffline */
    public record TaskRequest(String goal, List<String> scopeIds, List<String> allowedActions, String inputEventId) {}

    /** Durable parent/child task record with exact audit boundaries. @author czhcc-trsdsj\codexsandboxoffline */
    public record TaskRecord(
            String runId,
            String taskId,
            String parentTaskId,
            String agentId,
            int round,
            String status,
            long inputVersion,
            long outputVersion,
            TaskRequest request,
            Map<String, Object> result,
            long createdSequence,
            Long completedSequence) {}

    /** Public master report; model prose cannot declare business success. @author czhcc-trsdsj\codexsandboxoffline */
    public record RoundReport(String summary, List<String> findings, List<String> nextTasks) {}

    /** One evaluated business condition; expression errors are explicit. @author czhcc-trsdsj\codexsandboxoffline */
    public record ConditionResult(
            String id,
            String nameZh,
            String nameEn,
            boolean satisfied,
            String error,
            Object evidence,
            String evidenceError) {
        /** Keeps callers that report only the verdict and condition error. @author czhcc-trsdsj\codexsandboxoffline */
        public ConditionResult(String id, String nameZh, String nameEn, boolean satisfied, String error) {
            this(id, nameZh, nameEn, satisfied, error, null, null);
        }
    }

    /** Business outcome is distinct from execution status and termination reason. @author czhcc-trsdsj\codexsandboxoffline */
    public record BusinessOutcome(String result, int evaluatedRound, long version, List<ConditionResult> conditions) {}

    /** Selects a versioned clock and bounds work at one logical instant. @author czhcc-trsdsj\codexsandboxoffline */
    public record SchedulingPolicy(int contractVersion, String mode, String timeUnit, Integer maxWorkPerTime) {
        /** Validates the frozen mode, unit and per-time budget. @author czhcc-trsdsj\codexsandboxoffline */
        public SchedulingPolicy {
            if (contractVersion != 1
                    || !List.of("TASK_DRIVEN", "EVENT_DRIVEN").contains(mode == null ? "" : mode)
                    || !"TICK".equals(timeUnit)
                    || maxWorkPerTime != null && (maxWorkPerTime < 1 || maxWorkPerTime > 100)) {
                throw new IllegalArgumentException("Invalid scheduling policy");
            }
            maxWorkPerTime = maxWorkPerTime == null ? 100 : maxWorkPerTime;
        }

        /** Identifies the opt-in strict clock contract. @author czhcc-trsdsj\codexsandboxoffline */
        public boolean eventDriven() {
            return "EVENT_DRIVEN".equals(mode);
        }
    }

    /** Freezes the deterministic prerequisites for assessing a run. @author czhcc-trsdsj\codexsandboxoffline */
    public record EvaluationPolicy(
            String mode,
            Integer earliestRound,
            Long earliestSimulationTime,
            Long horizonSimulationTime,
            Integer horizonRound,
            Boolean requireQuiescence,
            String completionMode) {
        /** Fills defaults and validates the ordering of configured evaluation points. @author czhcc-trsdsj\codexsandboxoffline */
        public EvaluationPolicy {
            earliestRound = earliestRound == null ? 0 : earliestRound;
            earliestSimulationTime = earliestSimulationTime == null ? 0L : earliestSimulationTime;
            requireQuiescence = requireQuiescence == null || requireQuiescence;
            completionMode = completionMode == null ? "QUIESCENCE" : completionMode;
            if (earliestRound < 0
                    || earliestSimulationTime < 0
                    || (horizonSimulationTime != null && horizonSimulationTime < earliestSimulationTime)
                    || (horizonRound != null && (horizonRound < 1 || horizonRound < earliestRound))
                    || !List.of("QUIESCENCE", "HORIZON", "OBJECTIVE").contains(completionMode)
                    || ("HORIZON".equals(completionMode) && horizonSimulationTime == null && horizonRound == null)
                    || (horizonRound != null && horizonSimulationTime != null)
                    || ("QUIESCENCE".equals(completionMode) && !requireQuiescence)
                    || (mode != null && !List.of("TEXT_ONLY", "CONDITIONS").contains(mode))) {
                throw new IllegalArgumentException("Invalid evaluation policy");
            }
        }
    }

    /** Scenario configuration frozen separately from mutable execution state. @author czhcc-trsdsj\codexsandboxoffline */
    public record Scenario(
            String id,
            String name,
            String worldId,
            List<AgentDefinition> agents,
            int maxSteps,
            int maxEvents,
            TeamConfig team,
            String objective,

            @JsonInclude(JsonInclude.Include.NON_NULL) SchedulingPolicy scheduling,

            @JsonInclude(JsonInclude.Include.NON_NULL) EvaluationPolicy evaluationPolicy,

            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> ruleIds,

            @JsonInclude(JsonInclude.Include.NON_NULL) ExpansionPolicy expansionPolicy) {
        /** 保留阶段四场景构造接口。 @author czhcc-trsdsj\codexsandboxoffline */
        public Scenario(
                String id,
                String name,
                String worldId,
                List<AgentDefinition> agents,
                int maxSteps,
                int maxEvents,
                TeamConfig team,
                String objective,
                SchedulingPolicy scheduling,
                EvaluationPolicy evaluationPolicy,
                List<String> ruleIds) {
            this(
                    id,
                    name,
                    worldId,
                    agents,
                    maxSteps,
                    maxEvents,
                    team,
                    objective,
                    scheduling,
                    evaluationPolicy,
                    ruleIds,
                    null);
        }

        /** Accepts the phase-three scheduling and evaluation configuration. @author czhcc-trsdsj\codexsandboxoffline */
        public Scenario(
                String id,
                String name,
                String worldId,
                List<AgentDefinition> agents,
                int maxSteps,
                int maxEvents,
                TeamConfig team,
                String objective,
                SchedulingPolicy scheduling,
                EvaluationPolicy evaluationPolicy) {
            this(id, name, worldId, agents, maxSteps, maxEvents, team, objective, scheduling, evaluationPolicy, null);
        }
        /** Preserves existing scenario callers and their legacy clock semantics. @author czhcc-trsdsj\codexsandboxoffline */
        public Scenario(
                String id,
                String name,
                String worldId,
                List<AgentDefinition> agents,
                int maxSteps,
                int maxEvents,
                TeamConfig team,
                String objective) {
            this(id, name, worldId, agents, maxSteps, maxEvents, team, objective, null, null);
        }

        /** Checks the frozen scheduling contract. @author czhcc-trsdsj\codexsandboxoffline */
        public boolean eventDriven() {
            return scheduling != null && scheduling.eventDriven();
        }
        /** Preserves the original hierarchy scenario protocol. @author czhcc-trsdsj\codexsandboxoffline */
        public Scenario(
                String id,
                String name,
                String worldId,
                List<AgentDefinition> agents,
                int maxSteps,
                int maxEvents,
                TeamConfig team) {
            this(id, name, worldId, agents, maxSteps, maxEvents, team, null);
        }
        /** Keeps the original scene protocol and legacy execution mode. @author czhcc-trsdsj\codexsandboxoffline */
        public Scenario(
                String id, String name, String worldId, List<AgentDefinition> agents, int maxSteps, int maxEvents) {
            this(id, name, worldId, agents, maxSteps, maxEvents, null);
        }
    }

    /** An action proposal is untrusted until the world engine validates it. @author agent */
    public record Decision(
            String actionType, String targetId, Map<String, Object> parameters, String reason, String targetKind) {
        /** Accepts persisted legacy decisions without a target kind. @author czhcc-trsdsj\codexsandboxoffline */
        public Decision(String actionType, String targetId, Map<String, Object> parameters, String reason) {
            this(actionType, targetId, parameters, reason, null);
        }
    }

    /** Agent-visible state and recent public action feedback. @author agent */
    public record Observation(
            String runId,
            long simulationTime,
            long version,
            List<Entity> entities,
            List<Relation> relations,
            List<String> feedback,
            List<Map<String, Object>> visibleEvents,
            List<ActionCapability> availableActions,
            String domainCode) {
        /** Accepts observations constructed before domain-specific decision protocols. @author czhcc-trsdsj\codexsandboxoffline */
        public Observation(
                String runId,
                long simulationTime,
                long version,
                List<Entity> entities,
                List<Relation> relations,
                List<String> feedback,
                List<Map<String, Object>> visibleEvents,
                List<ActionCapability> availableActions) {
            this(runId, simulationTime, version, entities, relations, feedback, visibleEvents, availableActions, "");
        }
        /** Accepts legacy observations and non-object-action domains. @author czhcc-trsdsj\codexsandboxoffline */
        public Observation(
                String runId,
                long simulationTime,
                long version,
                List<Entity> entities,
                List<Relation> relations,
                List<String> feedback,
                List<Map<String, Object>> visibleEvents) {
            this(runId, simulationTime, version, entities, relations, feedback, visibleEvents, List.of());
        }
        /**
         * Creates an observation without injected events for connection/protocol tests. @author agent
         */
        public Observation(
                String runId,
                long simulationTime,
                long version,
                List<Entity> entities,
                List<Relation> relations,
                List<String> feedback) {
            this(runId, simulationTime, version, entities, relations, feedback, List.of());
        }
    }

    /** Validated, delayed user event; effects remain subject to the World Engine. @author agent */
    public record ScheduledInput(
            String eventType,
            String targetId,
            long simulationTime,
            Map<String, Object> parameters,

            @JsonInclude(JsonInclude.Include.NON_NULL) Integer priority) {
        /** Keeps the original scheduled input shape when no priority is supplied. @author czhcc-trsdsj\codexsandboxoffline */
        public ScheduledInput(String eventType, String targetId, long simulationTime, Map<String, Object> parameters) {
            this(eventType, targetId, simulationTime, parameters, null);
        }
    }

    /** Isolated runtime state. @author agent */
    public record State(
            String runId, long simulationTime, long version, List<Entity> entities, List<Relation> relations) {}

    /** Persisted lifecycle metadata. @author agent */
    public record Run(
            String id,
            String scenarioId,
            String status,
            int step,
            int maxSteps,
            long version,
            long activeRuntimeMs,
            String terminationReason,
            String objective) {
        /** Keeps run listings and callers that predate configured objectives. @author czhcc-trsdsj\codexsandboxoffline */
        public Run(
                String id,
                String scenarioId,
                String status,
                int step,
                int maxSteps,
                long version,
                long activeRuntimeMs,
                String terminationReason) {
            this(id, scenarioId, status, step, maxSteps, version, activeRuntimeMs, terminationReason, null);
        }
        /** Accepts metadata created before active timing and explicit termination. @author czhcc-trsdsj\czhcc */
        public Run(String id, String scenarioId, String status, int step, int maxSteps, long version) {
            this(id, scenarioId, status, step, maxSteps, version, 0, null);
        }
    }

    /** Append-only event with a per-run sequence number. @author agent */
    public record Event(
            String runId,
            long sequence,
            String eventType,
            long simulationTime,
            String agentId,
            Map<String, Object> payload) {}

    /** Validated execution result, never an unvalidated LLM state patch. @author agent */
    public record Outcome(
            boolean accepted,
            String message,
            State state,
            boolean changed,
            String code,
            List<String> affectedRelationIds) {
        /** Preserves domain results without relation changes. @author czhcc-trsdsj\codexsandboxoffline */
        public Outcome(boolean accepted, String message, State state, boolean changed, String code) {
            this(accepted, message, state, changed, code, List.of());
        }
        /** Preserves other domain plugins' original result contract. @author czhcc-trsdsj\codexsandboxoffline */
        public Outcome(boolean accepted, String message, State state, boolean changed) {
            this(accepted, message, state, changed, accepted ? "ACTION_ACCEPTED" : "ACTION_REJECTED");
        }
    }
}
