# Graph Agent Simulation Studio

## 通用 AI 智能体事件推演工作台 — V1.1 完整开发指导文档

**文档类型：** 产品需求规格说明书 / 技术架构设计 / AI 编程实施指导\
**项目名称：** Graph Agent Simulation Studio\
**版本：** V1.1\
**部署模式：** 独立部署、前后端分离\
**核心技术：** Java 21、Spring Boot 4、AgentScope Java 2、SQLite、JGraphT、Vue3、TypeScript、Tailwind CSS\
**参考产品：** Palantir Vertex、Paracosm、OntoFlow\
**第一业务场景：** 敏感信息泄露影响推演\
**扩展验证场景：** 银行大额转账模拟\
**未来支持：** 社会舆论、企业经营、交通事件、多方策略博弈等

---

# 1. 项目概述

## 1.1 项目背景

开发一套独立运行的通用 AI 智能体事件推演系统。

用户可以通过自然语言或结构化表单定义一个假设事件，系统在模拟世界中配置多个具有不同目标、能力和观察范围的 AI Agent。

Agent 根据当前世界状态、历史记忆、可观察信息和自身目标，自主分析局势、提出行动、调用工具，并通过 World Engine 改变模拟世界状态。

系统持续推进模拟过程，记录事件的发展、主体行为与状态变化，并通过可视化 GUI 展示推演过程。

本系统的目标不是生成一段看似合理的故事，而是建立一个：

**智能体自主决策 → 行动校验 → 世界状态变化 → 新事件产生 → 智能体重新观察与决策 → 持续演化**

的可执行推演闭环。

## 1.2 产品定位

Graph Agent Simulation Studio 是一个基于 AI Agent 和可配置世界模型的通用事件推演平台。

系统具备以下核心特征：

1. 不绑定特定业务领域。
2. 支持多个 AI Agent 自主决策。
3. 支持动态世界对象与关系。
4. 支持模拟事件注入。
5. 支持状态变化与影响传播。
6. 支持多轮自主推演。
7. 支持推演过程可视化。
8. 支持推演回放、分支和结果比较。
9. 支持领域模型扩展。
10. 模拟过程与真实业务环境隔离。

## 1.3 与普通 AI Agent 平台的区别

| 能力 | 普通 Agent 平台 | Graph Agent Simulation Studio |
|---|---|---|
| 用户问答 | 支持 | 支持 |
| 工具调用 | 支持 | 支持 |
| 多 Agent 协作 | 部分支持 | 支持 |
| 独立世界状态 | 通常没有 | 必须支持 |
| Agent 自主行动 | 支持 | 支持 |
| 行动改变模拟世界 | 通常需要外部实现 | 核心能力 |
| 事件持续演化 | 通常不是核心 | 核心能力 |
| 图形化状态展示 | 通常没有 | 必须支持 |
| 时间线回放 | 需要开发 | 必须支持 |
| 场景分支 | 需要开发 | 必须支持 |
| 结果比较 | 需要开发 | 必须支持 |

## 1.4 核心开发原则

**原则一：Agent 负责决策，World Engine 负责执行。**

Agent 可以自主决定尝试什么行动，但不能直接改变世界状态。

**原则二：模拟状态必须真实存在。**

状态变化必须写入模拟状态存储，不能只通过前端动画展示。

**原则三：Agent 决策不能依赖固定剧本。**

系统可以定义 Agent 的目标、工具、约束和行动集合，但不能通过固定步骤预先决定完整推演结果。

**原则四：推演可回放、可验证。**

任何状态变化必须具有明确的事件记录、行动来源及执行结果。

**原则五：不同业务领域共享通用运行时。**

增加银行转账等场景时，应通过领域插件扩展，而不是重新开发整套推演系统。

---

# 2. 项目建设范围

## 2.1 第一版实现内容

V1.1 实现以下功能：

- 世界定义及管理。
- 动态实体类型和关系类型。
- SQLite 轻量级图结构存储。
- AgentScope Java 2 集成。
- Agent 模型和角色配置。
- 多 Agent 自主决策。
- 世界状态引擎。
- Action 参数校验与裁定。
- 模拟时间推进。
- 事件调度。
- 状态变化记录。
- 泄密事件影响推演。
- 动态关系图可视化。
- Agent 行动可视化。
- 推演时间线。
- 暂停、恢复、单步、停止。
- 历史事件回放。
- 场景 Fork。
- 场景结果比较。
- 最小银行转账场景验证。

## 2.2 暂不实现

第一版不实现：

- 分布式仿真集群。
- 大规模百万 Agent 模拟。
- 真实业务系统控制。
- 真实银行交易。
- 大规模图计算集群。
- 完整地理信息系统。
- 真实交通流仿真。
- 专业物理仿真。
- Agent 模型训练。
- 高级强化学习。
- 自动创建任意 Java 领域执行代码。
- 复杂的多人协同权限体系。
- 生产环境多租户。
- 任意外部工具的自动授权执行。

需要预留扩展接口，但不得为了这些功能过度设计第一版。

---

# 3. 技术架构与选型

## 3.1 后端技术

| 技术 | 要求 | 用途 |
|---|---|---|
| Java | JDK 21 | 核心开发语言 |
| Spring Boot | 4.x | Web API、配置、依赖注入 |
| AgentScope Java | 2.x | 多智能体决策、工具调用 |
| Maven | 3.9+ | 依赖管理 |
| SQLite | 3.x | 数据与轻量图结构存储 |
| Spring JDBC | 与 Spring Boot 匹配 | SQL 和图关系查询 |
| Spring Data JPA | 可选 | 普通管理数据持久化 |
| Flyway | 支持 SQLite 的兼容版本 | Schema 版本管理 |
| JGraphT | 兼容 JDK 21 | 图路径和中心性算法 |
| Jackson | Spring Boot 管理版本 | JSON 处理 |
| SSE | Spring Web | 推演事件实时推送 |

版本要求：

- AgentScope 必须使用 **Java SDK 2.x**。
- 不得使用 Python AgentScope 替代。
- 项目初始化时应核实 SDK 的 Maven 依赖和实际 API。
- Spring Boot、SQLite JDBC、Flyway、Hibernate 的版本必须经过兼容性验证。
- SQLite 的 JSON 函数、递归 CTE 及窗口函数必须经过集成测试。
- 若 SQLite JPA 方言兼容性存在问题，优先使用 JDBC 实现数据访问，不得为了 ORM 强行引入新数据库。

## 3.2 前端技术

| 技术 | 用途 |
|---|---|
| Vue3 | 前端框架 |
| TypeScript | 类型检查 |
| Vite | 构建工具 |
| Tailwind CSS | UI 样式 |
| Pinia | 状态管理 |
| Vue Router | 页面路由 |
| Cytoscape.js | 实体与关系图可视化 |
| ECharts | 指标与结果对比 |
| EventSource / SSE | 接收运行事件 |

