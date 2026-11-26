# GASS 第二阶段独立验收报告（第 2 次）

- 范围为第二阶段 46 项必验，包括本体、Action、提交与权限的专项验证，以及第一阶段累计回归。
- 原始基线 `2708c13de67db48877bf54c5e11929c9187a758a`，修复候选 `fc7b03d5fedce90b7fc0bf792ee0cb833a6f4d89`，登记提交 `c095a79cac400b8aac12723a2bdfc72ef9e41397`。
- 开发指南修订版 2 和验收指南 V1.0 的哈希与批准清单一致。冻结 SHA-256 为 `ad0697d6c94a929d51c1a09317d9517993e4307383c469c6129c371bd111121b`。
- 批准时间 `2026-11-26T11:20:46.3398371+08:00`，冻结时间 `2026-11-26T11:21:10.252+08:00`。冻结清单、草案、摘要及基线记录与 `96f63bac2a495b2a5357ee0da4eddc5ca83c1250` 逐字节一致。第一阶段准入报告哈希和祖先关系通过。
- 首轮候选 `356ea246171d6bcc252747b08301d82ae8e801f1` 的 `FAILED` 结果和 160 份证据核对通过。首轮登记从原 Git 提交恢复至隔离目录以验证其原哈希。首轮报告目录保持原样。
- Reviewer 会话 `gass-s2-attempt02-x65t1d`。固定 SHA 导出到隔离副本，每版使用独立 SQLite、回环端口和本地模型协议。

## 阶段准入

**PASSED（第二阶段验收通过）**。自动 Stage Gate 实际退出 `0`，`nextStageAllowed=true`。门禁复核 46 项必验、33 条必需命令记录及 170 份证据。STAGE02-001 与 STAGE02-002 已关闭。 [门禁报告](gate-report.json)

## 缺陷修复复现

- **STAGE02-001（MAJOR）已关闭。** 同一原始夹具在旧候选退出 1，修复候选退出 0；独立 HTTP 测试确认 1 分转账后账户余额为 1000000000001 分，资金守恒、版本只增一次、回放及幂等重试一致。初始模板超过上限返回 HTTP 400。
- **STAGE02-002（BLOCKER）已关闭。** 同一原始夹具在旧候选错误放行，在修复候选拒绝。额外夹具确认宽范围缓存与窄范围隔离、嵌套缩窄不能重新扩大、允许范围正例可读，且宽范围证据不可由窄范围引用。完整套件重跑 WorkerTools 审计用例。

两处用例均从首轮证据索引按 SHA-256 校验后逐字节复制。相同断言分别对旧候选和最新候选实际执行。旧候选的两条预期失败退出码、原始日志及 XML 摘要见 EV-158，修复候选的成功结果见下表和证据索引。

## 检查矩阵

