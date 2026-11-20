package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Authenticated configuration and trace endpoints for the shared hierarchy. @author czhcc-trsdsj\codexsandboxoffline */
@RestController
@RequestMapping("/api")
public class HierarchyController {
  private final ScenarioProfiles profiles;
  private final RunService runs;
  /** Injects declarative profiles and runtime readers. @author czhcc-trsdsj\codexsandboxoffline */
  public HierarchyController(ScenarioProfiles profiles, RunService runs) { this.profiles = profiles; this.runs = runs; }
  /** Lists reusable role, tool, prompt and outcome configurations. @author czhcc-trsdsj\codexsandboxoffline */
  @GetMapping("/scenario-profiles")
  public List<ScenarioProfiles.Profile> profiles() { return profiles.list(); }
  /** Seeds the profile's input against the frozen selected world without hard-coded object IDs. @author czhcc-trsdsj\codexsandboxoffline */
  @PostMapping("/runs/{id}/initial-input")
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void initialInput(@PathVariable String id) { runs.seedInput(id, profiles); }
  /** Lists authoritative parent/child tasks. @author czhcc-trsdsj\codexsandboxoffline */
  @GetMapping("/runs/{id}/tasks")
  public List<TaskRecord> tasks(@PathVariable String id) { return runs.tasks(id); }
  /** Returns the engine's business evaluation separately from lifecycle status. @author czhcc-trsdsj\codexsandboxoffline */
  @GetMapping("/runs/{id}/outcome")
  public BusinessOutcome outcome(@PathVariable String id) { return runs.outcome(id); }
  /** Returns shared model reservations across all roles. @author czhcc-trsdsj\codexsandboxoffline */
  @GetMapping("/runs/{id}/usage")
  public Map<String,Object> usage(@PathVariable String id) { return runs.usage(id); }
}
