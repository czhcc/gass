# V1.1 功能与验收记录

需求原文：REQUIREMENTS_V1.1.md。工作分支：feat/simulation-runtime。

## 本次验收口径

用户已明确“模型服务暂时使用模拟生成，以完成后续功能”。因此以 **MOCK 模型**继续验证 P4–P6，不再等待真实模型凭证；**真实 LLM 的 P3 验收仍未执行，不能用模拟结果代替**。

模拟模型实现 AgentScope `Model` 接口，仍经过真实 ReActAgent、只读工具调用及结构化 generate_response 协议。它按最新观察、权限、状态和反馈选择候选行动，不预置完整时间线；World Engine 才能产生持久化状态变化。为验证拒绝反馈，模拟响应 Agent 会执行一次明确标注的“重复关闭通道”校验探针，然后根据拒绝反馈改选行动。

## 已实现

| 阶段 | 当前交付 |
| --- | --- |
| P0 | Spring Boot 4 / JDK21 / Vue3；SQLite/Flyway；会话登录；中英文；加密模型配置、连接测试；无需凭证的模拟模型 |
| P1 | 动态类型随世界 JSON 管理；实体/有向关系存储；递归多跳和局部图；路径、度数、介数与桥接候选；Cytoscape 图谱 |
| P2 | AgentScope Java 2.0.5；角色/目标/模型/权限/可见范围配置；七种只读图工具；结构化提案、公开反馈记忆与工具审计 |
| P3（MOCK） | 双 Agent 五轮、反馈改变后续行动、合法/非法操作裁定、版本/事务提交、运行隔离、幂等、延迟注入与预算；真实 LLM 部分未验收 |
| P4 | 数据库驱动 SSE，Last-Event-ID 补读；实时图/Agent 活动/对象状态；计算暂停、继续、单步、停止；事件时间线、拖动、事件跳转、1x/2x/4x历史播放 |
| P5 | 任意有效事件位置恢复状态；回放不执行模型或 Action；检查点 Fork；独立事件序号/状态/时钟/待处理事件；合法干预；结构化实体/关系 Diff、指标与 ECharts 比较 |
| P6 | BankEnvironment 插件复用同一运行时；精确分单位金额、正金额/精度/账户/权限/币种/余额校验；500万元转账、风险标记、回放与比较 |

## 自动化验证

后端 JUnit Jupiter：16 项；前端 Vitest + jsdom：6 项。构建与类型检查通过。

覆盖：SQLite JSON/CTE/窗口函数；认证；图查询；SDK 协议和密钥加密；五轮模拟决策；环境反馈；拒绝非法行动；幂等；世界/运行/分支隔离；Fork 历史时钟与晚注入事件边界；回放不调用模型；银行资金守恒与精度；单连接运行列表回归；模型表单与模拟模式；前端回放不触发计算；SSE重复/乱序事件去重。

HTTP 全流程脚本：`python scripts/smoke_mock.py`。已验证泄密五轮的20次决策、至少三次正式变更及拒绝记录；幂等、初始回放、SSE断线补读、Fork、手工干预、结构化比较以及银行转账后余额 A=500万元、B=700万元；再次单步不重复转账。全部为模拟操作。

真实 Chromium 浏览器全流程通过：登录、泄密计算、只读回放、Fork、干预、ECharts比较、银行示例和390px窄屏布局。修复了运行列表嵌套连接阻塞、Select可访问标签和画布响应式重排。暂停/恢复/停止边界与工作线程交接另有后端回归测试。

## 仍需区分的限制

- 真实 OpenAI 兼容模型可配置，SDK 协议测试通过，但真实 LLM 自主性、成本与五轮验收未执行。
- 当前使用串行 EVENT_DRIVEN 调度；未提供 TURN_BASED 同轮快照并行裁定。
- 动态类型和 Agent 可通过 JSON 配置，尚无完整可视化 Schema 编辑器或多人协同权限。
- Token/请求限制采用保守预留预算，未实现供应商实际 Token 计费。
- 暂停/停止在当前计算单步结束后生效；历史播放是独立的只读过程。
- 单机、单写入调度器；不支持多实例横向部署。
- 不能把 MOCK、HYPOTHESIS、INFERRED、SIMULATED 当成真实证据。

## 检查命令

```sh
cd backend && mvn clean verify
cd ../frontend && pnpm install --frozen-lockfile && pnpm test && pnpm build
# 启动前后端后，从仓库根目录执行：
python scripts/smoke_mock.py
```

运行镜像以已验证的本地构建产物组装，容器中前端HTML/JS、模拟推演、SSE补读、Fork/干预/比较及银行转账检查通过。标准多阶段Dockerfile依赖下载流程未重新验证。旧初始化镜像保留，新版导出为 docker/studio-current-image.tar（Git忽略）。
