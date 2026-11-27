# 运行架构

工程依赖保持 `bootstart → app → common`。业务位于 `com.trs.simulation`，不依赖外部图数据库、消息队列或真实银行服务。

泄密与银行业务 Action 统一使用 `ActionPrograms` 的版本三对象本体和 `DeclarativeActionEngine`。CEL-Java 编译条件、候选对象和步骤参数；引擎执行 SET_PROPERTY / DISABLE_RELATIONS / TRANSFER_MONEY 白名单原语，验证权限、可见性和金融不变量，原子提交。业务 Action 无须逐个增加 Java 名称分支；新增原语仍需 Java 实现。历史本体在加载时适配，图谱与运行状态不重置。

`ActionLibraryService` 维护 V8 草稿 / 不可变发布修订；生成器复用模型配置与并发限制，只保存合法草稿。显式发布、绑定和场景授权后，新运行才使用该定义。完整定义随世界和运行冻结，编辑和重新绑定不改变已有回放或分支。模型决策上下文仅包含可见能力的参数规范、说明和条件提示，不发送执行程序；完整运行契约仍由引擎与人工干预 UI 使用。详细格式与资源边界见 [声明式 Action](DECLARATIVE_ACTIONS.md)。

## 推演组件

`runtime.RunService` 提供原有运行入口并持有运行锁。轮次时钟与待投递输入由 `RoundScheduler` 处理，冻结数据和审计日志由 `RunStateRepository` 读取，`ActionCommitService` 提交裁定及状态快照，`RunTerminationPolicy` 执行既有终止规则和共享预算。完整源码路径见 [第一阶段重构映射](development/REFACTOR_MAP.md)。

```mermaid
flowchart LR
    API[REST / SSE] --> Runtime[RunService]
    API --> History[HistoryService]
    History --> Runtime
    Runtime --> Scheduler[RoundScheduler]
    Runtime --> Termination[RunTerminationPolicy]
    Runtime --> Store[RunStateRepository]
    Runtime --> Coordinator[HierarchicalCoordinator]
    Coordinator --> Graph[graph 查询与缓存]
    Coordinator --> Evidence[evidence 投影与校验]
    Coordinator -->|HierarchyHost 契约| Host[HierarchyRuntimeHost]
    Host -->|校验并协调提交| Commit[ActionCommitService]
    Runtime --> Commit
    Commit --> Store
    Store --> SQLite[(SQLite 日志与运行数据)]
    Commit --> SQLite
```

## 决策与状态隔离

`Model → AgentScope ReActAgent → DecisionProvider → DomainEnvironment → ActionCommitService → SQLite 事件/快照 → SSE/查询 → GUI`

- 模型仅收到授权观察，不能访问世界表、连接或任意 HTTP/SQL 工具。
- AgentScopeDecisionProvider 使用锁定并核实 API 的 Java SDK 2.0.5。支持 OpenAIChatModel 与显式 SimulatedModel；两者共用真实 SDK 工具/结构化输出流程。
- 模拟模型按当前状态、权限及反馈选择行动。模拟生成通过 AGENT_STARTED.modelMode=MOCK 标识；不是实际 LLM 自主性证明。
- World Engine 根据已注册 DomainEnvironment 插件校验 Schema、权限、可见范围及前置条件，唯一负责模拟状态变更。
- RunService 不包含银行执行逻辑；世界验证、动态生成对象的观察投影、Action Schema、效果和指标均交给领域插件。领域观察扩展点用于让账户可见的 Agent 看到对应模拟交易，不扩大其账户权限。
- 用户干预同样走领域校验，使用 USER_INTERVENTION 来源，不能冒充 Agent 模型决策。需要暂停运行与匹配 expectedVersion。

## 持久化与分支

Flyway V2–V5 增加世界/图、场景、运行、追加事件、快照、命令去重、延迟队列、分支关系和分支时钟。

SQLite 使用 WAL、foreign_keys、busy_timeout=5000；所有正式 Action 的状态、裁定、变更事件和快照在同一事务中提交。LLM 调用不持有 DB 事务。单连接模式下禁止在 JdbcTemplate RowMapper 内嵌套查询。

