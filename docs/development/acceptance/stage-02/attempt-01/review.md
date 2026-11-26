# GASS 第二阶段独立验收报告（第 1 次）

- 范围为形式化本体、Action、统一状态提交与前序累计回归。按冻结清单核验 46 项。
- 基线 `2708c13de67db48877bf54c5e11929c9187a758a`，候选 `356ea246171d6bcc252747b08301d82ae8e801f1`，Reviewer 会话 `gass-s2-review-356ea24-pldyz`。
- 冻结清单 SHA-256 `ad0697d6c94a929d51c1a09317d9517993e4307383c469c6129c371bd111121b`，冻结时间 `2026-11-26T11:21:10.252+08:00`。
- 开发计划为修订版 2，验收指南为 V1.0。SHA-256 和批准记录见[冻结清单](../check-manifest.json)。
- 第一阶段通过报告的 SHA-256 与清单一致，其候选为本阶段基线祖先。
- Windows，JDK 21、Maven 3.9.16、Node 24、pnpm 11.25.0。基线和候选分别从 Git SHA 导出，使用本地回环端口与独立 SQLite。验收前业务工作区无未提交修改。

## 准入结论

**FAILED**。46 项必验中 38 项通过，8 项因两处缺陷失败。自动 Stage Gate 实际退出 `1`，`nextStageAllowed=false`。门禁先遇到 `required review check G-08: FAILED` 并拒绝准入。其余失败项见本报告。 [门禁报告](gate-report.json)

## 检查矩阵

