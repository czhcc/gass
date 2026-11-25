package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.SimulationTypes.TeamConfig;
import com.trs.simulation.evidence.TaskEvidence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Derives SDK tool schemas from the authorized, executable Action bindings of a task. @author czhcc-trsdsj\codexsandboxoffline */
final class ActionToolSchemas {
    private ActionToolSchemas() {}

    /** Exact tool contract exposed to the model for one Action type. @author czhcc-trsdsj\codexsandboxoffline */
    record Submission(String actionType, String description, Map<String, Object> schema, boolean inputBound) {}

    /** A parameter contract and its event choices, shared by equivalent targets. @author czhcc-trsdsj\codexsandboxoffline */
    private record Form(Map<String, Object> parameters, List<String> inputIds) {}

    static String toolName(String actionType) {
        String base = "submit_" + actionType.toLowerCase(Locale.ROOT);
        if (base.length() <= 64) return base;
        String digest = UUID.nameUUIDFromBytes(base.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return base.substring(0, 47) + "_" + digest.substring(0, 16);
    }

    static List<Submission> submissions(HierarchyHost host, AgentDefinition agent, TaskRequest task) {
        var capabilities = host.capabilities(agent).stream()
                .filter(ActionCapability::available)
                .collect(Collectors.groupingBy(c -> c.action().actionType(), TreeMap::new, Collectors.toList()));
        var team = host.scenario().team();
        long version = host.state().version();
        var pending = capabilities.keySet().stream().anyMatch(action -> TaskEvidence.requiresInput(team, action))
                ? pendingInputs(host.history(), task.inputEventId())
                : List.<Event>of();
        var submissions = new ArrayList<Submission>();
        capabilities.forEach((action, targets) -> {
            boolean bound = TaskEvidence.requiresInput(team, action);
            var forms = new LinkedHashMap<Form, List<String>>();
            for (var target : targets) {
                var inputIds = bound ? inputIds(team, action, target.targetId(), pending) : List.<String>of();
                if (bound && inputIds.isEmpty()) continue;
                var parameters = parameters(target);
                if (parameters != null)
                    forms.computeIfAbsent(new Form(parameters, inputIds), unused -> new ArrayList<>())
                            .add(target.targetId());
            }
            if (!forms.isEmpty()) {
                var definition = targets.getFirst().action();
                String description = definition.nameEn() + ". " + definition.description();
                if (bound) description += " Match target and parameters exactly to the selected delivered input.";
                submissions.add(new Submission(
                        action,
                        description.substring(0, Math.min(1024, description.length())),
                        submissionSchema(forms, version, bound),
                        bound));
            }
        });
        return List.copyOf(submissions);
    }

    private static List<Event> pendingInputs(List<Event> history, String preselected) {
        var handled = TaskEvidence.handledInputs(history);
        return history.stream()
                .filter(e -> "SIMULATION_EVENT_CREATED".equals(e.eventType()))
                .filter(e -> e.payload().get("scheduledEventId") instanceof String id
                        && !handled.contains(id)
                        && (preselected == null || preselected.equals(id)))
                .toList();
    }

    private static List<String> inputIds(TeamConfig team, String action, String target, List<Event> events) {
        return events.stream()
                .filter(e ->
                        target.equals(e.payload().get("targetId")) && TaskEvidence.matchesInputType(team, action, e))
                .map(e -> (String) e.payload().get("scheduledEventId"))
                .distinct()
                .sorted()
                .toList();
    }

    private static Map<String, Object> parameters(ActionCapability capability) {
        var definition = capability.action().parametersSchema();
        var fields = (Map<?, ?>) definition.get("properties");
        var required = (List<?>) definition.getOrDefault("required", List.of());
        var properties = new LinkedHashMap<String, Object>();
        for (var entry : fields.entrySet()) {
            String name = (String) entry.getKey();
            var source = (Map<?, ?>) entry.getValue();
            var schema = new LinkedHashMap<String, Object>();
            schema.put("type", source.get("type"));
            if (source.containsKey("description")) schema.put("description", source.get("description"));
            if (source.containsKey("enum")) schema.put("enum", source.get("enum"));
            var options = capability.parameterOptions().get(name);
            if (options != null) {
                var choices = options.stream()
                        .filter(id -> !(source.get("enum") instanceof List<?> values) || values.contains(id))
                        .toList();
                if (choices.isEmpty()) {
                    if (required.contains(name)) return null;
                    continue;
                }
                schema.put("enum", choices);
            }
            properties.put(name, schema);
        }
        return object(properties, required);
    }

    private static Map<String, Object> submissionSchema(Map<Form, List<String>> forms, long version, boolean bound) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(
                "targetId",
                choices(forms.values().stream()
                        .flatMap(List::stream)
                        .distinct()
                        .sorted()
                        .toList()));
        fields.put(
                "parameters",
                forms.size() == 1 ? forms.keySet().iterator().next().parameters() : Map.of("type", "object"));
        fields.put(
                "expectedVersion",
                Map.of("type", "integer", "enum", List.of(version), "description", "Graph version for these choices"));
        fields.put("reason", Map.of("type", "string", "maxLength", 1000, "description", "Short public reason"));
        if (bound)
            fields.put(
                    "inputEventId",
                    choices(forms.keySet().stream()
                            .flatMap(f -> f.inputIds().stream())
                            .distinct()
                            .sorted()
                            .toList()));
        var schema = new LinkedHashMap<>(object(fields, List.copyOf(fields.keySet())));
        // Exactly one branch can match because the groups have disjoint target IDs.
        if (forms.size() > 1)
            schema.put(
                    "oneOf",
                    forms.entrySet().stream()
                            .map(entry -> {
                                var constraints = new LinkedHashMap<String, Object>();
                                constraints.put("targetId", choices(entry.getValue()));
                                constraints.put("parameters", entry.getKey().parameters());
                                if (bound)
                                    constraints.put(
                                            "inputEventId",
                                            choices(entry.getKey().inputIds()));
                                return Map.of("properties", constraints);
                            })
                            .toList());
        return schema;
    }

    static Map<String, Object> object(Map<String, ?> properties, List<?> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    static Map<String, Object> choices(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }
}
