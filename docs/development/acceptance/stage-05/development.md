# 第五阶段开发进展

状态为待第三次独立验收。修复候选的完整 Git SHA 在同目录 candidate.json 单独登记。

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

## 首次独立验收缺陷修复

本轮只修复 STAGE05-001、STAGE05-002 和 STAGE05-003。原基线、两份指南、冻结清单、批准记录和自动门禁条件不变。第一次独立验收的报告、结果、门禁和证据索引按原始字节保留。原候选是 28b903253a2f73da33b60a9a0e836eaf8daae92e。

### STAGE05-001 Java 包循环

根因是 SimulationTypes.Scenario 直接引用 knowledge.ExpansionPolicy。策略校验和知识合并又使用共享 World、State 和本体校验，形成由共享类型进入知识实现并回到共享类型的环。

新增 contract/ExpansionInput.java 和 contract/FrozenExpansion.java。场景只存无业务依赖的冻结 DTO，保留 JSON 字段名、空字段省略和原构造入口。ExpansionPolicy.java 在知识侧显式转换并校验，WorldService.java 和 KnowledgeExpansionService.java 通过该边界读取策略。没有 Maven 模块依赖调整。scripts/acceptance/test_stage5_architecture.py 在原实现观察到 knowledge → simulation → knowledge，修复后断言无该环并检查共享契约包没有模拟实现依赖。

### STAGE05-002 继承余额的模拟来源缺失

根因是历史 Fork 已保存精确快照和外部导入收据，但来源读取只扫描子运行自己的 WORLD_STATE_CHANGED，遗漏该快照吸收的祖先变更。

RunStateRepository.java 根据持久分支谱系逐级取每个父运行的原始事件截点。KnowledgeExpansionService.java 用导入收据及有 Action 身份的已提交字段差异返回原 sourceRunId 和 eventSequence。旧记录若没有可证实的字段差异或来源坐标，只保留可确认的字段来源，不造记录。没有迁移或回写历史。KnowledgeRuntimeTest.java 验证导入、模拟转账、前后及嵌套分支、重导入以及子分支单独修改。verify_knowledge_restart.py 在 C 为 4900 分后杀掉 JVM、关闭本地 MCP，验证前后分支、历史快照、原事件坐标与收据重试。

### STAGE05-003 局部结果完成外部全图目标

根因是完成工具只校验有效授权、图谱和覆盖修订及路径搜索完整性，没有将目标的结论范围与证据范围对齐。旧完成记录也可能直接满足判定。

SimulationTypes.java 和 WorldService.java 支持冻结 evaluationPolicy.objectiveScope。ObjectiveEvidence.java 统一校验目标范围、查询授权、图谱和覆盖修订、搜索完成、截断及分页。RunGraphReadService.java 标识授权运行子图。CompleteObjective.java 在证据不足时记录 OBJECTIVE_COMPLETION_DEFERRED，EventRunPolicy.java、RunTerminationPolicy.java 和 ObjectiveReviewService.java 稳定处理延后、DATA_REQUIRED 及范围不明确。当前系统的局部结果和 Provider 的范围内 COMPLETE 不构成外部全图证明，所以缺少可验证的全图范围、来源和版本时保持待补数据。ObjectiveCoverageTest.java 覆盖本地、外部、模糊范围、UNKNOWN、PARTIAL、COMPLETE、搜索与授权边界。原 ReviewerStage5TimingTest 同时复验模拟第 9 个 tick 的本地目标仍能在新版本重验并正常完成。

### 修复后的开发测试

- Maven 后端全量 155 项，累计原夹具 66 项（第一至第四阶段 47 项、第五阶段 19 项），均无失败、错误或跳过。原分支来源和外部全图复现断言均通过。Spotless、后端打包通过。
- pnpm test 86 项、生产构建、旧 HTTP/SSE/Fork 冒烟、原图谱与证据夹具和 Chrome 生产构建回放通过。生产构建仍有已有的 Comparison 分块体积提示。
- 实际 JVM 复跑第三阶段待处理与活跃任务恢复、第四阶段 ECA 事务切点恢复及第五阶段断源后的模拟字段来源。门禁单测 37 项通过，真实业务 MCP 与外部 LLM 按冻结范围未启用。
- 本轮是开发自测。新的 Stage Gate 结果与阶段准入由独立复验生成。

