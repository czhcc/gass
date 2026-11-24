package com.trs.simulation;

import com.trs.simulation.SimulationTypes.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.util.*;
import reactor.core.publisher.Flux;

/** Configured simulation policy exercising the same SDK delegation/tools as real models. @author czhcc-trsdsj\codexsandboxoffline */
public final class HierarchyMockModel implements Model {
  private final HierarchyHost host;
  private final AgentDefinition agent;
  private final List<AgentDefinition> children;
  private final boolean master;
  private final TaskRequest task;
  private int calls;
  private int actionTasks;
  private int delegations;
  /** Binds an invocation to a permission-scoped live gateway. @author czhcc-trsdsj\codexsandboxoffline */
  public HierarchyMockModel(HierarchyHost host, AgentDefinition agent, List<AgentDefinition> children, boolean master, TaskRequest task) {
    this.host = host; this.agent = agent; this.children = children; this.master = master; this.task = task;
  }
  /** Emits actual SDK tool calls; no event or state changes are fabricated here. @author czhcc-trsdsj\codexsandboxoffline */
  @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    calls++;
    if (master) {
      if (calls == 1) {
        var reader = children.stream().filter(a -> a.runtime().role().equals("QUERY")).findFirst();
        if (reader.isPresent()) { delegations++; return tool(reader.get().runtime().toolName(), Map.of("goal", "查询当前关联对象和图分析证据，向主 Agent 汇报")); }
      }
      if (delegations < host.scenario().team().maxDelegationsPerRound() && actionTasks < host.scenario().team().maxActionsPerRound()) {
        for (var child : children) {
          var next = candidate(child, null);
          if (next != null) { delegations++; actionTasks++; return tool(child.runtime().toolName(), Map.of("goal", "执行当前可用的对象 Action，并汇报执行引擎回执", "allowedActions", List.of(next.decision().actionType()))); }
        }
      }
      return tool("generate_response", Map.of("response", new RoundReport("已依据当前图谱证据完成本轮任务分派，执行结果以引擎回执为准。", List.of("本轮已分派 " + delegations + " 项任务；当前状态版本为 " + host.state().version()), List.of("按场景配置的轮数继续研判，由执行引擎评价场景效果"))));
    }
    if (agent.runtime().role().equals("QUERY") && calls == 1) {
      var allowed = agent.runtime().tools();
      String query = allowed.contains("graph_subgraph") ? "graph_subgraph" : allowed.getFirst();
      Map<String,Object> arguments = query.equals("graph_subgraph") || query.equals("graph_metrics") ? Map.of() : Map.of("entityId", agent.visibleEntityIds().getFirst());
      return tool("graph_query", Map.of("tool", query, "arguments", arguments));
    }
    if (agent.runtime().role().equals("ACTION")) {
      if (calls == 1) return tool("action_capabilities", Map.of());
      if (calls == 2) {
        var next = candidate(agent, task);
        if (next != null) {
          var decision = next.decision();
          var arguments = new LinkedHashMap<String, Object>(Map.of("targetId", decision.targetId(), "parameters", decision.parameters(),
              "expectedVersion", host.state().version(), "reason", decision.reason()));
          if (next.inputId() != null) arguments.put("inputEventId", next.inputId());
          return tool(ActionToolSchemas.toolName(decision.actionType()), arguments);
        }
      }
    }
    return Flux.just(ChatResponse.builder().id("mock-" + calls).content(List.of(TextBlock.builder().text("子任务已结束；查询证据与 Action 结果见工具的实际回执。").build())).finishReason("stop").build());
  }
  /** A candidate selected exclusively from declared rules and current capabilities. @author czhcc-trsdsj\codexsandboxoffline */
  private record Candidate(Decision decision, String inputId) {}
  private Candidate candidate(AgentDefinition worker, TaskRequest request) {
    var state = host.state();
    var history = host.history();
    var handled = TaskEvidence.handledInputs(history);
    var team = host.scenario().team();
    var capabilities = host.capabilities(worker);
    for (var rule : team.mockRules()) {
      if (!worker.allowedActions().contains(rule.actionType()) || request != null && !request.allowedActions().isEmpty() && !request.allowedActions().contains(rule.actionType())) continue;
      boolean inputBound = TaskEvidence.requiresInput(team, rule.actionType());
      for (var capability : capabilities) {
        if (!capability.available() || !capability.action().actionType().equals(rule.actionType())) continue;
        var parameters = new LinkedHashMap<String,Object>(); String inputId = null;
        if (inputBound || rule.eventType() != null) {
          var input = history.stream().filter(e -> e.eventType().equals("SIMULATION_EVENT_CREATED") && capability.targetId().equals(e.payload().get("targetId")))
              .filter(e -> rule.eventType() == null || rule.eventType().equals(e.payload().get("eventType")))
              .filter(e -> !inputBound || TaskEvidence.matchesInputType(team, rule.actionType(), e))
              .filter(e -> !handled.contains(String.valueOf(e.payload().get("scheduledEventId"))))
              .filter(e -> request == null || request.inputEventId() == null || request.inputEventId().equals(e.payload().get("scheduledEventId"))).findFirst();
          if (input.isEmpty()) continue;
          if (inputBound) {
            inputId = String.valueOf(input.get().payload().get("scheduledEventId"));
            parameters.putAll(Json.read(Json.write(input.get().payload().get("parameters")), Map.class));
          }
        }
        if (!inputBound) {
          var required = (List<?>) capability.action().parametersSchema().getOrDefault("required", List.of());
          boolean ready = true;
          for (var key : required) { var choices = capability.parameterOptions().getOrDefault(String.valueOf(key), List.of()); if (choices.isEmpty()) { ready = false; break; } parameters.put(String.valueOf(key), choices.getFirst()); }
          if (!ready) continue;
        }
        var variables = HierarchyData.variables(host.initial(), state, Map.of(), 0);
        variables.put("target", HierarchyData.json(state.entities().stream().filter(e -> e.id().equals(capability.targetId())).findFirst().orElseThrow())); variables.put("params", HierarchyData.json(parameters));
        if (Boolean.TRUE.equals(CelExpressions.evaluate(rule.whenExpression(), variables))) return new Candidate(new Decision(rule.actionType(), capability.targetId(), parameters, "模拟模型根据场景声明的策略及当前可执行能力选择操作", "ENTITY"), inputId);
      }
    }
    return null;
  }
  private Flux<ChatResponse> tool(String name, Map<String,Object> input) { return Flux.just(ChatResponse.builder().id("mock-" + calls).content(List.of(ToolUseBlock.builder().id("mock-call-" + calls).name(name).input(input).build())).finishReason("tool_calls").build()); }
  /** Marks all model audit entries explicitly simulated. @author czhcc-trsdsj\codexsandboxoffline */
  @Override public String getModelName() { return "SIMULATED_HIERARCHY_MODEL"; }
}
