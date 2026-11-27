package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes.ActionCapability;
import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.Decision;
import com.trs.simulation.SimulationTypes.Event;
import com.trs.simulation.SimulationTypes.Scenario;
import com.trs.simulation.SimulationTypes.State;
import com.trs.simulation.SimulationTypes.TaskRecord;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.graph.KnowledgeCoverage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Explicit invocation context: SDK callbacks never receive persistence or raw mutation access. @author czhcc-trsdsj\codexsandboxoffline */
public interface HierarchyHost {
    /** Frozen scene. @author czhcc-trsdsj\codexsandboxoffline */
    Scenario scenario();
    /** 当前图谱的覆盖修订。未启用外部扩展时为空。 @author czhcc-trsdsj\codexsandboxoffline */
    default KnowledgeCoverage knowledgeCoverage() {
        return null;
    }
    /** Latest committed state. @author czhcc-trsdsj\codexsandboxoffline */
    State state();
    /** Frozen original state for stable scope and invariant evaluation. @author czhcc-trsdsj\codexsandboxoffline */
    State initial();
    /** Logical round. @author czhcc-trsdsj\codexsandboxoffline */
    int round();
    /** Visible capabilities freshly evaluated by the domain engine. @author czhcc-trsdsj\codexsandboxoffline */
    List<ActionCapability> capabilities(AgentDefinition agent);
    /** Projects generated objects through the domain's scope policy. @author czhcc-trsdsj\codexsandboxoffline */
    Set<String> visibleIds(AgentDefinition agent);
    /** Public bounded input and task history. @author czhcc-trsdsj\codexsandboxoffline */
    List<Event> history();
    /** Throws when stopped, paused or exhausted. @author czhcc-trsdsj\codexsandboxoffline */
    void check();
    /** Completes when this invocation is cancelled. @author czhcc-trsdsj\codexsandboxoffline */
    Mono<Void> cancelled();
    /** Reserves one actual SDK model request across all roles. @author czhcc-trsdsj\codexsandboxoffline */
    void beforeModel(AgentDefinition agent, String taskId, int tokens);
    /** Starts or retrieves an idempotent task. @author czhcc-trsdsj\codexsandboxoffline */
    TaskRecord startTask(String taskId, String parentTaskId, AgentDefinition agent, TaskRequest request);
    /** Saves actual results separately from model narration. @author czhcc-trsdsj\codexsandboxoffline */
    void completeTask(String taskId, String status, Map<String, Object> result);
    /** Atomic, version-checked object Action gateway. @author czhcc-trsdsj\codexsandboxoffline */
    default Map<String, Object> execute(
            TaskRecord task, AgentDefinition agent, Decision decision, long expectedVersion) {
        return execute(task, agent, decision, expectedVersion, task.request().inputEventId());
    }
    /** Executes an Action with its optional, explicitly selected input event. @author czhcc-trsdsj\codexsandboxoffline */
    Map<String, Object> execute(
            TaskRecord task, AgentDefinition agent, Decision decision, long expectedVersion, String inputEventId);
    /** Appends a bounded public task event and returns its sequence. @author czhcc-trsdsj\codexsandboxoffline */
    long record(String type, String agentId, Map<String, Object> payload);
}