| 编号 | 实际结果 | 证据组编号 | 状态 |
| --- | --- | --- | --- |
| G-01 | 核对原始冻结提交、批准时序、指南 SHA-256、基线、第一阶段门禁和最新候选登记。四份冻结材料逐字节相同。 | EV-001, EV-002, EV-003 | PASSED |
| G-02 | 变化限本体、Action、统一提交、权限、证据及准入工具。未启用 ECA、外部导入或可选原语。 | EV-004, EV-005, EV-006 | PASSED |
| G-03 | JDK 21、Maven、Node 24、pnpm 11.25.0 已核对。POM、前端依赖和默认配置未改，Maven 模块方向保持。 | EV-029, EV-030, EV-081 | PASSED |
| G-04 | 基线和候选测试 diff 未删旧断言或新增跳过。全量用例从 94 增至 109，前端用例原样。 | EV-004, EV-005, EV-006 | PASSED |
| G-05 | 基线 94 项、修复候选 109 项，均为 Maven 全 reactor 独立执行，零失败、零错误、零跳过。 | EV-007, EV-008, EV-009 | PASSED |
| G-06 | 两版冻结安装、82 项前端测试、生产构建及真实 Chrome 回放均通过。 | EV-031, EV-032, EV-083 | PASSED |
| G-07 | V1–V9 原样保留，V10 仅新增收据表。空库、V9 升级及候选重开三条历史运行通过。 | EV-004, EV-005, EV-006 | PASSED |
| G-08 | 历史 REST、JSON 与事件读取兼容。旧银行 Action 的 1 分边界恢复，独立 HTTP 验证原响应、真实提交和回放。 | EV-004, EV-005, EV-006 | PASSED |
| G-09 | 显式排除派生交易的任务无法查询它，已有宽范围缓存不可复用，局部证据引用被拒绝。保留允许范围的正例。 | EV-004, EV-005, EV-006 | PASSED |
| G-10 | 同版本并发为一次 HTTP 200、一次 409。快照、收据和审计写入故障回滚、重试及只读回放通过。 | EV-007, EV-008, EV-009 | PASSED |
| G-11 | 银行、信息、分支、暂停/恢复、SSE 续传和常规模拟账款通过。银行金额守恒，单次转账。 | EV-007, EV-008, EV-009 | PASSED |
| G-12 | 候选 Spotless 通过，迁移命名、Java 注释、双语原样。Chrome 验证实际构建。 | EV-004, EV-005, EV-006 | PASSED |
| G-13 | 两次报告单独保存。首轮 160 份证据逐项核对，旧候选登记从其 Git 提交恢复后匹配原摘要。二轮证据另行采集。 | EV-001, EV-002, EV-003 | PASSED |
| S1-01 | graph、evidence、runtime 的业务边界保留，新增 state 用于共享确定性校验和投影。 | EV-004, EV-005, EV-006 | PASSED |
| S1-02 | 生命周期入口及调度、提交、持久化职责继续拆分。 | EV-004, EV-005, EV-006 | PASSED |
| S1-03 | 主从仍由协调器编排，工具、授权、研判及上下文边界保留。两版本地协议验证。 | EV-004, EV-005, EV-006 | PASSED |
| S1-04 | 两版 Spring 实际 HTTP 启动和 Controller 集成测试通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-05 | V1–V9 不变，V10 追加；普通 Mock 轨迹和边界 Action 与旧契约兼容，工具名、协议和旧 JSON 保留。 | EV-004, EV-005, EV-006 | PASSED |
| S1-06 | 同版本双请求、取消、恢复和事务回滚通过，运行时共享监视器仍在。 | EV-004, EV-005, EV-006 | PASSED |
| S1-07 | 同输入银行/信息 Mock 比较通过。余额与裁定、收据、状态、逻辑时间和事件相对顺序一致。 | EV-007, EV-008, EV-009 | PASSED |
| S1-08 | V9 冻结 Action、历史快照、Fork 及只读回放继续通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-09 | 两版用本地 AgentScope 协议夹具投递无关告警，QUERY 执行查询且无状态变更。 | EV-007, EV-008, EV-009 | PASSED |
| S1-10 | 两版独立测试通过 64 跳、50000 探索预算、有向路径、并列和截断。 | EV-007, EV-008, EV-009 | PASSED |
| S1-11 | 同运行同版本同精确范围仍命中，跨运行、版本、分支及不同委派范围隔离。窄范围查询可产生自身审计。 | EV-007, EV-008, EV-009 | PASSED |
| S1-12 | 跨轮只复用当前版本图查询，Action 改变状态后旧查询从当前上下文排除。 | EV-007, EV-008, EV-009 | PASSED |
| S1-13 | 有效证据、NO_CONDITIONS、CEL horizon 及旧记录限全范围使用的预期通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-14 | 历史 Action 与最终状态分别投影，仍返回实际持久事件序号。新增显式 actionRunId 关联通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-15 | Chrome 通过新旧报告、原文、引用跳转、SSE 去重与回放。真实后端 SSE 也通过。 | EV-033, EV-034, EV-035 | PASSED |
| S1-16 | 源码与文档差异及提交范围可核对，保留前一阶段的验收记录。 | EV-001, EV-002, EV-003 | PASSED |
| E2E-A | 银行及信息 Mock、分支与历史回放通过。核对批准新增字段与范围集合后，同输入轨迹零业务差异。 | EV-042, EV-043, EV-044 | PASSED |
| E2E-K | 文字目标、图路径与证据测试通过，旧未知范围按本阶段规则处理。 | EV-007, EV-008, EV-009 | PASSED |
| S2-01 | V9 升级后读出三条既有运行、冻结 Action、快照、回放。旧本体边界转账在最新候选成功。 | EV-007, EV-008, EV-009 | PASSED |
| S2-02 | 独立用例通过默认值、字符串替代整数、缺必需、超范围、非法枚举、不可变和未知字段验证。 | EV-007, EV-008, EV-009 | PASSED |
| S2-03 | 独立重复边、悬空端点及基数负例通过。既有套件验证非法金融值和关系端点类型。 | EV-007, EV-008, EV-009 | PASSED |
| S2-04 | 原三种原语和保护字段负例通过。初始录入超过上限仍拒绝，已有账户通过 1 分转账在运行中可合法越过录入上限，资金守恒。 | EV-007, EV-008, EV-009 | PASSED |
| S2-05 | 原有多步失败测试及严格约束失败保护原状态，独立故障测试确认提交阶段原子回滚。 | EV-007, EV-008, EV-009 | PASSED |
| S2-06 | 独立差异测试区分缺失/null、等价数字。原有无净变更和单次版本提交测试通过。 | EV-007, EV-008, EV-009 | PASSED |
| S2-07 | 同版本并发仅一笔有效提交，同键同负载重试保留原收据，同键不同负载被拒绝。 | EV-046, EV-098, EV-140 | PASSED |
| S2-08 | 独立审计写入失败在快照之后触发，状态、快照、事件和收据全部回滚，同键重试成功。 | EV-007, EV-008, EV-009 | PASSED |
| S2-09 | 端点可见但交易被排除时，查询、证据引用和宽范围缓存均受显式上限限制。独立验证缩窄后不能再放宽，显式允许交易时可读取。 | EV-007, EV-008, EV-009 | PASSED |
| S2-10 | 运行、版本和精确范围的缓存隔离通过。宽范围预热后，窄范围不会命中该项，同窄范围重复查询正常命中。 | EV-038, EV-039, EV-090 | PASSED |
| S2-11 | 未知 scopeIds 的旧查询对局部范围拒绝，证明全范围时仍可用。独立用例通过。 | EV-040, EV-041, EV-092 | PASSED |
| S2-12 | 显式 actionRunId 在无关事件交错时仍正确关联。三条旧运行 V9→V10 重开结果零差异。 | EV-007, EV-008, EV-009 | PASSED |
| S2-13 | 实际提交核对发起人、来源、命令、版本、证据及只读回放。变更事件证据为 SIMULATED，来源独立记录。 | EV-007, EV-008, EV-009 | PASSED |
| S2-14 | 完整搜索但未返回全部并列结果时不接受为完成依据。参考范围、版本及历史操作分开验证。 | EV-038, EV-039, EV-090 | PASSED |
| E2E-M | 真实 HTTP 新增提交标识串联裁定/状态/收据，本地审计插入与旧历史回放核对通过。 | EV-126, EV-127, EV-128 | PASSED |

