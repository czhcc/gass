# GASS 第一阶段独立验收报告（第 2 次）

- 基线 `95ce067282a350c8332699d90f16a9bd51a8c01d`，候选 `2e3e8f048cfbbe76cd43b59f3eb6c47c352a63e0`。
- 冻结清单 SHA-256 `30abec244e90fbbd4fc8f4a6b473ab8b796a306da27839e1dac31baf5eb9e4ad`。
- 两份指南的版本和 SHA-256 载于[冻结清单](../check-manifest.json)。批准于 `2026-11-25T17:48:55.3427612+08:00`，冻结于 `2026-11-25T17:49:28.698+08:00`，均为首次验收后的实际时间。
- 首轮独立报告、结果及证据索引均保留，文件 SHA-256 见[本轮结果](result.json)。

## 准入结论

**PASSED（准入通过）**。自动 Stage Gate 实际退出 `0`，`nextStageAllowed=true`。门禁核验了 31 项必验、28 条必需命令记录和 136 份证据。

本轮验收按已批准的补录清单重新核验同一候选。根目录 AGENTS.md 新增的验收流程文字、进展记录及本轮验收基础设施属于候选之外的治理改动。Stage Gate 实际命令为 `python scripts/acceptance/stage_gate.py --stage 1`，退出码 0 表示通过，1 表示发现不符合项，2 表示输入或环境阻塞。原始证据继续存放在索引所列隔离目录。再次执行可通过 `--report` 另存输出。[门禁报告](gate-report.json)

## 独立复核

首轮 120 份证据文件的哈希均匹配，首轮 22 条命令的退出码与独立日志一致。候选业务代码、测试、配置、依赖与迁移仍绑定同一提交。补充验证两版工具版本及隔离候选的 `app` 和 `bootstart` Spotless 检查，记录 Git 和证据核查。

