# 主从推演框架

## 使用入口

1. 重启后端以应用 V9 数据迁移，并更新前端。
2. 在“场景配置”选择世界、已保存模型和最大轮数，创建主从 Agent 场景。所选世界的领域决定默认模板。
3. “载入模板进行编辑”可修改角色提示词、委派工具描述、查询权限、Action 权限、每轮委派/操作限额及效果条件。创建后的配置随运行冻结。
4. “创建暂停运行”使用所选模型；初始事件根据世界实际对象生成，不依赖固定对象 ID。
5. “演示世界”的泄密、银行演示使用同样的框架，但明确选择模拟模型，不使用真实 LLM。
6. 在“推演与回放”计算后，查看“主从任务与研判”、事件时间线及 Agent 活动。回放时隐藏游标之后才产生的任务结果和业务评价。

主 Agent 的研判、发现和下一步，以及子任务目标、报告和操作理由统一要求简洁的简体中文，技术字段与编码保留原样。子任务摘要移除 SDK 的 session_id；页面分条展示并允许长文本换行。历史英文报告不覆盖数据库原文：中文界面默认依据持久化查询与执行回执生成中文记录摘要，原始研判与任务内容可展开查看。此摘要不是对原英文报告的逐句翻译，不会新增未经记录证实的结论。

旧场景和旧运行保留兼容执行及回放入口。要使用新模式，应新建主从场景或演示；本次不清空已有数据。真实模型的行为和费用取决于模型及配置，协议测试和模拟演示不能证明真实 LLM 的决策质量。

## 可复用结构

`HierarchicalCoordinator` 使用项目锁定版本 AgentScope Java 2.0.5 的 `ReActAgent`、`Toolkit` 和原生 `SubAgentTool`。主 Agent 只注册配置的子 Agent 委派工具，不能提交业务 Action；QUERY 子 Agent 只暴露只读查询工具；ACTION 子 Agent 可读图、获取对象能力并提交操作。执行引擎仍是图谱变更的唯一入口。

协调器位于 `com.trs.simulation.agent`。模型并发和请求预留由 `ModelInvocationGuard` 处理，授权后的观察与 Worker 工具由 `AgentContextBuilder` 和 `WorkerTools` 提供。`CompleteObjective` 使用 `evidence.ObjectiveEvidence` 校验引用，`ObjectiveReviewService` 生成独立的最终研判。所有工具经 `HierarchyHost` 与运行时交互。

一轮是主 Agent 完成一次有限的研判、委派、获取反馈和总结。没有“每个 Agent 每轮必须调用一次”的约束。模拟模型按配置的 `mockRules` 从最新能力与输入中选择任务；真实 LLM 按角色提示词、委派描述、证据与引擎回执决定任务。

| 场景 | 主 Agent | 查询分析子 Agent | 操作子 Agent |
|---|---|---|---|
| 泄密 | lead | research | response、propagation |
| 转账 | lead | research | bank、risk |

定义位于 `backend/app/src/main/resources/scenario-profiles.json`。`ScenarioProfiles` 加载模板，`DemoService` 仅选择世界、模型及配置；调度器和模拟模型不硬编码领域名称或 Action 名称。新增角色、提示词和已支持的声明式 Action 可以通过配置实现；新增图算法或执行原语仍需实现受控工具或引擎能力。

### 配置字段

- `agents[].runtime.role`：MASTER、QUERY 或 ACTION；恰好一个 MASTER。
- `runtime.toolName`、`description`：主 Agent 所见的委派工具名和描述。
- 主 Agent 的 `runtime.tools`：可委派的子 Agent ID，覆盖所有子 Agent；子 Agent 的 `runtime.tools`：允许的只读查询工具。
- `allowedActions`：操作角色可提交的对象 Action；主 Agent、QUERY 角色为空。END 不属于新模式业务 Action。
- `team.rootAgentId`：主 Agent ID。
- `maxDelegationsPerRound`：1～8，内置默认 4；`maxActionsPerRound`：1～4 且不大于委派限额，内置默认 2。
- `inputActions`：声明事件与 Action 的绑定。例如 `{"BANK_TRANSFER":["TRANSFER"]}`。绑定的 Action 工具要求从已送达、可见、待处理的匹配事件里选择 `inputEventId`。引擎核对原始请求的目标和参数，并防止重复处理。
- `conditions`：带中英文名称的 CEL 效果条件。
- `mockRules`：仅模拟模型使用的 Action 优先级、可选输入事件类型和 CEL 条件。真实模型不执行这些策略规则。

模板的 `initialInput` 用 CEL 表达式选择世界中的对象及参数；`bindings` 按声明顺序求值。银行示例用整数分计算后生成精确金额字符串；不会绕过资金校验。

## 查询和 Action 协议