## 命令、测试与证据

| 命令 | 退出码 | 总数 / 失败 / 错误 / 跳过 | 日志 |
| --- | ---: | --- | --- |
| CMD-INPUT | 0 | — | EV-001 |
| CMD-STATIC | 0 | — | EV-004 |
| CMD-BASELINE-MAVEN | 0 | 94 / 0 / 0 / 0 | EV-007 |
| CMD-BASELINE-ENV | 0 | — | EV-029 |
| CMD-BASELINE-PNPM-RETRY-INSTALL | 0 | — | EV-031 |
| CMD-BASELINE-PNPM-RETRY-TEST | 0 | 82 / 0 / 0 / 0 | EV-033 |
| CMD-BASELINE-PNPM-RETRY-BUILD | 0 | — | EV-036 |
| CMD-BASELINE-GRAPH | 0 | — | EV-038 |
| CMD-BASELINE-EVIDENCE-RETRY | 0 | — | EV-040 |
| CMD-BASELINE-SMOKE | 0 | — | EV-042 |
| CMD-BASELINE-CONCURRENCY | 0 | — | EV-046 |
| CMD-BASELINE-BROWSER-VERIFIED | 0 | — | EV-047 |
| CMD-BASELINE-QUERY-OBJECTIVE | 0 | 1 / 0 / 0 / 0 | EV-050 |
| CMD-BASELINE-LEGACY-BANK | 0 | 1 / 0 / 0 / 0 | EV-054 |
| CMD-CANDIDATE-MAVEN | 0 | 109 / 0 / 0 / 0 | EV-058 |
| CMD-CANDIDATE-ENV | 0 | — | EV-081 |
| CMD-CANDIDATE-PNPM-RETRY-INSTALL | 0 | — | EV-083 |
| CMD-CANDIDATE-PNPM-RETRY-TEST | 0 | 82 / 0 / 0 / 0 | EV-085 |
| CMD-CANDIDATE-PNPM-RETRY-BUILD | 0 | — | EV-088 |
| CMD-CANDIDATE-GRAPH | 0 | — | EV-090 |
| CMD-CANDIDATE-EVIDENCE-RETRY | 0 | — | EV-092 |
| CMD-CANDIDATE-SMOKE | 0 | — | EV-094 |
| CMD-CANDIDATE-CONCURRENCY | 0 | — | EV-098 |
| CMD-CANDIDATE-BROWSER-VERIFIED | 0 | — | EV-099 |
| CMD-CANDIDATE-QUERY-OBJECTIVE | 0 | 1 / 0 / 0 / 0 | EV-102 |
| CMD-CANDIDATE-REVIEW-ONTOLOGY | 0 | 2 / 0 / 0 / 0 | EV-106 |
| CMD-CANDIDATE-REVIEW-COMMIT | 0 | 2 / 0 / 0 / 0 | EV-110 |
| CMD-CANDIDATE-REVIEW-VISIBILITY | 0 | 2 / 0 / 0 / 0 | EV-114 |
| CMD-CANDIDATE-REVIEW-EVIDENCE | 0 | 2 / 0 / 0 / 0 | EV-118 |
| CMD-CANDIDATE-FORMAT | 0 | — | EV-122 |
| CMD-CANDIDATE-GATE-TESTS | 0 | — | EV-124 |
| CMD-HTTP-COMPARE | 0 | — | EV-126 |
| CMD-HISTORICAL-REOPEN | 0 | — | EV-130 |
| CMD-CANDIDATE-REVIEW-REPAIRSCOPE | 0 | 1 / 0 / 0 / 0 | EV-149 |
| CMD-REPAIR-BANK-HTTP | 0 | — | EV-153 |
| CMD-EVIDENCE-REVIEW | 0 | — | EV-168 |

完整命令、工作目录、开始和结束时间、退出码及检查映射见 [result.json](result.json)。原始输出和测试报告路径及 SHA-256 见 [evidence-index.json](evidence-index.json)。

- 两版分别重跑 Maven、前端冻结安装、82 项测试及生产构建。两版完整后端分别为 94 和 109 项，零失败、零错误、零跳过。Vite 保留基线的分块提示。定向 Reviewer 夹具在全量测试后加入隔离副本。
- 两版银行和信息 HTTP Mock、实际后端 SSE、分支和回放通过。同输入验证获批新增审计字段后，UUID、墙钟和无序 scopeIds 规范化对比无业务差异。V9 副本重开三条历史运行零差异。
- 真实 Chrome 加载两版生产构建，各通过 9 个浏览器检查。合成报告和 SSE 夹具用于浏览器交互，真实后端 HTTP/SSE 和真实 SDK 本地协议单独运行。外部模型、MCP 和延期原语按冻结条件处理。
- 自动准入脚本与首次候选的 Git 内容相同，其候选版自测 30 项通过。Windows 日志按 LF 捕获供门禁解析，原输出保留。历史库重开曾遇到端口被占用，使用新 V9 副本和自动端口重试，确认升级至 V10 且三条历史运行零差异。
