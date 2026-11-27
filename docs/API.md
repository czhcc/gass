# API（当前实现）

## Action 提交和事件证据

人工干预继续使用已有的 Action 接口与 `Idempotency-Key`。同一运行中新请求以相同键和载荷重试时，返回首次处理的状态。同键不同载荷返回 HTTP 409。升级前已有的命令键继续按旧记录处理。

新 Action 的提议、裁定、状态变化和任务回执共享 `runId`、`actionRunId`、`commandId`、`actorId`、`actionOrigin`、`inputVersion` 和 `outputVersion`。新增的来源字段区分 `AGENT_ACTION`、`USER_INTERVENTION` 与初始化事件中的 `INITIALIZATION`。既有 `origin` 和证据标签继续兼容。仅发生实际变化时写入 `WORLD_STATE_CHANGED`，其 `stateDiff` 按对象或关系列出字段变化，并以 `beforePresent` 和 `afterPresent` 区分缺失与显式 null。旧事件按原内容读取。

业务接口均要求登录会话。登录 API 保持 `/api/auth/login`、`me`、`logout`。API 错误使用 400（参数）、401（未登录）、404（资源不存在）、409（状态/配置冲突），连接测试失败为 502。全部为 JSON，无真实外部业务写入。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / POST | /api/worlds | 列表 / 创建动态世界（结构参见示例响应） |
| PUT | /api/worlds/{id} | 更新名称、对象及关系，必须提交当前 version；成功后版本加一，已有运行保持初始副本 |
| DELETE | /api/worlds/{id} | 删除未被场景引用的自定义世界，返回 204；两份初始世界禁止删除，已有运行历史保留 |
| POST | /api/worlds/{id}/copy | 请求体 `{"name":"副本名称"}`，创建独立世界 |
| GET | /api/worlds/{id}/export | 下载可重新导入的完整世界 JSON |
| POST | /api/examples/information-leak | 返回固定的泄密初始世界，重复调用不新增世界、不覆盖维护结果 |
| POST | /api/examples/bank-transfer | 返回固定的银行初始世界，重复调用不新增世界、不覆盖维护结果 |
| GET | /api/worlds/{id} 或 /graph | 世界完整实体和有向关系 |
| GET | /api/worlds/{id}/expand?entityId=document_f&depth=3 | 递归多跳；可选 relationType |
| GET | /api/worlds/{id}/path?from=document_f&to=system_t | 启用边最短路径 |
| GET | /api/worlds/{id}/centrality | 介数中心性 |
| GET / POST | /api/scenarios | 列表 / 保存场景 |
| GET / PUT | /api/scenarios/{id} | 读取 / 修改场景，已创建运行仍使用原配置 |
| DELETE | /api/scenarios/{id} | 删除场景，返回 204；已有运行、事件和回放快照保留，场景不存在返回 404 |
| POST | /api/scenarios/{id}/runs | 创建独立 PAUSED 运行 |
| GET | /api/runs 或 /api/runs/{id} | 列表 / 元数据 |
| GET | /api/domains/information/ontology 或 /api/domains/bank/ontology | 对象类型及所属 CEL + 声明式 Action 契约（版本三，不含关系 Action） |
| GET | /api/runs/{id}/action-capabilities | 当前状态版本和各 Agent 可见的对象行动、可用状态及前置条件失败原因 |
| DELETE | /api/runs/{id} | 删除非 RUNNING 且无子分支的运行及其事件、快照、命令、待执行事件和分支关联，返回 204；不删除场景或世界；不存在返回 404 |
| POST | /api/runs/{id}/step | 单步；必须提供 Idempotency-Key（最长100字符），重复键不重复执行 |
| POST | /api/runs/{id}/pause、/resume、/stop | 生命周期控制 |
| GET | /api/runs/{id}/events?after=0 | 递增 sequence 的持久化事件，最多2000条 |
| GET | /api/runs/{id}/state 或 /graph | 当前状态；可选 version 获取确切已提交版本 |
| GET / POST | /api/models | 安全配置列表 / 新增配置 |
| PUT | /api/models/{id} | 原位编辑已保存模型，保留模型 ID |
| POST | /api/models/{id}/test | 用实际 SDK 测试结构化响应，不修改世界 |

模型配置请求示例（不要把真实密钥写入示例文件）：

```json
{"name":"Local Qwen","baseUrl":"http://localhost:8000/v1","modelName":"qwen","apiKey":"","apiKeyEnv":"","temperature":0.2,"maxTokens":1024,"timeoutSeconds":30,"concurrency":1}
```

