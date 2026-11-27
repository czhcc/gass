package com.trs.simulation.rule;

import com.trs.simulation.SimulationTypes.ActionDefinition;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.action.expression.CelExpressions;
import com.trs.simulation.common.Json;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Manages editable drafts and immutable rule revisions. @author czhcc-trsdsj\codexsandboxoffline */
@Service
public final class TriggerRuleCatalog {
    private static final List<String> ORIGINS =
            List.of("AGENT_ACTION", "USER_INTERVENTION", "SYSTEM_RULE", "SIMULATION_AGENT");
    private static final Set<String> ENTITY_EVENTS = Set.of("PROPERTY_CHANGED", "ENTITY_CREATED");
    private static final Set<String> RELATION_EVENTS =
            Set.of("RELATION_ENABLED", "RELATION_DISABLED", "RELATION_PROPERTY_CHANGED", "RELATION_CREATED");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    /** Uses the same database and transaction manager as runs. @author czhcc-trsdsj\codexsandboxoffline */
    public TriggerRuleCatalog(JdbcTemplate db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    /** Lists a world's current drafts. @author czhcc-trsdsj\codexsandboxoffline */
    public List<TriggerRule> drafts(String world) {
        return rules(
                "SELECT definition_json FROM simulation_trigger_rule_draft WHERE world_id=? ORDER BY rule_id", world);
    }

    /** Lists the most recent publication for each rule. @author czhcc-trsdsj\codexsandboxoffline */
    public List<TriggerRule> published(String world) {
        return rules("""
                SELECT r.definition_json FROM simulation_trigger_rule_revision r
                JOIN simulation_trigger_rule_draft d ON d.rule_id=r.rule_id
                WHERE d.world_id=? AND r.revision=(
                    SELECT MAX(candidate.revision) FROM simulation_trigger_rule_revision candidate
                    WHERE candidate.rule_id=r.rule_id
                ) ORDER BY r.rule_id
                """, world);
    }

    /** Reads one draft without altering its publication. @author czhcc-trsdsj\codexsandboxoffline */
    public TriggerRule draft(String id) {
        return rules("SELECT definition_json FROM simulation_trigger_rule_draft WHERE rule_id=?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Rule draft not found"));
    }

    /** Lists immutable publications newest first. @author czhcc-trsdsj\codexsandboxoffline */
    public List<TriggerRule> revisions(String id) {
        draft(id);
        return rules("""
                SELECT definition_json FROM simulation_trigger_rule_revision
                WHERE rule_id=? ORDER BY revision DESC
                """, id);
    }

    /** Stores a validated revision-independent draft. @author czhcc-trsdsj\codexsandboxoffline */
    public synchronized TriggerRule save(World world, String id, TriggerRule rule) {
        validate(world, rule);
        String key = id == null ? UUID.randomUUID().toString() : id;
        if (id != null && !world.id().equals(draft(id).worldId()))
            throw new IllegalArgumentException("A rule cannot change worlds");
        var candidate = copy(rule, key, 0);
        db.update("""
                INSERT INTO simulation_trigger_rule_draft(rule_id,world_id,definition_json) VALUES (?,?,?)
                ON CONFLICT(rule_id) DO UPDATE SET definition_json=EXCLUDED.definition_json
                """, key, world.id(), Json.write(candidate));
        return candidate;
    }

    /** Publishes a new revision and keeps all previous publications. @author czhcc-trsdsj\codexsandboxoffline */
    public synchronized TriggerRule publish(World world, String id) {
        return tx.execute(status -> {
            var draft = draft(id);
            validate(world, draft);
            int revision = db.queryForObject("""
                    SELECT COALESCE(MAX(revision),0)+1 FROM simulation_trigger_rule_revision WHERE rule_id=?
                    """, Integer.class, id);
            var published = copy(draft, id, revision);
            db.update(
                    "INSERT INTO simulation_trigger_rule_revision VALUES (?,?,?)", id, revision, Json.write(published));
            return published;
        });
    }

    /** Resolves the selected published revisions against the run's world and scope. @author czhcc-trsdsj\codexsandboxoffline */
    public List<TriggerRule> freeze(World world, Scenario scene) {
        var ids = scene.ruleIds();
        if (ids == null || ids.isEmpty()) return List.of();
        require(scene.eventDriven(), "Rules require strict event scheduling");
        require(
                ids.size() <= 16
                        && ids.stream().allMatch(id -> id != null && !id.isBlank())
                        && ids.stream().distinct().count() == ids.size(),
                "Invalid rule bindings");
        return ids.stream()
                .map(id -> {
                    var revisions = rules("""
                    SELECT definition_json FROM simulation_trigger_rule_revision
                    WHERE rule_id=? ORDER BY revision DESC LIMIT 1
                    """, id);
                    if (revisions.isEmpty())
                        throw new IllegalArgumentException("A bound rule has no published revision");
                    var rule = revisions.getFirst();
                    require(
                            !"SCENARIO".equals(rule.scope()) || scene.id().equals(rule.scopeId()),
                            "Rule is scoped to a different scenario");
                    validate(world, rule);
                    return rule;
                })
                .sorted(Comparator.comparingInt(TriggerRule::priority)
                        .reversed()
                        .thenComparing(TriggerRule::id))
                .toList();
    }

