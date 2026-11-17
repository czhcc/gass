# API（当前实现）

业务接口均要求登录会话。登录 API 保持 `/api/auth/login`、`me`、`logout`。API 错误使用 400（参数）、401（未登录）、404（资源不存在）、409（状态/配置冲突），连接测试失败为 502。全部为 JSON，无真实外部业务写入。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / POST | /api/worlds | 列表 / 创建动态世界（结构参见示例响应） |
| POST | /api/examples/information-leak | 导入泄密示例事实图 |
| GET | /api/worlds/{id} 或 /graph | 世界完整实体和有向关系 |
| GET | /api/worlds/{id}/expand?entityId=document_f&depth=3 | 递归多跳；可选 relationType |
| GET | /api/worlds/{id}/path?from=document_f&to=system_t | 启用边最短路径 |
| GET | /api/worlds/{id}/centrality | 介数中心性 |
| GET / POST | /api/scenarios | 列表 / 保存场景 |
| GET / PUT | /api/scenarios/{id} | 读取 / 修改场景，已创建运行仍使用原配置 |
| POST | /api/scenarios/{id}/runs | 创建独立 PAUSED 运行 |
| GET | /api/runs 或 /api/runs/{id} | 列表 / 元数据 |
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

`SIMULATE_PROPAGATION` 参数为 `{"sourceId":"document_f"}`，targetId 指向目标对象。其他 Action 参数必须为空对象。传播必须有启用的直接关系，并在结果中标记为 SIMULATED。

## 模拟模型、实时、分支与银行

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/models/mock | 创建/复用无需密钥的 mock://local 模型 |
| POST | /api/examples/information/demo | 创建泄密世界、模拟模型、四角色场景和暂停运行，注入初始事件 |
| POST | /api/examples/bank/demo | 创建银行世界、银行/风险 Agent、暂停运行和500万元初始请求 |
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

干预请求：`{"agentId":"response","expectedVersion":0,"decision":{"actionType":"DISABLE_SYNC","targetId":"sync_st","parameters":{},"reason":"用户模拟干预"}}`。记录为 USER_INTERVENTION，而不是模型决策。带唯一 Idempotency-Key；过期版本返回409。

银行 TRANSFER 参数：`{"sourceId":"account_a","amount":"5000000.00","currency":"CNY"}`，targetId 为 account_b。初始账户 A=1000000000分、B=200000000分；成功后 A=500000000分、B=700000000分，合计不变。非正数、三位小数、越权或不足余额均拒绝。

模拟模型输出在 Agent 事件中标记 MOCK。它可以验证完整功能闭环，但不能证明真实 LLM 自主性。

模型编辑：API Key 留空且密钥来源未变时保留已保存的密钥；填写新 API Key 会加密替换；填写不同的 API Key 环境变量名时改用该变量。响应仍只返回安全 View，不返回明文或密文。修改的运行参数在后续模型调用中生效。
