package com.trs.simulation;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Run;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.World;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Replay, branch, transport and demo endpoints, all protected by the common session guard. @author
 * agent
 */
@RestController
@RequestMapping("/api")
public class ContinuationController {
    private final DemoService demos;
    private final HistoryService history;
    private final RunService runs;
    private final ModelService models;
    private final WorldService worlds;
    private final EventStreamService streams;

    /** Injects application services. @author agent */
    public ContinuationController(
            DemoService demos,
            HistoryService history,
            RunService runs,
            ModelService models,
            WorldService worlds,
            EventStreamService streams) {
        this.demos = demos;
        this.history = history;
        this.runs = runs;
        this.models = models;
        this.worlds = worlds;
        this.streams = streams;
    }

    /** Explicit existing world and required round budget for a demo. @author czhcc-trsdsj\codexsandboxoffline */
    public record DemoInput(String worldId, Integer maxSteps) {}

    /** Creates a simulated demo from the selected world. @author czhcc-trsdsj\codexsandboxoffline */
    @PostMapping("/examples/{domain}/demo")
    public Run demo(@PathVariable String domain, @RequestBody DemoInput input) {
        if (input == null || input.maxSteps() == null)
            throw new IllegalArgumentException("Maximum simulation rounds required (integer from 1 to 20)");
        return demos.create(domain, input.worldId(), input.maxSteps());
    }

    /** Creates an explicitly simulated SDK model without credentials. @author agent */
    @PostMapping("/models/mock")
    public ModelService.View mock() {
        return models.mock();
    }

    /** Imports financial example input, without performing any real transfer. @author agent */
    @PostMapping("/examples/bank-transfer")
    public World bank() {
        return worlds.example("bank");
    }

    /** Lists plugin parameter schemas. @author agent */
    @GetMapping("/domains/bank/actions")
    public List<Map<String, Object>> bankActions() {
        return new BankEnvironment().actions();
    }

    /** Replays the exact event position without models or world actions. @author agent */
    @GetMapping("/runs/{id}/replay")
    public State replay(@PathVariable String id, @RequestParam long sequence) {
        return history.stateAt(id, sequence);
    }

    /** Returns the run's frozen agents for trace and intervention UI. @author agent */
    @GetMapping("/runs/{id}/agents")
    public List<AgentDefinition> agents(@PathVariable String id) {
        return runs.agents(id);
    }

    /** Lists state checkpoints. @author agent */
    @GetMapping("/runs/{id}/checkpoints")
    public List<HistoryService.Checkpoint> checkpoints(@PathVariable String id) {
        return history.checkpoints(id);
    }

    /** Lists persisted branch lineage. @author agent */
    @GetMapping("/branches")
    public List<Map<String, Object>> branches() {
        return history.branches();
    }

    /** Fork request. @author agent */
    public record Fork(long sequence, String label) {}

    /** Creates a retry-safe intervention branch. @author agent */
    @PostMapping("/runs/{id}/fork")
    public Run fork(@PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody Fork input) {
        return history.fork(id, input.sequence(), input.label(), key);
    }

    /** Compares persisted state objects and domain metrics. @author agent */
    @GetMapping("/runs/compare")
    public HistoryService.Comparison compare(@RequestParam String left, @RequestParam String right) {
        return history.compare(left, right);
    }

    /** Intervention request with optimistic version control. @author agent */
    public record Intervention(String agentId, long expectedVersion, Decision decision) {}

    /** Applies a user intervention through the World Engine only. @author agent */
    @PostMapping("/runs/{id}/actions")
    public State action(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody Intervention input) {
        return runs.intervene(id, key, input.agentId(), input.decision(), input.expectedVersion());
    }

    /** Streams persisted events, supporting standard Last-Event-ID recovery. @author agent */
    @GetMapping(value = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String last) {
        try {
            return streams.open(id, last == null ? after : Long.parseLong(last));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SSE cursor");
        }
    }
}