`apiKey` 输入后加密保存，不回传；`apiKeyEnv` 可指定服务端已有的变量名。两者同时提供时使用加密保存的 API Key。本地免密模型可两项留空。服务需要支持 OpenAI chat completions、function/tool calling。连接测试不是 P3 真实多 Agent 验收。

运行示例：先在界面配置模型、导入世界并创建场景。使用浏览器同一会话或 REST 登录获得 cookie 后，创建运行、带唯一 Idempotency-Key 调用五次 step，再读取 events 和 state。自动执行使用 resume。生产环境需要 HTTPS。

SSE、Fork、Compare 与银行领域已开放，参见下节。POST 创建资源目前不做跨请求幂等去重，仅 step 具有显式请求键。

## Action 维护与生成

所有接口需要登录。定义格式见 [声明式 Action](DECLARATIVE_ACTIONS.md)。

| 方法 | 路径 | 请求与结果 |
| --- | --- | --- |
| GET / POST | /api/action-library | 列表 / 新建草稿；输入 `{domainCode,ownerType,definition,version:0}` |
| PUT | /api/action-library/{id} | 修改草稿，输入同上，version 必须为最新编辑版本；身份字段不可修改 |
| DELETE | /api/action-library/{id}?version=N | 仅删除草稿，成功返回 204；已发布或版本过期返回 409 |
| POST | /api/action-library/validate | 输入同新建，校验 Schema、原语和 CEL；合法返回 `{valid:true}`，非法返回 400 |
| POST | /api/action-library/preview | 输入 `{domainCode,ownerType,definition,worldId,targetId,parameters}`；返回 Outcome 与候选 state，不持久化图谱 |
| POST | /api/action-library/generate | `{domainCode,ownerType,modelId,description}`；通过指定模型生成并校验，仅保存 DRAFT，source 为 LLM 或 MOCK |
| POST | /api/action-library/{id}/publish | `{version:N}`；校验并发布不可变修订，不自动绑定 |
| POST | /api/action-library/{id}/bind | `{worldId,expectedVersion:N}`；将已发布定义绑定世界对应对象类型并递增世界版本；世界过期返回 WORLD_STALE |
| POST | /api/action-library/unbind | `{worldId,ownerType,actionType,expectedVersion:N}`；移除未来运行的绑定，保留库和历史运行 |

Entry 包含 id、domainCode、ownerType、revision（同编码修订号）、status、source、modelId、definition、version（编辑并发版本）。发布后修改请 POST 新草稿；绑定成功后，还需在场景 Agent 的 allowedActions 中授权，已有运行保持冻结定义。

## 图与领域补充接口

- `GET /api/worlds/{id}/entities?type=PERSON`：按类型筛选；`/entities/{entityId}` 查询单实体。
- `GET /api/worlds/{id}/relations?entityId=system_s&direction=both&type=SYNCS_TO`：入边/出边/双向邻接查询。
- `GET /api/worlds/{id}/subgraph?entityId=document_f&depth=3`：局部子图。
- `GET /api/worlds/{id}/metrics`：度数、介数中心性、桥接候选。
- `GET /api/domains/information/actions`：注册的领域 Action 参数 Schema。
- `GET /api/runs/{id}/evaluation`：区分已确认接触、潜在可达、模拟暴露与模拟干预的指标。
- `POST /api/runs/{id}/inject`：带 `Idempotency-Key` 注入 `{"eventType":"POTENTIAL_LEAK","targetId":"document_f","simulationTime":0,"parameters":{"confidence":0.8}}`，或 USER_NOTE / BANK_TRANSFER。事件不直接改变实体，必须由 Agent 提案经过 World Engine 才能变更状态。

`SIMULATE_PROPAGATION` 参数为 `{"sourceId":"document_f"}`，targetId 指向接收对象。`REVOKE_SHARING` 的 targetId 为共享来源人员，参数为 `{"recipientId":"接收对象ID"}`；`DISABLE_SYNC` 的 targetId 为同步来源系统，参数为 `{"destinationId":"同步目标对象ID"}`。`MARK_REVIEWED`、`ANALYZE_RELATIONS` 参数必须为 `{}`。相关对象必须来自能力列表的 `parameterOptions`；传播必须有启用的直接关系，并在结果中标记为 SIMULATED。

## 模拟时钟、工作队列和评价