| 检查 | 复核结果 | 状态 |
| --- | --- | --- |
| G-01 | 已复核实际阶段起点和候选、指南 SHA-256、补录批准、冻结清单及核验范围。批准时间晚于候选与首轮验收，首轮记录保留。 | PASSED |
| G-02 | 74 个文件的差异集中于包迁移、职责抽取、回放抽取、特征测试和文档。未引入后续阶段机制。 | PASSED |
| G-03 | JDK 21、Spring Boot 4.1.2、AgentScope 2.0.5、CEL 0.15.0、SQLite/Flyway、Vue 3。POM 和锁文件未改，Maven 方向与候选包导入图无环。 | PASSED |
| G-04 | 测试差异未删断言或新增 skip/only。候选增加快照写入失败后整笔回滚及实际 HTTP SSE 续传验证。 | PASSED |
| G-05 | 基线 92 项、候选 94 项，失败、错误、跳过均为 0。 | PASSED |
| G-06 | pnpm 11.25.0，两版各 82 项通过，安装、测试及构建退出 0。Chrome 验证生产构建。 | PASSED |
| G-07 | V1–V9 无差异。通过空库启动和 V5 升至 V9 测试。候选打开基线库，三条运行的历史记录、快照和报告零差异。 | PASSED |
| G-08 | SimulationTypes 与前端类型未改。Controller 映射保持。三条运行的 HTTP 轨迹零差异，同版本并发冲突均为 409。 | PASSED |
| G-09 | Agent 经受控查询和 Host，未直接使用 JDBC。既有越权、隐藏对象和凭据安全测试通过。缺 scopeIds 的旧证据例外单独登记。 | PASSED |
| G-10 | 并发提交为一次 200 和一次 409，版本只增一次。回滚、重试和无副作用读取通过。 | PASSED |
| G-11 | 银行与信息 Mock HTTP 通过，历史读取和分支兼容，暂停、恢复、取消由 Spring 集成测试覆盖。 | PASSED |
| G-12 | 核查新增 Java 类及公共方法 Javadoc、@author、包名、SQL 和前端文案。旧注释随类迁移，Spotless 随全量 Maven 通过。 | PASSED |
| G-13 | 记录完整提交、基线、映射、回滚点、执行记录与 SHA-256 证据索引。候选差异无凭证、数据库、依赖或构建产物。 | PASSED |
| S1-01 | graph 负责有向查询和缓存，evidence 负责引用与投影。按类导入分析 20 余个包，未发现跨包环。 | PASSED |
| S1-02 | RunService 非空行 1442→751。抽取 RunStateRepository、RoundScheduler、ActionCommitService、HierarchyRuntimeHost、RunTerminationPolicy。 | PASSED |
| S1-03 | 协调器非空行 1083→478。委派保留在 Invocation.Delegate，SDK Agent 构造仍就近于编排。查询工具、上下文、预算守卫、证据、完成和最终研判已有独立边界。 | PASSED |
| S1-04 | 实际启动 StudioApplication 并通过 HTTP 登录、两个演示和历史端点。Spring 测试加载实际应用上下文。 | PASSED |
| S1-05 | 迁移、DTO、依赖、配置无差异。AgentScope 本地协议 fixture 验证 schema、工具和调用链，Mock 比对事件及业务状态零差异。 | PASSED |
| S1-06 | RunService 和 Host 使用同一监视器。HTTP 同版本双请求只产生一次提交。Spring 测试覆盖暂停取消、恢复、失败回滚和模型请求期间可读。 | PASSED |
| S1-07 | 银行 A/B 余额 500000000/700000000 分，总额 1200000000 分，仅一笔转账。两领域均 SUCCEEDED，金额、状态、裁定、收据和事件顺序零差异。 | PASSED |
| S1-08 | 冻结定义与分支时钟测试通过。HTTP 回放先后事件列表相同，候选读出基线库的三个运行快照与审计日志。 | PASSED |
| S1-09 | 在本地 SDK fixture 中放入无关 POTENTIAL_LEAK。两版保持已保存 objective，产生查询与告警投递，无 Action 提案/接受、无图谱版本变化。 | PASSED |
| S1-10 | 独立图 fixture 在两版各验证 10 个断言，覆盖两条并列最优、complete、tiesTruncated、64 跳及 50000 路径前缀预算。 | PASSED |
| S1-11 | 图 fixture 验证规范化命中及 run/branch/version/精确 scope 隔离。两版 HTTP 日志均保留 16 次图查询审计，明确标出 5 次缓存命中。 | PASSED |
| S1-12 | 两版的本地 AgentScope 协议 fixture 验证当前版本进入下一轮上下文，Action 变更后旧版本不再复用。 | PASSED |
| S1-13 | 本地协议验收覆盖有效引用、NO_CONDITIONS、OBJECTIVE_REVIEW_COMPLETED 和 CEL 到期评估。独立证据 fixture 验证过期、越界、截断、不完整及拒绝收据的判定。旧缺 scopeIds 行为与基线一致。 | PASSED |
| S1-14 | 本地协议 fixture 验证历史接收与后续禁用分开，引用从持久化事件解析。Host 在同一锁下 append 后读序号。浏览器独立呈现两部分。 | PASSED |
| S1-15 | Chrome 对两版生产构建各通过 9 个独立断言，使用合成历史 JSON 与实际测试 SSE。引用跳 #1，播放到 #4 停止且只发 GET。真实后端 SSE 另经 HTTP 验证。 | PASSED |
| S1-16 | 提交独立、映射覆盖主要移动和新边界，文档列出已知问题与回滚点。diff 检查通过。 | PASSED |
| E2E-A | HTTP Mock 采集 245 条信息事件、162 条银行事件和 5 条分支事件。按 ID 引用一致映射及墙钟字段规范化后零差异。 | PASSED |
| E2E-K | 汇总两版真实 SDK 本地协议、独立图及引用 fixture 和完整浏览器回放，均通过。 | PASSED |

## 执行结果与遗留事项

- 基线后端 92 项、候选后端 94 项。两版前端各 82 项。以上均零失败、零错误、零跳过，两版前端生产构建成功。全量用例沿用首轮独立运行，第二次复核通过原始 XML 和命令记录重新统计。
- 银行、信息、分支、历史库重开、图谱、目标证据、并发和真实浏览器夹具的首轮证据已复核。浏览器使用合成报告和 SSE 夹具，真实 Spring HTTP SSE 另有测试。
- 本轮未启用外部 LLM 和 MCP。查询事件缺 `scopeIds` 的既有行为（STAGE01-002）仍按阶段二登记。
- 版本、Spotless、Git 核对的实际命令和退出码见[本轮结果](result.json)，日志及摘要见[证据索引](evidence-index.json)。

- 自动门禁自身的 27 项单元测试通过，覆盖拒绝缺失或篡改证据、不一致候选、缺项、错误退出码、跳过测试、开放重大缺陷及不满足的前置阶段。
