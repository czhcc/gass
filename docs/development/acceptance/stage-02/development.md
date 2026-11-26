# 第二阶段开发报告

本阶段待重新验收。修复候选为 `fc7b03d5fedce90b7fc0bf792ee0cb833a6f4d89`，基线仍为 `2708c13de67db48877bf54c5e11929c9187a758a`。46 项验收清单的 SHA-256 为 `ad0697d6c94a929d51c1a09317d9517993e4307383c469c6129c371bd111121b`。冻结材料和批准记录沿用原版本。当前候选见 [candidate.json](candidate.json)。

第一次独立验收的候选为 `356ea246171d6bcc252747b08301d82ae8e801f1`，结果为 `FAILED`。下面的初次开发记录和 [attempt-01](attempt-01/review.md) 保留该版本的证据。修复及复测见本报告末尾。

## 交付

| 范围 | 变更 |
| --- | --- |
| 本体与 Action 定义 | `SimulationTypes`、`OntologyConstraints`、`WorldService` 和 Action 库支持可选的属性、关系端点、基数和 CEL 不变量约束。模板保存应用默认值，已冻结定义和历史快照原样兼容读取。白名单保留三种既有原语。 |
| 原子状态提交 | `DeclarativeActionEngine` 先计算最终候选。 `StateDiff` 确定性比较值及存在性，`ActionCommitService` 统一提交版本、快照、收据与审计。无实际变化的候选保持版本。 |
| 持久幂等与审计 | 新 Action 的提议、裁定、变更和任务收据共享 `actionRunId`。人工干预同键同载荷重试返回原始版本和逻辑时钟，同键异载荷拒绝。Worker 可使用回执输出版本重试原决策，保持其既有协议。 |
| 可见范围与证据 | `VisibilityPolicy` 供运行观察、工具查询、Action、委派与证据投影复用。隐藏关联参数和字段从局部视图中省略。旧查询范围未知时仅在证明全范围可见的情况下用作研判证据。历史关联优先使用显式身份。 |
| Stage Gate | 支持冻结时保留空候选、候选提交后登记、以哈希定位前置阶段报告，以及阶段二验证命令。第一阶段历史验收格式仍受支持。 |

新增 Flyway `V10__durable_action_receipts.sql`。V1–V9 无内容变化。V10 仅新增收据表，每张收据引用快照和原始逻辑时钟。旧运行、历史事件和已发布 Action 不回写。已有 REST 路径、状态 DTO、事件类型和前端入口保持兼容。事件附加身份、来源、版本和差异字段，未经授权的历史证据可见性按本阶段标准收紧。

## 开发自测

| 命令 | 实际结果 |
| --- | --- |
| 后端 `mvn -B -o -s <本机 Maven 设置> verify` | 105 项测试通过，0 失败、0 错误、0 跳过。打包成功。 |
| 后端 `mvn -pl app,bootstart spotless:apply spotless:check` | 通过。 |
| 前端 `pnpm.cmd test` | 82 项测试通过。 |
| 前端 `pnpm.cmd build --outDir <临时目录>` | 构建通过。Vite 保留基线的大分块提示。 |
| `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 30 项通过。 |
| `python scripts/smoke_mock.py --base-url http://127.0.0.1:<隔离端口>` | 退出 0。信息场景 245 个事件，SSE 续传正确。银行场景只转账一次，账户总额为 1,200,000,000 分。 |

Maven 验证日志 SHA-256 为 `5a80d295a5e52fd15ae3d2ea9c5244b5fb7afa09eac30ce8aec89cac8ca7180c`。Mock HTTP 日志 SHA-256 为 `f2960592147ce04a7c672b9a7940d3d9faf5dbed85a8e3b453f7b5da09e9a6ac`。开工前基线及命令采集见 [baseline.json](baseline.json)。

先行测试在新增契约和门禁接口缺失时失败。实现后曾出现严格 Action 测试夹具漏配现有的额外参数开关，已修正夹具且保留断言。首次 Mock HTTP 冒烟在临时实例刚开放端口时登录返回 401，随后显式配置临时账号并等待初始化，重试通过。

## 待验收与后续范围

待独立 Reviewer 执行冻结清单及自动 Stage Gate。开发自测包含服务器端本地模型协议与 Mock HTTP，独立 Chromium 验收留给验收会话。可选新原语及外部 LLM、MCP 按获批条件本轮不启用。模拟时钟、终止策略及完整因果链仍按后续阶段实施。

## 首次验收缺陷修复

