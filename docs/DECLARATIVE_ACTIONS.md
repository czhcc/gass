# CEL 与声明式对象 Action

## 第二阶段本体约束

原有本体和已冻结的 Action 仍可按旧契约读取。世界 JSON 的 `ontology.types[]` 可以追加 `properties` 与 `allowUnknownProperties`。声明属性列表时默认拒绝未知属性，可显式允许。属性定义使用 `code`、`dataType`、`required`、`mutable`、可选 `defaultValue` 和可选 `constraintExpression`。`dataType` 支持 `STRING`、`BOOLEAN`、`INTEGER`、`NUMBER`、`OBJECT`、`ARRAY`、`JSON`。属性 CEL 约束使用 `value`。默认值只在世界模板保存时填补缺失字段，历史快照按原数据读取；`mutable=false` 约束 Action 中的变化，模板编辑仍按模板校验规则生效。

`ontology.relations[]` 可以按关系类型指定 `sourceTypes`、`targetTypes`、可选 `maxOutgoing`、`maxIncoming` 和属性规则。基数按类型分别计算每个对象的启用入边和出边。 `ontology.stateConstraints[]` 接受 `code` 与布尔 CEL `expression`，对最终候选图谱求值。编译错误和不满足约束的候选会被拒绝，运行期 Action 的约束拒绝记录为 `ONTOLOGY_CONSTRAINT_VIOLATION`。

白名单原语为 `SET_PROPERTY`、`DISABLE_RELATIONS`、`TRANSFER_MONEY`。金融余额、币种和交易字段继续受专用原语与领域不变量保护。多步 Action 的约束和版本变化以最终候选为准。最终图谱相同的 Action 保留裁定和收据，图谱版本保持原值。

银行模板录入时，每户初始余额最多为 1,000,000,000,000 分。合法转账可使旧契约的运行余额超过该录入上限。运行时仍校验非负整数分、币种一致、金额精度、按币种资金守恒和整数溢出。冻结本体显式声明的属性与状态约束继续适用。

所有业务 Action 定义在世界的对象类型上；关系类型不拥有 Action。Agent 根据可见状态和参数规范提交决策，执行引擎负责权限、类型、参数、前置条件、关联对象可见性、金融约束与原子提交。`END` 仍为 Agent 生命周期指令。

## 数据与版本

`world.ontology.version = 3`，`ActionDefinition.program.version = 1`。Action 库使用 V8 新表 `action_library` 保存草稿和不可变的已发布修订；绑定时把完整定义复制进世界，世界版本递增。运行和分支继续使用各自 `world_json` 中冻结的定义，回放只读事件与快照。

历史泄密 v1/v2 契约在加载时转换，保留已缩减的绑定；历史银行未存本体时补上银行内置定义，不重置对象、关系、场景或运行。V8 仅新增 Action 库，不执行数据清理。

界面流程为：新建 / 载入修订 → 手工编辑或 LLM 生成 → 校验与独立副本试算 → 保存草稿 → 发布 → 显式绑定世界对象类型 → 场景授权 → 创建新运行。已发布定义不能编辑或删除；变更需要新建修订、发布并重新绑定。删除草稿和解除绑定均使用弹窗确认。绑定及草稿修改使用版本检查，防止覆盖另一编辑者的变更。

## 内置 Action

| 领域 | 所属对象类型 | Action | 参数 | 执行效果 |
| --- | --- | --- | --- | --- |
| 泄密 | DOCUMENT / PERSON / SYSTEM / ORGANIZATION | MARK_REVIEWED | 无 | 尚未审查时写入 reviewed=true 与模拟审查证据 |
| 泄密 | 同上 | ANALYZE_RELATIONS | 无 | 只读分析，不改变图谱 |
| 泄密 | 同上 | SIMULATE_PROPAGATION | sourceId | 从可见文档或已模拟暴露对象，经启用直接关系模拟接收信息 |
| 泄密 | PERSON | REVOKE_SHARING | recipientId | 禁用当前人员指向该接收对象的所有启用 SHARED_WITH 关系 |
| 泄密 | SYSTEM | DISABLE_SYNC | destinationId | 禁用当前系统指向该目标对象的所有启用 SYNCS_TO 关系 |
| 银行 | BANK_ACCOUNT | TRANSFER | sourceId、amount（元，文本）、currency | 当前账户为接收方；按整数分扣款与入账，创建模拟交易记录 |
| 银行 | BANK_ACCOUNT | FLAG_RISK | 无 | 标记模拟风险，不修改余额 |
| 银行 | BANK_ACCOUNT | ANALYZE_ACCOUNT | 无 | 只读分析 |
| 银行 | BANK_ACCOUNT | ASSESS_RISK_CEL | 无 | CEL 根据余额计算风险分，并写入模拟属性 |

