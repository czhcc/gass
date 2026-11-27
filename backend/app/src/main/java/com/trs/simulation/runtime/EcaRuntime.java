package com.trs.simulation.runtime;

import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.common.Json;
import com.trs.simulation.domain.DomainEnvironment;
import com.trs.simulation.rule.RuleEvaluation;
import com.trs.simulation.rule.TriggerRule;
import com.trs.simulation.state.OntologyConstraints;
import com.trs.simulation.state.StateDiff;
import com.trs.simulation.state.VisibilityPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Converts committed changes to bounded queue work and adjudicates due rule Actions. @author czhcc-trsdsj\codexsandboxoffline */
final class EcaRuntime {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_FAN_OUT = 32;
    private static final int MAX_RULE_WORK = 512;
    private final RunStateRepository store;
    private final EventClockScheduler queue;
    private final ActionCommitService commits;
    private final Map<String, DomainEnvironment> domains;

    EcaRuntime(
            RunStateRepository store,
            EventClockScheduler queue,
            ActionCommitService commits,
            Map<String, DomainEnvironment> domains) {
        this.store = store;
        this.queue = queue;
        this.commits = commits;
        this.domains = domains;
    }

    /** Describes completion or a budget boundary that leaves the current item pending. @author czhcc-trsdsj\codexsandboxoffline */
    record Delivery(String workStatus, Long completedSequence, String limit) {
        static Delivery completed() {
            return new Delivery("COMPLETED", null, null);
        }

        static Delivery limited(String reason) {
            return new Delivery(null, null, reason);
        }
    }

    /** Stores the planned queue identity and its frozen action. @author czhcc-trsdsj\codexsandboxoffline */
    private record Pending(String key, long due, int priority, RuleWork work) {}

    /** Records matching outcomes without exposing condition inputs. @author czhcc-trsdsj\codexsandboxoffline */
    private record Match(TriggerRule rule, String reason, int count) {}

    Delivery consume(String id, long time, Scenario scene, Map<String, Object> payload) {
        if (payload.get("sourceEventRef") == null) return Delivery.completed();
        var ref = as(payload.get("sourceEventRef"), RuleWork.EventRef.class);
        var source = store.event(ref);
        if (!"WORLD_STATE_CHANGED".equals(source.eventType()))
            throw new IllegalStateException("Rule source is not a committed state change");
        var before = as(source.payload().get("before"), State.class);
        var after = as(source.payload().get("after"), State.class);
        int depth = source.payload().get("causeDepth") instanceof Number n ? n.intValue() + 1 : 1;
        String correlation = Objects.toString(
                source.payload().getOrDefault("correlationId", source.payload().get("actionRunId")));
        var pending = new LinkedHashMap<String, Pending>();
        var matches = new ArrayList<Match>();
        for (var rule : queue.frozenRules(id)) {
            if (!rule.enabled()) continue;
            if (!rule.allowedOrigins().contains(source.payload().get("actionOrigin"))) {
                matches.add(new Match(rule, "ORIGIN_FILTERED", 0));
                continue;
            }
            var left = RuleEvaluation.project(rule, before);
            var right = RuleEvaluation.project(rule, after);
            var current = RuleEvaluation.project(rule, store.state(id));
            var planned = new ArrayList<Pending>();
            try {
                for (var change : matchingChanges(rule, source, left, right)) {
                    for (var response : RuleEvaluation.plan(rule, right, current, change)) {
                        long due = Math.addExact(time, response.delay());
                        var cause = new RuleWork.Cause(ref, rule.id(), rule.revision(), depth, correlation);
                        var work = new RuleWork(cause, response.targetType(), response.decision());
                        String key = key(work, due);
                        planned.add(new Pending(key, due, rule.priority(), work));
                    }
                }
            } catch (IllegalArgumentException | ArithmeticException failure) {
                matches.add(new Match(rule, "RULE_EVALUATION_FAILED", 0));
                continue;
            }
            matches.add(new Match(rule, planned.isEmpty() ? "NO_MATCH_OR_TARGET" : "MATCHED", planned.size()));
            planned.forEach(work -> pending.putIfAbsent(work.key(), work));
        }
        pending.entrySet().removeIf(entry -> queue.workExists(id, entry.getKey()));
        if (!pending.isEmpty() && depth > MAX_DEPTH) return Delivery.limited("MAX_RULE_DEPTH");
        if (pending.size() > MAX_FAN_OUT) return Delivery.limited("MAX_RULE_FAN_OUT");
        if (queue.ruleWorkCount(id) + pending.size() > MAX_RULE_WORK) return Delivery.limited("MAX_RULE_WORK");
        if (store.eventCount(id) + matches.size() + pending.size() + 1 > scene.maxEvents() - 10)
            return Delivery.limited("MAX_EVENTS");
        for (var match : matches) {
            store.append(
                    id,
                    "RULE_MATCH_RECORDED",
                    time,
                    "rule:" + match.rule().id(),
                    Map.of(
                            "causeEventRef",
                            ref,
                            "ruleId",
                            match.rule().id(),
                            "ruleRevision",
                            match.rule().revision(),
                            "code",
                            match.reason(),
                            "matchCount",
                            match.count(),
                            "actionOrigin",
                            "SYSTEM_RULE"));
        }
        pending.values()
                .forEach(work -> queue.scheduleRuleWork(id, work.key(), work.due(), work.priority(), work.work()));
        return Delivery.completed();
    }