每次状态变更保存版本快照与 before/after 事件。HistoryService 按事件序号寻找其最近已提交版本，回放不依赖 Agent、LLM 或再次执行 Action。

第二阶段的 `ActionCommitService` 用最终候选图谱计算结构化差异，再按版本比较写入。运行状态、快照、Action 审计与 V10 的持久化收据在同一事务中提交。既有 V1–V9 迁移和历史事件保留。表内收据按运行、来源与命令键隔离；同一命令重试可以读取首次返回的状态与回执。

`VisibilityPolicy` 为运行查询、Action 上下文、委派和证据投影提供有效范围。派生交易要求可见转出和转入双方。局部视图会省略直接引用隐藏对象或关系的字段。新查询证据明确记录范围，新 Action 审计通过 `actionRunId` 关联；缺少范围的旧查询或 Action 证据，仅在可证明全图可见时用于研判。

委派先继承主从角色共有的授权。显式 `scopeIds` 是任务的最终对象范围上限，派生对象也必须在清单内，且其关联端点仍须可见。省略范围时沿用角色的派生可见性。图谱投影、缓存键和查询审计共用每次查询计算出的有效范围。

Fork 原子复制精确状态、冻结场景、当时待处理事件和受限反馈记忆。子分支事件序号从 1 开始。兼容轮次模式保留原有 `base_time` 和从 0 开始的 `step`。严格事件模式从历史序号恢复逻辑时间、图谱版本、研判轮次及当时仍未完成的工作，继承工作保持相对入队顺序。比较使用实体/关系实际值差异和插件指标。

V11 扩展现有延迟队列，旧记录保留交付标记。严格调度每批最多领取 10 项，按同一时刻预算提交该时刻的工作，即使需要多批领取。领取之后的时钟、投递审计和工作状态同事务写入。进程重启时回收尚未提交的领取。输入绑定的 Action 对应的工作确认及领域事件入队与 Action 事务共同提交。

V12 将严格模式的待研判轮次及原步进键随投递一并持久化。需要研判的普通输入投递后仍处于处理中，研判完成事件与工作完成标记同事务保存。暂停和重启优先继续原时点、原轮次。模型调用在写事务之外，已提交 Action 的检查点随业务状态和收据落盘。历史分支只继承分支位置之前已存在且尚未关闭的研判，以及在该位置已经生效的 Action 检查点。

严格模式的逻辑时间、研判轮次、图谱版本和审计序号独立。SSE 审计保留无图谱变化的时钟推进；数据库事务和回放均无模型重调用。调度使用单进程运行 lease；在当前时点的等待输入有处理结果之前，队列保持该时点。已完成 Action 的持久化命令回执与输入确认共同防止恢复或重复投递再次产生效果。

## ECA 规则执行

V13 保存可编辑规则草稿、不可变发布修订和运行冻结版本，并扩展持久化 Action 收据的来源命名空间。启用传播的严格事件场景把 Action 产生的真实图谱差异连同其审计坐标加入持久队列。规则条件在授权的变化快照上求值，目标用处理时的授权投影解析。

队列按到期时间、优先级及入队顺序消费。匹配、派生入队与源工作完成在同一事务，自动 Action、业务收据、状态差异、领域事件入队及工作完成也同事务。每个派生身份由运行、因果事件、规则修订、目标、命令参数和到期时点决定。因果深度上限为 16，单个事件最多派生 32 项，每个运行最多安排 512 项自动 Action。同刻与审计预算继续由场景限制。

初始世界与历史回放是只读的传播边界。分支继承所选序号的冻结规则和待办。跨分支源事件用原 `runId` 和 `sequence` 定位，表示该模拟的因果链。相关规则工作参与评价和终止门禁。

## 前端和实时协议

Vue 3 + TypeScript + Pinia + Vue Router hash 路由。Cytoscape 反映后端实体/关系和状态；对象高亮同时提供标签与图例。ECharts 比较潜在范围/模拟暴露/干预数量或账户余额，并有可读数据表。

SSE 每条记录具有持久化 sequence；Last-Event-ID 或 after 恢复，前端按 sequence 去重排序。SSE 不是存储。连接关闭/超时会清理后台轮询。模拟计算与历史播放分离：计算控制调用生命周期/step，播放、拖动、事件跳转只读取 replay。

