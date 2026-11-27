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

## 第三阶段独立验收（第 1 次）

- 基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`，候选 `2cf4ad3f7d963d9bd32a16b9db38f2652afe4c90`。四份冻结文件、批准记录及两份指南摘要核对通过，第一、第二阶段的准入记录保持原样。
- 60 项必验全部执行，57 项通过、3 项失败。STAGE03-001 为严格时钟待研判工作在中断或暂停后遗漏，涉及 G-10、S3-04、S3-05。
- 候选后端 119 项、前端 83 项通过，生产构建成功。Stage Gate 退出 `1`，`nextStageAllowed=false`，第四阶段未获准入。
- [独立验收报告](acceptance/stage-03/attempt-01/review.md) · [机器可读结果](acceptance/stage-03/attempt-01/result.json) · [证据索引](acceptance/stage-03/attempt-01/evidence-index.json) · [Stage Gate 报告](acceptance/stage-03/attempt-01/gate-report.json)

## 第三阶段 STAGE03-001 修复（待重新验收）

- 首次候选 `2cf4ad3f7d963d9bd32a16b9db38f2652afe4c90`，修复候选 `68898b543c680ea926b61b06569b2bd0c2c9a750`，基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`。
- 投递审计与待研判检查点同事务保存。恢复原时点、原轮次和未完成的工作，并依据已落盘 Action 检查点保证幂等。未完成研判继续阻止自然终止。
- 后端 123 项、前端 83 项、门禁自测 32 项和首轮原版 Java 夹具 27 项通过。生产构建、真实进程重启、历史库重开、HTTP、并发、浏览器、图谱及证据回归通过。
- [修复报告与证据](acceptance/stage-03/development.md#stage03-001-修复与重新验收候选) · [候选登记](acceptance/stage-03/candidate.json) · [首次独立验收](acceptance/stage-03/attempt-01/review.md)

## 第三阶段独立验收（第 2 次）

- 基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`，修复候选 `68898b543c680ea926b61b06569b2bd0c2c9a750`。原始批准、基线、清单和首轮报告保留。
- 原冻结 60 项必验及累计回归全部通过，关闭 STAGE03-001。首轮夹具在旧候选复现失败，在修复候选通过。真实应用进程重启验证未完成研判继续，已经接受的 Action 未重复提交。
- 候选后端 123 项、前端 83 项通过，生产构建通过。Stage Gate 退出 `0`，`nextStageAllowed=true`，第四阶段获得准入。
- [独立验收报告](acceptance/stage-03/attempt-02/review.md) · [机器可读结果](acceptance/stage-03/attempt-02/result.json) · [证据索引](acceptance/stage-03/attempt-02/evidence-index.json) · [Stage Gate 报告](acceptance/stage-03/attempt-02/gate-report.json)

## 第四阶段开发完成（待独立验收）

- 分支 `feat/eca-propagation`，基线 `f67ef4851405f91f2b8ce56d01f7b0a99860e7ec`，候选 `b7e2f1937f0791ad664f27b55555f3d21fdc9078`。
- 获批的 79 项清单于 `2026-11-27T09:12:57.1790244+08:00` 冻结，SHA-256 为 `7daf69e50b80012ea9876a28605a57b9240cc092678456ad01b84805383e50a0`，使用自动 Stage Gate。
- 实现严格事件模式的 ECA 传播、受控系统规则身份、V13 持久工作、历史分支、规则管理和因果审计。
- 后端 132 项、前端 86 项、门禁自测 34 项通过。Spotless、生产构建、V12 历史数据迁移、隔离 HTTP 与真实进程重启通过。
- [开发报告与证据](acceptance/stage-04/development.md) · [候选登记](acceptance/stage-04/candidate.json) · [冻结清单](acceptance/stage-04/check-manifest.json)

## 第四阶段独立验收（第 1 次）

- 基线 `f67ef4851405f91f2b8ce56d01f7b0a99860e7ec`，候选 `b7e2f1937f0791ad664f27b55555f3d21fdc9078`。冻结清单、批准和前置阶段门禁已核对。
- 79 项必验和累计回归通过。设备 T0/T1/T2 传播零模型调用，规则收据和因果引用可追溯。同一 SQLite 跨 JVM 恢复、父子分支隔离、V9/V10/V12 升级与真实浏览器规则管理通过。
- 后端 132 项、前端 86 项及生产构建通过。Stage Gate 退出 `0`，`nextStageAllowed=true`，第五阶段获得准入。
- [独立验收报告](acceptance/stage-04/attempt-01/review.md) · [机器可读结果](acceptance/stage-04/attempt-01/result.json) · [证据索引](acceptance/stage-04/attempt-01/evidence-index.json) · [Stage Gate 报告](acceptance/stage-04/attempt-01/gate-report.json)

## 第五阶段开工准备（待批准）

- 已核对第四阶段第一次独立验收及候选完整 SHA。79 项通过，Stage Gate 退出 0、`nextStageAllowed=true`。已准入的候选与合入后的 `main` 在后端、前端及脚本代码一致；历史独立验收材料保留原记录。
- 开发分支 `feat/knowledge-graph-expansion`，基线 `2aa1f0b2344393304ca966b64f42669658ea12dd`。基线完整后端 132 项、前端 86 项、门禁单测 34 项通过；Spotless 与生产构建通过，Vite 保留既有分块体积提示。
- 草案包含第四阶段冻结的 79 项累计检查、第五阶段新增 21 项必验和真实只读 MCP 联调条件项。新增场景采用 Reviewer 独立 Mock 和本地 MCP 协议服务；默认不启用真实业务源和外部 LLM，拟沿用自动 Stage Gate。
- [开发基线与第四阶段收尾记录](acceptance/stage-05/baseline.json) · [第五阶段必验清单草案](acceptance/stage-05/check-manifest.draft.json)

## 第五阶段输入冻结（开发中）

- 项目负责人明确批准验收清单及自动 Stage Gate 准入，接收记录时间 2026-11-27T11:37:10.3187195+08:00，冻结时间 2026-11-27T11:37:46.218553+08:00。
- 冻结 SHA-256 为 4a9da357bc353a32c41635f86b7dad51f767106b2380d39905728bcb4e71fbad。候选 SHA 待开发完成后单独登记。
- [冻结清单](acceptance/stage-05/check-manifest.json) · [校验文件](acceptance/stage-05/check-manifest.sha256)

### 第五阶段首批开发进展（开发中）

- 实现操作人只读查询、候选确认、V14 原子导入、状态保护、断源回放与分支隔离，接入第五阶段门禁。
- 本轮后端 142 项、前端 86 项、门禁自测 37 项通过，Spotless 和生产构建通过。动态授权及证据缓存覆盖等工作尚在第五阶段开发范围内。
- [开发进展及测试记录](acceptance/stage-05/development.md)

### 第五阶段授权与覆盖修订（开发中）

- 继续使用已冻结清单。持久化按角色对新导入对象的读权及独立的本地 Action 授权，保留显式任务范围与历史分支隔离。
- 运行缓存和当前目标证据按覆盖修订校验。后端 145 项、前端 86 项、门禁单测 37 项通过，Spotless 和生产构建通过。
- [第五阶段开发与测试记录](acceptance/stage-05/development.md)

## 第五阶段开发完成（待独立验收）

- 分支 `feat/knowledge-graph-expansion`，基线 `2aa1f0b2344393304ca966b64f42669658ea12dd`，候选 `28b903253a2f73da33b60a9a0e836eaf8daae92e`。
- 保留批准后冻结的 100 项必验和 1 项条件检查，清单 SHA-256 为 `4a9da357bc353a32c41635f86b7dad51f767106b2380d39905728bcb4e71fbad`，自动 Stage Gate 等待独立验收结果。
- 交付只读外部接入、智能体候选申请、持久化审批与撤销、分币种评价、字段来源、覆盖修订及历史分支。导入不推进逻辑时间或进入 ECA 业务传播。
- 后端 151 项、前端 86 项、累计专项 47 项及门禁单测 37 项通过。生产构建、原 HTTP/SSE/浏览器回归、V9/V10/V12/V13 回放、前置阶段与第五阶段真实进程重启通过。
- [第五阶段候选登记](acceptance/stage-05/candidate.json) · [开发报告](acceptance/stage-05/development.md) · [冻结清单](acceptance/stage-05/check-manifest.json)

## 第五阶段独立验收（第 1 次）

- 原始基线 `2aa1f0b2344393304ca966b64f42669658ea12dd`，候选 `28b903253a2f73da33b60a9a0e836eaf8daae92e`。第四阶段通过记录、冻结清单及原批准记录核对通过。
- 100 项适用必验中 95 项通过、5 项失败，真实只读业务 MCP 条件不适用。待修复 STAGE05-001 包循环、STAGE05-002 分支字段来源丢失、STAGE05-003 局部证据错误完成外部全图目标。
- 候选后端 151 项、前端 86 项及生产构建通过。Stage Gate 退出 `1`，`nextStageAllowed=false`，第六阶段未获准入。
- [独立验收报告](acceptance/stage-05/attempt-01/review.md) · [机器可读结果](acceptance/stage-05/attempt-01/result.json) · [证据索引](acceptance/stage-05/attempt-01/evidence-index.json) · [Stage Gate 报告](acceptance/stage-05/attempt-01/gate-report.json)

## 第五阶段缺陷修复（待重新验收）

- 原候选 `28b903253a2f73da33b60a9a0e836eaf8daae92e`，修复候选 `177f62657eb800760a9263ed23989f1fb30596af`。原基线 `2aa1f0b2344393304ca966b64f42669658ea12dd`、冻结清单 SHA-256 `4a9da357bc353a32c41635f86b7dad51f767106b2380d39905728bcb4e71fbad`、批准及第一次独立验收记录保留。
- STAGE05-001 将冻结配置移至无业务依赖的共享契约。STAGE05-002 按历史分支截点保留原始来源运行与事件序号。STAGE05-003 由服务端区分局部与外部全图目标并验证证据范围，覆盖未验证时记录待补数据。
- 后端 155 项、前端 86 项、累计原夹具 66 项及门禁单测 37 项通过。架构检查、Spotless、生产构建、真实 JVM 的分支来源、待研判和 ECA 恢复复测通过。
- [复验候选登记](acceptance/stage-05/candidate.json) · [缺陷根因与实际测试证据](acceptance/stage-05/development.md) · [第一次独立验收](acceptance/stage-05/attempt-01/review.md)