前端使用独立项目，避免与 Spring Boot 静态资源强耦合。

## 3.3 数据库选型

**第一版统一使用 SQLite。**

SQLite 用于保存：

- 世界定义。
- 实体和关系。
- Agent 配置。
- 场景。
- 模拟状态。
- 模拟事件。
- Agent 行动。
- 快照。
- 场景分支。
- 结果与报告。

### 强制要求

SQLite 仅作为关系数据库和轻量级属性图存储。

**不使用 Sqlg、Gremlin Server、Neo4j 或其他图数据库服务。**

实现方式：

- 实体与关系使用标准关系表。
- 属性使用 JSON 字段。
- 多跳遍历使用 SQLite 递归 CTE。
- 复杂局部图算法使用 JGraphT。
- 图谱可视化使用 Cytoscape.js。

不能假设 Sqlg 可以直接运行在 SQLite 上。

## 3.4 SQLite 并发策略

第一版采用单机运行。

要求：

- 启用 WAL 模式。
- 启用外键约束。
- 配置合理的 busy timeout。
- 数据库写入通过受控事务或单写入调度器执行。
- Agent 的 LLM 调用可以并发。
- 世界状态提交必须有确定顺序。
- 避免一个模拟运行中的并发写入造成 SQLite 锁竞争。
- 每次状态提交应原子保存变更和对应事件。
- 不允许 Agent 直接持有数据库连接执行任意 SQL。

---

# 4. 系统总体架构

```text
┌────────────────────────────────────────────────────────┐
│           Graph Agent Simulation Studio               │
│                    Vue3 Web GUI                        │
│                                                        │
│   Scenario   Graph   Agents   Timeline   Compare       │
└──────────────────────────┬─────────────────────────────┘
                           │
                       REST / SSE
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                  Spring Boot API                       │
│                                                        │
│   WorldController                                      │
│   ScenarioController                                   │
│   SimulationController                                 │
│   AgentController                                      │
│   GraphController                                      │
│   ModelController                                      │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                 Simulation Runtime                     │
│                                                        │
│   ScenarioManager                                      │
│   SimulationScheduler                                  │
│   SimulationClock                                      │
│   EventQueue                                           │
│   RunRecorder                                          │
│   SnapshotManager                                      │
│   BranchManager                                        │
│   ReplayService                                        │
└───────────────────┬──────────────────┬─────────────────┘
                    │                  │
                    ▼                  ▼
┌─────────────────────────┐ ┌────────────────────────────┐
│      Agent Runtime      │ │        World Engine        │
│                         │ │                            │
│   AgentScope Java 2     │ │  WorldState                │
│   AgentManager          │ │  ObservationProvider       │
│   DecisionEngine        │ │  ActionValidator           │
│   AgentMemory           │ │  ActionExecutor            │
│   AgentTools            │ │  EffectApplier             │
└────────────┬────────────┘ └──────────────┬─────────────┘
             │                             │
             └──────────────┬──────────────┘
                            ▼
┌────────────────────────────────────────────────────────┐
│               Reasoning & Domain Layer                 │
│                                                        │
│   GraphReasoningService                                │
│   ImpactAnalysisService                                │
│   DomainEnvironment                                    │
│   DomainActionHandler                                  │
│   DomainEvaluation                                     │
└──────────────────────────┬─────────────────────────────┘
                           ▼
┌────────────────────────────────────────────────────────┐
│                        SQLite                          │
│                                                        │
│ World / Entity / Relation / Scenario / Agent           │
│ Event / State / Snapshot / Branch / Trace              │
└────────────────────────────────────────────────────────┘
```

## 4.1 模块职责

### Agent Runtime

负责：

- Agent 创建。
- 模型调用。
- Agent 观察。
- Agent 记忆。
- 自主决策。
- 工具调用。
- 候选 Action 生成。
- Agent 执行日志。

### World Engine

负责：

- 当前世界状态。
- Action 参数校验。
- Action 权限校验。
- 业务约束校验。
- 行动冲突裁定。
- 状态变化。
- 新事件生成。
- 状态一致性维护。

### Simulation Runtime

负责：

- 模拟运行生命周期。
- Agent 调度。
- 事件队列。
- 模拟时间。
- 暂停与恢复。
- 单步执行。
- 运行状态。
- 快照与回放。
- 分支管理。

### Reasoning Engine

负责：

- 实体关系查询。
- 图路径分析。
- 关键节点识别。
- 影响范围计算。
- 假设验证。
- 领域规则与分析模型调用。

### Domain Environment

负责：

- 业务对象语义。
- 可执行行动。
- 业务约束。
- 行动效果。
- 业务指标。
- 场景结束条件。

## 4.2 强制隔离原则

**AgentScope Java 2 不承担 World Engine 的职责。**

AgentScope 只负责：

- Agent 推理和决策。
- 工具调用。
- Agent 协作。
- 对话及上下文管理。

AgentScope 不得直接修改：

- `WorldState`。
- `world_entity`。
- `world_relation`。
- `simulation_state_change`。
- 其他模拟状态表。

所有具有状态修改效果的 Action 必须统一经过：

`AgentDecision → ActionValidator → World Engine → StateStore`

**这一限制属于核心架构约束，不允许在开发中绕过。**

---

# 5. 通用世界模型

## 5.1 WorldDefinition

表示一个模拟世界的定义。

字段建议：

- `id`
- `name`
- `domainCode`
- `description`
- `entityTypes`
- `relationTypes`
- `actionDefinitions`
- `initialVariables`
- `constraints`
- `terminationConditions`
- `version`

世界定义是可复用的模板，不等同于某次运行状态。

## 5.2 Entity

采用动态实体定义。

示例：

```json
{
  "id": "person_a",
  "type": "PERSON",
  "name": "人员A",
  "properties": {
    "department": "研发部",
    "status": "ACTIVE"
  }
}
```

实体类型不应使用固定 Java 枚举限制。

世界可以动态定义新的实体类型。

默认示例类型：

- PERSON
- DOCUMENT
- SYSTEM
- ORGANIZATION
- BANK_ACCOUNT
- TRANSACTION
- ROAD
- VEHICLE
- EVENT

## 5.3 Relation

示例：

```json
{
  "id": "relation_001",
  "sourceId": "person_a",
  "targetId": "document_f",
  "relationType": "HAS_ACCESS",
  "properties": {
    "permission": "READ"
  }
}
```

关系支持：

- 类型。
- 方向。
- 属性。
- 生效时间。
- 失效时间。
- 数据来源。
- 证据状态。
- 模拟状态。