主 Agent 的委派工具接收结构化的 `goal`、可选 `scopeIds` 和 `allowedActions`。省略或传入空列表时沿用子 Agent 的已有授权。输入事件在执行绑定的 Action 时选择。子任务默认独立会话，运行网关生成父子任务关联。

`graph_query` 支持：

| 工具 | 参数与结果 |
|---|---|
| graph_get_entity | entityId，对象详情 |
| graph_neighbors | entityId、direction（in/out/both）、relationType，邻接对象与关系，包括关闭状态 |
| graph_subgraph | 可选 entityId 和 depth（0～6），沿启用关系的有向局部子图；无根对象时返回授权投影 |
| graph_path | `from`、`to` 查询启用关系的有向最短路径。传入 `rankByType` 时，按路径经过的该类型不同对象数排名，`to` 可省略。结果提供最大计数、并列路径和搜索完整性。 |
| graph_metrics | 授权范围内的度数、介数中心性、桥接候选 |

查询基于运行的最新提交状态；不读取可编辑的世界模板代替运行图。范围由领域可见性策略和子任务范围共同限定。结果包含 version、scope、evidence、totalObjects、totalRelations、offset、limit、truncated；默认分页 50 项、最多 100 项，每任务最多五次查询，单次结果最多 24,000 字符。算法结论属于 INFERRED，局部范围不能当作全图结论，可达不能当作已传播事实。

路径排名沿启用的有向关系枚举不重复经过同一对象的路径。未指定终点时，中途结束的路径也参与排名。`maxDepth` 最多 64 跳、搜索最多访问 50,000 个路径前缀，超出时返回 `complete=false`。`limit` 限制返回的并列路径条数，`tiesTruncated` 标记省略的结果。相同运行、图谱版本、授权对象集合和有效参数的查询复用计算结果。审计记录保留每次查询及其 `reused` 标记。

`action_capabilities` 返回当前对象上可执行 Action 的参数 Schema、关联对象选项和状态版本。每个操作子任务根据其授权范围、当前图谱和对象绑定生成 `submit_<action>` 工具。Schema 声明目标对象、参数、关联对象候选、`expectedVersion` 和简短理由。只有场景声明了输入绑定的工具才提供 `inputEventId`。引擎校验任务权限、对象可见范围、输入绑定、版本、参数、CEL 条件与执行原语后原子提交。任务级重复提交返回第一次回执，拒绝结果也固定。若需修正参数或刷新版本，主 Agent 创建新任务。

转账仍使用整数分结算、非负余额和币种匹配约束。风控子 Agent 保留 `ASSESS_RISK_CEL`，其评分直接由声明式 CEL 表达式执行。Agent 不返回任意状态补丁，也不生成/运行任意 Java 或脚本代码。

## 任务、记忆和取消

`simulation_task` 持久化父子 ID、轮次、角色、读取/提交版本、原始任务、实际查询、执行回执、简短公开结果及创建/完成事件序号。公开研判记录为 `MASTER_REVIEW`；不持久化或展示模型隐藏思维链。

任务状态区分 SUCCEEDED、REJECTED、FAILED、CANCELLED，以及没有提交操作的 NO_ACTION、没有实际查询证据的 NO_EVIDENCE。模型声称完成不会生成执行回执或成功效果。

本轮委派额度用完后，主 Agent 的后续请求只提供剩余可用工具。若模型调用正常结束但未返回有效的结构化报告，运行会按已保存的查询和行动回执生成本轮执行摘要，并在 `MASTER_REVIEW` 标记 `reportSource=ENGINE_FALLBACK`。编辑后的场景条件仍由引擎在指定轮次判定。

每轮持有独立执行令牌；模型调用期间不持有运行写锁和数据库事务。提交时使用短锁及事务。暂停和停止取消整棵正在执行的任务树，取消后禁止新的状态提交；已经原子提交的 Action 保留，不回滚整个轮次。中途取消消耗已经开始的轮次，恢复时从新一轮研判开始。暂停等待、轮间调度等待、进程停机不计入实际推演耗时。

分支只继承所选事件位置之前的图谱、已送达/待处理输入、任务记忆及输入处理回执；不复制父运行的活动任务表。状态变更事件携带输入处理关联，避免在 Action 已提交、子任务摘要尚未返回时 Fork 再次转账。新分支具有独立任务、命令键、计时和资源预算。重启时运行恢复为暂停，未完成任务标记为取消。

## 资源与业务判定

每个实际 SDK 模型请求都计入运行的共享计数。`reserved_tokens` 按各次请求配置的最大输出 Token 累计，供运行审计使用。供应商的实际用量以其记录为准。并发名额只覆盖一次模型请求，在执行嵌套工具之前释放，因此并发数 1 也可主从调用。

