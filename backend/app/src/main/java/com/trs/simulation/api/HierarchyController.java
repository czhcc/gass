package com.trs.simulation.api;

import com.trs.simulation.SimulationTypes.BusinessOutcome;
import com.trs.simulation.SimulationTypes.ObjectiveDraft;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.evaluation.ObjectiveDraftService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.scenario.ScenarioProfiles;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated configuration and trace endpoints for the shared hierarchy. @author czhcc-trsdsj\codexsandboxoffline */
@RestController
@RequestMapping("/api")
public class HierarchyController {
    private final ScenarioProfiles profiles;
    private final RunService runs;
    private final ObjectiveDraftService drafts;
    /** Injects declarative profiles and runtime readers. @author czhcc-trsdsj\codexsandboxoffline */
    public HierarchyController(ScenarioProfiles profiles, RunService runs, ObjectiveDraftService drafts) {
        this.profiles = profiles;
        this.runs = runs;
        this.drafts = drafts;
    }

    /** Input for generating editable goal conditions. @author czhcc-trsdsj\codexsandboxoffline */
    public record DraftRequest(String worldId, String modelId, String objective, int maxSteps) {}

    /** Generates a draft without saving it or invoking simulation actions. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/scenario-objectives/draft")
    public ObjectiveDraft draft(@RequestBody DraftRequest request) {
        return drafts.generate(request.worldId(), request.modelId(), request.objective(), request.maxSteps());
    }
    /** Lists reusable role, tool, prompt and outcome configurations. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/scenario-profiles")
    public List<ScenarioProfiles.Profile> profiles() {
        return profiles.list();
    }
    /** Seeds the profile's input against the frozen selected world without hard-coded object IDs. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/runs/{id}/initial-input")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void initialInput(@PathVariable String id) {
        runs.seedInput(id, profiles);
    }
    /** Lists authoritative parent/child tasks. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/runs/{id}/tasks")
    public List<TaskRecord> tasks(@PathVariable String id) {
        return runs.tasks(id);
    }
    /** Returns the engine's business evaluation separately from lifecycle status. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/runs/{id}/outcome")
    public BusinessOutcome outcome(@PathVariable String id) {
        return runs.outcome(id);
    }
    /** Returns shared model reservations across all roles. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/runs/{id}/usage")
    public Map<String, Object> usage(@PathVariable String id) {
        return runs.usage(id);
    }
}
