# API（当前实现）

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
| GET | /api/domains/information/ontology | 泄密领域对象类型及所属 Action 契约（版本二，不含关系 Action） |
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

## 图与领域补充接口

- `GET /api/worlds/{id}/entities?type=PERSON`：按类型筛选；`/entities/{entityId}` 查询单实体。
- `GET /api/worlds/{id}/relations?entityId=system_s&direction=both&type=SYNCS_TO`：入边/出边/双向邻接查询。
- `GET /api/worlds/{id}/subgraph?entityId=document_f&depth=3`：局部子图。
- `GET /api/worlds/{id}/metrics`：度数、介数中心性、桥接候选。
- `GET /api/domains/information/actions`：注册的领域 Action 参数 Schema。
- `GET /api/runs/{id}/evaluation`：区分已确认接触、潜在可达、模拟暴露与模拟干预的指标。
- `POST /api/runs/{id}/inject`：带 `Idempotency-Key` 注入 `{"eventType":"POTENTIAL_LEAK","targetId":"document_f","simulationTime":0,"parameters":{"confidence":0.8}}`，或 USER_NOTE / BANK_TRANSFER。事件不直接改变实体，必须由 Agent 提案经过 World Engine 才能变更状态。

`SIMULATE_PROPAGATION` 参数为 `{"sourceId":"document_f"}`，targetId 指向接收对象。`REVOKE_SHARING` 的 targetId 为共享来源人员，参数为 `{"recipientId":"接收对象ID"}`；`DISABLE_SYNC` 的 targetId 为同步来源系统，参数为 `{"destinationId":"同步目标对象ID"}`。`MARK_REVIEWED`、`ANALYZE_RELATIONS` 参数必须为 `{}`。相关对象必须来自能力列表的 `parameterOptions`；传播必须有启用的直接关系，并在结果中标记为 SIMULATED。

## 模拟模型、实时、分支与银行

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/models/mock | 创建/复用无需密钥的 mock://local 模型 |
| POST | /api/examples/information/demo | 请求体 `{"worldId":"已有泄密世界ID"}`；复用所选世界，创建模拟模型、四角色场景和暂停运行，注入初始事件，不新增世界 |
| POST | /api/examples/bank/demo | 请求体 `{"worldId":"已有银行世界ID"}`；复用所选世界，创建银行/风险 Agent 和暂停运行，注入不超过源账户余额及500万元的初始请求，不新增世界 |

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

模拟模型输出在 Agent 事件中标记 MOCK。它可以验证完整功能闭环，但不能证明真实 LLM 自主性。

模型编辑：API Key 留空且密钥来源未变时保留已保存的密钥；填写新 API Key 会加密替换；填写不同的 API Key 环境变量名时改用该变量。响应仍只返回安全 View，不返回明文或密文。修改的运行参数在后续模型调用中生效。
