package com.trs.simulation.scenario;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.AgentRuntime;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.GoalCondition;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TeamConfig;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.action.expression.CelExpressions;
import com.trs.simulation.common.Json;
import com.trs.simulation.common.context.HierarchyData;
import com.trs.simulation.graph.RunGraphReadService;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Data-driven roles, prompts and mock recipes; the orchestration engine has no domain branches. @author czhcc-trsdsj\codexsandboxoffline */
@Service
public class ScenarioProfiles {
    /** Reusable role template with a model chosen when creating a scene. @author czhcc-trsdsj\codexsandboxoffline */
    public record AgentTemplate(
            String id, String name, String goal, List<String> allowedActions, AgentRuntime runtime) {}
    /** CEL-bound input recipe for reusable worlds with arbitrary object identifiers. @author czhcc-trsdsj\codexsandboxoffline */
    public record InputRecipe(
            String eventType, Map<String, String> bindings, String target, Map<String, String> parameters) {}
    /** A scene differs by these declarations rather than by Java orchestration code. @author czhcc-trsdsj\codexsandboxoffline */
    public record Profile(
            String domainCode,
            String nameZh,
            String nameEn,
            List<AgentTemplate> agents,
            TeamConfig team,
            InputRecipe initialInput) {}

    private final List<Profile> profiles;
    /** Loads version-controlled profile data once; credentials never belong to profiles. @author czhcc-trsdsj\codexsandboxoffline */
    public ScenarioProfiles() {
        try (var stream = new ClassPathResource("scenario-profiles.json").getInputStream()) {
            profiles = List.of(Json.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Profile[].class));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load scenario profiles", e);
        }
    }
    /** Returns editable configuration templates to the authenticated UI. @author czhcc-trsdsj\codexsandboxoffline */
    public List<Profile> list() {
        return profiles;
    }
    /** Resolves a declared domain template. @author czhcc-trsdsj\codexsandboxoffline */
    public Profile get(String domain) {
        return profiles.stream()
                .filter(p -> p.domainCode().equals(domain))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No scenario profile for this domain"));
    }
    /** Builds a hierarchical scene with one model configuration and explicit runtime budgets. @author czhcc-trsdsj\codexsandboxoffline */
    public Scenario scenario(String domain, World world, String model, String name, int rounds) {
        var p = get(domain);
        var visible = world.entities().stream().map(Entity::id).toList();
        var agents = p.agents().stream()
                .map(a -> new AgentDefinition(
                        a.id(), a.name(), a.goal(), model, a.allowedActions(), visible, 20, a.runtime()))
                .toList();
        return new Scenario(null, name, world.id(), agents, rounds, 2000, p.team());
    }
    /** Evaluates a profile input against the chosen world; no IDs are hard-coded in the runtime. @author czhcc-trsdsj\codexsandboxoffline */
    public ScheduledInput initialInput(World world) {
        var recipe = get(world.domainCode()).initialInput();
        var s = new State("initial", 0, 0, world.entities(), world.relations());
        var variables = HierarchyData.variables(s, s, Map.of(), 0);
        var bindings = new LinkedHashMap<String, Object>();
        variables.put("params", bindings);
        for (var entry : recipe.bindings().entrySet())
            bindings.put(entry.getKey(), CelExpressions.evaluate(entry.getValue(), variables));
        var parameters = new LinkedHashMap<String, Object>();
        recipe.parameters()
                .forEach((key, expression) -> parameters.put(key, CelExpressions.evaluate(expression, variables)));
        return new ScheduledInput(
                recipe.eventType(), String.valueOf(CelExpressions.evaluate(recipe.target(), variables)), 0, parameters);
    }

    /** Rejects invalid roles, delegation widening and effect expressions before a run can be created. @author czhcc-trsdsj\codexsandboxoffline */
    public static void validate(Scenario scenario) {
        if (scenario.objective() != null) {
            validateObjective(scenario.objective());
            require(scenario.team() != null, "An objective requires a hierarchical scenario");
        }
        var team = scenario.team();
        if (team == null) return;
        require(
                team.rootAgentId() != null
                        && team.maxDelegationsPerRound() >= 1
                        && team.maxDelegationsPerRound() <= 8
                        && team.maxActionsPerRound() >= 1
                        && team.maxActionsPerRound() <= 4
                        && team.maxActionsPerRound() <= team.maxDelegationsPerRound(),
                "Invalid hierarchy limits");
        var root = scenario.agents().stream()
                .filter(a -> a.id().equals(team.rootAgentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Master agent missing"));
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        for (var a : scenario.agents()) {
            var r = a.runtime();
            require(
                    r != null
                            && r.role() != null
                            && Set.of("MASTER", "QUERY", "ACTION").contains(r.role()),
                    "Unknown hierarchy role");
            require(
                    r.toolName() != null
                            && r.toolName().matches("[a-z][a-z0-9_]{0,49}")
                            && names.add(r.toolName())
                            && r.description() != null
                            && !r.description().isBlank()
                            && r.description().length() <= 2000
                            && r.tools() != null,
                    "Invalid agent tool configuration");
            if (a.id().equals(root.id()))
                require(
                        r.role().equals("MASTER") && a.allowedActions().isEmpty(),
                        "Master may delegate but cannot execute business Actions");
            else {
                require(!r.role().equals("MASTER"), "Only one master is allowed");
                ids.add(a.id());
                require(RunGraphReadService.TOOLS.containsAll(r.tools()), "Unknown query tool permission");
                require(
                        root.visibleEntityIds().containsAll(a.visibleEntityIds()),
                        "Child visibility cannot exceed master visibility");
            }
            if (r.role().equals("QUERY")) require(a.allowedActions().isEmpty(), "Query agents cannot mutate objects");
            require(!a.allowedActions().contains("END"), "Hierarchy termination belongs to the execution engine");
            if (r.role().equals("QUERY")) require(!r.tools().isEmpty(), "Query roles require at least one read tool");
        }
        require(
                ids.equals(new HashSet<>(root.runtime().tools()))
                        && root.runtime().tools().size() == ids.size(),
                "Master delegates must reference every child agent exactly once");
        require(
                team.conditions() != null
                        && (!team.conditions().isEmpty() || scenario.objective() != null)
                        && team.mockRules() != null
                        && team.mockRules().size() <= 30,
                "Outcome conditions and bounded mock rules required");
        validateConditions(team.conditions());
        for (var rule : team.mockRules()) {
            require(rule != null && rule.actionType() != null, "Invalid mock policy");
            CelExpressions.validateBoolean(rule.whenExpression());
        }
        require(
                team.inputActions() != null
                        && team.inputActions().size() <= 20
                        && team.inputActions().entrySet().stream()
                                .allMatch(e -> e.getKey() != null
                                        && e.getKey().matches("[A-Z][A-Z0-9_]{0,59}")
                                        && e.getValue() != null
                                        && !e.getValue().isEmpty()
                                        && e.getValue().size() <= 20
                                        && e.getValue().stream()
                                                .allMatch(action -> scenario.agents().stream()
                                                        .anyMatch(a -> a.allowedActions()
                                                                .contains(action)))),
                "Invalid input/Action bindings");
    }

    /** Validates the saved objective text used by the scenario and draft flows. @author czhcc-trsdsj\codexsandboxoffline */
    public static void validateObjective(String objective) {
        require(
                objective != null && !objective.isBlank() && objective.length() <= 2000,
                "Objective must contain 1 to 2000 characters");
    }

    /** Validates condition identities, labels and CEL expressions. @author czhcc-trsdsj\codexsandboxoffline */
    public static void validateConditions(List<GoalCondition> conditions) {
        require(conditions != null && conditions.size() <= 10, "At most ten outcome conditions are supported");
        var ids = new HashSet<String>();
        for (var condition : conditions) {
            require(
                    condition != null
                            && condition.id() != null
                            && !condition.id().isBlank()
                            && condition.id().length() <= 100
                            && ids.add(condition.id())
                            && condition.nameZh() != null
                            && !condition.nameZh().isBlank()
                            && condition.nameZh().length() <= 200
                            && condition.nameEn() != null
                            && !condition.nameEn().isBlank()
                            && condition.nameEn().length() <= 200,
                    "Outcome conditions require unique IDs and labels");
            CelExpressions.validateBoolean(condition.expression());
            if (condition.evidenceExpression() != null
                    && !condition.evidenceExpression().isBlank())
                CelExpressions.validate(condition.evidenceExpression());
        }
    }

    private static void require(boolean test, String message) {
        if (!test) throw new IllegalArgumentException(message);
    }
}