| 证据 | 本机路径 | SHA-256 |
|---|---|---|
| 原候选的原始架构脚本 | D:/temp/gass-stage05-fixes-red-df58edfn/review-input-audit.json | e8f55f4f9e127205b075b3662bd054e0be5372100a2f4f29bd71c418ed684083 |
| 原候选的两处断言复现 | D:/temp/gass-stage05-fixes-red-df58edfn/original-reproduction.log | bc617a522b4aa4ebfe7547f3d7e273481135a54a9ab4cc2a0e085f43580599ef |
| 修复后的包检查及冻结校验 | D:/temp/gass-stage05-fix-architecture.json | a134dfb28b664663c7f63ea9c5d952a8154954993da81efd30a509bfbb05665c |
| 后端全量及打包 | D:/temp/gass-stage05-fix-final-backend.log | fc40a9db66e259df5abc214acb832a88189024380b0f4b11cf8e595f7c4c3d46 |
| Spotless | D:/temp/gass-stage05-fix-format.log | f3057aa688f1c446d4a949b0dc0eaa1d412cc3a68eea3a777197866b99d87a93 |
| 前端全量及构建 | D:/temp/gass-stage05-fixes-frontend.log | 34877ac6870c814e00ccddf88b99f6d9aa51c25d48fc9eb63936d77411739dab |
| 门禁单测 | D:/temp/gass-stage05-fix-gate-tests.log | d9eff7286e9196a7da76d41040c520733945e9093bdbd33f8de7798b09ecbd76 |
| 原验收夹具和阶段一至四累计夹具 | D:/temp/gass-stage05-fixes-regression-zrxhocel/cumulative-final.log | 810644009554a59eff24cd913ef465e4f9249141d9d2d3819bf9cb9d8e97fe86 |
| 修复后原分支来源夹具 | D:/temp/gass-stage05-fixes-regression-zrxhocel/fork-source-difference.json | 8e253b1f74c307150c16c48b04dc21b6b6c49d0e7ffb74cd56e58caf316f0633 |
| 修复后原全图目标夹具 | D:/temp/gass-stage05-fixes-regression-zrxhocel/global-objective-scope.json | 60f2e37e7686962071bcb327ddb513353bc08e62b1ce76fff0550860af884940 |
| 阶段三待研判进程恢复 | D:/temp/gass-stage05-fix-prior-processes-ns68hvnn/pending_restart/pending-restart-trace.json | 842a69dbc56f8c2bae0b4197d80a65061e1cf08f83e7bf28ac500a92d3c64684 |
| 阶段三任务进程恢复 | D:/temp/gass-stage05-fix-prior-processes-ns68hvnn/task_restart/task-restart-trace.json | e8d3aec277bfb4be371fd742bd6b844bb20f5ea5dd801cbbd77c18a8305d749a |
| 阶段四 ECA 进程恢复 | D:/temp/gass-stage05-fix-prior-processes-ns68hvnn/rule_restart/rule-restart-trace.json | f930e07082af8fe02cf09e01371fe6a4f22d6ecdf93ee039d3e31bca1fbfe308 |
| 浏览器生产构建回归 | D:/temp/gass-stage05-fix-legacy-xphg795z/candidate-browser-verified-meta.json | 6a6343e6f95d12f97d3db8bd15d1da68de09a94164c06d0ef8793be18bd7e68b |
| 修复后的断源重启来源回归 | D:/temp/gass-stage05-fix-final-restart-1795762364003/result.json | ddeafee06f0a2d112c05bdbcaa31e4f287d44dffadd098d229c8ddeff69b0f29 |

## 第二次缺陷修复（STAGE05-003）