关系类型与属性应允许动态扩展。

## 5.4 WorldState

`WorldState` 表示模拟世界在某个模拟时间点的逻辑状态。

建议结构：

```json
{
  "runId": "run_001",
  "simulationTime": 10,
  "version": 12,
  "entities": {},
  "relations": [],
  "variables": {},
  "pendingEvents": []
}
```

这是逻辑结构，不要求每个事件都保存完整状态。

实际存储建议采用：

- 初始快照。
- 状态增量。
- 周期性检查点。
- 事件日志。

## 5.5 世界版本

每次成功提交模拟 Action：

1. 验证当前世界版本。
2. 执行状态变更。
3. 更新版本号。
4. 保存变更事件。
5. 发布运行事件。

版本号单调递增。

并发操作发生冲突时，应由 World Engine 根据领域规则决定重试、拒绝或排序执行。

---

# 6. AgentScope Java 2 集成

## 6.1 集成要求

使用 AgentScope Java 2 实现：

- ReAct Agent 或对应稳定 Agent 实现。
- 工具调用。
- 多 Agent 协作。
- 模型配置。
- Agent 上下文管理。
- Agent 运行事件记录。
- 结构化行动输出。
- 异常及超时控制。

必须根据锁定版本的 SDK 官方 API 实现，不能凭空创建不存在的 AgentScope 类或方法。

## 6.2 SimulationAgent

建议定义项目内部抽象：

```java
public interface SimulationAgent {

    AgentDecision decide(
        AgentObservation observation,
        SimulationContext context
    );
}
```

由 AgentScope 适配器实现。

例如：

```text
SimulationAgent
    |
    +-- AgentScopeSimulationAgent
    |
    +-- MockSimulationAgent
```

Mock Agent 用于单元测试和确定性推演验证。

## 6.3 AgentDefinition

每个 Agent 至少包含：

```json
{
  "id": "investigation_agent",
  "name": "调查智能体",
  "role": "INVESTIGATOR",
  "goal": "调查潜在信息传播范围",
  "modelId": "default_model",
  "tools": [
    "graph_search",
    "graph_expand",
    "impact_analysis"
  ],
  "memoryEnabled": true,
  "maxDecisionCount": 20
}
```

进一步支持：

- Agent 描述。
- 系统提示词。
- 目标。
- 角色。
- 可见数据范围。
- 可使用的 Action。
- 模型。
- 记忆策略。
- 最大 Token 预算。
- 最大执行时间。
- 最大决策次数。
- 可选阵营。

## 6.4 AgentObservation

Agent 不应该直接获得完整 WorldState。

通过 `ObservationProvider` 为每个 Agent 生成观察信息。

```java
public interface AgentObservationProvider {

    AgentObservation observe(
        WorldState state,
        SimulationAgentDefinition agent
    );
}
```

观察内容包括：

- 当前可见事件。
- 可见实体及关系。
- 当前任务目标。
- 最近行动结果。
- 可用工具。
- 资源和状态约束。
- Agent 个人记忆。

Agent 只能根据允许观察的信息进行决策。

未来多阵营对抗时，必须支持阵营间的信息隔离。

## 6.5 AgentDecision

Agent 的输出必须结构化。

```json
{
  "agentId": "investigation_agent",
  "actionType": "ANALYZE_RELATIONS",
  "targetId": "document_f",
  "parameters": {
    "depth": 3
  },
  "reason": "需要确定潜在传播路径"
}
```

注意：

- `reason` 为 Agent 提供的行动说明。
- 不将其视为真实世界证据。
- 不要求记录模型不可访问的内部推理过程。
- Action 类型和参数必须通过 Schema 校验。
- 未知 Action 必须拒绝。

## 6.6 Agent 自主决策要求

**Agent 必须有真实的自主选择能力。**

错误方式：

```text
Agent 1：固定查询文件
Agent 2：固定查询人员
Agent 3：固定计算传播
Agent 4：固定输出结论
```

正确方式：

```text
Agent 观察当前状态
       |
       +-- 自主选择查询工具
       +-- 自主分析关系
       +-- 自主提出假设
       +-- 自主申请模拟操作
       +-- 自主进一步调查
       +-- 自主结束当前任务
```

允许出现不同的合法事件链。

不能通过硬编码固定步骤模拟 Agent 自主性。

## 6.7 Agent 行动与世界状态隔离

Agent 只能生成候选 Action。

例如：

```text
Agent:
  希望关闭系统 S 到系统 T 的共享通道
```

随后由 World Engine：

1. 校验关系是否存在。
2. 校验 Action 是否被允许。
3. 校验当前状态。
4. 执行模拟变更。
5. 记录执行结果。
6. 返回新的观察。

**禁止 Agent 通过 SQL、HTTP 或自定义脚本绕过 World Engine 修改世界状态。**

## 6.8 LLM 模型配置

支持 OpenAI 兼容 API。

配置包括：

- 模型名称。
- Base URL。
- API Key。
- Temperature。
- 最大输出 Token。
- 请求超时。
- 并发限制。

支持本地 Qwen/vLLM 模型服务。

模型配置应由后端保存，密钥不得明文返回前端。

---

# 7. Simulation Runtime

## 7.1 核心接口

```java
public interface SimulationEngine {

    SimulationRun start(String scenarioId);

    SimulationStepResult step(String runId);

    void pause(String runId);

    void resume(String runId);

    void stop(String runId);

    WorldState getState(String runId);
}
```

上述接口为项目自定义接口。

## 7.2 运行状态

```text
CREATED
   |
   v
READY
   |
   v
RUNNING
   |
   +----> PAUSED
   |        |
   |        v
   |      RUNNING
   |
   +----> COMPLETED
   |
   +----> FAILED
   |
   +----> STOPPED
```

状态转换必须统一管理，不允许非法转换。

## 7.3 调度模式

### EVENT_DRIVEN

事件驱动模式。

某个事件发生后，系统根据事件类型和影响范围激活相关 Agent。

适用于：

- 泄密。
- 交易异常。
- 系统故障。
- 业务流程变化。

### TURN_BASED

回合驱动模式。

每轮确定活跃 Agent，所有 Agent 基于当前轮快照提出行动。

World Engine 统一处理行动。

适用于：

- 企业竞争。
- 多方谈判。
- 群体互动。
- 抽象策略博弈。

## 7.4 标准推演周期

```text
获取世界状态 S(t)
        |
        v
选择活跃 Agent
        |
        v
为每个 Agent 生成独立观察
        |
        v
AgentScope 自主决策
        |
        v
收集候选 Action
        |
        v
统一 Action 校验
        |
        v
World Engine 裁定
        |
        v
提交 WorldState S(t+1)
        |
        v
保存事件及状态变化
        |
        v
生成下一轮观察
        |
        v
继续推演
```

