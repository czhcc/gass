package com.trs.simulation.api;

import com.trs.simulation.ActionPrograms;
import com.trs.simulation.SimulationTypes.ActionCatalog;
import com.trs.simulation.SimulationTypes.Entity;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Ontology;
import com.trs.simulation.SimulationTypes.Relation;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.ScheduledInput;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.agent.AgentScopeDecisionProvider;
import com.trs.simulation.domain.information.InformationEnvironment;
import com.trs.simulation.graph.GraphQueries;
import com.trs.simulation.model.ModelService;
import com.trs.simulation.runtime.RunService;
import com.trs.simulation.world.WorldService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin authenticated REST boundary; all mutations delegate to domain services. @author agent */
@RestController
@RequestMapping("/api")
public class SimulationController {
    private final WorldService worlds;
    private final RunService runs;
    private final ModelService models;
    private final AgentScopeDecisionProvider sdk;

    /** Injects application services. @author agent */
    public SimulationController(
            WorldService worlds, RunService runs, ModelService models, AgentScopeDecisionProvider sdk) {
        this.worlds = worlds;
        this.runs = runs;
        this.models = models;
        this.sdk = sdk;
    }

    /** Lists registered domain action schemas. @author agent */
    @GetMapping("/domains/information/actions")
    public List<Map<String, Object>> actions() {
        return new InformationEnvironment().actions();
    }

    /** Lists type-owned action contracts for information worlds. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/domains/information/ontology")
    public Ontology informationOntology() {
        return ActionPrograms.ontology("information");
    }

    /** Returns bank object action contracts, including the CEL scoring demo. @author czhcc-trsdsj\czhcc */
    @GetMapping("/domains/bank/ontology")
    public Ontology bankOntology() {
        return ActionPrograms.ontology("bank");
    }

    /** Lists scoped target capabilities against the run's current state. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/runs/{id}/action-capabilities")
    public ActionCatalog actionCapabilities(@PathVariable String id) {
        return runs.actionCapabilities(id);
    }

    /** Returns domain evaluation with explicit fact/inference/simulation labels. @author agent */
    @GetMapping("/runs/{id}/evaluation")
    public Map<String, Object> evaluate(@PathVariable String id) {
        return runs.evaluate(id);
    }

    /** Lists reusable worlds. @author agent */
    @GetMapping("/worlds")
    public List<World> worlds() {
        return worlds.list();
    }

    /** Creates a validated world. @author agent */
    @PostMapping("/worlds")
    public World create(@RequestBody World world) {
        return worlds.create(world);
    }

    /** Imports the document-leak example facts. @author agent */
    @PostMapping("/examples/information-leak")
    public World example() {
        return worlds.example("information");
    }

    /** Updates a world with optimistic version validation. @author czhcc-trsdsj\codexsandboxoffline */
    @PutMapping("/worlds/{id}")
    public World updateWorld(@PathVariable String id, @RequestBody World input) {
        return worlds.update(id, input);
    }

    /** World copy display name. @author czhcc-trsdsj\codexsandboxoffline */
    public record WorldCopyInput(String name) {}

