# 第二阶段开发报告

本阶段待独立验收。候选为 `356ea246171d6bcc252747b08301d82ae8e801f1`，基线为 `2708c13de67db48877bf54c5e11929c9187a758a`。第一阶段第二次验收准入记录已核对。第二阶段 46 项清单在实现前获批冻结，SHA-256 为 `ad0697d6c94a929d51c1a09317d9517993e4307383c469c6129c371bd111121b`。候选提交完成后在 [candidate.json](candidate.json) 单独登记。

## 交付

| 范围 | 变更 |
| --- | --- |
| 本体与 Action 定义 | `SimulationTypes`、`OntologyConstraints`、`WorldService` 和 Action 库支持可选的属性、关系端点、基数和 CEL 不变量约束。模板保存应用默认值，已冻结定义和历史快照原样兼容读取。白名单保留三种既有原语。 |
| 原子状态提交 | `DeclarativeActionEngine` 先计算最终候选。 `StateDiff` 确定性比较值及存在性，`ActionCommitService` 统一提交版本、快照、收据与审计。无实际变化的候选保持版本。 |
| 持久幂等与审计 | 新 Action 的提议、裁定、变更和任务收据共享 `actionRunId`。人工干预同键同载荷重试返回原始版本和逻辑时钟，同键异载荷拒绝。Worker 可使用回执输出版本重试原决策，保持其既有协议。 |
| 可见范围与证据 | `VisibilityPolicy` 供运行观察、工具查询、Action、委派与证据投影复用。隐藏关联参数和字段从局部视图中省略。旧查询范围未知时仅在证明全范围可见的情况下用作研判证据。历史关联优先使用显式身份。 |
| Stage Gate | 支持冻结时保留空候选、候选提交后登记、以哈希定位前置阶段报告，以及阶段二验证命令。第一阶段历史验收格式仍受支持。 |

新增 Flyway `V10__durable_action_receipts.sql`。V1–V9 无内容变化。V10 仅新增收据表，每张收据引用快照和原始逻辑时钟。旧运行、历史事件和已发布 Action 不回写。已有 REST 路径、状态 DTO、事件类型和前端入口保持兼容。事件附加身份、来源、版本和差异字段，未经授权的历史证据可见性按本阶段标准收紧。

## 开发自测

| 命令 | 实际结果 |
| --- | --- |
| 后端 `mvn -B -o -s <本机 Maven 设置> verify` | 105 项测试通过，0 失败、0 错误、0 跳过。打包成功。 |
| 后端 `mvn -pl app,bootstart spotless:apply spotless:check` | 通过。 |
| 前端 `pnpm.cmd test` | 82 项测试通过。 |
| 前端 `pnpm.cmd build --outDir <临时目录>` | 构建通过。Vite 保留基线的大分块提示。 |
| `python -m unittest discover -s scripts/acceptance -p test_stage_gate.py` | 30 项通过。 |
| `python scripts/smoke_mock.py --base-url http://127.0.0.1:<隔离端口>` | 退出 0。信息场景 245 个事件，SSE 续传正确。银行场景只转账一次，账户总额为 1,200,000,000 分。 |

Maven 验证日志 SHA-256 为 `5a80d295a5e52fd15ae3d2ea9c5244b5fb7afa09eac30ce8aec89cac8ca7180c`。Mock HTTP 日志 SHA-256 为 `f2960592147ce04a7c672b9a7940d3d9faf5dbed85a8e3b453f7b5da09e9a6ac`。开工前基线及命令采集见 [baseline.json](baseline.json)。

先行测试在新增契约和门禁接口缺失时失败。实现后曾出现严格 Action 测试夹具漏配现有的额外参数开关，已修正夹具且保留断言。首次 Mock HTTP 冒烟在临时实例刚开放端口时登录返回 401，随后显式配置临时账号并等待初始化，重试通过。

## 待验收与后续范围

待独立 Reviewer 执行冻结清单及自动 Stage Gate。开发自测包含服务器端本地模型协议与 Mock HTTP，独立 Chromium 验收留给验收会话。可选新原语及外部 LLM、MCP 按获批条件本轮不启用。模拟时钟、终止策略及完整因果链仍按后续阶段实施。