## 7.5 Agent 并发策略

允许 Agent 并发执行 LLM 调用。

推荐使用 Java 21 虚拟线程或经验证的异步执行方式。

必须限制：

- LLM 同时请求数。
- Token 预算。
- 超时。
- 重试次数。
- 活跃 Agent 数量。

**Agent 决策可以并行，世界状态提交必须受控。**

同一轮使用统一快照，避免 LLM 请求完成顺序直接影响世界状态。

## 7.6 模拟时间

采用逻辑模拟时间。

支持：

- 当前模拟时间。
- 事件计划时间。
- 延迟事件。
- 回合编号。
- 时间终止条件。

不要求模拟时间与真实时间同步。

## 7.7 运行限制

必须配置：

- 最大模拟步数。
- 最大事件数。
- 最大 Agent 决策次数。
- 最大 LLM 请求数。
- 最大 Token 预算。
- 最大运行时间。
- 最大关系传播深度。
- 最大状态修改数量。

达到限制时，系统应安全终止或暂停，不得无限循环。

---

# 8. World Engine

## 8.1 核心职责

World Engine 负责：

- 世界状态管理。
- 行动合法性。
- 约束校验。
- 行动冲突处理。
- 状态变化提交。
- 新事件生成。
- 结果反馈。

AgentScope 不负责这些工作。

## 8.2 领域接口

```java
public interface WorldEnvironment {

    WorldState initialize(
        SimulationScenario scenario
    );

    AgentObservation observe(
        String agentId,
        WorldState state
    );

    ActionResult execute(
        AgentDecision decision,
        WorldState state
    );

    boolean isFinished(
        WorldState state
    );
}
```

## 8.3 Action 执行流程

```text
AgentDecision
      |
      v
Action Schema Validation
      |
      v
Action Permission Validation
      |
      v
Domain Constraint Validation
      |
      v
Action Conflict Resolution
      |
      v
Domain Action Handler
      |
      v
State Change Commit
      |
      v
New Event Generation
```

## 8.4 统一状态变化

```json
{
  "eventId": "event_002",
  "simulationTime": 2,
  "changes": [
    {
      "entityId": "system_s",
      "property": "sharingEnabled",
      "before": true,
      "after": false
    }
  ]
}
```

状态变化必须有：

- 事件 ID。
- Agent ID 或系统规则 ID。
- 模拟时间。
- Action 类型。
- 变化前状态。
- 变化后状态。
- 状态版本。
- 执行结果。

## 8.5 领域插件

```java
public interface DomainEnvironment
        extends WorldEnvironment {

    String getDomainCode();

    List<ActionDefinition> getActions();

    EvaluationResult evaluate(
        WorldState state
    );
}
```

扩展结构：

```text
DomainEnvironment
   |
   +-- InformationLeakEnvironment
   |
   +-- BankTransferEnvironment
   |
   +-- TrafficEnvironment
   |
   +-- EnterpriseEnvironment
   |
   +-- AdversarialEnvironment
```

各领域的状态和约束可以不同，但调度、事件记录、快照、回放协议应保持统一。

---

# 9. SQLite 图存储和推理

## 9.1 基本方案

第一版使用 SQLite 存储轻量级属性图。

核心表：

```text
world_entity_type
world_relation_type
world_entity
world_relation
```

图结构采用实体、关系表实现。

**严禁将 Sqlg 或 Gremlin Server 引入第一版。**

## 9.2 图查询

至少支持：

- 根据 ID 查询实体。
- 根据类型查询实体。
- 查询出边。
- 查询入边。
- 一跳关系扩展。
- 多跳关系扩展。
- 两实体之间的路径。
- 指定关系类型过滤。
- 局部子图查询。

采用 SQLite 递归 CTE 实现多跳查询。

必须有深度限制和循环检测。

## 9.3 图计算

对于需要计算的局部图，加载至 JGraphT。

V1 支持：

- BFS。
- 最短路径。
- 可达范围。
- 简单节点度数。
- 介数中心性。
- 关键桥接节点识别。

算法结果应返回实体 ID 及可解释指标。

## 9.4 图谱智能体工具

提供：

```text
graph.search_entity
graph.get_entity
graph.expand_relations
graph.find_paths
graph.calculate_centrality
graph.estimate_impact
graph.get_subgraph
```

工具只返回结构化数据。

默认只读。

模拟状态变更必须使用 World Engine Action，不允许图查询工具直接写数据库。

## 9.5 事实、推理和模拟区分

所有事件及重要关系应标记为：

| 类型 | 含义 |
|---|---|
| FACT | 输入数据或证据确认的事实 |
| INFERRED | 规则或算法推导结果 |
| HYPOTHESIS | Agent 提出的假设 |
| SIMULATED | 模拟中产生的状态或事件 |

可选附加：

- 来源。
- 证据 ID。
- 生成方法。
- 模型版本。
- 假设条件。
- 置信信息。

不能将 Agent 假设自动升级为 FACT。

---

# 10. 数据库结构

SQLite 数据库路径：

`data/simulation.db`

使用 Flyway 自动初始化。

## 10.1 世界模型

| 表名 | 用途 |
|---|---|
| `world_definition` | 世界定义 |
| `world_entity_type` | 实体类型 |
| `world_relation_type` | 关系类型 |
| `world_entity` | 世界对象 |
| `world_relation` | 世界关系 |
| `domain_action_definition` | 领域 Action |

## 10.2 场景管理

| 表名 | 用途 |
|---|---|
| `scenario` | 场景 |
| `scenario_agent` | Agent 配置 |
| `simulation_run` | 推演运行 |
| `simulation_branch` | 推演分支 |
| `simulation_snapshot` | 状态快照 |

## 10.3 运行记录

| 表名 | 用途 |
|---|---|
| `simulation_event` | 推演事件 |
| `simulation_action` | 候选及执行行动 |
| `simulation_state_change` | 状态变化 |
| `agent_execution` | Agent 运行记录 |
| `agent_memory` | Agent 记忆 |

## 10.4 系统配置

| 表名 | 用途 |
|---|---|
| `model_config` | LLM 模型配置 |
| `system_setting` | 系统设置 |

## 10.5 数据库设计约束

- 统一 ID 生成策略。
- 启用外键。
- 使用 JSON 保存动态属性。
- 按 `run_id + sequence_no` 唯一约束事件顺序。
- 对关系的 source、target 建立索引。
- 对 run、scenario、simulation_time 建立索引。
- 所有状态变化采用事务提交。
- 快照与事件日志具有明确的版本关系。
- 防止不同运行分支修改同一基准数据。
- 日志和运行轨迹采用追加写入方式。
- 大对象状态和运行记录要避免无限增长。

