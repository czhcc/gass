# 第三阶段开发报告

状态为 **待独立验收**。

- 开发分支 `feat/simulation-clock-scheduler`
- 基线 `9a266333d0a7f5c08282068643da6840d1a42d9b`
- 候选 `2cf4ad3f7d963d9bd32a16b9db38f2652afe4c90`
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