| 编号 | 实际结果 | 证据首条编号 | 状态 |
| --- | --- | --- | --- |
| G-01 | 冻结批准早于候选，指南、清单、基线记录与第一阶段门禁哈希匹配。HEAD 仅追加候选登记和开发文档。 | EV-001, EV-002, EV-003 | PASSED |
| G-02 | 变化限本体、Action、统一提交、权限、证据及准入工具。未启用 ECA、外部导入或可选原语。 | EV-004, EV-005, EV-006 | PASSED |
| G-03 | JDK 21、Maven、Node 24、pnpm 11.25.0 已核对。POM、前端依赖和默认配置未改，Maven 模块方向保持。 | EV-029, EV-030, EV-080 | PASSED |
| G-04 | 测试 diff 没有删除旧断言或增加跳过。后端原有 94 项保留，增加 11 项，前端用例未改。 | EV-004, EV-005, EV-006 | PASSED |
| G-05 | 两版 Maven 全 reactor 实际通过，基线 94 项、候选 105 项，零失败、零错误、零跳过。 | EV-007, EV-008, EV-009 | PASSED |
| G-06 | 两版冻结安装、82 项前端测试、生产构建及真实 Chrome 回放均通过。 | EV-031, EV-032, EV-082 | PASSED |
| G-07 | V1–V9 原样保留，V10 仅新增收据表。空库、V9 升级及候选重开三条历史运行通过。 | EV-004, EV-005, EV-006 | PASSED |
| G-08 | 旧 REST/事件读取及常规模拟兼容。既有转账在余额边界被新增运行期上限拒绝。 | EV-004, EV-005, EV-006 | FAILED |
| G-09 | 显式委派只包含两账户时，仍能查询未被委派的派生交易。其他普通查询和旧证据负例通过。 | EV-004, EV-005, EV-006 | FAILED |
| G-10 | 同版本并发为一次 HTTP 200、一次 409。快照、收据和审计写入故障回滚、重试及只读回放通过。 | EV-007, EV-008, EV-009 | PASSED |
| G-11 | 银行、信息、分支、暂停/恢复、SSE 续传和常规模拟账款通过。银行金额守恒，单次转账。 | EV-007, EV-008, EV-009 | PASSED |
| G-12 | 候选 Spotless 通过，迁移命名、Java 注释、双语原样。Chrome 验证实际构建。 | EV-004, EV-005, EV-006 | PASSED |
| G-13 | 候选绑定、批准时间、测试捕获、证据 SHA-256 与缺陷可核对。原始产物存放在隔离目录。 | EV-001, EV-002, EV-003 | PASSED |
| S1-01 | graph、evidence、runtime 的业务边界保留，新增 state 用于共享确定性校验和投影。 | EV-004, EV-005, EV-006 | PASSED |
| S1-02 | 生命周期入口及调度、提交、持久化职责继续拆分。 | EV-004, EV-005, EV-006 | PASSED |
| S1-03 | 主从仍由协调器编排，工具、授权、研判及上下文边界保留。两版本地协议验证。 | EV-004, EV-005, EV-006 | PASSED |
| S1-04 | 两版 Spring 实际 HTTP 启动和 Controller 集成测试通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-05 | 迁移及增加的审计字段符合范围，旧银行 Action 的余额边界有不兼容变化。 | EV-004, EV-005, EV-006 | FAILED |
| S1-06 | 同版本双请求、取消、恢复和事务回滚通过，运行时共享监视器仍在。 | EV-004, EV-005, EV-006 | PASSED |
| S1-07 | 同输入银行/信息 Mock 比较通过。余额与裁定、收据、状态、逻辑时间和事件相对顺序一致。 | EV-007, EV-008, EV-009 | PASSED |
| S1-08 | V9 冻结 Action、历史快照、Fork 及只读回放继续通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-09 | 两版用本地 AgentScope 协议夹具投递无关告警，QUERY 执行查询且无状态变更。 | EV-007, EV-008, EV-009 | PASSED |
| S1-10 | 两版独立测试通过 64 跳、50000 探索预算、有向路径、并列和截断。 | EV-007, EV-008, EV-009 | PASSED |
| S1-11 | 普通作用域和版本缓存隔离通过。派生交易被重新纳入明确缩窄的委派范围，缓存无法按该范围隔离。 | EV-007, EV-008, EV-009 | FAILED |
| S1-12 | 跨轮只复用当前版本图查询，Action 改变状态后旧查询从当前上下文排除。 | EV-007, EV-008, EV-009 | PASSED |
| S1-13 | 有效证据、NO_CONDITIONS、CEL horizon 及旧记录限全范围使用的预期通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-14 | 历史 Action 与最终状态分别投影，仍返回实际持久事件序号。新增显式 actionRunId 关联通过。 | EV-007, EV-008, EV-009 | PASSED |
| S1-15 | Chrome 通过新旧报告、原文、引用跳转、SSE 去重与回放。真实后端 SSE 也通过。 | EV-033, EV-034, EV-035 | PASSED |
| S1-16 | 源码与文档差异及提交范围可核对，保留前一阶段的验收记录。 | EV-001, EV-002, EV-003 | PASSED |
| E2E-A | 银行及信息 Mock、分支与历史回放通过。核对批准新增字段与范围集合后，同输入轨迹零业务差异。 | EV-042, EV-043, EV-044 | PASSED |
| E2E-K | 文字目标、图路径与证据测试通过，旧未知范围按本阶段规则处理。 | EV-007, EV-008, EV-009 | PASSED |
| S2-01 | V9 可迁移、回放及 Fork，发布定义未重写。旧银行冻结契约的执行在余额边界退化。 | EV-007, EV-008, EV-009 | FAILED |
| S2-02 | 独立用例通过默认值、字符串替代整数、缺必需、超范围、非法枚举、不可变和未知字段验证。 | EV-007, EV-008, EV-009 | PASSED |
| S2-03 | 独立重复边、悬空端点及基数负例通过。既有套件验证非法金融值和关系端点类型。 | EV-007, EV-008, EV-009 | PASSED |
| S2-04 | 三原语和受保护字段负例通过。既有合法最小货币单位转账在边界被新逻辑拒绝。 | EV-007, EV-008, EV-009 | FAILED |
| S2-05 | 原有多步失败测试及严格约束失败保护原状态，独立故障测试确认提交阶段原子回滚。 | EV-007, EV-008, EV-009 | PASSED |
| S2-06 | 独立差异测试区分缺失/null、等价数字。原有无净变更和单次版本提交测试通过。 | EV-007, EV-008, EV-009 | PASSED |
| S2-07 | 同版本并发仅一笔有效提交，同键同负载重试保留原收据，同键不同负载被拒绝。 | EV-046, EV-097, EV-139 | PASSED |
| S2-08 | 独立审计写入失败在快照之后触发，状态、快照、事件和收据全部回滚，同键重试成功。 | EV-007, EV-008, EV-009 | PASSED |
| S2-09 | 限制为两账户且排除派生交易的任务仍能查询派生交易。候选的统一规则没有保留显式委派上限。基线对照也复现。 | EV-007, EV-008, EV-009 | FAILED |
| S2-10 | 常规精确范围、不同运行/分支/版本缓存隔离通过。派生交易在缩窄前后仍落入同一有效范围。 | EV-038, EV-039, EV-089 | FAILED |
| S2-11 | 未知 scopeIds 的旧查询对局部范围拒绝，证明全范围时仍可用。独立用例通过。 | EV-040, EV-041, EV-091 | PASSED |
| S2-12 | 独立事件使用相同 actionRunId 跨两处无关事件正确关联。旧版历史库重开通过。 | EV-007, EV-008, EV-009 | PASSED |
| S2-13 | 实际提交核对发起人、来源、命令、版本、证据及只读回放。变更事件证据为 SIMULATED，来源独立记录。 | EV-007, EV-008, EV-009 | PASSED |
| S2-14 | 完整搜索但未返回全部并列结果时不接受为完成依据。参考范围、版本及历史操作分开验证。 | EV-038, EV-039, EV-089 | PASSED |
| E2E-M | 真实 HTTP 新增提交标识串联裁定/状态/收据，本地审计插入与旧历史回放核对通过。 | EV-125, EV-126, EV-127 | PASSED |