---

# 11. 第一业务场景：敏感信息泄露影响推演

## 11.1 场景目标

假设某份敏感文件可能泄露。

系统需要分析：

1. 文件可能被哪些对象接触。
2. 哪些关系可能形成传播路径。
3. 哪些节点对进一步传播影响较大。
4. Agent 会自主选择哪些调查行动。
5. 在模拟中可能出现哪些后续事件。
6. 可以采取哪些限制传播措施。
7. 不同措施对影响范围有什么变化。

本场景属于模拟分析，不得把模拟推断直接视为真实泄密事实。

## 11.2 示例实体

```text
DOCUMENT
  文件 F

PERSON
  人员 A
  人员 B
  人员 C

SYSTEM
  系统 S
  系统 T

ORGANIZATION
  外部机构 D
```

## 11.3 示例关系

```text
文件 F
  |
  +-- ACCESSED_BY --> 人员 A
  |
  +-- ACCESSED_BY --> 人员 B
  |
  +-- STORED_IN ----> 系统 S

人员 A -- SHARED_WITH --> 人员 C

系统 S -- SYNCS_TO ----> 系统 T

人员 C -- CONTACTS ----> 外部机构 D
```

注意：

人员 C 联系外部机构 D，不代表文件 F 已经传播到机构 D。

系统必须区分路径可达与已发生的传播。

## 11.4 初始事件

```json
{
  "eventType": "POTENTIAL_LEAK",
  "targetId": "document_f",
  "simulationTime": 0,
  "parameters": {
    "source": "system_s",
    "confidence": 0.8
  }
}
```

## 11.5 默认 Agent

### Investigation Agent

目标：调查信息接触和可能传播的范围。

工具：

- 实体查询。
- 关系扩展。
- 路径搜索。
- 关键节点分析。
- 证据查询。

### Propagation Agent

目标：在给定传播假设和环境条件下模拟进一步扩散。

工具：

- 图传播分析。
- 接触关系查询。
- 模拟传播事件。
- 传播范围评价。

### Response Agent

目标：提出减少进一步传播的措施。

工具：

- 模拟撤销共享权限。
- 模拟关闭同步通道。
- 生成控制方案。
- 请求运行干预模拟。

### Evaluation Agent

目标：评价不同方案。

工具：

- 影响对象统计。
- 传播范围比较。
- 关键路径比较。
- 剩余风险分析。

## 11.6 推演要求

不能硬编码完整的固定事件顺序。

系统可以提供预置场景和初始 Agent 目标，但下一步调查和模拟行动由 Agent 自主决定。

例如：

```text
发现疑似泄密
      |
      v
Investigation Agent 自主查询
      |
      +-- 查询访问人员
      +-- 查询系统同步
      +-- 查询关联路径
      |
      v
Agent 提出传播假设
      |
      v
Propagation Agent 提出模拟事件
      |
      v
World Engine 裁定
      |
      v
世界状态发生变化
      |
      v
Response Agent 提出控制措施
      |
      v
模拟措施执行
      |
      v
Evaluation Agent 比较结果
```

Agent 可以出现不同的调查顺序及合法行动组合。

## 11.7 输出结果

至少包括：

- 已确认接触范围。
- 潜在影响范围。
- 模拟传播路径。
- 关键传播节点。
- Agent 行动记录。
- 采取的模拟措施。
- 干预前后差异。
- 证据与假设标记。
- 推演摘要。

---

# 12. 银行转账扩展验证

## 12.1 目标

验证系统支持非泄密领域。

不能为银行转账重新实现通用 Simulation Runtime。

## 12.2 实体

```text
PERSON
BANK_ACCOUNT
BANK_TRANSFER
BANK
ENTERPRISE
RISK_ALERT
```

## 12.3 初始事件

```json
{
  "eventType": "BANK_TRANSFER",
  "sourceAccountId": "account_a",
  "targetAccountId": "account_b",
  "amount": 5000000,
  "currency": "CNY"
}
```

## 12.4 领域规则

至少支持：

- 账户必须存在。
- 来源与目标账户不同。
- 余额必须充足。
- 转账金额必须为正数。
- 转出和转入金额一致。
- 状态变化不允许产生无依据资金。
- 所有操作在模拟环境中执行。

## 12.5 Agent

建议：

- Bank Agent。
- Risk Analysis Agent。
- Enterprise Agent。

Agent 可以根据自身目标选择行动。

例如：

- 审核交易。
- 请求补充信息。
- 提出模拟转账。
- 查询交易历史。
- 分析潜在风险。
- 结束当前流程。

## 12.6 验收

实现：

```text
账户 A：1000万元
账户 B： 200万元

模拟转账：500万元

完成后：

账户 A：500万元
账户 B：700万元
```

结果必须由 BankTransferEnvironment 根据领域规则计算。

LLM 不能直接填写修改后的余额作为真实执行结果。

还应验证：

- 余额不足时 Action 被拒绝。
- Agent 能收到拒绝原因。
- 新状态能进入下一轮 Agent 观察。
- 运行结果可以回放。
- 不修改其他场景分支的状态。

---

# 13. GUI 设计

## 13.1 设计风格

参考 Palantir Vertex 的专业分析工作台：

- 深色主题。
- 蓝灰色背景。
- 多面板布局。
- 中央关系画布。
- 动态事件高亮。
- 右侧 Agent 行为。
- 底部推演时间线。
- 方案及状态比较。

不是复制 Palantir 的具体视觉资产。

## 13.2 主界面

```text
┌──────────────────────────────────────────────────────────────┐
│ Graph Agent Simulation Studio        Scenario   Run  Fork │
├────────────────┬───────────────────────────┬─────────────────┤
│                │                           │                 │
│ 场景配置       │       World Graph         │ Agent Activity  │
│                │                           │                 │
│ 世界类型       │       实体关系图          │ 当前 Agent      │
│                │                           │                 │
│ 初始事件       │       状态变化            │ 当前观察        │
│                │                           │                 │
│ Agent 配置     │       影响传播            │ 候选 Action     │
│                │                           │                 │
│ 推演时长       │       动态高亮            │ 执行结果        │
│                │                           │                 │
│ 运行参数       │                           │ 推演依据        │
│                │                           │                 │
├────────────────┴───────────────────────────┴─────────────────┤
│ Simulation Timeline                                          │
│                                                              │
│ T0 ─── T1 ─── T2 ─── T3 ─── T4 ─── T5                      │
│                                                              │
│ Play / Pause / Step / Replay / Fork                           │
├──────────────────────────────────────────────────────────────┤
│ Events / Affected Entities / State Changes / Comparison      │
└──────────────────────────────────────────────────────────────┘
```

