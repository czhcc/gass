package com.trs.simulation;

import com.trs.simulation.SimulationTypes.Run;
import org.springframework.stereotype.Service;

/** Creates both demo domains through the same configured hierarchy. @author czhcc-trsdsj\codexsandboxoffline */
@Service
public class DemoService {
    private final WorldService worlds;
    private final ModelService models;
    private final RunService runs;
    private final ScenarioProfiles profiles;
    /** Injects reusable profile and runtime services. @author czhcc-trsdsj\codexsandboxoffline */
    public DemoService(WorldService worlds, ModelService models, RunService runs, ScenarioProfiles profiles) {
        this.worlds = worlds;
        this.models = models;
        this.runs = runs;
        this.profiles = profiles;
    }
    /** Creates a credential-free paused demo with an explicit evaluation horizon. @author czhcc-trsdsj\codexsandboxoffline */
    public Run create(String domain, String worldId, int maxSteps) {
        if (maxSteps < 1 || maxSteps > 20)
            throw new IllegalArgumentException("Maximum simulation rounds must be an integer from 1 to 20");
        profiles.get(domain);
        if (worldId == null || worldId.isBlank())
            throw new IllegalArgumentException("Select an existing world for the demo");
        var world = worlds.get(worldId);
        if (!domain.equals(world.domainCode()))
            throw new IllegalArgumentException("Selected world does not match the demo domain");
        var initial = profiles.initialInput(world);
        var scenario = worlds.scenario(
                profiles.scenario(domain, world, models.mock().id(), world.name() + " · MOCK", maxSteps));
        var run = runs.create(scenario.id());
        runs.inject(run.id(), "demo-initial", initial);
        return run;
    }
}