场景创建和编辑接受可选的 `scheduling` 和 `evaluationPolicy`。两者随运行创建冻结。现有场景及带旧版 `EVENT_DRIVEN` 审计标签的运行继续按轮次执行。`scheduling` 取 `null` 或显式选择 `TASK_DRIVEN` 时也使用轮次时钟。以下配置启用严格事件时间。

```json
{
  "scheduling": {
    "contractVersion": 1,
    "mode": "EVENT_DRIVEN",
    "timeUnit": "TICK",
    "maxWorkPerTime": 40
  },
  "evaluationPolicy": {
    "mode": "TEXT_ONLY",
    "earliestRound": 1,
    "earliestSimulationTime": 9,
    "horizonSimulationTime": 20,
    "requireQuiescence": true,
    "completionMode": "OBJECTIVE"
  }
}
```

- `POST /api/runs/{id}/inject` 的 `simulationTime` 是到期时刻。严格模式可传整数 `priority`，范围 −1000 至 1000，默认 0。调度按到期时间升序、优先级降序、入队序号升序、工作项 ID 排序。未来输入可以排在 horizon 之外。相同输入和幂等键重试沿用原记录。
- `POST /api/runs/{id}/step` 在严格模式推进一个逻辑时点或执行当前时点的研判。有待确认的输入时，可在同一时点进行后续研判。研判轮次只在调用 Agent 时增加。`SIMULATION_CLOCK_ADVANCED` 即使没有图谱变更也会保存，SSE 和按事件序号回放均可读取。
- `GET /api/runs/{id}/scheduling` 返回时钟模式和当前逻辑时间。严格模式还返回冻结配置、研判轮次、图谱版本、队列状态及各工作项的 `id`、`type`、`dueTime`、`priority`、`enqueueSequence`、`status` 和 `attempts`。`AWAITING_ACTION` 表示已送达且仍等候符合冻结权限和绑定的 Action。
- 严格模式下，常规输入在等待研判时为 `PROCESSING`。原步进键的重试或恢复运行会继续已持久化的未完成研判。该轮次完成后，重复的步进键返回当前状态。
- `evaluationPolicy.mode` 默认按有无 CEL 条件推断为 `CONDITIONS` 或 `TEXT_ONLY`。缺省的最早轮次和时点均为 0，`requireQuiescence` 为 true。`completionMode` 默认 `QUIESCENCE`，也可选 `HORIZON` 或 `OBJECTIVE`。选择 `HORIZON` 需指定逻辑时点或轮次之一。选择 `OBJECTIVE` 要求配置文字目标。
- 最早轮次和最早逻辑时点共同约束评价。`complete_objective` 可先记录文字结论，评价和运行结束仍由队列与冻结策略决定。空队列可记录 `QUIESCENT`。到达 horizon 时，先处理该时点内的工作，保留 horizon 外待办，再记录 `HORIZON_REACHED`。明确允许非静止时完成的文字策略会记录剩余工作。

## ECA 触发规则

