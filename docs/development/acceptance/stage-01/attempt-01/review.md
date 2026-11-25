# GASS 第一阶段独立验收报告

- 阶段范围为行为保持型重构，覆盖通用及专项强制项，以及 E2E-A、E2E-K。
- 基线 `95ce067282a350c8332699d90f16a9bd51a8c01d`，候选 `2e3e8f048cfbbe76cd43b59f3eb6c47c352a63e0`，候选分支 `refactor/simulation-structure`。
- 开发指南修订版 2，SHA-256 `bc91f91cc12a5081a24980fbddc9615927a650a49cdb3392ee48c9edfff4f475`。
- 验收指南 V1.0，SHA-256 `987b2bb8aace891d494016dde401bd52d52e59491111493336e70705df2c4d1f`。
- 首阶段没有前置阶段。独立 Reviewer 会话 `gass-s1-2e3e8f04-lt0v4r`。Windows 11，JDK 21.0.8，Maven 3.9.16，Node 24.22.1，pnpm 11.25.0。
- 正式计划文件名仍使用中文原件，未发现独立的开发前基线报告、冻结清单或验收尝试记录。本轮基线数据从实际阶段起点独立重跑。
- 两个提交通过 Git archive 导出后验收。验收前 Git 状态只有原有未跟踪的 `.idea/`。运行实例使用回环端口和独立 SQLite。

## 1. 总体结论

**BLOCKED**。30 项通过，1 项阻塞。两版的全量测试、专项夹具及 HTTP 对比成功。G-01 缺开发前冻结的检查清单和批准记录，Stage Gate 脚本也缺失。阶段二未准入。

## 2. 需求、实现与证据

下表章节指验收指南或开发指南。列出的为证据组首条编号，完整检查到文件的关联见 [result.json](result.json)，路径和 SHA-256 见 [evidence-index.json](evidence-index.json)。

