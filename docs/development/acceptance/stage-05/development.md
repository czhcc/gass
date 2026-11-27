# 第五阶段开发进展

状态为开发中。已完成外部图谱的操作人查询与导入路径，候选将在全阶段开发和自测完成后登记。

## 实施范围

- 从已批准的草案冻结输入。基线 `2aa1f0b2344393304ca966b64f42669658ea12dd`，批准接收记录为 `2026-11-27T11:37:10.3187195+08:00`，冻结为 `2026-11-27T11:37:46.218553+08:00`。固定清单 SHA-256 为 `4a9da357bc353a32c41635f86b7dad51f767106b2380d39905728bcb4e71fbad`。
- 增加独立 Mock、MCP Streamable HTTP adapter、结构化覆盖范围、本体映射、跨源身份区分、FILL_MISSING 和 V14 候选/导入收据。默认无真实外部服务配置。
- 登录操作人按冻结策略从已登记 Provider 查询并确认服务端候选。网络调用在运行锁和数据库写事务之外，确认时复验发起人、运行、图谱版本、生命周期、摘要及预算。
- 导入原子提交快照、身份、来源和 `WORLD_EXPANDED`；数据扩展不生成模拟业务事件或 ECA 待办，不推进时钟、不消耗模拟业务变化预算。回放按原事件序号读取，历史 Fork 继承相应数据与收据；金融指标分币种区分导入增量和动作增量。
- 第五阶段门禁已接入。新增必验专项命令、保留四阶段前置验证，条件项依据冻结范围检查是否需要真实只读 MCP 联调。

## 本轮验证

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `mvn.cmd -B -o -s <本机设置> test` | 142 项通过，退出 0，无失败、错误或跳过 |
| 格式 | `mvn.cmd -B -o -s <本机设置> -pl app,bootstart spotless:apply spotless:check` | 退出 0 |
| 前端全量 | `pnpm.cmd test` | 14 个文件、86 项通过，退出 0 |
| 生产构建 | `pnpm.cmd build --outDir <临时目录>` | 退出 0，Vite 保留 Comparison 分块体积提示 |
| 门禁单测 | `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 37 项通过，退出 0 |

`KnowledgeExpansionTest` 验证固定 Mock、合法合并、保留旧余额与失效关系、隔离外部身份和拒绝非法批次。`KnowledgeMcpTest` 使用本地回环协议服务验证工具白名单、实际结构化请求、重定向与响应限额。`KnowledgeRuntimeTest` 验证候选约束、幂等、持久化故障回滚、历史分支、业务计时和银行评价。`WorldCatalogMigrationTest` 保留既有 V12→V13 断言并验证 V13→V14 不改写快照与规则字段。

## 角色授权和覆盖修订

- 使用冻结数据源策略为指定新导入对象授予指定角色读取权，另行配置本地 Action 访问。导入收据保存两类授权。委派仍与父角色和显式任务范围相交。通过运行能力查询、真实银行 Action 提交和历史分支验证授权边界。
- 缓存加入覆盖修订。当前查询、文字目标完成记录和研判复用检查运行、图谱版本及覆盖修订。即使图谱版本未变，新的覆盖记录也使旧查询证据失效。局部完备与外部全图未知独立表达。
- 后端全量 145 项通过，前端 86 项通过，门禁单测 37 项通过，Spotless 和生产构建退出 0。新增测试验证显式任务范围保持、只读角色不能提交原有 Action、明确获准的角色能执行本地 Action，以及同图谱版本不同覆盖修订和分支间的查询证据隔离。

- 本机日志 gass-stage05-permission-full.log 的 SHA-256 为 0a6ae55ad75991883004988e72ba41e093f05295ebe15aebb000ce390607ffe2。
- 本机日志 gass-stage05-permission-spotless.log 的 SHA-256 为 7f5c6bc222d1d61fe6779e250612e2e41f8d92db8858aa4c931c742ac480c690。

## 待完成

- 任务智能体发起外部查询、申请导入的受控工具路径，以及动态授权撤销及范围版本验证。
- 完善字段来源查询、外部数据不完整时的目标判定、断源及真实进程重启专项。
- 完整独立验收夹具预检及正式候选登记。

[冻结清单](check-manifest.json) · [本阶段基线](baseline.json)

本机日志 `gass-stage05-backend-full.log` 的 SHA-256 为 `846c8a98d1c22808d8a74a40df68281b532fc7aaeb38e8c697cbf7a7355903e7`。

本机日志 `gass-stage05-format.log` 的 SHA-256 为 `632b9bb941caa51cdd90f2921aecc138b7d5d976afbeedc6ebb3ff90c2cbad01`。
