# 第四阶段开发报告

状态为 **待独立验收**。

- 分支 `feat/eca-propagation`
- 基线 `f67ef4851405f91f2b8ce56d01f7b0a99860e7ec`
- 候选 `b7e2f1937f0791ad664f27b55555f3d21fdc9078`
- 实际批准时间 `2026-11-27T09:12:56.8680310+08:00`
- 冻结时间 `2026-11-27T09:12:57.1790244+08:00`
- 冻结清单 SHA-256 `7daf69e50b80012ea9876a28605a57b9240cc092678456ad01b84805383e50a0`
- [批准与冻结记录](check-manifest.json) · [基线记录](baseline.json) · [候选登记](candidate.json)

## 实现与开发验证

| 范围 | 主要实现 | 开发验证 |
| --- | --- | --- |
| S4-01 至 S4-03，E2E-B | `EcaRuntime`、`RuleEvaluation`、`ActionCommitService` | 设备 A 在 T0 故障，B 在 T1 不可用，C/D 在 T2 按序中断。E 保持正常。受控关系方向、备份条件、no-op 和零模型调用均有覆盖。 |
| S4-04 至 S4-06，E2E-H | `TriggerRuleCatalog`、`CelExpressions`、`ContinuationController` | 校验 CEL 及绑定。预览只读，授权范围外的对象和伪造 SYSTEM_RULE 来源的请求被拒绝。到期重校验备份前置条件，父 Action 保留。 |
| S4-07 至 S4-11，E2E-F/G | `EventClockScheduler`、`RuleWork`、`HistoryService`、`V13__trigger_rules.sql` | 规则消费及 Action 完成故障注入、重复投递、同刻预算及因果深度保护、进程重启、版本冻结、历史分支和 V12 收据升级。 |
| S4-12 至 S4-13，E2E-L/M | `EventRunPolicy`、`ActionCommitService`、`EcaRuntime` | HTTP 时限测试保留两项未来自动工作，逐事件复算 causeEventRef、规则修订和 actionRunId。 |
| S4-14 | `TriggerRuleController`、`Rules.vue`、`Workspace.vue`、`eventPresentation.ts` | 草稿、校验、只读预览、发布和场景绑定。新增双语来源和因果字段，旧事件和未知类型回归继续通过。 |
| G-01 与阶段门禁 | `scripts/acceptance/stage_gate.py`、`scripts/acceptance/test_stage_gate.py` | 扩展第四阶段的四组独立专项命令和各自 JUnit 证据。 |

规则需显式绑定到严格事件场景。规则条件使用授权的变化快照，目标选择使用当前授权图谱。规则消费与派生入队同事务提交。自动 Action 与原有 Action 使用统一执行路径，持久收据与因果引用随状态更新提交。V13 扩展工作种类和收据来源，并保留 V1 至 V12 迁移及历史数据。

首版规则管理用 JSON 编辑器配置，场景可从当前世界的发布版本中选择绑定。首版响应为 `EXECUTE_ACTION`，合成规则不代表现实因果。外部 LLM 和 MCP 按获批条件关闭。

## 测试与证据

在冻结后先加入传播、规则展示和门禁测试。修正设备夹具的本体版本及既有 Action 参数名后，隔离基线复跑 6 项，均因 `/api/rules` 返回 404 失败。门禁新增 2 项在旧脚本失败，前端新增事件展示用例在旧组件失败。保留 `baseline-feature-red.log`、`gate-red.log` 和 `front-red.log`。

日志保存在本机临时目录 `D:/temp/gass-stage04-development-1795742244900`。后端环境为 Oracle JDK 21.0.8 和 Maven 3.9.16，前端用 Node 24.22.1 和 pnpm 11.25.0，脚本使用 Python 3.12.7。

| 工作目录 | 命令 | 开始 | 结束 | 退出码 | 结果 | 日志及 SHA-256 |
| --- | --- | --- | --- | --- | --- | --- |
| backend | `mvn.cmd -B -o -s <settings.xml> test` | 2026-11-27T09:43:51.9420076+08:00 | 2026-11-27T09:44:26.3909193+08:00 | 0 | 132 项通过，失败、错误和跳过为 0。 | `backend.log`，`0dde69081cccc1e3a01d58565354818fb52144230b10dc9577bddd1d8baf8667` |
| backend | `mvn.cmd -B -o -s <settings.xml> -pl app,bootstart spotless:check` | 2026-11-27T09:44:26.4360537+08:00 | 2026-11-27T09:44:28.4170902+08:00 | 0 | 通过 | `format-check.log`，`0d8719b21752d81a1a894a7d5daa566547a8ece19e3bef67902e8a10f119fd41` |
| backend | `mvn.cmd -B -o -s <settings.xml> -DskipTests package` | 2026-11-27T09:44:28.4588905+08:00 | 2026-11-27T09:44:35.7414457+08:00 | 0 | 全量测试后对冒烟服务单独打包。 | `package.log`，`7c39496674329645b5b068fe030b2d48060facead21f99e5b10da1fbf1bb80a8` |
| frontend | `pnpm.cmd test` | 2026-11-27T09:34:37.8259934+08:00 | 2026-11-27T09:34:42.3416510+08:00 | 0 | 14 个测试文件，86 项通过。 | `frontend-test.log`，`f6bdc0d488aac9517f0dd886b38b12214d71ac8b4a82429e70a019fe478e748a` |
| frontend | `pnpm.cmd build --outDir <临时目录>/dist` | 2026-11-27T09:34:42.4142623+08:00 | 2026-11-27T09:34:49.6131898+08:00 | 0 | 成功，保留基线已有的 500 kB 分块提示。 | `frontend-build.log`，`20f1ffd049804908783de2dd2c0c7b6c6815e3beabf655156cb37a9e044a1d83` |
| 仓库根目录 | `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 2026-11-27T09:39:52.0114902+08:00 | 2026-11-27T09:39:52.3152537+08:00 | 0 | 34 项通过。 | `gate-test.log`，`3d357658b110f131001eb827393129fed659150f77ea29dbe3e6f6ace9236ffa` |
| backend | `python stage4_http.py --repo <仓库> --evidence <隔离目录>` | 2026-11-27T09:44:35.7952375+08:00 | 2026-11-27T09:44:51.0626367+08:00 | 0 | 旧银行/信息场景、规则事务失败及真实重启、时限保留工作和显式因果链通过。 | `http.log`，`a5af2be337d22b110d05479bee715c2b877afcbc4ce250dc4567b55c75684c30` |

同一临时 SQLite 在进程重启后继续匹配。审计记录 `http-final/stage4-audit.json` 的 SHA-256 为 `5d97b528ae30bb17ffed362104b32cde5b76ec756cfdfbc6f5b9fc81cfa01aaa`，验证夹具 `stage4_http.py` 的 SHA-256 为 `70729e50135d06a45e940d73545350407bb08f1a218b561e6f6ddbee54abbb08`。

## 交付

独立 Reviewer 以固定候选执行冻结 79 项及自动 Stage Gate。源代码回滚点为阶段基线。数据库升级通过 V13 迁移后的回滚应搭配 V13 前备份，并保留迁移后数据供核对。
