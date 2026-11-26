# 第三阶段开发报告

状态为 **待重新验收**。

- 开发分支 `feat/simulation-clock-scheduler`
- 基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`
- 首次候选 `2cf4ad3f7d963d9bd32a16b9db38f2652afe4c90`
- 项目负责人批准时间 `2026-11-26T15:04:16.6327624+08:00`
- 冻结时间 `2026-11-26T15:04:16.8344445+08:00`
- 冻结清单 SHA-256 `3ce65d029034063d1761976e4d6d7dfa7528f3b4efc9750192a03decb7647c3b`
- [批准与冻结记录](check-manifest.json) · [基线记录](baseline.json) · [候选登记](candidate.json)

## 实现与开发验证

| 检查范围 | 实现位置 | 开发验证 |
| --- | --- | --- |
| S3-01 至 S3-03、S3-07 | `EventClockScheduler`、`RunService`、`SimulationTypes`、`WorldService` | 同刻 15 项、排序、预算、逻辑跳转和旧模式。 |
| S3-04 至 S3-06 | `V11__event_clock_queue.sql`、`ActionCommitService`、`HistoryService` | 控制 I/O 期间停止推演，验证事务故障恢复、银行输入一次性确认、按审计序号 Fork 及实际重启。 |
| S3-08 至 S3-13、E2E-L | `EventRunPolicy`、`RunTerminationPolicy`、`ObjectiveReviewService` | 本地模型协议覆盖早期文字回答和评价时点、继续处理和显式保留未来项。专项覆盖图谱变化后重研判、时限与静止状态。 |
| G-06、G-08、G-12 | `SimulationController`、`eventPresentation.ts`、`docs/API.md`、`docs/ARCHITECTURE.md`、`docs/HIERARCHICAL_RUNTIME.md` | 队列读取、双语时钟事件、旧银行及信息场景回归。 |
| G-01 与自动准入 | `scripts/acceptance/stage_gate.py`、`scripts/acceptance/test_stage_gate.py` | 第三阶段复用既有确定性判定，并要求四组新增的独立专项命令及对应 JUnit 报告。 |

严格事件时间通过版本 1 的 `scheduling` 显式选择，单位为 `TICK`。缺省场景沿用历史轮次契约。V11 追加工作状态、优先级、入队和完成位置。领域事件入队与 Action 收据、状态及输入确认同事务保存。

## 测试证据

环境为 Windows 11 amd64、Oracle JDK 21.0.8、Maven 3.9.16、Node 24.22.1、pnpm 11.25.0 和 Python 3.12.7。开发日志及隔离 SQLite 证据目录为 `D:/temp/gass-stage03-development-1795678021265`。

| 工作目录 | 命令 | 开始 | 结束 | 退出码 | 结果 | 日志及 SHA-256 |
| --- | --- | --- | --- | --- | --- | --- |
| backend | `mvn.cmd -B -o -s <settings.xml> test` | 2026-11-26T15:39:52.7773329+08:00 | 2026-11-26T15:40:33.5657374+08:00 | 0 | 119 项通过，失败、错误及跳过均为 0。 | `maven.log`，`2c1e112d46b27e1bd350fbba8bf32d27a346291844d0411989913572d4e99e39` |
| backend | `mvn.cmd -B -o -s <settings.xml> -pl app,bootstart spotless:check` | 2026-11-26T15:39:50.3114494+08:00 | 2026-11-26T15:39:52.7331170+08:00 | 0 | 通过 | `format.log`，`8a6239b94db1ac4b6af9b8339e52d4fff6f6c108d3c321cf1ff98b0a989fb487` |
| backend | `mvn.cmd -B -o -s <settings.xml> -DskipTests package` | 2026-11-26T15:40:33.6798562+08:00 | 2026-11-26T15:40:45.6876745+08:00 | 0 | 全量测试完成后单独打包，供 HTTP 验证。 | `package.log`，`258067264d1a1dafc654d791a7b035a0c581874511770a9a04ae27a758da2e7e` |
| frontend | `pnpm.cmd test` | 2026-11-26T15:27:03.4649922+08:00 | 2026-11-26T15:27:08.0795444+08:00 | 0 | 13 个测试文件，83 项通过。 | `frontend-test.log`，`4e9dcbd59c51f2d2c08ebc4604d1999dfb843994c28040ab6699f42e114a5a7e` |
| frontend | `pnpm.cmd build --outDir <临时目录>/dist` | 2026-11-26T15:27:02.6534775+08:00 | 2026-11-26T15:27:10.6833543+08:00 | 0 | 成功，保留基线已有的 500 kB 分块提示。 | `frontend-build.log`，`0c6f287709a0efea5022cd996f2412da00829c95743de362eb8685cd87de7c6e` |
| 仓库根目录 | `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 2026-11-26T15:27:04.2628663+08:00 | 2026-11-26T15:27:04.5945487+08:00 | 0 | 32 项通过。 | `gate-test.log`，`637f70e8c416530efb40b2cf2354de16352e74336e74e3db78df0acb500b250c` |
| 仓库根目录 | `python strict_http_fixture.py --repo <仓库> --evidence <隔离目录>` | 2026-11-26T15:41:43.8278420+08:00 | 2026-11-26T15:42:02.5753457+08:00 | 0 | 旧场景和严格模式 HTTP、SQLite 重开、只读回放和 SSE 通过。 | `http.log`，`ee5f5cebeb481686ffca59240afb4dbc7c5eae2bca88fad1382615a078665d41` |