    /** Copies a world under a new identity. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/worlds/{id}/copy")
    public World copyWorld(@PathVariable String id, @RequestBody WorldCopyInput input) {
        return worlds.copy(id, input.name());
    }

    /** Exports a reusable world as JSON. @author czhcc-trsdsj\codexsandboxoffline */
    @GetMapping("/worlds/{id}/export")
    public ResponseEntity<World> exportWorld(@PathVariable String id) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("world.json")
                                .build()
                                .toString())
                .body(worlds.get(id));
    }

    /** Removes an unused custom world and its graph indexes. @author czhcc-trsdsj\codexsandboxoffline */
    @DeleteMapping("/worlds/{id}")
    public ResponseEntity<Void> deleteWorld(@PathVariable String id) {
        worlds.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Gets the world template. @author agent */
    @GetMapping({"/worlds/{id}", "/worlds/{id}/graph"})
    public World world(@PathVariable String id) {
        return worlds.get(id);
    }

    /** Returns bounded directed multi-hop reachability. @author agent */
    @GetMapping("/worlds/{id}/expand")
    public List<String> expand(
            @PathVariable String id,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "2") int depth,
            @RequestParam(required = false) String relationType) {
        return worlds.expand(id, entityId, depth, relationType);
    }

    /** Returns an enabled shortest path. @author agent */
    @GetMapping("/worlds/{id}/path")
    public List<String> path(@PathVariable String id, @RequestParam String from, @RequestParam String to) {
        return worlds.path(id, from, to);
    }

    /** Returns betweenness centrality. @author agent */
    @GetMapping("/worlds/{id}/centrality")
    public Map<String, Double> centrality(@PathVariable String id) {
        return worlds.centrality(id);
    }

    /**
     * Queries entity types and a bounded local subgraph including incoming/outgoing edges. @author
     * agent
     */
    @GetMapping("/worlds/{id}/entities")
    public List<Entity> entities(@PathVariable String id, @RequestParam(required = false) String type) {
        return worlds.get(id).entities().stream()
                .filter(e -> type == null || e.type().equals(type))
                .toList();
    }

    /** Returns one visible template entity. @author agent */
    @GetMapping("/worlds/{id}/entities/{entityId}")
    public Entity entity(@PathVariable String id, @PathVariable String entityId) {
        return worlds.get(id).entities().stream()
                .filter(e -> e.id().equals(entityId))
                .findFirst()
                .orElseThrow(NoSuchElementException::new);
    }

    /** Queries inbound or outbound relationships with optional type filtering. @author agent */
    @GetMapping("/worlds/{id}/relations")
    public List<Relation> relations(
            @PathVariable String id,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "out") String direction,
            @RequestParam(required = false) String type) {
        if (!List.of("in", "out", "both").contains(direction)) throw new IllegalArgumentException("Invalid direction");
        return worlds.get(id).relations().stream()
                .filter(e -> type == null || e.relationType().equals(type))
                .filter(e -> (!direction.equals("in") && e.sourceId().equals(entityId))
                        || (!direction.equals("out") && e.targetId().equals(entityId)))
                .toList();
    }

    /** Returns degree, betweenness and bridge candidates. @author agent */
    @GetMapping("/worlds/{id}/metrics")
    public Map<String, Object> metrics(@PathVariable String id) {
        var w = worlds.get(id);
        return GraphQueries.metrics(w.entities(), w.relations());
    }

    /** Returns a cycle-safe local property subgraph. @author agent */
    @GetMapping("/worlds/{id}/subgraph")
    public Map<String, Object> subgraph(
            @PathVariable String id, @RequestParam String entityId, @RequestParam(defaultValue = "2") int depth) {
        var w = worlds.get(id);
        var ids = worlds.expand(id, entityId, depth, null);
        return Map.of(
                "entities",
                w.entities().stream().filter(e -> ids.contains(e.id())).toList(),
                "relations",
                w.relations().stream()
                        .filter(e -> ids.contains(e.sourceId()) && ids.contains(e.targetId()))
                        .toList());
    }

    /** Lists scenarios. @author agent */
    @GetMapping("/scenarios")
    public List<Scenario> scenarios() {
        return worlds.scenarios();
    }

    /** Creates a scenario. @author agent */
    @PostMapping("/scenarios")
    public Scenario scenario(@RequestBody Scenario s) {
        return worlds.scenario(s);
    }

    /** Gets a scenario. @author agent */
    @GetMapping("/scenarios/{id}")
    public Scenario scenario(@PathVariable String id) {
        return worlds.scenario(id);
    }

    /**
     * Removes a scenario while preserving independent runs, events and replay snapshots.
     * @author czhcc-trsdsj\codexsandboxoffline
     */
    @DeleteMapping("/scenarios/{id}")
    public ResponseEntity<Void> deleteScenario(@PathVariable String id) {
        worlds.deleteScenario(id);
        return ResponseEntity.noContent().build();
    }

    /** Updates an editable scenario; existing runs retain frozen configuration. @author agent */
    @PutMapping("/scenarios/{id}")
    public Scenario update(@PathVariable String id, @RequestBody Scenario s) {
        return worlds.scenario(new Scenario(
                id, s.name(), s.worldId(), s.agents(), s.maxSteps(), s.maxEvents(), s.team(), s.objective()));
    }

    /** Creates a paused run for inspection or single-step. @author agent */
    @PostMapping("/scenarios/{id}/runs")
    public Run run(@PathVariable String id) {
        return runs.create(id);
    }

    /** Lists runs. @author agent */
    @GetMapping("/runs")
    public List<Run> runs() {
        return runs.list();
    }

    /** Deletes an inactive leaf run and its history. @author czhcc-trsdsj\codexsandboxoffline */
    @DeleteMapping("/runs/{id}")
    public ResponseEntity<Void> deleteRun(@PathVariable String id) {
        runs.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Gets lifecycle metadata. @author agent */
    @GetMapping("/runs/{id}")
    public Run runInfo(@PathVariable String id) {
        return runs.get(id);
    }

    /** Gets the current state or an exact committed version. @author agent */
    @GetMapping({"/runs/{id}/state", "/runs/{id}/graph"})
    public State state(@PathVariable String id, @RequestParam(required = false) Long version) {
        return version == null ? runs.state(id) : runs.replay(id, version);
    }

    /** Returns durable event history. @author agent */
    @GetMapping("/runs/{id}/events")
    public List<Event> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        return runs.events(id, after);
    }

    /** Schedules a bounded event input, with a client idempotency key. @author agent */
    @PostMapping("/runs/{id}/inject")
    public ResponseEntity<Void> inject(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody ScheduledInput event) {
        runs.inject(id, key, event);
        return ResponseEntity.noContent().build();
    }

    /** Advances one idempotent round. @author agent */
    @PostMapping("/runs/{id}/step")
    public State step(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return runs.step(id, key);
    }

    /** Pauses automatic advancement. @author agent */
    @PostMapping("/runs/{id}/pause")
    public Run pause(@PathVariable String id) {
        return runs.pause(id);
    }

    /** Resumes bounded automatic advancement. @author agent */
    @PostMapping("/runs/{id}/resume")
    public Run resume(@PathVariable String id) {
        return runs.resume(id);
    }

    /** Stops a run permanently. @author agent */
    @PostMapping("/runs/{id}/stop")
    public Run stop(@PathVariable String id) {
        return runs.stop(id);
    }

    /** Lists redacted model settings. @author agent */
    @GetMapping("/models")
    public List<ModelService.View> models() {
        return models.list();
    }

    /** Creates model settings; the API key is encrypted at rest. @author agent */
    @PostMapping("/models")
    public ModelService.View model(@RequestBody ModelService.Input input) {
        return models.save(input);
    }

    /**
     * Updates model settings in place; a blank API key retains the saved credential. @author agent
     */
    @PutMapping("/models/{id}")
    public ModelService.View updateModel(@PathVariable String id, @RequestBody ModelService.Input input) {
        return models.update(id, input);
    }

    /** Tests the actual configured AgentScope model. @author agent */
    @PostMapping("/models/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        try {
            sdk.test(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(Map.of(
                            "ok",
                            false,
                            "message",
                            "Model connection/structured output failed. Check endpoint, credentials and model"
                                    + " compatibility."));
        }
    }
}