| 编号 | 章节 | 预期 | 实际结果 | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| G-01 | 3、2.1 | 提交、阶段及冻结输入一致 | HEAD 与阶段起点匹配，指南已取 SHA-256。缺开发前冻结清单及批准记录。 | EV-001 | BLOCKED |
| G-02 | 3、开发 4–6 | 阶段一只做行为保持型重构 | 74 个文件的差异集中于包迁移、职责抽取、回放抽取、特征测试和文档。未引入后续阶段机制。 | EV-001, EV-003 | PASSED |
| G-03 | 3、开发 1.1 | 锁定技术栈及 Maven 依赖方向保持 | JDK 21、Spring Boot 4.1.2、AgentScope 2.0.5、CEL 0.15.0、SQLite/Flyway、Vue 3。POM 和锁文件未改，Maven 方向与候选包导入图无环。 | EV-001, EV-003 | PASSED |
| G-04 | 3、2.2 | 保留原有断言，新增回归验证实际效果 | 测试差异未删断言或新增 skip/only。候选增加快照写入失败后整笔回滚及实际 HTTP SSE 续传验证。 | EV-001, EV-003, EV-007 | PASSED |
| G-05 | 3、2.2 | Maven 全 reactor 退出 0，无失败或跳过 | 基线 92 项、候选 94 项，失败、错误、跳过均为 0。 | EV-007 | PASSED |
| G-06 | 3、2.2 | 前端锁定安装、测试和构建通过 | pnpm 11.25.0，两版各 82 项通过，安装、测试及构建退出 0。Chrome 验证生产构建。 | EV-029, EV-049 | PASSED |
| G-07 | 3、4 | V1–V9 不变，空库、旧库升级和历史读取正常 | V1–V9 无差异。通过空库启动和 V5 升至 V9 测试。候选打开基线库，三条运行的历史记录、快照和报告零差异。 | EV-001, EV-006, EV-007, EV-107 | PASSED |
| G-08 | 3、4 | 现有 REST、JSON、错误及冻结契约兼容 | SimulationTypes 与前端类型未改。Controller 映射保持。三条运行的 HTTP 轨迹零差异，同版本并发冲突均为 409。 | EV-001, EV-003, EV-006, EV-048 | PASSED |
| G-09 | 3、2.3 | 维持读、Action、系统提交的权限边界 | Agent 经受控查询和 Host，未直接使用 JDBC。既有越权、隐藏对象和凭据安全测试通过。缺 scopeIds 的旧证据例外单独登记。 | EV-003, EV-007, EV-042 | PASSED |
| G-10 | 3、4 | 原子提交、版本冲突、幂等和只读回放保持 | 并发提交为一次 200 和一次 409，版本只增一次。回滚、重试和无副作用读取通过。 | EV-006, EV-007, EV-048, EV-107 | PASSED |
| G-11 | 3、13 | 银行、信息、干预、恢复、分支、报告与 SSE 回归 | 银行与信息 Mock HTTP 通过，历史读取和分支兼容，暂停、恢复、取消由 Spring 集成测试覆盖。 | EV-006, EV-007, EV-049, EV-107 | PASSED |
| G-12 | 3、开发规范 | 注释、命名、SQL、双语及可读错误保持 | 核查新增 Java 类及公共方法 Javadoc、@author、包名、SQL 和前端文案。旧注释随类迁移，Spotless 随全量 Maven 通过。 | EV-001, EV-003, EV-007, EV-049 | PASSED |
| G-13 | 3、14 | 交付和候选可追溯 | 记录完整提交、基线、映射、回滚点、执行记录与 SHA-256 证据索引。候选差异无凭证、数据库、依赖或构建产物。 | EV-001, EV-003 | PASSED |
| S1-01 | 4、开发 5、6.1–6.2 | graph / evidence 有实质边界，保持依赖方向 | graph 负责有向查询和缓存，evidence 负责引用与投影。按类导入分析 20 余个包，未发现跨包环。 | EV-001, EV-003 | PASSED |
| S1-02 | 4、开发 6.3 | RunService 生命周期入口与职责按边界拆分 | RunService 非空行 1442→751。抽取 RunStateRepository、RoundScheduler、ActionCommitService、HierarchyRuntimeHost、RunTerminationPolicy。 | EV-001, EV-003 | PASSED |
| S1-03 | 4、开发 6.4 | 协调器聚焦编排，调用状态按 invocation 隔离 | 协调器非空行 1083→478。委派保留在 Invocation.Delegate，SDK Agent 构造仍就近于编排。查询工具、上下文、预算守卫、证据、完成和最终研判已有独立边界。 | EV-001, EV-003, EV-007 | PASSED |
| S1-04 | 4、开发 6.1 | 启动、配置、扫描、注入、序列化和 Controller 正常 | 实际启动 StudioApplication 并通过 HTTP 登录、两个演示和历史端点。Spring 测试加载实际应用上下文。 | EV-006, EV-007, EV-107 | PASSED |
| S1-05 | 4、开发 4 | 保持迁移、REST/JSON、工具协议和业务语义 | 迁移、DTO、依赖、配置无差异。AgentScope 本地协议 fixture 验证 schema、工具和调用链，Mock 比对事件及业务状态零差异。 | EV-001, EV-003, EV-006, EV-007 | PASSED |
| S1-06 | 4、开发 6.3 | 并发、锁、取消和事务边界等价 | RunService 和 Host 使用同一监视器。HTTP 同版本双请求只产生一次提交。Spring 测试覆盖暂停取消、恢复、失败回滚和模型请求期间可读。 | EV-003, EV-007, EV-048 | PASSED |
| S1-07 | 4、13 A | 同一银行、信息 Mock 输入得到相同业务结果 | 银行 A/B 余额 500000000/700000000 分，总额 1200000000 分，仅一笔转账。两领域均 SUCCEEDED，金额、状态、裁定、收据和事件顺序零差异。 | EV-006, EV-039 | PASSED |
| S1-08 | 4、开发 6.6 | 冻结 Action、历史回放、Fork 和旧记录兼容 | 冻结定义与分支时钟测试通过。HTTP 回放先后事件列表相同，候选读出基线库的三个运行快照与审计日志。 | EV-006, EV-007, EV-107 | PASSED |
| S1-09 | 4、开发 6.4 | 只按用户文字目标图查询，无关告警不触发修改 | 在本地 SDK fixture 中放入无关 POTENTIAL_LEAK。两版保持已保存 objective，产生查询与告警投递，无 Action 提案/接受、无图谱版本变化。 | EV-007, EV-044 | PASSED |
| S1-10 | 4、13 K | 有向及启用边、无环单路径、并列、深度和预算保持 | 独立图 fixture 在两版各验证 10 个断言，覆盖两条并列最优、complete、tiesTruncated、64 跳及 50000 路径前缀预算。 | EV-040 | PASSED |
| S1-11 | 4、13 K | 请求规范化、缓存隔离和命中审计保持 | 图 fixture 验证规范化命中及 run/branch/version/精确 scope 隔离。两版 HTTP 日志均保留 16 次图查询审计，明确标出 5 次缓存命中。 | EV-006, EV-039, EV-040 | PASSED |
| S1-12 | 4、开发 6.4 | 跨轮复用当前版本证据，提交后排除旧查询 | 两版的本地 AgentScope 协议 fixture 验证当前版本进入下一轮上下文，Action 变更后旧版本不再复用。 | EV-007, EV-044 | PASSED |
| S1-13 | 4、开发 6.4 | 保持提前完成、拒绝非法引用、委派关闭及 CEL horizon | 本地协议验收覆盖有效引用、NO_CONDITIONS、OBJECTIVE_REVIEW_COMPLETED 和 CEL 到期评估。独立证据 fixture 验证过期、越界、截断、不完整及拒绝收据的判定。旧缺 scopeIds 行为与基线一致。 | EV-003, EV-007, EV-042 | PASSED |
| S1-14 | 4、开发 6.4 | 历史 Action 与最终状态分开，引用序号已持久化 | 本地协议 fixture 验证历史接收与后续禁用分开，引用从持久化事件解析。Host 在同一锁下 append 后读序号。浏览器独立呈现两部分。 | EV-003, EV-007, EV-049 | PASSED |
| S1-15 | 4、开发 6.5 | 新旧 ObjectiveReview、原文、引用、播放和 SSE 去重兼容 | Chrome 对两版生产构建各通过 9 个独立断言，使用合成历史 JSON 与实际测试 SSE。引用跳 #1，播放到 #4 停止且只发 GET。真实后端 SSE 另经 HTTP 验证。 | EV-006, EV-029, EV-049 | PASSED |
| S1-16 | 4、开发 6 | 重构映射、范围、风险、测试和回滚点可核对 | 提交独立、映射覆盖主要移动和新边界，文档列出已知问题与回滚点。diff 检查通过。 | EV-001, EV-003, EV-007 | PASSED |
| E2E-A | 13 A、4 | 原银行、信息、分支及回放保持 | HTTP Mock 采集 245 条信息事件、162 条银行事件和 5 条分支事件。按 ID 引用一致映射及墙钟字段规范化后零差异。 | EV-006, EV-039, EV-107 | PASSED |
| E2E-K | 13 K、4 | 文字目标、路径排名、缓存、提前结束和历史/最终证据保持 | 汇总两版真实 SDK 本地协议、独立图及引用 fixture 和完整浏览器回放，均通过。 | EV-007, EV-040, EV-042, EV-044, EV-049 | PASSED |