## 命令结果

完整命令、实参、执行目录、起止时间和证据引用见 [result.json](result.json)。日志及 XML SHA-256 见 [evidence-index.json](evidence-index.json)。

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
| CMD-CANDIDATE-MAVEN | 0 | 105 / 0 / 0 / 0 | EV-058 |
| CMD-CANDIDATE-ENV | 0 | — | EV-080 |
| CMD-CANDIDATE-PNPM-RETRY-INSTALL | 0 | — | EV-082 |
| CMD-CANDIDATE-PNPM-RETRY-TEST | 0 | 82 / 0 / 0 / 0 | EV-084 |
| CMD-CANDIDATE-PNPM-RETRY-BUILD | 0 | — | EV-087 |
| CMD-CANDIDATE-GRAPH | 0 | — | EV-089 |
| CMD-CANDIDATE-EVIDENCE-RETRY | 0 | — | EV-091 |
| CMD-CANDIDATE-SMOKE | 0 | — | EV-093 |
| CMD-CANDIDATE-CONCURRENCY | 0 | — | EV-097 |
| CMD-CANDIDATE-BROWSER-VERIFIED | 0 | — | EV-098 |
| CMD-CANDIDATE-QUERY-OBJECTIVE | 0 | 1 / 0 / 0 / 0 | EV-101 |
| CMD-CANDIDATE-REVIEW-ONTOLOGY | 1 | 2 / 1 / 0 / 0 | EV-105 |
| CMD-CANDIDATE-REVIEW-COMMIT | 0 | 2 / 0 / 0 / 0 | EV-109 |
| CMD-CANDIDATE-REVIEW-VISIBILITY | 1 | 2 / 1 / 0 / 0 | EV-113 |
| CMD-CANDIDATE-REVIEW-EVIDENCE | 0 | 2 / 0 / 0 / 0 | EV-117 |
| CMD-CANDIDATE-FORMAT | 0 | — | EV-121 |
| CMD-CANDIDATE-GATE-TESTS | 0 | — | EV-123 |
| CMD-HTTP-COMPARE | 0 | — | EV-125 |
| CMD-HISTORICAL-REOPEN | 0 | — | EV-130 |
| CMD-EVIDENCE-REVIEW | 0 | — | EV-154 |

