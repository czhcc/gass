package com.trs.simulation.evidence;

import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.ExternalExistence;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import com.trs.simulation.evidence.ObjectiveEvidence.CompletionCheck;
import com.trs.simulation.evidence.ObjectiveEvidence.GoalScope;
import com.trs.simulation.graph.KnowledgeCoverage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks the frozen objective against current, authorized evidence. @author czhcc-trsdsj\codexsandboxoffline */
public final class ObjectiveProof {
    // Compatibility for a narrow legacy observation request. The temporal suffix is optional;
    // scope comes from the single subject and must be grounded in the frozen run before acceptance.
    private static final Pattern OBSERVATION = Pattern.compile(
            "(?iu)\\A(?:reassess|inspect|review|observe|check|describe)\\s+the\\s+([\\p{L}\\p{N}_-]+)"
                    + "(?:\\s+if\\s+it\\s+changes)?(?:\\s+and\\s+wait\\s+until\\s+(?:simulation\\s+)?tick\\s+[0-9]+)?[.!]?\\z");
    private static final Pattern BOUNDED_RANKING =
            Pattern.compile("(?iu)\\Arank\\s+(?:the\\s+)?paths\\s+(?:in|within)\\s+the\\s+"
                    + "(?:current\\s+(?:authorized\\s+|local\\s+)?|local\\s+|authorized\\s+)"
                    + "(?:run(?:\\s+graph)?|graph|subgraph)[.!]?\\z");

    private ObjectiveProof() {}

    /** A consistent view of the run and exact permissions of its root reviewer. @author czhcc-trsdsj\codexsandboxoffline */
    public record Context(
            Scenario scene,
            State initial,
            State current,
            Set<String> visible,
            String agentId,
            KnowledgeCoverage coverage) {
        boolean wholeRunVisible() {
            return current.entities().stream().allMatch(entity -> visible.contains(entity.id()))
                    && initial.entities().stream().allMatch(entity -> visible.contains(entity.id()));
        }
    }

    /** Outcome of proof checking, with a server-authored description limited to that proof. @author czhcc-trsdsj\codexsandboxoffline */
    public record Assessment(CompletionCheck check, String summary) {}

    /** Admits only explicitly bounded syntax for unstructured expansion objectives. @author czhcc-trsdsj\codexsandboxoffline */
    static boolean declaresLocalRequest(String objective) {
        return observationSubject(objective) != null
                || BOUNDED_RANKING.matcher(objective.strip()).matches();
    }

    static ExternalExistence externalExistence(Scenario scene) {
        return scene.evaluationPolicy() == null
                ? null
                : scene.evaluationPolicy().externalExistence();
    }

