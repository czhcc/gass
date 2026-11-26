# 第一阶段开发记录

## 基线

2026-11-25 从 `main` 创建 `refactor/simulation-structure`。基线为 `95ce067282a350c8332699d90f16a9bd51a8c01d`，包含评审提交 `1e11b598` 之后的后端 Spotless 配置和开发、验收指南。初始工作区只有未跟踪的 `.idea/`，保留原状。Flyway 最高版本为 V9。

| 同一 fixture 的回归参照 | 基线行为及候选复测 |
| --- | --- |
| 银行 Mock，`HierarchicalFrameworkIntegrationTest` | 首轮写入一笔交易，账户总余额为 1,200,000,000 分，收据引用 `demo-initial`，重试原命令复用结果。四轮时业务评价为 `SUCCEEDED`。 |
| 信息 Mock，`HierarchicalFrameworkIntegrationTest` | 一轮截止时运行结束原因是 `OBJECTIVE_NOT_MET`；八轮时为 `SUCCEEDED`，相关共享和同步边已禁用，响应任务成功。 |
| 回放与历史分支，`ContinuationIntegrationTest`、`HierarchicalFrameworkIntegrationTest` | 分支读取指定事件对应的精确快照，保持祖先时钟，隔离父分支状态与未来注入。回放读取无需调用模型。 |
| 文字目标和历史证据，`HierarchyModelContractTest` | 有效引用可在首轮提前完成，业务记录为 `NO_CONDITIONS`；已执行 Action 的历史结果与最终图谱视图分开。下一轮只复用当前版本的查询证据。 |
| 排名、缓存、权限，`ObjectiveGraphQueryTest`、`HierarchyGatewayIntegrationTest` | 启用、有向关系上的简单路径保留并列，深度受限报告不完整。等价请求在同 runId、version 与确切授权范围命中，运行、分支、版本或范围不同则隔离。版本冲突、越权、取消均保留现有裁定。 |
| Action 事务，`SimulationIntegrationTest` 新特征 | 快照写入失败时，状态、事件、回放和幂等键同步回滚。重试同键产生连续的 `USER_INTERVENTION → ACTION_PROPOSED → ACTION_ACCEPTED → WORLD_STATE_CHANGED` 序列。 |
| 播放，`RunStudio.test.ts` 扩展 | 从历史位置自动前进，只 GET 对应事件的回放接口，到末端停止。 |

比对使用测试中的业务结果、事件类型与顺序及快照内容，随机生成的运行 ID 和实际耗时仅用于定位。

## 交付

- 完成包迁移和运行边界拆分。按非空行计，`RunService` 从 1,442 行降到 751 行，`HierarchicalCoordinator` 从 1,083 行降到 478 行。路径、依赖、偏差与回滚点见 [REFACTOR_MAP.md](REFACTOR_MAP.md)。
- 新增回滚和 SSE 续传集成测试。SSE 测试在真实 Spring HTTP 端口比对 `Last-Event-ID` 续传结果、事件查询 JSON 和持久化日志，验证读取后原事件不变。回滚和定时播放特征断言分别先在拆分前通过。
- REST 继续通过原有生命周期、Action、查询与回放入口工作。业务 DTO 字段、构造器、事件名、持久化格式与 API 映射沿用原实现。V1–V9 文件保持原样。空库迁移、旧世界目录迁移、冻结运行读取、回放和分支的自动化测试通过。
- 当前提交为独立的第一阶段开发提交。可用 `git log -1 -- docs/development/PROGRESS.md` 查阅提交号。

## 实际验证

| 时点 | 命令与结果 |
| --- | --- |
| 修改前 | 后端 Maven `test`，92 项通过，0 失败、0 跳过。前端 `pnpm.cmd test`，82 项通过。 |
| 修改前 | `pnpm.cmd build` 在沙箱默认 `dist` 清理时返回 EPERM。使用 `pnpm.cmd build --outDir <临时目录>` 后通过。 |
| 分批迁移 | 领域和配置集成测试 16 项，Action、图谱、领域回归 21 项，HTTP、运行终止与模型契约 32 项，运行时专项 43 项，协调器专项 23 项，均通过。 |
| 修改后 | 后端 Maven `-B -o -s <本机 Maven 设置> test`，94 项通过，0 失败、0 错误、0 跳过。 |
| 修改后 | Maven `-pl app,bootstart spotless:apply spotless:check` 通过。当前包的 import 依赖图无跨包环。 |
| 修改后 | `pnpm.cmd test`，82 项通过。`pnpm.cmd build` 在本机默认 `dist` 通过，Vite 保留基线已有的分块体积提示。 |

本机 Maven 首次在沙箱内无法访问依赖库，随后用已配置的本机 Maven 缓存完成验证。包迁移期间有编译期可见性及 import 错误，已修正。一次 Spring 上下文因残留旧包字节码出现 Bean 同名，后续清理 Maven 产物并重新测试通过。模型验证使用 Mock 与本地模型协议 fixture。独立人工验收、实际浏览器操作和外部模型服务联调未执行。

## 后续阶段登记

| 现有行为 | 计划阶段 |
| --- | --- |
| 旧查询事件可能缺少 `scopeIds`，查询完整性和可见范围逻辑分散。 | 阶段 2 统一可见性，阶段 5 扩展外部覆盖范围语义。 |
| 历史 Action 证据通过相邻事件序号关联。 | 阶段 2 引入显式 Action 关联，阶段 4 扩展因果链。 |
| `complete_objective` 与运行终止耦合，未来轮次约束和证据充分性仍受现有提示词及规则限制。 | 阶段 3 明确完成、评价及终止门禁，阶段 6–7 增强证据与评价。 |
| 时钟随轮次推进，每轮最多处理 10 条到期输入。 | 阶段 3 调整时钟与持久化调度。 |