| CMD-BASELINE-LEGACY-SCOPE | 0 | — | EV-157 |

候选四组独立定向测试共 8 项，其中 2 项失败。后端全量套件的 105 项统计独立于临时 Reviewer 夹具。候选的 Stage Gate 自测 30 项通过。前端离线缓存首次缺包，改用隔离复制缓存后，两版冻结安装、测试及构建均成功。

## 架构与运行验证

- V10 追加持久收据表，V1–V9 未改。候选重开从基线独立生成的 V9 SQLite 副本，三个历史运行的事件、收据、快照、回放和评价零差异。
- Mock 银行与信息场景、SSE 续传和分支通过。HTTP 对比校验新增审计标识、作用域集合、逻辑时钟、收据和金额。将 UUID、墙钟以及无序 scopeIds 规范化，并验证获批新增的审计字段后，同输入轨迹零业务差异。
- 审计写入失败注入验证状态、快照、命令键、审计与收据一并回滚。重试提交一次。同版本并发两版均为一次 200、一次 409。
- Chrome 使用两版生产构建验证历史回放与研判。浏览器使用合成报告和 SSE 夹具，实际后端 HTTP/SSE 与 AgentScope 本地协议由各自夹具覆盖。
- 按批准条件不启用外部 LLM、MCP、可选新原语或独立权限版本。

## 缺陷与复现

### STAGE02-001 · MAJOR

- 检查项 S2-04，状态 OPEN。候选 `356ea246171d6bcc252747b08301d82ae8e801f1`。
- 位置 `backend/app/src/main/java/com/trs/simulation/state/OntologyConstraints.java:202`。隔离副本运行 mvn -Dtest=ReviewerStage2OntologyTest -Dsurefire.failIfNoSpecifiedTests=false test。基线对照 ReviewerStage2LegacyBankTest。
- 输入为旧银行本体，无新增属性约束。收款账户起始 1,000,000,000,000 分，同币种转入 0.01 元（1 分），出款方有足额资金。 预期为保持旧 Action 语义，接受这笔资金守恒且没有精度或整数溢出的转账。 实际为基线通过。候选拒绝，裁定 ONTOLOGY_CONSTRAINT_VIOLATION，理由 Bank account balance outside allowed range。
- 模板初始余额上限被用于所有 Action 的最终运行态，拒绝既有银行契约允许的交易。 区分模板录入上限和旧运行态金融不变量。若要对运行余额新增上限，按冻结流程审批契约范围并验证旧运行过渡。
- 证据 EV-105, EV-106, EV-107, EV-108, EV-054, EV-055, EV-056, EV-057。

### STAGE02-002 · BLOCKER

- 检查项 S2-09，状态 OPEN。候选 `356ea246171d6bcc252747b08301d82ae8e801f1`。
- 位置 `backend/app/src/main/java/com/trs/simulation/state/VisibilityPolicy.java:21`。隔离副本运行 mvn -Dtest=ReviewerStage2VisibilityTest -Dsurefire.failIfNoSpecifiedTests=false test。
- 输入为QUERY 任务显式范围只有 debit、credit 两个账户。派生交易 tx 连接两账户，但 tx 不在委派 scopeIds 中。 预期为显式任务范围限制最终读取和缓存。请求 tx 应拒绝。 实际为VisibilityPolicy.restrict 收窄授予列表后，readableIds 根据两端账户重新加入 tx，查询返回且未拒绝。
- 任务范围外的模拟交易及其金额仍可见。两次查询的缓存作用域无法反映该委派限制。基线对照同样能读到 tx，第二阶段未关闭该行为。 把角色授权、派生对象授权与显式任务上限分别计算，再把最终交集用于查询、能力、Action、证据及缓存。
- 证据 EV-113, EV-114, EV-115, EV-116。

基线观察范围对照证据为 EV-157, EV-158, EV-159, EV-160。

## 阶段准入

须由开发方修复开放缺陷并提交新候选，再独立验收。 `nextStageAllowed=false`。
