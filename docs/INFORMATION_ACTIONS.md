# 泄密领域的对象 Action

泄密世界使用版本化的 `ontology` 定义对象类型和关系类型的业务能力。Agent 观察世界后只提交行动决策，执行引擎负责解析目标、校验和提交状态变化。图谱工具只用于读取可见数据，不承担业务写入。

## 类型与行动

| 目标种类 | 类型 | 类型定义的 Action |
| --- | --- | --- |
| ENTITY | DOCUMENT、PERSON、SYSTEM、ORGANIZATION | MARK_REVIEWED、ANALYZE_RELATIONS、SIMULATE_PROPAGATION |
| RELATION | ACCESSED_BY、STORED_IN、CONTACTS | ANALYZE_RELATIONS |
| RELATION | SHARED_WITH | ANALYZE_RELATIONS、REVOKE_SHARING |
| RELATION | SYNCS_TO | ANALYZE_RELATIONS、DISABLE_SYNC |

Action 定义包括名称、说明、参数 JSON Schema、前置条件和执行效果。`END` 单独表示 Agent 结束任务，不绑定对象类型。

`InformationOntology` 提供版本一的注册契约；`InformationActionEngine` 统一计算能力并校验执行。世界中可以缩小类型的行动绑定；导入时不能修改注册契约的 Schema、前置条件或效果，也不能注入任意可执行代码。扩展新的业务效果需要实现并注册相应的受控执行规则。

## 观察、决策、执行

每个 Agent 的 `Observation.availableActions` 来自运行冻结的世界契约和当前状态。能力列表只包含权限允许且目标可见的 Action；关系目标要求两个端点都可见。每项包含 `targetKind`、`targetId`、类型、Action 契约、`available`、不可用原因和传播可选来源 `sourceIds`。列表也包含暂不可执行的能力，以便说明前置条件。

执行前，Agent 可以使用只读图谱工具分析路径，但只能提交决策：

```json
{
  "actionType": "MARK_REVIEWED",
  "targetKind": "ENTITY",
  "targetId": "document_f",
  "parameters": {},
  "reason": "已审查文件相关路径，图谱可达性不代表实际泄露。"
}
```

`MARK_REVIEWED` 不接受 `reviewed`、`confidence`、路径列表等额外参数；分析依据放入 `reason`，不超过 1000 字符。引擎成功执行后自行设置 `reviewed=true` 和 `reviewEvidence=SIMULATED`。

`SIMULATE_PROPAGATION` 只接受 `{"sourceId":"来源ID"}`。来源必须可见，并且是文档或已标记为模拟暴露的对象；来源与目标间必须存在当前启用的直接有向边，目标不能已暴露。执行后保存 `exposed=true`、`exposureSource`、`exposureEvidence=SIMULATED`。

禁用同步和撤销共享作用于关系实例，要求关系仍启用。引擎将 `enabled` 改为 `false`，并记录模拟证据。只读分析不增加状态版本。

结束任务的决策使用 `actionType=END`、`targetKind=AGENT`、`targetId=""`、`parameters={}`。结束后该 Agent 的能力列表为空，不再执行后续人工行动或模型决策。

执行顺序为：Agent 权限 → 目标种类与存在性 → 可见范围 → 类型绑定 → 参数契约 → 当前状态前置条件 → 应用注册效果。Agent 无法通过决策直接提交状态补丁。运行锁保证模型观察到执行期间不会发生并发写入；同一轮后续 Agent 重新获取最新状态和能力。

## 接口与界面

- `GET /api/domains/information/ontology`：注册类型与 Action 契约。
- 世界的读取、复制、编辑、导入和导出均包含 `ontology`。
- `GET /api/runs/{id}/action-capabilities`：返回当前状态版本，以及每个 Agent 的能力列表和 `ended` 标志；不调用模型。
- `POST /api/runs/{id}/actions`：提交 `agentId`、`expectedVersion` 和上述决策；沿用 `Idempotency-Key`。版本过期返回 409，重复请求键不重复执行。

能力查询通过短事务读取一致性快照，不等待模型决策持有的执行锁。返回的状态版本用于后续执行校验；图谱发生变化时前端重新获取能力。

世界管理显示各类型的 Action 和契约。在推演与回放的实时暂停状态下，通过“对象行动”选择 Agent、对象或关系，再选择该目标的 Action。传播来源使用引擎提供的选项，其余泄密 Action 无需填写参数。执行后刷新状态与能力；历史回放不执行 Action。

行动失败仍作为 `ACTION_REJECTED` 审计事件保存，不修改世界状态，事件包含 `code`、目标种类和具体原因：

| code | 含义 |
| --- | --- |
| ACTION_FORBIDDEN | Agent 没有行动权限 |
| ACTION_TARGET_INVALID | 目标不存在、种类不匹配或旧协议目标不明确 |
| ACTION_TARGET_HIDDEN | 目标不在可见范围内 |
| ACTION_NOT_BOUND | 目标类型未绑定此行动 |
| ACTION_PARAMETERS_INVALID | 参数或说明不符合契约 |
| ACTION_PRECONDITION_FAILED | 当前状态不满足执行条件 |

## 持久化与兼容

新运行将世界及其 `ontology` 一起保存到 `simulation_run.world_json`，分支复制同一契约。修改可编辑世界不会改变已有运行的能力。旧泄密世界和运行缺少 `ontology` 时，加载版本一契约；旧决策缺少 `targetKind` 时，只允许根据唯一目标推断种类。

本次没有清理世界、场景或运行，没有数据库重置迁移。旧事件与快照保持原有内容，可继续回放。银行领域保留原有 Action 插件协议。

模拟模型也只选择引擎提供的可用对象能力，移除旧的“故意重复禁用同步”拒绝探针；非法行动仍由独立测试验证，不再混入演示决策。

## 图谱对象详情

中文界面以说明文字展示 Action 参数的用途、类型、必填项、前置条件和执行效果。共享接收对象、同步目标对象和传播来源对象均对应原有参数 ID；当前候选对象及不可执行原因也以中文标签说明。原始参数 JSON、条件编码和效果编码保留在默认折叠的“技术定义”中。未知定义保留原始内容，不推断未注册的执行规则；历史回放仅展示定义，不将实时能力当作历史可用性。