整次运行的输出 Token 总额度检查默认关闭。将后端配置 `studio.simulation.enforce-output-token-budget` 设为 `true` 后，主 Agent 与全部子 Agent 共用 `studio.simulation.max-generated-tokens` 指定的额度。单次请求按模型配置限制最大输出。轮数、全局请求次数、实际耗时、任务或决策次数、事件及状态变更上限各自生效。子 Agent 每任务最多一次业务操作，每轮委派及操作也受配置限额约束。

效果 CEL 可引用 `initial`（运行冻结的原始世界状态）、`entities`、`relations`、`version`、`pendingEvents`、`metrics`。`metrics.totals` 和 `initialTotals` 按“对象类型.数值属性”汇总，`unhandledInputs` 统计声明为需 Action 处理且尚无成功回执的已送达输入。条件解释和表达式属于场景数据，调度引擎不内置泄密或银行完成判断分支。

到指定轮数后，记录独立的 `BusinessOutcome`：SUCCEEDED、FAILED 或 ERROR，并逐项记录条件是否满足。生命周期 COMPLETED 可以对应效果成功或失败，结束原因为 OBJECTIVE_MET 或 OBJECTIVE_NOT_MET；表达式计算错误为 FAILED / OBJECTIVE_EVALUATION_FAILED。提前耗尽资源为 BUDGET_EXHAUSTED，效果仍为 NOT_EVALUATED；不得把它显示为业务成功。

### 通用文字目标

在「场景配置」填写推演目标并选择截止轮次。真实模型可以按所选世界草拟 CEL 判定式、依据取值式和待补充的问题。模拟模型提供该领域的模板条件，可手工修改。最终保存的 `objective` 和 `team.conditions` 随运行冻结。条件为空时，引擎记录 `NO_CONDITIONS`。

填写文字目标后，主 Agent 根据目标和判定条件选择查询或操作子 Agent，并使用当前图谱版本的查询证据。有判定条件时依照设置的截止轮次评价。条件为空时，主 Agent 可通过 `complete_objective` 提交结论以及可见的当前版本查询或已接受操作的事件序号。系统核验引用后停止当轮的进一步委派，且在没有待处理的未来输入时结束运行。推演目标指定未来轮次时，主 Agent 需要等到该轮再提交结论。

「推演与回放」在图谱上方展示目标、引擎判定和主研判结论。可选 `evidenceExpression` 读取判定时的状态并展示实际依据。引擎先保存 `SCENARIO_EVALUATED`，主 Agent 再核对历史操作当时的参数、回执和状态变化，以及结束时的图谱和查询结果，生成 `OBJECTIVE_REVIEWED`。引用指向可回放的审计事件。结论生成失败时保留引擎结果，回放各项结论仅在对应事件出现后可见。

## 接口与验证

- `GET /api/scenario-profiles`：模板；普通 `POST /api/scenarios` 保存完整 `agents` 与 `team`。
- `POST /api/runs/{id}/initial-input`：按冻结世界生成并幂等注入模板初始事件。
- `GET /api/runs/{id}/tasks`：父子任务。
- `GET /api/runs/{id}/outcome`：独立业务效果。
- `GET /api/runs/{id}/usage`：共享 SDK 请求次数及输出 Token 预留。

回归覆盖两个演示、任意对象 ID、第三个自定义配置场景、单连接数据库、版本冲突、重复提交、重复输入、分支点继承、越权图查询、暂停取消任务树、共享预算，以及并发数 1 的 OpenAI 兼容协议嵌套调用。`scripts/smoke_mock.py` 检查两个演示、回放、分支、干预和 SSE；`verify_p3.py` 使用所选真实模型和同一主从模板，实际结果如实输出。

### 本次验证记录（2026-11-20）

- `backend Maven test`：80 项通过，0 失败、0 错误、0 跳过。
- `frontend pnpm test`：76 项通过；`pnpm build` 通过。Comparison 分包仍有超过 500 kB 的构建提示，不影响构建成功。
- 独立 SQLite 测试库与随机本地 HTTP 端口的冒烟：泄密 8 轮、20 个子任务、12 次实际状态提交，效果 SUCCEEDED；银行 8 轮只产生一笔转账，余额 A=500000000 分、B=700000000 分，总额 1200000000 分，效果 SUCCEEDED；幂等单步、只读回放、分支隔离、对象干预、SSE 续传均通过。
- 本机 `python` 指向不可用的 Windows 应用别名，因此 HTTP 冒烟使用 Node 执行等价流程；没有把未执行的 Python 脚本记为通过。
- 本机 OpenAI 协议桩验证配置模型路径、父子反馈、8192 输出参数、并发数 1 和嵌套网络请求取消；未调用付费远程模型进行决策质量验收。
- 首次后端全量回归发现旧测试仍要求银行仅两角色及轮次耗尽为预算结束，已按本次主从语义更新断言；最终全量结果如上。新增恢复测试确认重启将中断任务补齐取消事件和完成序号，并保留已提交回执。