## 第一阶段独立验收（第 1 次）

- 基线 `95ce067282a350c8332699d90f16a9bd51a8c01d`，候选 `2e3e8f048cfbbe76cd43b59f3eb6c47c352a63e0`。
- 结论 `BLOCKED`。31 项必验中 30 项通过。G-01 缺开发前冻结清单及批准记录，自动 Stage Gate 尚无脚本可执行。
- 候选后端 94 项、前端 82 项通过，前端生产构建成功。基线 HTTP 与候选的银行、信息及分支记录零差异。候选重新读取基线数据库的三条历史运行，零差异。
- `nextStageAllowed=false`。[验收报告](acceptance/stage-01/attempt-01/review.md)、[机器可读结果](acceptance/stage-01/attempt-01/result.json)、[证据索引](acceptance/stage-01/attempt-01/evidence-index.json)。

## 第一阶段独立验收（第 2 次）

- 本轮候选 `2e3e8f048cfbbe76cd43b59f3eb6c47c352a63e0`。获批清单为事后补录，首轮报告与 `BLOCKED` 记录完整保留。
- 自动 Stage Gate 结论 `PASSED`，退出 `0`，`nextStageAllowed=true`。
- [第二次验收报告](acceptance/stage-01/attempt-02/review.md) · [门禁报告](acceptance/stage-01/attempt-02/gate-report.json) · [第二次机器可读结果](acceptance/stage-01/attempt-02/result.json)

## 第二阶段开发完成，待验收

- 基线 `2708c13de67db48877bf54c5e11929c9187a758a`，候选 `356ea246171d6bcc252747b08301d82ae8e801f1`。46 项必验清单经项目负责人批准后，于 2026-11-26 11:21:10（UTC+8）冻结。候选 SHA 单独登记。
- 完成本体约束、统一状态提交与可见性、持久化幂等收据和显式 Action 证据关联。新增 V10，兼容原 V1–V9 与历史记录。
- 后端 105 项、前端 82 项与 Stage Gate 回归 30 项通过。Spotless、前端生产构建与隔离 Mock HTTP 冒烟通过。独立验收和阶段准入待执行。
- [冻结清单](acceptance/stage-02/check-manifest.json) · [候选登记](acceptance/stage-02/candidate.json) · [开发报告](acceptance/stage-02/development.md)

## 第二阶段独立验收（第 1 次）

- 候选 `356ea246171d6bcc252747b08301d82ae8e801f1`，基线 `2708c13de67db48877bf54c5e11929c9187a758a`。
- 结论 `FAILED`。46 项必验中 38 项通过，8 项失败。自动门禁退出 `1`，`nextStageAllowed=false`。
- 待修复旧银行转账兼容回归 STAGE02-001，以及显式委派范围遗漏 STAGE02-002。
- [验收报告](acceptance/stage-02/attempt-01/review.md) · [门禁报告](acceptance/stage-02/attempt-01/gate-report.json) · [机器可读结果](acceptance/stage-02/attempt-01/result.json)

## 第二阶段缺陷修复完成，待重新验收

- 新候选 `fc7b03d5fedce90b7fc0bf792ee0cb833a6f4d89`，基线仍为 `2708c13de67db48877bf54c5e11929c9187a758a`。冻结材料和第一次验收记录保留。
- STAGE02-001 修复模板录入上限误用于运行余额的问题，保留金融不变量。STAGE02-002 修复派生交易越过显式任务范围的问题，统一查询、能力、Action、审计与证据的有效范围。
- 后端 109 项、前端 82 项、原 Reviewer 后端夹具 15 项、门禁回归 30 项通过。生产构建、Chromium 双版本回归、HTTP 同输入对比、V9 历史库重开及并发提交复测通过。
- [修复候选登记](acceptance/stage-02/candidate.json) · [修复与测试证据](acceptance/stage-02/development.md#修复验证)

## 第二阶段独立验收（第 2 次）

- 基线 `2708c13de67db48877bf54c5e11929c9187a758a`，修复候选 `fc7b03d5fedce90b7fc0bf792ee0cb833a6f4d89`。冻结清单、批准记录和原始基线逐字节一致，首轮 `FAILED` 记录与证据保留。
- 46 项必验和累计回归均通过。后端基线 94 项、候选 109 项，两版前端各 82 项及生产构建成功。原样复测旧候选及修复候选，关闭 STAGE02-001 与 STAGE02-002。
- 自动 Stage Gate 退出 `0`，`nextStageAllowed=true`。
- [第 2 次验收报告](acceptance/stage-02/attempt-02/review.md) · [门禁报告](acceptance/stage-02/attempt-02/gate-report.json) · [证据索引](acceptance/stage-02/attempt-02/evidence-index.json)

## 第三阶段开发完成（待独立验收）

- 分支 `feat/simulation-clock-scheduler`，基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`，候选 `2cf4ad3f7d963d9bd32a16b9db38f2652afe4c90`。
- 批准后的 60 项清单于 `2026-11-26T15:04:16.8344445+08:00` 冻结，SHA-256 为 `3ce65d029034063d1761976e4d6d7dfa7528f3b4efc9750192a03decb7647c3b`。准入采用自动 Stage Gate。
- 新增版本化的严格模拟时钟、V11 持久化调度、按审计序号的历史分支与冻结评价策略。保留旧场景轮次契约。
- 后端 119 项、前端 83 项、门禁自测 32 项通过。Spotless、生产构建、HTTP、SQLite 重开和 SSE 续读通过。
- [开发报告及证据](acceptance/stage-03/development.md) · [候选登记](acceptance/stage-03/candidate.json) · [冻结清单](acceptance/stage-03/check-manifest.json)