## 3. 架构、安全与兼容性

- `runtime/RunService.java` 保留生命周期入口，`HierarchyRuntimeHost.java` 沿用同一监视器和 lease。Action 审计、状态和快照的提交仍由 TransactionTemplate 维护。独立并发夹具验证同一分支上两个版本 0 请求只写入一次。
- `agent/HierarchicalCoordinator.java` 保留主从编排。`WorkerTools`、`AgentContextBuilder`、`CompleteObjective`、`ObjectiveReviewService` 与 `ModelInvocationGuard` 承接分离职责。AgentScope 本地 OpenAI 协议测试实际执行 SDK，Mock HTTP 场景使用 `mock://local`。
- Flyway V1–V9、Maven 配置、前端锁文件、公开 DTO 及应用默认配置无差异。候选重开基线数据库后，事件、收据、快照、回放和评价字段与原记录一致。

## 4. 命令与测试结果

开始时间、结束时间、执行目录与完整命令见 [result.json](result.json)。后端统计来自 Surefire XML，前端来自 Vitest JUnit XML。定向无关告警 fixture 只放在隔离副本，使用额外 Maven 命令并对该临时 fixture 关闭格式检查。全量 reactor 验证启用原有 Spotless 配置。

| 命令 ID | 退出码 | 测试总数 / 失败 / 错误 / 跳过 | 日志 |
| --- | ---: | --- | --- |
| CMD-BASELINE-MAVEN | 0 | 92 / 0 / 0 / 0 | EV-007 |
| CMD-BASELINE-PNPM-INSTALL | 0 | — | EV-029 |
| CMD-BASELINE-PNPM-TEST | 0 | 82 / 0 / 0 / 0 | EV-031 |
| CMD-BASELINE-PNPM-BUILD | 0 | — | EV-034 |
| CMD-BASELINE-HTTP | 0 | — | EV-036 |
| CMD-BASELINE-GRAPH | 0 | — | EV-040 |
| CMD-BASELINE-EVIDENCE | 0 | — | EV-042 |
| CMD-BASELINE-OBJECTIVE-ALERT | 0 | 1 / 0 / 0 / 0 | EV-044 |
| CMD-BASELINE-CONCURRENCY | 0 | — | EV-048 |
| CMD-BASELINE-CHROMIUM | 0 | — | EV-049 |
| CMD-CANDIDATE-MAVEN | 0 | 94 / 0 / 0 / 0 | EV-052 |
| CMD-CANDIDATE-PNPM-INSTALL | 0 | — | EV-074 |
| CMD-CANDIDATE-PNPM-TEST | 0 | 82 / 0 / 0 / 0 | EV-076 |
| CMD-CANDIDATE-PNPM-BUILD | 0 | — | EV-079 |
| CMD-CANDIDATE-HTTP | 0 | — | EV-081 |
| CMD-CANDIDATE-GRAPH | 0 | — | EV-085 |
| CMD-CANDIDATE-EVIDENCE | 0 | — | EV-087 |
| CMD-CANDIDATE-OBJECTIVE-ALERT | 0 | 1 / 0 / 0 / 0 | EV-089 |
| CMD-CANDIDATE-CONCURRENCY | 0 | — | EV-093 |
| CMD-CANDIDATE-CHROMIUM | 0 | — | EV-094 |
| CMD-HTTP-COMPARE | 0 | — | EV-104 |
| CMD-HISTORICAL-DB | 0 | — | EV-107 |