    /** Validates permissions, target contracts and bounded CEL syntax. @author czhcc-trsdsj\codexsandboxoffline */
    public void validate(World world, TriggerRule rule) {
        require(rule != null && world.id().equals(rule.worldId()), "Rule belongs to a different world");
        require(rule.name() != null && !rule.name().isBlank() && rule.name().length() <= 120, "Rule name required");
        require(
                rule.scope() != null && Set.of("TYPE", "WORLD", "SCENARIO").contains(rule.scope()),
                "Invalid rule scope");
        require(rule.priority() >= -1000 && rule.priority() <= 1000, "Rule priority is outside the allowed range");
        var trigger = rule.trigger();
        require(trigger != null && trigger.eventType() != null && trigger.objectType() != null, "Trigger required");
        boolean relation = RELATION_EVENTS.contains(trigger.eventType());
        require(
                relation
                        ? world.relationTypes().contains(trigger.objectType())
                        : (ENTITY_EVENTS.contains(trigger.eventType()) || "DOMAIN_EVENT".equals(trigger.eventType()))
                                && world.entityTypes().contains(trigger.objectType()),
                "Invalid trigger kind or object type");
        if ("TYPE".equals(rule.scope()))
            require(trigger.objectType().equals(rule.scopeId()), "Scope and trigger type differ");
        if ("SCENARIO".equals(rule.scope()))
            require(
                    rule.scopeId() != null
                            && !rule.scopeId().isBlank()
                            && db.queryForObject("""
                    SELECT COUNT(*) FROM scenario WHERE id=? AND JSON_EXTRACT(definition_json,'$.worldId')=?
                    """, Integer.class, rule.scopeId(), world.id()) == 1,
                    "Unknown rule scenario");
        require(
                !trigger.eventType().endsWith("PROPERTY_CHANGED")
                        || trigger.property() != null && trigger.property().matches("[A-Za-z_][A-Za-z0-9_]{0,79}"),
                "A property-change trigger requires a property");
        var grant = rule.grant();
        require(
                grant != null
                        && nonempty(grant.visibleEntityIds())
                        && nonempty(grant.targetTypes())
                        && nonempty(grant.allowedActions())
                        && world.entities().stream().map(e -> e.id()).toList().containsAll(grant.visibleEntityIds())
                        && world.entityTypes().containsAll(grant.targetTypes())
                        && world.ontology().types().stream()
                                .flatMap(t -> t.actions().stream())
                                .map(ActionDefinition::actionType)
                                .toList()
                                .containsAll(grant.allowedActions()),
                "Invalid rule authorization");
        require(
                rule.allowedOrigins() == null
                        || rule.allowedOrigins().stream()
                                .allMatch(origin -> origin != null && ORIGINS.contains(origin)),
                "Invalid rule event origin");
        require(
                rule.actions() != null
                        && !rule.actions().isEmpty()
                        && rule.actions().size() <= 8,
                "Requires 1 to 8 rule actions");
        require(Json.write(rule).length() <= 30000, "Rule exceeds size limit");
        CelExpressions.validateRule(rule.conditionExpression(), true);
        for (var action : rule.actions()) {
            require(
                    action != null
                            && grant.targetTypes().contains(action.targetType())
                            && grant.allowedActions().contains(action.actionType()),
                    "Rule action exceeds grant");
            var binding = world.ontology().types().stream()
                    .filter(t -> "ENTITY".equals(t.targetKind()) && t.type().equals(action.targetType()))
                    .flatMap(t -> t.actions().stream())
                    .filter(a -> a.actionType().equals(action.actionType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Action is not bound to the target type"));
            var params = action.parameterExpressions();
            require(
                    params != null
                            && binding.parametersSchema().getOrDefault("properties", Map.of())
                                    instanceof Map<?, ?> fields
                            && fields.keySet().containsAll(params.keySet())
                            && binding.parametersSchema().getOrDefault("required", List.of())
                                    instanceof List<?> required
                            && params.keySet().containsAll(required),
                    "Rule parameters do not match Action schema");
            CelExpressions.validateRule(action.targetExpression(), false);
            CelExpressions.validateRule(action.delayExpression(), false);
            if (action.delayExpression().strip().matches("-\\s*[0-9]+"))
                throw new IllegalArgumentException("Delay cannot be negative");
            params.values().forEach(expression -> CelExpressions.validateRule(expression, false));
        }
    }

    private TriggerRule copy(TriggerRule rule, String id, int revision) {
        return new TriggerRule(
                id,
                revision,
                rule.worldId(),
                rule.name().strip(),
                rule.scope(),
                rule.scopeId(),
                rule.enabled(),
                rule.priority(),
                rule.trigger(),
                rule.conditionExpression(),
                rule.actions(),
                rule.allowedOrigins() == null ? ORIGINS : List.copyOf(rule.allowedOrigins()),
                rule.grant());
    }

    private List<TriggerRule> rules(String sql, String id) {
        return db.query(sql, (row, index) -> Json.read(row.getString(1), TriggerRule.class), id);
    }

    private static boolean nonempty(List<String> list) {
        return list != null
                && !list.isEmpty()
                && list.stream().allMatch(s -> s != null && !s.isBlank())
                && list.stream().distinct().count() == list.size();
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
