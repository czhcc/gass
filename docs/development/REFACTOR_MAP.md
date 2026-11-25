# 第一阶段重构映射

源码路径以 [simulation](../../backend/app/src/main/java/com/trs/simulation) 为根。原类均位于该目录。Maven 模块依赖仍为 `bootstart → app → common`。

| 原文件 | 新路径 | 依赖变化与回归检查 |
| --- | --- | --- |
| `SimulationTypes`、`ActionPrograms`、`InformationOntology` | 根包 | `World` 构造器继续调用本体适配，保留旧 JSON 和直接构造行为。这三个类留在同一包，避免 DTO 与 Action 包互相依赖。 |
| `Json`、`HierarchyData` | `common`、`common.context` | `Json` 保持独立。CEL 和智能体观察所用的上下文转换可以读取 DTO。 |
| `DemoService`、`WorldCatalogInitializer`、`ScenarioProfiles`、`Examples` | `config`、`scenario`、`domain.examples` | 场景校验可供世界、运行、目标草稿复用。示例事实由领域和世界读取。 |
| `ModelService` | `model` | 智能体和生成器沿用模型配置与凭证处理。 |
| `DomainEnvironment`、`BankEnvironment`、`InformationEnvironment`、`InformationActionEngine` | `domain`、`domain.bank`、`domain.information` | 运行时按原有方式注入领域 SPI。业务执行仍走同一个声明式引擎。 |
| `DeclarativeActionEngine`、`CelExpressions`、`ActionLibraryService` | `action`、`action.expression`、`action.library` | CEL 编译独立于执行。Action 维护和模型生成通过 `action.library` 依赖引擎、模型、世界及智能体适配。 |
| `WorldService` | `world` | 世界和场景模板继续独立于运行状态，引用场景校验和领域 SPI。 |
| `GraphQueries`、`RunGraphReadService` | `graph` | 保留排名算法、查询键、128 项 LRU、授权范围和版本隔离。图查询只接收 DTO。 |
| `ObjectiveEvidence`、`TaskEvidence` | `evidence` | 筛选、引用校验与投影基于事件与状态 DTO。近期查询投影从协调器移入 `ObjectiveEvidence`。 |
| `BusinessEvaluation`、`ObjectiveDraftService` | `evaluation` | 引擎评价沿用 CEL。目标草稿调用原有场景校验。 |
| `HistoryService` | `history` | 分支、对比和回放继续读取运行状态、快照及证据。 |
| `HierarchicalCoordinator`、`HierarchyHost`、`HierarchyMockModel`、`AgentScopeDecisionProvider`、`DecisionProvider`、`SimulatedModel`、`ActionToolSchemas` | `agent` | 协调器保留主从编排与轮次委派。模型请求守卫、Worker 工具、上下文、完成工具与最终研判分别落到 `ModelInvocationGuard`、`WorkerTools`、`AgentContextBuilder`、`CompleteObjective`、`ObjectiveReviewService`。模型适配只经 `HierarchyHost` 读取或提交。 |
| `RunService` | `runtime` | 保留生命周期入口。按职责抽出 `RunStateRepository`、`RoundScheduler`、`ActionCommitService`、`HierarchyRuntimeHost`、`RunTerminationPolicy`。 |
| `SimulationController`、`ContinuationController`、`HierarchyController`、`ActionLibraryController`、`ApiConfiguration`、`EventStreamService` | `api` | HTTP 映射、响应类型、异常映射与 SSE 续传规则沿用旧实现。 |
| 前端 `RunStudio.vue` 中的定时播放 | `frontend/src/usePlayback.ts` | 播放定时器按原速率调用工作台的回放读取。页面继续管理请求失效、SSE 和运行控制。 |

## 运行边界与验证

- `RunService` 与 `HierarchyRuntimeHost` 使用同一运行时监视器。Lease、取消、版本检查和每轮收据的作用域沿用旧实现。模型调用之前进行预算预留，调用期间释放运行时锁。
- `RoundScheduler` 沿用 `base_time + step + 1`、时间及事件 ID 排序、每轮最多投递 10 个输入和原事务边界。Action 命令、审计、状态与快照使用原有 `TransactionTemplate`。回滚测试故意令快照写入失败，并检查命令可以原键重试。
- 启动扫描仍覆盖 `com.trs`。现有 Spring Boot 集成测试从 `StudioApplication` 加载 Controller、Service 和领域 Bean。测试中反射读取的预算字段和 `nanoClock` 继续位于 `RunService`。AgentScope 注解工具由模型协议和 Mock 主从集成测试实际调用。
- 本次按 Java import 检查包依赖图，未发现跨包环依赖。JSON 记录仍在 `SimulationTypes`，原有兼容构造器及序列化路径继续使用。

## 回滚点

- 基线提交为 `95ce067282a350c8332699d90f16a9bd51a8c01d`。回滚时应以第一阶段提交为单位同时恢复代码、测试引用和文档，再清理旧编译产物后验证 Spring 扫描。
- 共享锁和事务变化优先检查 `runtime` 各组件的调用点。工具注册、收据与引用回归优先检查 `agent` 和 `evidence`。REST/SSE 路径与 JSON 兼容优先检查 `api` 和根包兼容类型。