    Delivery execute(String id, String key, long time, Scenario scene, RuleWork work) {
        if (store.eventCount(id) + 8 > scene.maxEvents() - 10) return Delivery.limited("MAX_EVENTS");
        var current = store.state(id);
        var world = store.frozenWorld(id);
        var rule = queue.frozenRules(id).stream()
                .filter(r -> r.id().equals(work.cause().ruleId())
                        && r.revision() == work.cause().ruleRevision())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Frozen rule revision is missing"));
        var actor = RuleEvaluation.actor(rule);
        var request = work.decision();
        var command = new ActionCommitService.Command(
                id, key, actor.id(), current.version(), request, "SYSTEM_RULE", null, null, work.cause());
        var prior = commits.retry(command);
        if (prior != null) return completion(prior);
        var scope = VisibilityPolicy.readableIds(actor, current);
        var plugin = domains.get(world.domainCode());
        var proposal = plugin.normalizeDecision(request, current);
        Outcome outcome;
        if (!rule.enabled()
                || !rule.grant().targetTypes().contains(work.targetType())
                || !rule.grant().allowedActions().contains(proposal.actionType())
                || !scope.contains(proposal.targetId())
                || current.entities().stream()
                        .noneMatch(e ->
                                e.id().equals(proposal.targetId()) && e.type().equals(work.targetType()))
                || !VisibilityPolicy.referencesVisible(
                        proposal.parameters(), VisibilityPolicy.hiddenIds(current, scope))) {
            outcome = reject(current, "RULE_ACTION_FORBIDDEN");
        } else {
            try {
                outcome = plugin.execute(world, actor, proposal, current);
                if (outcome.accepted()
                        && !StateDiff.between(current, outcome.state()).isEmpty())
                    OntologyConstraints.validateCandidate(world, current, outcome.state());
            } catch (IllegalArgumentException failure) {
                outcome = reject(current, "RULE_ACTION_INVALID_CANDIDATE");
            }
        }
        var result = commits.commit(command, proposal, current, outcome);
        store.append(
                id,
                "RULE_ACTION_PROCESSED",
                time,
                actor.id(),
                Map.of(
                        "scheduledEventId",
                        key,
                        "actionRunId",
                        result.details().get("actionRunId"),
                        "ruleId",
                        rule.id(),
                        "ruleRevision",
                        rule.revision(),
                        "causeEventRef",
                        work.cause().causeEventRef(),
                        "accepted",
                        result.details().get("accepted"),
                        "code",
                        result.details().get("code"),
                        "actionOrigin",
                        "SYSTEM_RULE"));
        return completion(result);
    }