    private static String observationSubject(String objective) {
        if (objective == null) {
            return null;
        }
        var matcher = OBSERVATION.matcher(objective.strip());
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static boolean needsGrounding(Scenario scene) {
        return scene.expansionPolicy() != null
                && (scene.evaluationPolicy() == null || scene.evaluationPolicy().objectiveScope() == null)
                && observationSubject(scene.objective()) != null;
    }

    private static String groundedSubject(Context context) {
        var subject = observationSubject(context.scene().objective());
        var matches = context.initial().entities().stream()
                .filter(entity -> subject.equalsIgnoreCase(entity.id())
                        || subject.equalsIgnoreCase(entity.type())
                        || subject.equalsIgnoreCase(entity.name()))
                .toList();
        return matches.size() == 1
                        && context.visible().contains(matches.getFirst().id())
                ? matches.getFirst().id()
                : null;
    }

    /** Revalidates selected persisted events against the frozen requirement without interpreting the proposed answer. @author czhcc-trsdsj\codexsandboxoffline */
    public static Assessment assess(Context context, List<Event> history, Set<Long> references) {
        var goal = ObjectiveEvidence.goalScope(context.scene());
        if (goal == GoalScope.UNDETERMINED) {
            return rejected(CompletionCheck.GOAL_SCOPE_UNDETERMINED);
        }
        var selected = history.stream()
                .filter(event ->
                        context.current().runId().equals(event.runId()) && references.contains(event.sequence()))
                .toList();
        if (references.isEmpty() || selected.size() != references.size()) {
            return rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
        }
        var external = externalExistence(context.scene());
        if (external != null) {
            if (selected.stream().anyMatch(event -> !externalWitness(context, event, external))) {
                return rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
            }
            var sourceVersion = selected.getFirst().payload().get("sourceVersion");
            return new Assessment(
                    CompletionCheck.SUPPORTED,
                    "在来源 " + external.providerCode() + " 的版本 "
                            + bounded(sourceVersion.toString(), 150) + " 中确认记录 "
                            + external.externalId() + " 存在。结论仅适用于该记录。");
        }
        for (var event : selected) {
            var check = ObjectiveEvidence.completionCheck(
                    event,
                    context.visible(),
                    context.current().version(),
                    context.wholeRunVisible(),
                    context.coverage(),
                    goal);
            if (check != CompletionCheck.SUPPORTED) {
                return rejected(check);
            }
        }
        if (needsGrounding(context.scene())) {
            var subject = groundedSubject(context);
            if (subject == null || selected.stream().noneMatch(event -> observesSubject(event, subject))) {
                return rejected(CompletionCheck.GOAL_SCOPE_UNDETERMINED);
            }
        }
        if (context.scene().expansionPolicy() != null
                && context.scene().objective() != null
                && BOUNDED_RANKING.matcher(context.scene().objective().strip()).matches()
                && selected.stream().noneMatch(ObjectiveProof::hasRanking)) {
            return rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
        }
        return new Assessment(CompletionCheck.SUPPORTED, describeLocal(selected));
    }

    private static Assessment rejected(CompletionCheck check) {
        return new Assessment(check, null);
    }

    private static boolean observesSubject(Event event, String subject) {
        return "GRAPH_QUERY_RESULT".equals(event.eventType())
                && "graph_get_entity".equals(event.payload().get("tool"))
                && event.payload().get("arguments") instanceof Map<?, ?> arguments
                && subject.equals(arguments.get("entityId"))
                && event.payload().get("result") instanceof Map<?, ?> result
                && result.get("entities") instanceof List<?> entities
                && entities.stream()
                        .anyMatch(item -> item instanceof Map<?, ?> entity && subject.equals(entity.get("id"))
                                || item instanceof Entity object && subject.equals(object.id()));
    }

    private static boolean externalWitness(Context context, Event event, ExternalExistence target) {
        var data = event.payload();
        return "EXTERNAL_GRAPH_QUERY".equals(event.eventType())
                && context.coverage() != null
                && KnowledgeCoverage.current(context.coverage(), event.runId(), data)
                && target.providerCode().equals(data.get("providerCode"))
                && target.externalId().equals(data.get("existenceWitness"))
                && Objects.equals(context.agentId(), data.get("parentAgentId"))
                && data.get("taskId") instanceof String task
                && !task.isBlank()
                && data.get("sourceVersion") instanceof String revision
                && !revision.isBlank()
                && data.get("inputVersion") instanceof Number version
                && version.longValue() == context.current().version()
                && data.get("scopeIds") instanceof List<?> ids
                && ids.stream().allMatch(context.visible()::contains);
    }

    /** Rechecks historical completion against current scope, versions and the original references. @author czhcc-trsdsj\codexsandboxoffline */
    public static Assessment recorded(Context context, List<Event> history, Event event) {
        var data = event.payload();
        if (!ObjectiveEvidence.COMPLETION_EVENT.equals(event.eventType())
                || !context.current().runId().equals(event.runId())
                || !(data.get("version") instanceof Number version)
                || version.longValue() != context.current().version()
                || !KnowledgeCoverage.current(context.coverage(), event.runId(), data)) {
            return rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
        }
        if (context.scene().expansionPolicy() == null) {
            // Older runs can contain completion audit written before all references were recorded.
            return ObjectiveEvidence.supportsRecordedCompletion(context.scene(), event)
                    ? new Assessment(CompletionCheck.SUPPORTED, Objects.toString(data.get("summary"), ""))
                    : rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
        }
        if (!(data.get("evidenceSequences") instanceof List<?> raw)) {
            return rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
        }
        var refs = new LinkedHashSet<Long>();
        for (var reference : raw) {
            if (!(reference instanceof Number number)) {
                return rejected(CompletionCheck.EVIDENCE_UNAVAILABLE);
            }
            refs.add(number.longValue());
        }
        return assess(context, history, refs);
    }

    private static boolean hasRanking(Event event) {
        return "GRAPH_QUERY_RESULT".equals(event.eventType())
                && event.payload().get("result") instanceof Map<?, ?> result
                && result.get("pathRanking") instanceof Map<?, ?>;
    }

    private static String describeLocal(List<Event> events) {
        for (var event : events) {
            if (!(event.payload().get("result") instanceof Map<?, ?> result)) {
                continue;
            }
            if (result.get("pathRanking") instanceof Map<?, ?> ranking
                    && ranking.get("maxCount") instanceof Number best
                    && ranking.get("paths") instanceof List<?> paths) {
                var sample = Json.write(paths.stream().limit(3).toList());
                return "在当前授权运行图谱中，路径排名的最高计数为 " + best + "，路径示例 " + bounded(sample, 700) + "。外部全量数据未验证。";
            }
            if ("graph_get_entity".equals(event.payload().get("tool"))
                    && event.payload().get("arguments") instanceof Map<?, ?> arguments
                    && arguments.get("entityId") instanceof String subject
                    && observesSubject(event, subject)) {
                return "已观测当前授权运行图谱中的对象 " + bounded(subject, 100) + "。外部全量数据未验证。";
            }
        }
        return "已核实引用记录在当前授权运行范围内的结果。外部全量数据未验证。";
    }

    private static String bounded(String text, int length) {
        return text.substring(0, Math.min(text.length(), length));
    }
}