| 缺陷 | 修复前复现与根因 | 修改 |
| --- | --- | --- |
| STAGE02-001（MAJOR） | 向初始余额为 1,000,000,000,000 分的账户转入 1 分，旧契约本应接受，候选却记录 `ONTOLOGY_CONSTRAINT_VIOLATION`。`OntologyConstraints.bankTotals` 把初始模板录入上限当作运行态不变量。 | `state/OntologyConstraints.java` 的运行态校验保留非负整数分、币种格式和按币种精确求和守恒。初始余额上限由 `BankEnvironment` 的模板录入校验保持。冻结本体的显式约束及转账原语的精度、币种匹配和溢出检查继续生效。 |
| STAGE02-002（BLOCKER） | JVM 中先以角色范围读取并缓存 `tx`，再以显式两账户任务范围查询同一交易。候选仍返回交易。委派原先只收窄直接对象列表，派生可见性计算会再次加入交易，查询适配器也会重建不带上限的角色。 | `SimulationTypes.java` 与 `state/VisibilityPolicy.java` 保留仅运行时使用的显式上限。协调器、上下文及 `runtime/HierarchyRuntimeHost.java` 使用统一委派计算，交集范围用于查询、能力和 Action。 `agent/WorkerTools.java` 与 `graph/RunGraphReadService.java` 使用实际投影范围完成缓存和审计，证据引用随该范围校验。 |

新增回归覆盖 `ContinuationIntegrationTest` 的 1 分转账、模板上限、快照和幂等重试，`DeclarativeActionEngineTest` 的溢出拒绝，`ObjectiveGraphQueryTest` 的派生范围、缓存、字段引用和证据隔离，以及 `WorkerToolsTest` 的 SDK 工具查询和审计范围。

## 修复验证

| 命令或复测入口 | 结果 |
| --- | --- |
| `mvn test` 定向执行 `ContinuationIntegrationTest#commitsALegalTransferBeyondTheInitialBalanceCapAndPreservesTheTemplateCap` 和 `ObjectiveGraphQueryTest#taskScopeCapsDerivedTransactionsInQueriesCacheAndCitations` | 修复前退出 1，两项均按首次验收描述失败。 |
| 后端全 reactor `mvn test` | 109 项通过，0 失败、0 错误、0 跳过。 |
| `mvn -pl app,bootstart spotless:apply spotless:check` | 通过。 |
| `pnpm.cmd test` 和 `pnpm.cmd build --outDir <临时目录>` | 82 项通过，生产构建成功。Vite 保留基线已有的分块提示。 |
| 隔离副本的 Reviewer 后端夹具 | 从 attempt-01 证据索引校验并原样复制五个测试类。15 项通过，含上轮失败的两个用例。 |
| `java @graph-fixture-arguments.txt` 和 `java @evidence-fixture-arguments.txt` | 原路径排名、预算、缓存隔离及证据夹具通过。 |
| `python http_fixture.py baseline/candidate/compare/reopen`（分别执行） | 银行及信息 Mock HTTP、SSE 续传、状态和事件轨迹通过。同输入在已批准附加审计字段以外零差异。V9 库中的三条历史运行重开后零差异。 |
| `python concurrency_fixture.py` | 基线和修复版各一次 HTTP 200 和一次 409。两版均只提交一次并保持审计顺序。 |
| `node browser-fixture-verified.mjs` | 基线和修复版生产构建各通过 9 项 Chromium 检查，覆盖研判、引用、原文展开、SSE 去重和回放。 |
| `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 30 项通过，Stage Gate 实现与通过条件无差异。 |

通过原有全量测试、已固定验收夹具和差异审查复核此前 38 项通过项所依赖的源码及运行证据，开发复测未发现回归。原验收 `FAILED` 结论保留，修复候选待独立重新验收。基线、冻结清单、批准记录、Stage Gate 规则和迁移文件无差异。

开发复测日志位于临时目录。以下摘要用于核对本轮实际输出。

| 文件 | SHA-256 |
| --- | --- |
| `gass-stage02-repair-red.log` | `970fd9ec280c8f131f63a35ed5568f3ef8726e785cb806179feaa822878eee0b` |
| `gass-stage02-repair-maven.log` | `f9e95c4930b5b3aeadbf49e38f5d280bf07aad8e375f8e2b09c1d1ba131f8ef4` |
| `gass-stage02-repair-pnpm-test.log` | `04522782b2007990d3af9bd6216a899c4804c3447c84c6ad65debae93fe80fd3` |
| `gass-stage02-repair-pnpm-build.log` | `832c7f28d94d78bdb43a5b4dbbf36f46667d10a33b8ea59faf2be4fd9bf26f92` |
| `gass-stage02-repair-regression-3UqvCg/candidate/review-fixtures.log` | `21de668d11da9641b80986561428d5f731f6271c96baf5a5f07e8c2b4c4f44eb` |
| `gass-stage02-repair-regression-3UqvCg/stage2-http-analysis.json` | `3d4b93121e1471468dedec48725fd89fbd8faf02d1f07730e005a59dd963d563` |
| `gass-stage02-repair-regression-3UqvCg/candidate-reopen-comparison.json` | `820d764333efe8f9337e9482e0cbaed2381f98195e2f4b18bf01feeabc77e354` |
| `gass-stage02-repair-regression-3UqvCg/candidate-concurrency.json` | `038caf7f81c0b06e22124e587dcc3b20eb775ec104fb930ec26d184d8dc18888` |
| `gass-stage02-repair-regression-3UqvCg/candidate-browser-verified.log` | `e268b389d4ebc305b361c23c797d59b1f9e3abdee3fbd88ec22694e450cda390` |