`RunStudio.vue` 通过 `usePlayback` 按播放速度读取历史事件位置。事件序号、引用跳转与 SSE 更新仍由工作台协调。

## 两个领域

InformationEnvironment：关闭同步、撤销共享、模拟审核、受启用直接关系约束的假设传播。可达关系不是已发生传播的事实，模拟效果保留 SIMULATED 标记。

BankEnvironment：账户余额使用整数分；转账金额用 BigDecimal 检查精度，禁止负数、自转、余额不足、币种不匹配和越权。转出/转入同时提交并保存模拟交易；风险 Agent 可添加模拟标记。总金额守恒，不调用真实业务写接口。

## 模型与边界

模型配置保存安全参数，密钥用 AES-256-GCM 加密，主密钥位于 `data/model.key`，权限0600；生产路径 `/data/model.key`。返回前端不包含密钥或密文。也支持环境变量引用。mock://local 不发送外部模型请求。

每个世界≤200实体/1000关系，场景≤20 Agent/20轮/2000事件；每运行≤50次模型决策。服务器预算在 studio.simulation 配置。SDK每次决策≤3迭代和3次只读工具查询，模型超时1–60秒，并发1–8，观察JSON≤32000字符。Token上限采用保守预留而非真实计费。当前仅单实例串行EVENT_DRIVEN，TURN_BASED模式未实现。

## 外部图谱数据扩展

`KnowledgeGraphProvider` 暴露结构化只读请求。Mock 由独立固定数据驱动，MCP adapter 使用服务端登记的 Streamable HTTP 端点与只读工具映射。真实服务默认无配置。HTTP 调用共享一个查询截止时间，限制响应字节数，拒绝重定向，凭证通过部署注入的引用取得。

V14 保存运行内候选、身份映射及独立导入收据。候选由运行、查询参数、发起人、生命周期、图谱版本和批次摘要绑定。Provider I/O 在运行锁和写事务之外。确认导入后重验候选，在统一图谱提交路径中原子落盘状态、快照、来源及 `WORLD_EXPANDED`。来源为 `EXTERNAL_IMPORT`，不进入 Action 的 ECA 入队路径，也不消耗模拟业务变化预算。

回放通过事件序号定位已经持久化的快照。历史 Fork 继承对应位置的收据和身份映射，候选缓存仍归属于原运行。新增数据改变图谱版本时自然隔离既有本地查询缓存。

运行从已提交的导入收据读取指定角色对导入对象的授权。角色授权先应用于冻结角色，随后与父角色范围和显式任务范围相交。动态读权不会自动增加 Action 授权。能力、参数选项和图谱变更原语都复查只读边界。历史分支仅继承其分支点可证明的授权。

启用扩展的运行用持久导入序号标识覆盖修订，包括图谱版本未变化的导入收据。运行查询缓存使用运行、图谱版本、有效范围和覆盖修订。旧修订的图谱证据、完成记录和研判边界不能用于当前结果，已提交 Action 仍可作为历史证据。

V15 将委派候选和操作人候选分别绑定到任务或登录会话，并保存覆盖修订和审批申请。外部 I/O 后复验委派存续、运行版本与覆盖修订，持久候选只保留任务可见的子图。自动运行将有效申请置于审批等待，已提交的 Action 和同刻工作按既有逻辑结算，审批本身不会推进时钟。

动态撤销和导入均持久化序号，允许同图谱版本下的授权缓存和证据失效。历史 Fork 从数据库按分支点复制收据与撤销，查询不重放外部 I/O。字段追踪使用冻结身份映射和世界快照关联导入，并可单独追溯已提交的模拟 Action。

第五阶段共享配置存储在 `com.trs.simulation.contract` 的纯数据快照。场景保存原有字段形状，知识层在相应入口把快照解析为可执行、可校验的策略。共享包无知识实现依赖，Maven 模块方向保持。

字段来源查询使用持久分支谱系和各级父运行截点选择原始导入及模拟状态差异。读取只使用已提交事件与导入收据。旧审计缺少来源证明时保留未知，不推测字段从何而来。文字目标的完成证据同时满足目标范围、当前图谱及覆盖修订、有效授权、算法完整性与分页边界。