## 13.3 左侧场景面板

配置：

- 场景名称。
- 世界类型。
- 初始事件。
- 模拟开始时间。
- 最大步数。
- 调度模式。
- Agent 列表。
- 模型配置。
- 运行、暂停、停止。

## 13.4 中央关系画布

使用 Cytoscape.js。

支持：

- 节点选择。
- 节点拖动。
- 缩放。
- 自动布局。
- 关系过滤。
- 实体搜索。
- 局部子图。
- 传播路径高亮。
- 状态差异展示。

视觉要求：

| 状态 | 表现 |
|---|---|
| 普通实体 | 蓝灰 |
| 当前影响节点 | 蓝色高亮 |
| 属性变化 | 橙色 |
| 重点关注 | 红色 |
| 新增模拟节点 | 绿色边框 |
| 假设关系 | 虚线 |
| 模拟删除关系 | 半透明或删除标记 |

不能只通过颜色表示状态，还应有图例或标签。

## 13.5 右侧 Agent 面板

展示：

- Agent 名称。
- 角色。
- 当前目标。
- 最新观察。
- 候选行动。
- 工具调用。
- 校验结果。
- 世界状态变化。
- 关联证据。

只展示可访问的行动说明、工具记录和结果，不要求输出模型内部推理过程。

## 13.6 底部时间线

必须支持：

- 播放。
- 暂停。
- 上一步。
- 下一步。
- 拖动。
- 1x / 2x / 4x。
- 跳转事件。
- 分支切换。

时间线应驱动：

- 中央图谱。
- Agent 面板。
- 对象状态。
- 指标面板。

同步变化。

## 13.7 运行与回放分离

**推演计算与 GUI 播放必须分离。**

后端运行时生成事件记录和状态变更。

前端消费事件实现动态图形。

历史回放：

- 不重新调用 LLM。
- 不重新执行 Action。
- 仅根据事件日志和快照恢复状态。
- 可以在任意有效检查点恢复图谱和属性。

这是一项强制性验收要求。

---

# 14. 实时事件协议

通过 SSE 推送。

事件类型：

```text
RUN_STARTED
RUN_PAUSED
RUN_RESUMED
RUN_STOPPED
RUN_COMPLETED
RUN_FAILED

AGENT_STARTED
AGENT_OBSERVATION
AGENT_DECISION
AGENT_TOOL_CALL

ACTION_PROPOSED
ACTION_ACCEPTED
ACTION_REJECTED

WORLD_STATE_CHANGED
RELATION_CHANGED
SIMULATION_EVENT_CREATED

SNAPSHOT_CREATED
BRANCH_CREATED
```

示例：

```json
{
  "runId": "run_001",
  "sequence": 12,
  "eventType": "WORLD_STATE_CHANGED",
  "simulationTime": 5,
  "agentId": "response_agent",
  "payload": {
    "entityId": "system_s",
    "property": "sharingEnabled",
    "before": true,
    "after": false
  }
}
```

要求：

- 事件具有递增序号。
- 事件可持久化。
- 前端断线后可以补读。
- SSE 不作为唯一的数据存储。
- 前端状态可从后端查询恢复。

---

# 15. REST API

| 方法 | 接口 | 功能 |
|---|---|---|
| GET | `/api/worlds` | 世界列表 |
| POST | `/api/worlds` | 创建世界 |
| GET | `/api/worlds/{id}` | 世界详情 |
| GET | `/api/worlds/{id}/graph` | 世界关系图 |
| POST | `/api/scenarios` | 创建场景 |
| GET | `/api/scenarios/{id}` | 场景详情 |
| PUT | `/api/scenarios/{id}` | 修改场景 |
| POST | `/api/scenarios/{id}/runs` | 启动推演 |
| GET | `/api/runs/{id}` | 查询运行 |
| POST | `/api/runs/{id}/pause` | 暂停 |
| POST | `/api/runs/{id}/resume` | 恢复 |
| POST | `/api/runs/{id}/step` | 单步执行 |
| POST | `/api/runs/{id}/stop` | 停止 |
| GET | `/api/runs/{id}/events` | 事件列表 |
| GET | `/api/runs/{id}/state` | 指定时间点状态 |
| GET | `/api/runs/{id}/graph` | 运行时关系图 |
| GET | `/api/runs/{id}/stream` | SSE |
| POST | `/api/runs/{id}/fork` | Fork |
| GET | `/api/runs/compare` | 分支对比 |
| GET | `/api/models` | 模型配置 |
| POST | `/api/models` | 新增模型 |

执行操作需要幂等控制，避免重复请求造成多次状态修改。

---

# 16. 工程目录

```text
graph-agent-simulation/
|
+-- pom.xml
|
+-- backend/
|   +-- pom.xml
|   +-- src/main/java/
|   |   +-- .../simulation/
|   |       +-- api/
|   |       +-- scenario/
|   |       +-- runtime/
|   |       +-- agent/
|   |       +-- world/
|   |       +-- graph/
|   |       +-- reasoning/
|   |       +-- domain/
|   |       |   +-- information/
|   |       |   +-- finance/
|   |       +-- persistence/
|   |       +-- event/
|   |       +-- config/
|   |
|   +-- src/main/resources/
|       +-- application.yml
|       +-- db/migration/
|
+-- frontend/
|   +-- src/
|       +-- views/
|       |   +-- SimulationStudio.vue
|       |   +-- ScenarioList.vue
|       |   +-- WorldEditor.vue
|       |
|       +-- components/
|       |   +-- graph/
|       |   +-- scenario/
|       |   +-- agent/
|       |   +-- timeline/
|       |   +-- results/
|       |
|       +-- stores/
|       +-- api/
|       +-- types/
|
+-- examples/
|   +-- information-leak/
|   +-- bank-transfer/
|
+-- data/
+-- docs/
+-- docker/
```

模块之间不得产生循环依赖。

Agent 层不得直接依赖 Controller 或数据库持久化实体。

---

# 17. 开发阶段与强制验收

## P0：项目初始化

实现：

- Spring Boot 4。
- Java 21。
- Vue3。
- SQLite。
- Flyway。
- REST API。
- 模型配置。
- 基础工作台页面。

验收：

- 后端可启动。
- 前端可启动。
- API 可通信。
- SQLite 自动初始化。
- 模型配置能完成连接测试。

## P1：世界模型与关系存储

实现：

- WorldDefinition。
- Entity。
- Relation。
- SQLite 属性图存储。
- 图谱查询。
- JGraphT 局部计算。
- 基础图谱界面。

验收：

- 导入泄密示例数据。
- 查询实体和多跳关系。
- 展示关系画布。
- 不依赖外部图数据库。