规则配置按世界管理。发布版本随严格事件驱动运行冻结。场景的可选 `ruleIds` 选择要冻结的规则。世界、类型和场景作用域分别使用 `WORLD`、`TYPE`、`SCENARIO`。类型规则的 `scopeId` 等于触发对象类型，场景规则的 `scopeId` 为已有场景 ID。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/rules?worldId=...` | 草稿列表 |
| GET | `/api/rules/published?worldId=...` | 每条规则的最新发布版 |
| POST | `/api/rules` | 保存新草稿 |
| PUT | `/api/rules/{id}` | 更新草稿 |
| POST | `/api/rules/validate` | 校验规则定义 |
| POST | `/api/rules/preview` | 对当前世界模板和假设事件试算计划 |
| POST | `/api/rules/{id}/publish` | 发布不可变修订 |
| GET | `/api/rules/{id}/revisions` | 读取发布历史 |
| GET | `/api/runs/{id}/rules` | 读取运行或分支的冻结版本 |

规则 JSON 包含 `worldId`、`name`、`scope`、`enabled`、`priority`、`trigger`、`conditionExpression`、`actions` 和 `grant`。修订号由发布接口管理。规则的 `grant` 显式列出 `visibleEntityIds`、`targetTypes` 和 `allowedActions`。

- `trigger` 的 `eventType` 可选 `PROPERTY_CHANGED`、`RELATION_ENABLED`、`RELATION_DISABLED`、`RELATION_PROPERTY_CHANGED`、`ENTITY_CREATED`、`RELATION_CREATED` 和 `DOMAIN_EVENT`。最后一项匹配已提交的对应实体变化。变化触发器指定 `objectType`，属性触发器同时指定 `property`。
- 每项响应指定 `actionType`、`targetType`、`targetExpression`、`parameterExpressions` 和 `delayExpression`。目标表达式返回单个 ID 或 ID 列表。延迟为整数 TICK。CEL 条件使用授权范围内的提交后快照及 `event.oldValue`、`event.newValue`、`event.objectId`。目标和参数使用处理时的授权图谱。
- 试算请求为 `{ "rule": { ... }, "event": { "objectId": "A", "oldValue": "NORMAL", "newValue": "FAILED" } }`。试算返回意向计划。到期执行仍检查授权、目标绑定、参数和当前前置条件。
- `AUTO_ACTION` 在调度端点可见。规则审计使用 `SYSTEM_RULE` 并公开 `ruleId`、`ruleRevision` 和 `causeEventRef`。用户干预的来源由服务端分配。

## 模拟模型、实时、分支与银行

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/models/mock | 创建/复用无需密钥的 mock://local 模型 |
| POST | /api/examples/information/demo | 请求体 `{"worldId":"已有泄密世界ID","maxSteps":8}`；maxSteps 必填，范围 1～20 轮；复用所选世界，创建模拟模型、四角色场景和暂停运行，注入初始事件，不新增世界 |
| POST | /api/examples/bank/demo | 请求体 `{"worldId":"已有银行世界ID","maxSteps":8}`；maxSteps 必填，范围 1～20 轮；复用所选世界，创建主研判、查询分析、银行操作、风控四角色和暂停运行，注入不超过源账户余额及500万元的初始请求，不新增世界 |

世界目录启动时补齐 `demo-information` 与 `demo-bank` 两份初始世界。新建、复制和导入的世界仅支持 `information`、`bank` 两类。导入复用 `POST /api/worlds`，由服务端校验完整图结构；编辑不得更换世界类型或移除仍被场景 Agent 引用的对象。

世界维护的 409 响应额外返回稳定 `code`：`WORLD_STALE`（版本过期）、`WORLD_IN_USE`（被场景引用）、`WORLD_INITIAL_PROTECTED`（初始世界禁止删除）。客户端按此提供中英文提示。

V6 是已授权的一次性历史数据重置：清除旧世界、场景、运行、事件、快照、命令与分支，保留账号及模型配置。应用重启不会重复清理，也不会覆盖之后维护的世界。运行和分支保存独立的 `world_json`，后续删除世界不影响历史回放、评估和推演。
| POST | /api/examples/bank-transfer | 仅导入银行模板 |
| GET | /api/runs/{id}/agents | 冻结 Agent 配置，供活动/干预面板使用 |
| GET | /api/runs/{id}/stream | SSE；事件名 simulation，id 为 sequence；支持 Last-Event-ID 与 after |
| GET | /api/runs/{id}/replay?sequence=0 | 指定历史事件位置，只读，不调用模型 |
| GET | /api/runs/{id}/checkpoints | 已提交版本及其事件位置 |
| POST | /api/runs/{id}/fork | 创建独立暂停分支；必须提供 Idempotency-Key |
| GET | /api/branches | 分支谱系和名称 |
| GET | /api/runs/compare?left=...&right=... | 同世界运行的实体/关系 Diff、领域指标和摘要 |
| POST | /api/runs/{id}/actions | 用户模拟干预，经过同一 World Engine，必须暂停和匹配版本 |
| GET | /api/domains/bank/actions | 银行 Action 参数 Schema |

Fork 请求：`{"sequence":12,"label":"限制传播方案"}`。子分支拥有独立快照、队列、时钟和事件序号，不修改父运行。重复相同命令键返回同一子运行。

运行删除冲突返回 409 和稳定错误码：`RUN_ACTIVE` 表示需先暂停或停止，`RUN_HAS_BRANCHES` 表示需先删除子分支。删除与单步、干预及分支创建使用同一运行锁；所有历史清理在同一事务中完成。

泄密对象 Action 的类型绑定、参数规范、执行规则和兼容策略见 [INFORMATION_ACTIONS.md](INFORMATION_ACTIONS.md)。泄密业务决策的 `targetKind` 只能为 ENTITY，END 使用 AGENT；关系由引擎根据对象参数维护。世界 JSON 包含随运行冻结的 `ontology`；Agent 观察的 `availableActions` 提供 `parameterOptions`；接受/拒绝事件包含 `affectedRelationIds`，记录本次实际修改的关系。

干预请求：`{"agentId":"response","expectedVersion":0,"decision":{"actionType":"DISABLE_SYNC","targetKind":"ENTITY","targetId":"system_s","parameters":{"destinationId":"system_t"},"reason":"用户模拟干预"}}`。记录为 USER_INTERVENTION，而不是模型决策。带唯一 Idempotency-Key；过期版本返回409。

银行 TRANSFER 参数：`{"sourceId":"account_a","amount":"5000000.00","currency":"CNY"}`，targetId 为 account_b。初始账户 A=1000000000分、B=200000000分；成功后 A=500000000分、B=700000000分，合计不变。非正数、三位小数、越权或不足余额均拒绝。

模拟模型输出在 Agent 事件中标记 MOCK（旧协议）或 SIMULATED（主从协议），界面均显示为模拟模型。它可以验证完整功能闭环，但不能证明真实 LLM 自主性。

## 主从场景与任务

新建场景和两个演示均使用配置的 MASTER / QUERY / ACTION 角色。`GET /api/scenario-profiles` 返回对应领域的 `agents` 角色模板、提示词、委派描述和 `team`；普通 `POST /api/scenarios` 同时保存 agents 和 team。每个 Agent 的 `runtime` 包含 role、toolName、description、tools；场景校验主角色无业务 Action、查询角色只读及可见范围不得扩大。旧场景 team 为 null 时保留旧执行协议。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/scenario-profiles | 场景主从模板；不会选择模型或创建世界 |
| POST | /api/scenario-objectives/draft | 提交 `worldId`、`modelId`、`objective`、`maxSteps`，返回可编辑的 `conditions`、待补充的 `unresolved` 和生成模式 `mode` |
| POST | /api/runs/{id}/initial-input | 根据运行冻结世界生成初始事件；相同初始化幂等，与演示初始化不会重复 |
| GET | /api/runs/{id}/tasks | 父子任务、轮次、版本、请求、查询与引擎回执、创建/完成事件序号 |
| GET | /api/runs/{id}/outcome | NOT_EVALUATED / SUCCEEDED / FAILED / ERROR，评价轮次与逐项条件 |
| GET | /api/runs/{id}/usage | requests（实际 SDK 请求数）、reserved_tokens（按模型最大输出保守预留） |

泄密与银行演示各包含一个主 Agent、一个查询分析子 Agent和两个操作子 Agent；银行操作与风控均保留。新事件包含 MASTER_TASK_STARTED、SUBTASK_DISPATCHED、SUBTASK_COMPLETED、GRAPH_QUERY_RESULT、ACTION_TASK_RECEIPT、MASTER_REVIEW、MODEL_REQUESTED、SCENARIO_EVALUATED。以查询证据和实际引擎回执为准，任务摘要不能替代状态提交。

新模式中一轮指主 Agent 的一次有限委派闭环，不要求全部子 Agent 都执行。到指定轮数后，生命周期为 COMPLETED，terminationReason 为 OBJECTIVE_MET 或 OBJECTIVE_NOT_MET，业务效果另由 outcome 表达；条件计算错误为 FAILED / OBJECTIVE_EVALUATION_FAILED。其他资源耗尽提前终止时 outcome 为 NOT_EVALUATED。每个 SDK 模型请求计入主从共享预算；并发名额在嵌套工具前释放。暂停/停止会取消整棵任务树并拒绝后续状态提交，已提交 Action 保留。

V9 增加 simulation_task 和 simulation_usage，不清理原数据。完整协议、配置、分支继承和限制见 [主从推演框架](HIERARCHICAL_RUNTIME.md)。

## 原平级协议的运行结束与实际耗时

`GET /api/runs` 和 `GET /api/runs/{id}` 增加 `activeRuntimeMs`（累计实际推演毫秒数）及 `terminationReason`（结束原因编码，未结束或旧记录为 null）。

| 状态 | 含义 | 结束事件 |
|---|---|---|
| COMPLETED | 所有 Agent 的 END 均被引擎接受 | RUN_COMPLETED，reasonCode=ALL_AGENTS_ENDED |
| BUDGET_EXHAUSTED | 推演因资源或次数限制结束，不表示任务完成 | RUN_BUDGET_EXHAUSTED |
| STOPPED | 用户手动停止 | RUN_STOPPED，reasonCode=USER_STOPPED |
| FAILED | 模型决策失败或超时 | RUN_FAILED，reasonCode=MODEL_DECISION_FAILED |

预算原因包括 `MAX_STEPS`（轮数）、`MAX_EVENTS`（事件数，预留 10 条审计事件）、`MAX_RUNTIME`（实际推演时间）、`MAX_AGENT_DECISIONS`（未结束 Agent 均达到各自决策上限）、`MAX_DECISIONS`（总决策数）、`MODEL_REQUEST_BUDGET`、`TOKEN_BUDGET`、`MAX_STATE_CHANGES`。整次运行的 Token 总额度检查默认关闭，开启 `studio.simulation.enforce-output-token-budget` 后可能触发 `TOKEN_BUDGET`。`reserved_tokens` 按请求的最大输出额度统计，供应商的实际计费以其记录为准。

完成或预算结束事件保存 `reasonCode`、可读 `reason`、`actual`、`limit`、`activeRuntimeMs`、`agentId`。时间预算的触发值和限额均为毫秒；请求/Token 预算的触发值是包含下一次决策预留的估算需求。Agent 次数预算的 agentId 指出一个尚未结束且已耗尽次数的 Agent。多项条件同时成立时优先识别所有 Agent 主动结束，其次记录检查到的首个预算原因；终态不重复生成结束事件。

单步及自动运行使用单调时钟，只累计单步执行区间（包含模型、工具和引擎耗时），不累计创建后等待、暂停、轮间调度等待或停机时间；每轮退出时保存累计值，失败也计时。时间限制在决策边界检查，不强制中断正在执行的模型调用；调用返回后立即检查，不必等待再点击单步。新分支拥有独立的零起始耗时预算。

V7 仅新增计时与原因字段，不清理数据、不改写历史事件。旧运行从升级后开始累计耗时，升级前耗时无法追溯；旧 COMPLETED 缺少原因时界面显示“历史已结束”，不能据此认定任务完成。预算结束后可以回放或从历史事件创建新分支，不能直接继续原终态运行。

模型编辑：API Key 留空且密钥来源未变时保留已保存的密钥；填写新 API Key 会加密替换；填写不同的 API Key 环境变量名时改用该变量。响应仍只返回安全 View，不返回明文或密文。修改的运行参数在后续模型调用中生效。

## 外部图谱数据扩展

运行冻结场景的可选 `expansionPolicy`。配置包含 `operators`（允许查询及确认导入的登录名）、`sources`（已登记 providerCode 对应的 `mapping` 和 `initialIdentities`）及 `maxCalls`、`maxDepth`、`maxEntitiesPerCall`、`maxRelationsPerCall`、`maxTotalImported`。省略该配置时关闭外部查询和导入。连接器由服务端装配为 `KnowledgeGraphProvider` Bean，场景及请求只引用已登记代号。端点、只读工具清单、认证引用由部署配置持有。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/runs/{id}/knowledge/query | 传入 `providerCode` 与 `query`（`operation` 为 SEARCH、GET_ENTITY 或 EXPAND，另有 `selector`、`depth`、`limit`）。返回服务端候选、输入图谱版本，以及来源修订、取数时间、查询范围、完整性和分页标识。 |
| POST | /api/runs/{id}/knowledge/imports | 传入 `candidateId` 确认该批次。返回导入收据、数据来源、映射版本、事件序号、图谱版本、补充字段、冲突和分币种余额增量。重复确认同一批次返回原收据。 |

操作人由登录会话确认。每次查询开始和返回后均检查运行保持暂停且无在途轮次，导入时再次复验候选身份、版本、生命周期、五分钟有效期和预算。候选只适用于发起它的运行和操作人。派生分支继承分支点之前已导入的数据及身份映射，可在外部源断开时读取，不接受父分支生成的候选 ID。

导入成功写入来源为 `EXTERNAL_IMPORT` 的 `WORLD_EXPANDED`。真实数据变化增加图谱版本，模拟时间和轮次保持原值。导入采用 FILL_MISSING，不覆盖已有属性或恢复失效关系。启用扩展的银行运行评价分别提供 `initialMinorByCurrency`、`currentMinorByCurrency`、`importDeltaMinorByCurrency` 和 `actionDeltaMinorByCurrency`。分支比较记录已导入批次的基线是否一致。