第二次独立验收在候选 `177f62657eb800760a9263ed23989f1fb30596af` 上确认 E2E-K、S5-15 未通过。先以未修改的 EV-314 ReviewerStage5ScopeMatrixTest 复现。原候选运行 4 项，1 项失败。显式外部范围和当前局部目标行为符合预期；对未指定图谱范围、要求在 T0 比较数据源全部记录的任务，系统接受了外部覆盖为 UNKNOWN 的本地路径排名。

### 第一次修复遗漏的边界

第一次修复把声明的外部范围正确地传给证据校验，但 `ObjectiveEvidence.goalScope` 对未声明范围的目标还使用模拟时刻或局部词语片段推断 LOCAL_WORLD。T0 由此隐式改写了结论范围。`complete_objective` 随后只按运行子图规则校验引用，导致当前授权子图的完整搜索被拿来支撑数据源全量结论。最终评估还存在从既有完成事件和模型摘要产生完成表述的路径。

### 服务端验证和输出

- `ObjectiveEvidence.java` 与 `ObjectiveProof.java` 分别处理目标范围和证明。优先采用冻结的结构化范围。扩展场景中的自由文本须具有可验证的局部边界，或经受限的单对象观测语法绑定到唯一的冻结对象并由授权点查证实；其余无明确范围的请求保留为 UNDETERMINED。T0 或其他逻辑时刻继续约束评价时间，独立于结论范围。
- `RunStateRepository.java` 提供运行、图谱版本、任务有效授权及覆盖修订。`CompleteObjective.java`、`EventRunPolicy.java`、`RunTerminationPolicy.java` 与 `ObjectiveReviewService.java` 使用同一套充分性判断。分别核对来源、范围、授权、版本、搜索完成、截断和分页。无法证明目标时记录 OBJECTIVE_COMPLETION_DEFERRED，已有完成事件也须在扩展场景重新验明证据。扩展场景的完成摘要和最终结果由服务端从验明的证据生成，并描述结果所适用的图谱范围。
- `SimulationTypes.java` 与 `WorldService.java` 支持可选的冻结 `evaluationPolicy.externalExistence`。`KnowledgeExpansionService.java` 与 `HierarchyRuntimeHost.java` 仅从已经授权且实际返回指定记录的外部点查生成来源、版本和记录标识证明。合法存在性目标即使在整体覆盖 UNKNOWN 时仍可完成。缺少目标匹配的可核验全量证明时，外部全量排名、否定结论或不确定范围的目标仍需补证；局部标记 COMPLETE 也无法满足外部全量需求。兼容旧的冻结策略、构造接口及事件数据。
- 原基线、批准、冻结清单和 Stage Gate 保持原样。两次独立验收的正式记录和原始证据分别核对 335 项、330 项 SHA-256。累计回归再次覆盖包边界、历史分支来源、权限隔离、事件去重、暂停和重启恢复及 ECA 传播。真实业务 MCP 保留原批准适用条件，本轮使用本地测试服务。

### 开发测试

- 在修改实现前增加失败回归。`ObjectiveCoverageTest.java` 覆盖显式外部 UNKNOWN/PARTIAL、不同措辞的无边界全量请求、带时刻的全量请求、否定性和模糊范围、完整覆盖与搜索截断和覆盖范围不匹配，以及过期与非授权证据。`ScopedCompletionTest.java` 覆盖拒绝后无完成事件、授权当前子图排名、单对象观测和服务端最终报告。`KnowledgeTaskGatewayTest.java` 使用本地外部服务验证准确的存在性证据、旧修订和窄授权。
- JDK 21 上用 Maven 3.9.16 离线执行 `mvn -B -o -s <本机设置> package`。后端 160 项通过，无失败、错误或跳过，打包和 Spotless 通过。原验收夹具使用隔离副本运行，合计 71 项通过，包含前四阶段累计 47 项及本阶段 24 项。同一份 EV-314 在修复后 4 项通过，原时刻夹具仍在允许时间完成合法局部目标。
- `pnpm test` 86 项通过，`pnpm build` 退出 0，仍有已有的 Comparison 分块体积提示。`python scripts/acceptance/test_stage5_architecture.py` 通过，源码和编译后包图均无循环。`python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` 37 项通过。
- 实际 JVM 复测第三阶段待研判与执行中任务重启恢复、第四阶段 ECA 事务恢复；第五阶段以本地 MCP 完成导入、模拟更新后中断进程与关闭知识源，重启后通过历史分支、字段来源和旧 HTTP/SSE/Fork 冒烟。
- 本记录为开发自测。第五阶段待第三次独立验收。