## P2：AgentScope Java 2 集成

实现：

- AgentScope SDK。
- AgentDefinition。
- Agent 管理。
- Graph Tools。
- 结构化决策输出。
- Agent 执行事件。
- Mock Agent。

验收：

Investigation Agent 可以根据任务目标自主选择一个或多个图查询工具。

不能只演示一次固定模型调用。

## P3：世界状态推演——核心技术验收关卡

**本阶段为项目核心里程碑。**

**P3 未通过验收，不允许进入 P4 动态 GUI 开发。**

### P3.1 必须实现

1. AgentScope Java 2 驱动至少两个具有不同目标的 Agent。
2. Agent 能根据当前观察自主选择下一步 Action。
3. Agent 不得依赖硬编码的固定执行步骤。
4. Agent 可以调用已注册的查询及分析工具。
5. World Engine 独立校验 Action 参数、权限和前置条件。
6. Agent 不得直接修改 WorldState。
7. Agent 不得直接修改 SQLite 模拟状态表。
8. 合法 Action 必须真正改变模拟世界状态。
9. 新状态必须在后续 Agent 观察中可见。
10. 新观察必须能够影响 Agent 下一轮决策。
11. 所有 Agent 决策、Action 裁定及状态变化必须持久化。
12. 使用 Mock LLM 能重现固定行动序列。
13. 使用真实 LLM 时允许生成不同合法事件链。
14. 所有世界状态变化必须遵守领域约束。
15. 系统必须能够安全拒绝非法 Action。

### P3.2 测试 A：自主决策

设置两个 Agent：

- Agent A：主要负责调查传播路径。
- Agent B：主要负责减少传播风险。

两者面对同一个初始事件，但具有不同目标、工具和观察范围。

验证：

- Agent 可以选择不同的合法行动。
- 行动选择依据当前可见状态。
- 系统没有写死完整执行步骤。
- Agent 可以根据工具返回结果调整后续行动。

### P3.3 测试 B：世界状态变化

Agent 提出：

`DISABLE_SYNC(system_s, system_t)`

World Engine：

1. 检查同步关系。
2. 校验操作条件。
3. 在模拟世界中关闭该关系。
4. 提交状态变更。
5. 生成事件记录。

再次查询时，必须返回更新后的模拟状态。

### P3.4 测试 C：环境反馈

在关闭同步通道后，再次执行 Agent 决策。

验证：

- Agent 可以观察到通道已关闭。
- Agent 不应把通道仍然开启当作已确认状态。
- Agent 可以选择其他调查或处置行动。
- 后续行动能够使用新世界状态。

不要求每次都生成同一种行动，但必须遵守当前状态约束。

### P3.5 测试 D：非法行动

Agent 提出一个不满足约束的操作。

例如：

- 关闭不存在的关系。
- 修改没有权限操作的对象。
- 使用无效参数。

World Engine 必须：

- 拒绝 Action。
- 返回明确失败原因。
- 不修改世界状态。
- 记录拒绝事件。
- 允许 Agent 根据反馈重新决策。

### P3.6 测试 E：运行一致性

使用：

- 固定世界初始状态。
- 固定 Agent 配置。
- Mock LLM。
- 固定行动序列。

运行两次。

验证：

- 事件顺序一致。
- 状态变化一致。
- 最终世界状态一致。
- 没有额外状态修改。

### P3.7 测试 F：真实 LLM 自主运行

使用实际配置的 LLM。

要求：

- 至少两个 Agent。
- 连续执行不少于五轮有效决策。
- Agent 能主动使用工具。
- 产生实际模拟状态变化。
- 可生成不同合法行动。
- 不允许越过 World Engine。
- 达到预算或终止条件后正确退出。

### P3.8 P3 验收通过条件

以下条件必须全部满足：

- [ ] 至少两个 Agent 完成不少于五轮有效决策与状态交互。
- [ ] 至少产生三次由 World Engine 正式提交的状态变化。
- [ ] 至少有一次成功的 Action。
- [ ] 至少有一次被拒绝的 Action。
- [ ] Agent 能观察上一轮状态变化。
- [ ] 后续决策受到新观察影响。
- [ ] 数据库可查询完整 Agent 决策及状态变更记录。
- [ ] Mock LLM 固定行动序列可重复得到相同结果。
- [ ] 真实 LLM 决策过程中没有违反世界约束的状态提交。
- [ ] 不依赖前端动画或预设事件脚本实现上述测试。

**只有 P3 全部通过，才能开始 P4。**

## P4：动态图谱 GUI

实现：

- SSE。
- 实时事件面板。
- 图谱动态高亮。
- Agent Activity。
- 状态变化动画。
- 时间线。
- 播放、暂停、单步。

验收：

用户能够直观看到 Agent 提出行动、World Engine 裁定、世界状态变化的过程。

不得通过前端硬编码假事件实现演示。

## P5：回放、Fork、Compare

实现：

- 初始快照。
- 周期检查点。
- 事件回放。
- Fork。
- 分支独立运行。
- 结果 Diff。
- 结果摘要。

验收：

- 任意有效检查点可以恢复世界状态。
- 回放不调用 LLM。
- 两个分支状态互不影响。
- 分支结果可以比较。
- 所有差异有对应状态变化记录。

## P6：银行转账扩展验证

实现最小银行转账场景。

包括：

- 银行账户。
- 模拟余额。
- 银行 Agent。
- 风险分析 Agent。
- 交易 Action。
- 余额校验。
- 模拟状态提交。
- 事件时间线。
- 结果回放。

验收：

**不能修改通用 Agent Runtime、Simulation Runtime 的核心设计来适配银行场景。**

允许通过预先设计的扩展接口新增领域实现。

如果扩展需要修改核心接口，必须先说明原因并调整架构设计，再实施。

---

# 18. 测试体系

## 18.1 单元测试

覆盖：

- 实体与关系。
- 图遍历。
- Action Schema。
- ActionValidator。
- WorldState。
- 状态变化。
- 模拟时间。
- 事件队列。
- 状态快照。
- 领域约束。

## 18.2 Agent 测试

使用 Mock LLM 验证：

- 工具选择。
- 参数生成。
- 输出解析。
- 行动拒绝。
- 环境反馈。
- 决策次数限制。
- 超时处理。

## 18.3 回放测试

要求：

给定相同初始快照和事件日志，恢复出的最终世界状态一致。

回放不调用 AgentScope 或 LLM。

## 18.4 分支测试

验证：

- 不同分支共享只读基准。
- 分支模拟变更隔离。
- 分支事件序号独立。
- Fork 后操作不修改父分支。
- 比较结果基于状态差异，而非仅比较文字报告。