首次调用的 Surefire 参数错误、pnpm 缓存与参数错误、浏览器执行器超时和等待问题均保留日志。在修正执行环境和夹具后完成重试。Vite 大分块提示两版均有。

## 5. 业务与回归证据

- HTTP 对比保存信息场景 245 条事件、分支 5 条、银行 162 条。保留事件顺序、序号、逻辑时间、状态版本、裁定、金额、收据及证据引用。只规范化 UUID 和墙钟/耗时字段。差异数为 0。
- 银行账户 A、B 分别为 500000000、700000000 分，合计 1200000000 分，转账仅一笔。信息场景通过，分支干预禁用目标关系，父运行状态保留。两个 Mock 脚本均验证 SSE 续传。
- 同输入查询保留授权范围、版本、分支隔离及 5 次可审计缓存命中。图的 64 跳、50000 路径前缀预算与并列截断已实跑对比。
- Chrome 使用两版生产构建，验证新旧研判、原文和可读文本、实际评价轮次、证据跳转、原生 EventSource 去重和历史播放。合成浏览器数据与真实 Spring HTTP/SDK 证据分别记录。

## 6. 问题与限制

| ID | 检查 | 等级 | 结果 |
| --- | --- | --- | --- |
| STAGE01-001 | G-01 | MAJOR | 缺开发前冻结清单、批准记录和 Stage Gate。待项目负责人确认本轮候选与验收清单如何进入治理流程。 |
| STAGE01-002 | S1-13 | MINOR | 两版均保留缺 `scopeIds` 的旧证据谓词行为。属于 BASELINE_DEFECT，登记到阶段二。 |

- 旧 Action 相邻序号关联、文字完成与终止耦合、轮次时钟和每轮最多投递 10 个输入，按开发指南保留为后续阶段事项。
- `verify_p3.py` 需要获批的外部模型配置，本轮未执行。已实际运行本地协议 fixture。
- 报告和证据索引与候选代码提交独立。大文件证据位于 `D:/temp/gass-s1-2e3e8f04-lt0v4r`，无需提交到仓库。

## 7. 阶段准入

门禁未运行，退出码为空。当前 `nextStageAllowed=false`。本轮结果应在冻结清单与门禁可核验后重新核定。