风险评分仅为演示规则：`target.properties.balanceMinor >= 500000000 ? 80 : 20`。银行演示的风险 Agent 获得此权限，优先对未评分账户执行；并非真实金融风险评估。

## 定义示例

以下自定义 Action 可在 BANK_ACCOUNT 上计算模拟评分，不需要增加 Java Action 处理器。

```json
{
  "actionType": "CUSTOM_SCORE",
  "nameZh": "计算模拟评分",
  "nameEn": "Compute simulated score",
  "description": "根据账户余额计算模拟评分，余额不变。",
  "parametersSchema": {
    "type": "object", "properties": {}, "required": [], "additionalProperties": false
  },
  "preconditions": [],
  "effect": "CUSTOM",
  "program": {
    "version": 1,
    "conditions": [{
      "expression": "!has(target.properties.customScore)",
      "messageZh": "当前账户已有模拟评分",
      "messageEn": "Account already has a simulated score"
    }],
    "operations": [{
      "op": "SET_PROPERTY",
      "arguments": {
        "object": "target.id",
        "property": "'customScore'",
        "value": "target.properties.balanceMinor >= 500000000 ? 80 : 20"
      }
    }],
    "parameterOptions": {}
  }
}
```

`effect` 是说明性编码，`preconditions` 为兼容的旧文案字段；实际执行由 `program` 驱动，不按 Action 名称映射固定 Java 代码。CEL 条件为 true 才执行，条件失败信息同时提供中英文。不同步骤按顺序读取候选状态，任一步失败则整体回滚，成功后只递增一次状态版本。

## 表达式环境与步骤

CEL 接收纯 JSON 值：`target` 当前对象、`params` 参数、`entities` 与 `relations`（均经过 Agent 可见范围过滤）、`version` 状态版本。对象字段为 id/type/name/properties；关系字段包含 id/sourceId/targetId/relationType/enabled/properties/evidence。支持 has、filter、map、exists 等标准宏，无 Java 对象、服务、网络、文件或数据库绑定。

`parameterOptions` 为参数名到 CEL 字符串列表表达式的映射，例如 `relations.filter(r, r.enabled && r.sourceId == target.id && r.relationType == 'SHARED_WITH').map(r, r.targetId)`。引擎检查列表只引用可见对象，执行时重新计算并验证选择。`format: "entity-id"` 且没有表达式时提供所有可见对象。普通参数支持 string/integer/number/boolean、必填和 enum；不支持的 Schema 约束会被拒绝，不会静默忽略。

| 步骤 | CEL 参数 | 引擎约束 |
| --- | --- | --- |
| SET_PROPERTY | object、property、value | 对象必须可见；禁止写 balanceMinor/currency 和交易对象属性；自动标记 simulationEvidence=SIMULATED |
| DISABLE_RELATIONS | source、target、relationType | 两端可见、类型已注册，禁用所有匹配启用边，记录 SIMULATED 证据 |
| TRANSFER_MONEY | source、target、amount、currency | 仅银行账户类型 Action 可声明；账户不同、币种一致、金额精确到分、正数、余额充足，整数记账与资金守恒 |

每个步骤的参数值都是 CEL 表达式字符串；固定文本需要内部单引号，例如 `"'riskScore'"`。转账金额表达式应返回元单位的文本，不使用浮点金额。新业务可以组合已有步骤；增加新的执行原语仍需实现和审查 Java 引擎代码。

资源限制：每条表达式最多 2048 字符，解析递归深度 32，推导迭代预算 2048，正则程序大小 256；每个 Action 最多 10 个参数、8 个条件、16 个步骤、20000 字符；单次 CEL 输入最多 250000 字符，编译缓存最多 256 项。仍受世界对象数、关系数、运行时间和决策预算约束。

## LLM 生成

生成器使用已有模型配置的凭证、Token、超时和并发限制，经真实 AgentScope SDK 请求结构化 `ActionDefinition`；不注册图工具，也不执行生成结果。只有通过定义及 CEL 编译校验后才保存草稿。草稿来源区分 MANUAL / BUILTIN / LLM / MOCK，修改生成草稿后来源记为 MANUAL。真实 SDK 的本地 OpenAI 协议测试已覆盖嵌套定义和非法表达式拒绝；该测试不代表外部真实 LLM 的质量验收。