## 18.5 端到端测试

必须提供：

1. 泄密事件完整推演。
2. Agent 自主调查。
3. 世界状态变更。
4. 干预方案。
5. Fork。
6. 结果比较。
7. 银行转账模拟。

---

# 19. 非功能需求

## 19.1 性能目标

第一版测试规模：

| 指标 | 目标 |
|---|---|
| Agent 数量 | 10～20 |
| 实体数量 | 不超过200 |
| 关系数量 | 不超过1000 |
| 推演轮次 | 20轮 |
| 运行事件 | 不超过2000条 |
| 运行方式 | 单机 |

这些是初始测试目标，不是平台未来容量上限。

## 19.2 安全性

- Agent 不得直接执行任意 SQL。
- Agent 工具必须白名单注册。
- 不得执行真实外部业务写操作。
- 模拟 Action 只能修改模拟世界。
- API Key 不得写入日志。
- 不可信输入不能覆盖系统指令。
- 不允许通过提示词绕过 ActionValidator。
- 不允许使用可执行脚本绕过 World Engine。
- 每个运行和分支必须有独立状态标识。

## 19.3 可解释性

用户应能够查询：

- 哪个 Agent 提出行动。
- Agent 当时观察到什么。
- 调用了哪些工具。
- 提出了哪个 Action。
- Action 为什么成功或失败。
- 哪些对象或关系发生变化。
- 变化属于事实、规则推导还是模拟结果。

## 19.4 可维护性

- 遵循模块分层。
- 核心逻辑不放在 Controller。
- 不直接耦合 AgentScope SDK 到领域模型。
- 世界状态和领域规则使用接口隔离。
- 数据库访问封装在 Repository。
- 前端按场景、图谱、Agent、时间线拆分组件。
- 提供必要注释和接口文档。

---

# 20. AI 开发智能体执行规范

本节供 Codex、Claude Code 等 AI 编程工具直接使用。

## 20.1 开发角色

你是一名负责完整实现 Graph Agent Simulation Studio 的高级 Java 全栈开发智能体。

目标是交付一个真实可运行、可测试、具有自主 Agent 决策和世界状态演化能力的应用。

不能只交付静态界面、伪代码或演示脚本。

## 20.2 强制技术要求

1. Java 21。
2. Spring Boot 4。
3. AgentScope Java 2。
4. SQLite。
5. JGraphT。
6. Vue3。
7. TypeScript。
8. Tailwind CSS。
9. Maven。
10. Flyway。

## 20.3 强制架构要求

- AgentScope 负责决策。
- World Engine 负责状态修改。
- Simulation Runtime 负责调度。
- DomainEnvironment 负责领域行为。
- SQLite 保存世界及运行状态。
- GUI 负责展示。

不得混淆上述职责。

## 20.4 禁止事项

不得：

- 使用 Python 替代核心 Java 实现。
- 使用 Sqlg。
- 引入 Neo4j、Redis、Kafka 等不必要基础设施。
- 将推演写成固定剧本。
- 使用前端动画伪造模拟状态。
- 让 Agent 直接更新世界状态表。
- 让 LLM 直接决定状态更新后的数值并跳过领域校验。
- 将 Agent 生成的假设标记为已确认事实。
- 跳过 P3 验收直接开发复杂 GUI。
- 用静态 JSON 代替真实推演结果作为最终实现。
- 为支持新领域复制一套 Simulation Runtime。

## 20.5 开发顺序

```text
P0 项目基础
       |
       v
P1 世界模型与 SQLite 图存储
       |
       v
P2 AgentScope 集成
       |
       v
P3 Agent 自主决策 + World Engine
       |
       | 必须验收通过
       v
P4 动态 GUI
       |
       v
P5 回放、Fork、Compare
       |
       v
P6 银行转账领域验证
```

## 20.6 每阶段执行要求

每一阶段必须：

1. 检查当前代码。
2. 确认现有模块。
3. 制定当前阶段实现计划。
4. 编写代码。
5. 编译。
6. 运行测试。
7. 修复错误。
8. 核对验收条件。
9. 更新 README 和开发记录。
10. 再进入下一阶段。

未完成的验收项目不得标记为完成。

如遇依赖不兼容或 SDK API 差异，应先查阅官方文档并修正，不得通过编造类或空实现绕过问题。

---

# 21. 最终交付物

至少包含：

- Spring Boot 后端。
- Vue3 前端。
- SQLite 自动初始化。
- AgentScope Java 2 集成。
- LLM 配置界面。
- 通用世界模型。
- World Engine。
- Simulation Runtime。
- 领域 Action 机制。
- 图谱查询。
- 动态图谱 GUI。
- Agent 行动面板。
- 模拟时间线。
- 历史回放。
- Fork。
- Compare。
- 泄密示例场景。
- 银行转账最小示例。
- 单元测试。
- 集成测试。
- 启动文档。
- API 文档。
- 系统架构文档。

---

# 22. 最终验收流程

必须演示：

```text
启动系统
     |
     v
选择泄密演示场景
     |
     v
加载世界实体与关系
     |
     v
配置 Agent 和模型
     |
     v
注入初始事件
     |
     v
启动推演
     |
     v
Agent 自主观察
     |
     v
Agent 自主选择工具与 Action
     |
     v
World Engine 校验与执行
     |
     v
世界状态发生变化
     |
     v
新状态反馈给 Agent
     |
     v
Agent 继续自主决策
     |
     v
GUI 动态展示事件与影响路径
     |
     v
暂停 / 单步 / 继续
     |
     v
查看事件时间线
     |
     v
Fork 创建干预分支
     |
     v
模拟不同控制措施
     |
     v
对比分支结果
     |
     v
生成推演摘要
```

随后切换到银行转账示例。

验证：

- 领域对象发生变化。
- Agent 类型和 Action 不同。
- World Engine 使用对应领域约束。
- 通用调度逻辑不需要重写。
- 可以正常运行与回放。

---

# 23. 项目成功标准

V1.1 是否成功，不以界面复杂度或 Agent 对话数量判断。

必须同时满足四项指标。

### 1. 自主性

Agent 能根据观察自主选择行动，而不是执行固定剧本。

### 2. 状态真实性

行动能够通过 World Engine 正式改变模拟世界，且符合领域约束。

### 3. 可解释性

能够追踪行动、裁定、状态变化及其依据。

### 4. 可视化与通用性

用户能在 GUI 中直观看到事件演化过程，且同一套核心运行时能够支持至少两个不同领域。

**最终目标：构建一个独立运行、基于 AgentScope Java 2、采用 SQLite 存储、支持动态关系、智能体自主决策、世界状态演化、可视化回放与多场景扩展的通用 AI 智能体推演工作台。**