    private Delivery completion(ActionCommitService.Receipt receipt) {
        return new Delivery(
                Boolean.TRUE.equals(receipt.details().get("accepted")) ? "COMPLETED" : "FAILED",
                ((Number) receipt.details().get("effectSequence")).longValue(),
                null);
    }

    private Outcome reject(State state, String code) {
        return new Outcome(
                false,
                "Rule Action rejected by current authorization or world constraints",
                state,
                false,
                code,
                List.of());
    }

    private List<Map<String, Object>> matchingChanges(TriggerRule rule, Event source, State before, State after) {
        var result = new ArrayList<Map<String, Object>>();
        for (var change : StateDiff.between(before, after)) {
            var trigger = rule.trigger();
            boolean entity = "ENTITY".equals(change.kind());
            String type = entity
                    ? after.entities().stream()
                            .filter(e -> e.id().equals(change.id()))
                            .map(e -> e.type())
                            .findFirst()
                            .orElse("")
                    : after.relations().stream()
                            .filter(r -> r.id().equals(change.id()))
                            .map(r -> r.relationType())
                            .findFirst()
                            .orElse("");
            if (!type.equals(trigger.objectType())) continue;
            if (("ENTITY_CREATED".equals(trigger.eventType()) && entity
                                    || "RELATION_CREATED".equals(trigger.eventType()) && !entity)
                            && "CREATED".equals(change.operation())
                    || "DOMAIN_EVENT".equals(trigger.eventType()) && entity) {
                result.add(event(trigger.eventType(), type, change.id(), "", null, null, source));
                continue;
            }
            if (!"UPDATED".equals(change.operation())) continue;
            for (var field : change.fields()) {
                if (entity
                                && "PROPERTY_CHANGED".equals(trigger.eventType())
                                && field.code().equals("properties." + trigger.property())
                        || !entity
                                && "RELATION_PROPERTY_CHANGED".equals(trigger.eventType())
                                && field.code().equals("properties." + trigger.property())
                        || !entity
                                && "enabled".equals(field.code())
                                && field.after() instanceof Boolean enabled
                                && trigger.eventType().equals(enabled ? "RELATION_ENABLED" : "RELATION_DISABLED")) {
                    result.add(event(
                            trigger.eventType(),
                            type,
                            change.id(),
                            trigger.property(),
                            field.before(),
                            field.after(),
                            source));
                }
            }
        }
        return result;
    }

    private Map<String, Object> event(
            String kind, String type, String id, String property, Object oldValue, Object newValue, Event source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("eventType", kind);
        result.put("objectType", type);
        result.put("objectId", id);
        result.put("property", property == null ? "" : property);
        result.put("oldValue", oldValue);
        result.put("newValue", newValue);
        result.put("origin", source.payload().get("actionOrigin"));
        result.put("simulationTime", source.simulationTime());
        result.put("inputVersion", source.payload().get("inputVersion"));
        result.put("outputVersion", source.payload().get("outputVersion"));
        return result;
    }

    private String key(RuleWork work, long due) {
        var cause = work.cause();
        var decision = work.decision();
        String identity = Json.write(List.of(
                cause.causeEventRef(),
                cause.ruleId(),
                cause.ruleRevision(),
                decision.actionType(),
                decision.targetId(),
                canonical(decision.parameters()),
                due));
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "auto:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> fields) {
            var result = new TreeMap<String, Object>();
            fields.forEach((k, v) -> result.put(k.toString(), canonical(v)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(this::canonical).toList();
        return value instanceof Number n ? new BigDecimal(n.toString()).stripTrailingZeros() : value;
    }

    private <T> T as(Object value, Class<T> type) {
        return Json.read(Json.write(value), type);
    }
}
