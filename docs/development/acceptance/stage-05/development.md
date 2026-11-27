# 第五阶段开发进展

状态为待独立验收。最终候选在本报告提交后单独登记。

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

## 开发完成

- V15 将 QUERY Worker 候选绑定到当前活跃任务、角色、父角色、图谱与覆盖修订。Provider I/O 返回后复验租约和版本。候选只保留授权投影。显式窄范围不取得新对象。
- Worker 仅提交申请。操作人从服务端预览后确认或拒绝，候选过期和授权变更会使申请失效。有效审批等待保留原逻辑时点。
- 持久撤销同时收回外部对象的读权和本地 Action 授权。重导入保持撤销，历史分支按事件序号恢复授权。字段级来源接口区分冻结的外部事实和本地模拟写入。
- 固定 Mock 与本地真实 MCP 协议服务自测通过。按冻结条件，真实业务 MCP 联调及外部 LLM 保持禁用。第五阶段 Stage Gate 等待独立 Reviewer 的结果和证据。

## 最终自测和累计回归

| 验证 | 实际命令或方法 | 结果 |
|---|---|---|
| 后端全量、格式、打包 | Maven reactor test，Spotless apply/check，另完成后端 package | 测试 151 项，无失败、错误、跳过。全部退出 0 |
| 前端及生产构建 | pnpm test 与 pnpm build --outDir <临时目录> | 86 项，14 文件。全部退出 0，保留已有 Vite 分块提示 |
| 门禁单测 | python -m unittest discover -s scripts/acceptance -p test_stage_gate.py | 37 项，全部通过 |
| 第一至第四阶段专项 | 在临时副本校验原夹具 SHA-256 后复用 16 个 Reviewer 测试套件，执行 Maven -Dtest=Reviewer*Test | 47 项通过，无跳过。另执行原图谱、证据和并发夹具 |
| 旧 HTTP 与生产 UI | 复用旧银行/泄密 HTTP、SSE、Fork、回放和 Chrome 生产构建夹具 | 归一化后零差异，生产 UI 回归通过 |
| 历史数据 | 只读备份 V9、V10、V12、V13，使用打包候选迁移至 V15 后回放 | 每版 3 条历史运行，零差异 |
| STAGE02-001、STAGE02-002 | 原银行模板与运行边界测试、授权修复夹具、任务来源投影及导入后分币种真实转账 | 合法转账、精度与守恒通过，派生交易、窄范围和缓存隔离通过 |
| STAGE03-001 与第四阶段 ECA | 复用原 SQLite 故障、暂停、审计、任务与规则进程恢复夹具，用生产 jar 启动独立 JVM | 原轮次与逻辑时点保留，Action 持久收据无重复，T0/T1/T2 规则恢复通过 |
| 第五阶段真实进程验证 | python scripts/verify_knowledge_restart.py --jar <后端包> --evidence-dir <隔离目录> --legacy-smoke | 本地 MCP 搜索和导入后强制结束 JVM 并关闭 Provider。重启后原收据、来源、回放、分支及范围可复验 |
| 导入隔离 | KnowledgeTaskGatewayTest、KnowledgeRuntimeTest、ObjectiveGraphQueryTest、RulePropagationIntegrationTest 等 | 无锁等候、取消、跨任务审批、越权、原子回滚、无业务时钟推进、故障设备导入不触发 ECA、真实故障仍传播 |

上表是开发自测记录。独立验收仍需按冻结清单逐项绑定候选证据，运行第五阶段 Stage Gate。

### 关键本机证据 SHA-256

| 本机路径 | SHA-256 |
|---|---|
| D:/temp/gass-stage05-final-backend.log | 3b15a91dec3f9b499e5d719b10504a3b7a8ab45ce83513b49648c5e7725c8632 |
| D:/temp/gass-stage05-final-format.log | d484c5778d12106116be79286ae870147d2e3a90dcf1e449b01e2d105c34ae83 |
| D:/temp/gass-stage05-candidate-frontend.log | a4568475c5568508a0e15eb8669e10283161feec9a1654a900b21ea5bda67199 |
| D:/temp/gass-stage05-candidate-gate.log | c6bb43a0346ba9b9ca35e3fe22ff150548a520bb84b3f2358c7679d9f9da4729 |
| D:/temp/gass-stage05-cumulative-7a2bdk6m/cumulative-final.log | c919a3201cf9ed73539bc7f5c5469820683d714c0d4f6229e183111728950abc |
| D:/temp/gass-stage05-http-compare-7p7_v9mu/stage2-http-analysis.json | fbe5a46adfbacdadbedac46bbac10ff64fef21929d48335ec770aa3b641488fb |
| D:/temp/gass-stage05-historical-dbs-f52wiza8/result.json | bf775e949b67f6e1246c921fe872b33a569ded28ffdfa274594723d13760ab61 |
| D:/temp/gass-stage05-restart-1795758188339/result.json | 6ac5c71c08dd597d6b6d7e089fe69bf949e081e358b6e4804446b252c80e4688 |
| D:/temp/gass-stage05-prior-restarts-9z6pizp2/pending_restart/pending-restart-trace.json | 9e801fd5be030e97d88d2e3ea79f7ae7f6b1294ff96687f73e98dcd55ad01c32 |
| D:/temp/gass-stage05-prior-restarts-9z6pizp2/task_restart/task-restart-trace.json | 897ebddedcb3781f327ef179619a8b47df90faf5be7d8d1afeebf633b4778fe7 |
| D:/temp/gass-stage05-prior-restarts-9z6pizp2/rule_restart/rule-restart-trace.json | 211c1a74347676586f122df8ec58ef7ecf6d26ac0fe8ac96733a22db8c5dfe1a |
| D:/temp/gass-stage05-prior-restarts-9z6pizp2/concurrency/candidate-concurrency.json | b1aecb405c3e5f96b4b364744feec7555b0e0e2c5f8bac271bb8ac81360652a9 |
| D:/temp/gass-stage05-legacy-fixtures-lbfb15g6/candidate-browser-verified-meta.json | 22df83f5960c02e1c996af9e57c36f18f653fe4a20b68f2d29bc03fc11a7f33f |
| D:/temp/gass-stage05-legacy-fixtures-lbfb15g6/graph.log | 72d6ab275d60c1505d4ea2557eca1412c8ee6d25125ec0dc95439274932f1064 |
| D:/temp/gass-stage05-restart-1795758188339/legacy-smoke.log | fbe421420c3617d0e500d9e6d942d3ae7021f98ad0017b80c8711dc9e4ae5206 |

[冻结清单](check-manifest.json) · [本阶段基线](baseline.json)

本机日志 `gass-stage05-backend-full.log` 的 SHA-256 为 `846c8a98d1c22808d8a74a40df68281b532fc7aaeb38e8c697cbf7a7355903e7`。

本机日志 `gass-stage05-format.log` 的 SHA-256 为 `632b9bb941caa51cdd90f2921aecc138b7d5d976afbeedc6ebb3ff90c2cbad01`。