HTTP 夹具 SHA-256 为 `ee533406b0f621c0c9c4f27473d174e1d5a9cbb7404f2b82287a35506d94a22c`。旧场景使用 `python scripts/smoke_mock.py --base-url <隔离回环地址>`，日志 `smoke-verified/http-legacy.log` 的 SHA-256 为 `f76e319538edbfe8c8b95862190091d861d5d9844b541da80ee30def52eac272`。严格模式在 T=5 投递 15 项，重启后保留 T=100 的待办。重试原步进键后累计交付 16 项，SSE 续读序号为 52、53。

## 交付

独立 Reviewer 对固定候选执行冻结的 60 项检查并采集独立证据。外部 LLM 和 MCP 联调按获批条件关闭。

源码回滚点为阶段基线。已迁移环境的数据库回滚使用 V11 迁移前的备份，并保留迁移后的运行数据供核对。

## STAGE03-001 修复与重新验收候选

- 第二次验收候选 `68898b543c680ea926b61b06569b2bd0c2c9a750`。原基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`。
- 冻结清单 SHA-256 `3ce65d029034063d1761976e4d6d7dfa7528f3b4efc9750192a03decb7647c3b`。批准记录及基线材料逐字节核对，Stage Gate 代码和通过条件保持原样。
- 首轮报告记录 G-10、S3-04、S3-05 失败。保留 [首轮报告](attempt-01/review.md)、[原门禁结果](attempt-01/gate-report.json) 和 [证据索引](attempt-01/evidence-index.json)。

### 根因与事务边界

旧候选在投递事务内将普通输入标记为已完成，同时写入步进键。待研判信息只由本次投递返回的输入数量表示。投递后故障时，原键因已经存在而提前返回。暂停后恢复虽使用新键，待办查询只获得尚未投递的工作，导致研判遗漏，并可在版本仍为 0 时结束推演。

- `EventClockScheduler` 与 `DurableReasoning` 将时钟、投递审计、需要研判的输入状态和待研判检查点同事务提交。普通输入可已送达且仍处于 `PROCESSING`。步进键关联轮次，并可在检查点尚未完成时重试。
- `RunService` 恢复原逻辑时刻及原轮次。开轮计数和审计同事务写入。完成审计、持久化检查点关闭及普通输入的完成状态同事务写入。`EventRunPolicy` 在未完成检查点存在时继续阻止正常终止。
- `ActionCommitService` 将本轮已裁定 Action 的检查点与世界状态、业务收据、审计、输入确认及领域待办同事务提交。普通 Agent 恢复时跳过已裁定的决策。主从流程恢复时用同轮、同一智能体、同一选择器、参数和输入标识核对已接受 Action，并按当前可见范围读取原收据。模型调用始终在写事务之外。
- `HistoryService` 按审计序号复制当时的未完成研判及已生效的 Action 检查点。父子分支分别持有后续写入。归属不同运行的旧收据保留为历史记录。
- `V12__durable_reasoning_round.sql` 追加迁移，V1 至 V11 保持原样。恢复依赖 V12 创建的持久化检查点。早期 V11 记录按已有审计和状态读取。

### 复现与开发回归

先在旧候选上新增回归测试。`EventClockIntegrationTest` 的三条新增用例分别复现投递后开轮失败、暂停后遗漏和 Action 已提交后无法完成原研判。旧候选运行 8 项，3 项失败。原始退出码 1，`reproduced.log` 的 SHA-256 为 `cc8238e7c338e750e6634abf00af33294bd7f362a66cd6d18fa801c019f6031d`。

修复后的开发证据位于 `D:/temp/gass-stage03-fix-1795681956399`。同输入重跑首轮原版 Java、浏览器、HTTP、并发、图谱及证据夹具。此前已通过检查所涉及的这些开发回归未发现新失败。

| 工作目录 | 命令 | 开始时间 | 退出码 | 实际结果 | 日志及 SHA-256 |
| --- | --- | --- | --- | --- | --- |
| backend | `mvn.cmd -B -o -s <settings.xml> test` | 2026-11-26T16:48:38.2448503+08:00 | 0 | 123 项，失败、错误及跳过均为 0 | `backend.log`，`aa9f7d021196740f1e3f9191937e8a00233e3c687496b9dd88ea9176c225e41c` |
| backend | `mvn.cmd -B -o -s <settings.xml> -pl app,bootstart spotless:check` | 2026-11-26T16:51:27.8496995+08:00 | 0 | 通过 | `spotless.log`，`4cb75462369429882c8c3dc161e8c545844c114b78e7c467f40cad9b16e5eb31` |
| frontend | `pnpm.cmd test` | 2026-11-26T16:48:20.5430257+08:00 | 0 | 83 项，13 个测试文件 | `frontend-test.log`，`a7984f7109f41b9cff6f76265d48e5affdb1c675a5d32758fc2a18c6bda0ad7f` |
| frontend | `pnpm.cmd build --outDir <临时目录>` | 2026-11-26T16:48:19.7190703+08:00 | 0 | 成功，保留原有 500 kB 分块提示 | `frontend-build.log`，`67cae8ed2b0f5ff59657d8ba31af4995b7b1fa091a9efdd11044ba765b43f26b` |
| 仓库根目录 | `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 2026-11-26T16:48:21.4762692+08:00 | 0 | 32 项 | `gate.log`，`7dbb86716ba090f9d63f106200c65603b59e51e124a380e84a45361477522acb` |
| 临时副本/backend | `mvn.cmd -B -o -s <settings.xml> -Dtest=Reviewer*Test -Dsurefire.failIfNoSpecifiedTests=false test` | 2026-11-26T16:48:38.1597819+08:00 | 0 | 首轮原版夹具 27 项，含暂停、故障注入、阶段二回归及阶段三专项 | `reviewer-junit.log`，`d4664bce46eee0a0af2bbe7d305d6c7bdaaedbd4060fb647cea919855857da16` |
| 临时回归目录 | `python reopen_pending.py` | 2026-11-26T16:51:30.5209111+08:00 | 0 | 研判启动前版本 0→1，Action 后版本保持 1。两次均在 T2，各一次投递、一笔状态变更 | `restart.log`，`9fcf08ca8b438b3402bf26fb7ef1624a9efab8aabac74f2945e73b3f6b1a52b3` |
| 临时回归目录 | `python http_fixture.py candidate` | 2026-11-26T16:51:16.0218984+08:00 | 0 | 旧银行和信息场景、分支、幂等与 SSE | `http.log`，`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| 临时回归目录 | `python http_fixture.py compare` | 2026-11-26T16:51:25.2594893+08:00 | 0 | 冻结原始基线对照，除第二阶段已认可的新增元数据外，差异为 0 | `compare.log`，`ab659515db087128af8eda3edc4827707c1a9ebe9ca056872867d3ce45044c64` |
| 临时回归目录 | `python http_fixture.py reopen` | 2026-11-26T16:51:25.5057367+08:00 | 0 | 3 个历史运行重开，差异为 0 | `historical.log`，`876225944dd246c26fd6f52fe14ce99f137a9f71af6e233d1ea2eefbd2c23d1c` |
| 临时回归目录 | `python concurrency_fixture.py` | 2026-11-26T16:51:28.4484725+08:00 | 0 | 两版并发写入均返回 200 和 409，图谱版本为 1 | `concurrency.log`，`c7109228aad14f7d0040a69fb6c6ffd1fe85e50c1200f1f2d04cbdf0c0646239` |
| 临时回归目录 | `node browser-fixture-verified.mjs` | 2026-11-26T16:51:41.2685304+08:00 | 0 | 两版生产构建的历史回放与旧报告兼容通过 | `browser.log`，`a5bd4958e6a57f0e9feee8c4fd11fb3d94780f60aa2db8f6cb985df475b3c118` |
| 临时副本 | `java @graph-fixture-arguments.txt` | 2026-11-26T16:51:09.7156735+08:00 | 0 | 图谱路径、截断及缓存隔离通过 | `graph.log`，`e760086f8ad8b1c0d3853fabe8a3a01ed1b13b2e95acfe7c683b1b258c15caa0` |
| 临时副本 | `java @evidence-fixture-arguments.txt` | 2026-11-26T16:51:10.9241668+08:00 | 0 | 当前版本证据、作用域及历史回执通过 | `evidence.log`，`9ef97320941800814aee1540cc9cff8614c3c403a4673e19534324ca6d533f82` |

验证另覆盖银行 Action 已提交后主任务中断，恢复仍只有一次转账且资金总额守恒。真实进程重启的逐事件轨迹保存在 `regression/pending-process-restart.json`。本轮为开发验证。60 项检查的独立结论和下一阶段准入由新的验收尝试裁定。
