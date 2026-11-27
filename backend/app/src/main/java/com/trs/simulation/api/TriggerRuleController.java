package com.trs.simulation.api;

import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.rule.RuleEvaluation;
import com.trs.simulation.rule.TriggerRule;
import com.trs.simulation.rule.TriggerRuleCatalog;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.world.WorldService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated rule management and pure preview endpoints. @author czhcc-trsdsj\codexsandboxoffline */
@RestController
@RequestMapping("/api")
public final class TriggerRuleController {
    private final TriggerRuleCatalog rules;
    private final WorldService worlds;
    private final RunService runs;

    /** Injects the catalog, world reader and frozen run reader. @author czhcc-trsdsj\codexsandboxoffline */
    public TriggerRuleController(TriggerRuleCatalog rules, WorldService worlds, RunService runs) {
        this.rules = rules;
        this.worlds = worlds;
        this.runs = runs;
    }

    /** Lists editable drafts. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/rules")
    public List<TriggerRule> drafts(@RequestParam String worldId) {
        return rules.drafts(worlds.get(worldId).id());
    }

    /** Lists the latest published revisions for scenario binding. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/rules/published")
    public List<TriggerRule> published(@RequestParam String worldId) {
        return rules.published(worlds.get(worldId).id());
    }

    /** Creates an editable draft. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/rules")
    public TriggerRule create(@RequestBody TriggerRule rule) {
        return rules.save(worlds.get(rule.worldId()), null, rule);
    }

    /** Updates the specified draft while keeping its published revisions. @author czhcc-trsdsj\codexsandboxoffline */
    @PutMapping("/rules/{id}")
    public TriggerRule update(@PathVariable String id, @RequestBody TriggerRule rule) {
        return rules.save(worlds.get(rule.worldId()), id, rule);
    }

    /** Confirms that a draft is valid for the named world. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/rules/validate")
    public Map<String, Object> validate(@RequestBody TriggerRule rule) {
        rules.validate(worlds.get(rule.worldId()), rule);
        return Map.of("valid", true);
    }

    /** Evaluates a hypothetical event against the current world template. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/rules/preview")
    public Map<String, Object> preview(@RequestBody Preview request) {
        if (request == null || request.rule() == null) throw new IllegalArgumentException("Rule and event required");
        var world = worlds.get(request.rule().worldId());
        rules.validate(world, request.rule());
        var state = new State("preview", 0, 0, world.entities(), world.relations());
        return Map.of("plans", RuleEvaluation.preview(request.rule(), state, request.event()));
    }

    /** Publishes one immutable revision. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/rules/{id}/publish")
    public TriggerRule publish(@PathVariable String id) {
        return rules.publish(worlds.get(rules.draft(id).worldId()), id);
    }

    /** Reads the rule revision history. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/rules/{id}/revisions")
    public List<TriggerRule> revisions(@PathVariable String id) {
        return rules.revisions(id);
    }

    /** Reads the exact rules frozen into a run or branch. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/runs/{id}/rules")
    public Map<String, Object> frozen(@PathVariable String id) {
        return Map.of("rules", runs.frozenRules(id));
    }

    /** A hypothetical event against an editable rule. @author czhcc-trsdsj\codexsandboxoffline */
    public record Preview(TriggerRule rule, Map<String, Object> event) {}
}