| 证据 | 本机路径 | SHA-256 |
|---|---|---|
| 原 EV-314 在旧候选上的断言失败 | D:/temp/gass-s5-scope-red-opjclayr/reproduction.log | be4292b1a9117f7be6ad0e6ebdc2363cfea26fb73403120a34a2f4e5f4d1ee5b |
| 原候选的带时刻全量误判 | D:/temp/gass-s5-scope-red-opjclayr/scope-timed-provider.json | 1e6500e2098bbb9c85a22cbcffa01672bbbac5b31bdf6d86a7a210492007f7ce |
| 后端全量、格式校验和打包 | D:/temp/gass-s5-scope-full.log | 2c46b4bf76795813cc9dcaf31cb3a89f1a265189348c1ef88ab853cf75f7380a |
| 原验收夹具与累计回归 | D:/temp/gass-s5-scope-green-ho8oka72/cumulative-final.log | bff49004520331d8aedec4dc7b4ee5da59846d2b5a031684e6e548d8d878be96 |
| 同一带时刻目标修复后延后完成 | D:/temp/gass-s5-scope-green-ho8oka72/scope-timed-provider.json | cd3eccf2975f03ea55bc2d6214a1ebf55adeaba602de7a61dc8393ea5a890c6d |
| 同一显式外部目标修复后待补数据 | D:/temp/gass-s5-scope-green-ho8oka72/scope-external.json | 0049ad9e939892eebc0284130c0d900bf12e8999575dcce07a038210b9c2729c |
| 同一当前局部目标修复后正常完成 | D:/temp/gass-s5-scope-green-ho8oka72/scope-local.json | 13dcd78f205c377ad2cc582c0f1b4e16663b6985a4ce5021eef3dc09b33ccd49 |
| 前端全量及生产构建 | D:/temp/gass-s5-scope-frontend.log | 93d223f6adb533dce22b7fe4c9ca001df8c562ac608d4f5b10265352cfacc6bb |
| 源码和编译包图、清单与原始证据核对 | D:/temp/gass-s5-scope-immutable-architecture.json | cdf8018c66b2590dbc979848ab016fea2a3653095d335abc37345d38ff1f68ff |
| 第三阶段待研判进程恢复 | D:/temp/gass-s5-scope-prior-processes-e2dgjdh8/pending_restart/pending-restart-trace.json | 2b7bee0c75bfa9cfe2e2a24741512662aa73f6c49d242de484cd1f9b70bf5fe3 |
| 第三阶段任务进程恢复 | D:/temp/gass-s5-scope-prior-processes-e2dgjdh8/task_restart/task-restart-trace.json | 37649c3bd7f695063586309cae1475a001a7263c65258c8e5515003f794e4aa4 |
| 第四阶段 ECA 进程恢复 | D:/temp/gass-s5-scope-prior-processes-e2dgjdh8/rule_restart/rule-restart-trace.json | cfc120023a2e51ffe41fa90f94f2d3795b2b269115ea0c9404b1e67b7cb8fb29 |
| 最终产物的第五阶段断源重启及分支来源 | D:/temp/gass-s5-scope-final-restart-1795765685494/result.json | e5d87e1b43f32413daf66f3b3833a88218d567e9a8609061c5fddd4217ba1649 |
| 最终产物的旧 HTTP/SSE/Fork 冒烟 | D:/temp/gass-s5-scope-final-restart-1795765685494/legacy-smoke.log | 770795a0a58bbb4ce6edc3b1ad9aad189d46562dbfffbf9b355537f58a236908 |
