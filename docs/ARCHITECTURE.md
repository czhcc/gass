# 运行架构

工程依赖保持 `bootstart → app → common`。业务位于 `com.trs.simulation`，不依赖外部图数据库、消息队列或真实银行服务。

## 决策与状态隔离

`Model → AgentScope ReActAgent → DecisionProvider → DomainEnvironment → RunService事务提交 → SQLite事件/快照 → SSE/查询 → GUI`

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

Fork 原子复制精确状态、冻结场景、当时待处理事件和受限反馈记忆；事件注入有独立入队序号，防止历史分支继承未来注入。子分支事件序号从1开始；`base_time` 保留祖先逻辑时间，`step` 从0计数，允许子分支继续计算。父运行不会修改。比较使用实体/关系实际值差异和插件指标，不仅比较文字摘要。

## 前端和实时协议

Vue 3 + TypeScript + Pinia + Vue Router hash 路由。Cytoscape 反映后端实体/关系和状态；对象高亮同时提供标签与图例。ECharts 比较潜在范围/模拟暴露/干预数量或账户余额，并有可读数据表。

SSE 每条记录具有持久化 sequence；Last-Event-ID 或 after 恢复，前端按 sequence 去重排序。SSE 不是存储。连接关闭/超时会清理后台轮询。模拟计算与历史播放分离：计算控制调用生命周期/step，播放、拖动、事件跳转只读取 replay。

## 两个领域

InformationEnvironment：关闭同步、撤销共享、模拟审核、受启用直接关系约束的假设传播。可达关系不是已发生传播的事实，模拟效果保留 SIMULATED 标记。

BankEnvironment：账户余额使用整数分；转账金额用 BigDecimal 检查精度，禁止负数、自转、余额不足、币种不匹配和越权。转出/转入同时提交并保存模拟交易；风险 Agent 可添加模拟标记。总金额守恒，不调用真实业务写接口。

## 模型与边界

模型配置保存安全参数，密钥用 AES-256-GCM 加密，主密钥位于 `data/model.key`，权限0600；生产路径 `/data/model.key`。返回前端不包含密钥或密文。也支持环境变量引用。mock://local 不发送外部模型请求。

每个世界≤200实体/1000关系，场景≤20 Agent/20轮/2000事件；每运行≤50次模型决策。服务器预算在 studio.simulation 配置。SDK每次决策≤3迭代和3次只读工具查询，模型超时1–60秒，并发1–8，观察JSON≤32000字符。Token上限采用保守预留而非真实计费。当前仅单实例串行EVENT_DRIVEN，TURN_BASED模式未实现。
