# Graph Agent Simulation Studio

通用 AI 智能体事件推演工作台。根据 [V1.1 需求](docs/REQUIREMENTS_V1.1.md) 实现世界图、Agent 推演、动态展示、回放、分支比较和银行扩展。按用户要求，当前使用明确标识的模拟模型完成后续功能验证；**模拟通过不等于真实 LLM P3 验收**。能力与限制见 [验收记录](docs/ACCEPTANCE.md)。

## 已提供的功能

- 用户名密码登录、中英文切换、注销，初始账号 `admin / 123456`。
- Vue 3 / TypeScript / Tailwind / Pinia / Vue Router 基础工作台。
- OpenAI 兼容模型配置：Base URL、模型名、加密 API Key 或环境变量引用、Temperature、Token/超时/并发限制、真实 SDK 连接测试。
- 世界 JSON 导入、泄密示例、Cytoscape 静态图；SQLite 递归多跳、局部图、最短路径、度数/介数/桥接候选分析。
- 场景与 Agent 目标、模型、权限、可见范围配置，运行状态隔离。
- AgentScope Java 2.0.5 ReAct、七种只读图工具、结构化行动提案、领域校验、真实模拟状态提交、持久化观察/决策/工具/裁定/状态变化记录。
- 暂停、恢复、单步、停止 REST，幂等单步，延迟事件注入，状态版本快照查询。
- 信息领域模拟关闭同步、撤销共享、审核和基于启用关系的假设传播；模拟结果不会回写事实模板。
- 泄密及银行 Action 使用 CEL + 声明式步骤，统一校验并原子执行；“Action 维护”支持手工编辑、试算、LLM 生成草稿、版本发布和世界对象类型绑定。

已提供 SSE 动态图谱、Agent 活动、对象状态、事件时间线、只读历史播放（暂停/前后/拖动/1x/2x/4x）、检查点 Fork、模拟干预、结构化结果比较与 ECharts 指标、银行资金守恒示例。

## 本地开发

需要 JDK 21、Maven 3.9+、Node 24、pnpm 11.25.0。

```sh
cd backend
mvn clean verify
java -jar bootstart/target/bootstart-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

另一个终端：

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm dev
```

Vite 默认监听本机 `127.0.0.1`，优先使用 5173；端口被占用时自动尝试 5174、5175 等后续端口，请访问启动终端中显示的实际地址。本地开发统一使用 IPv4 回环地址，避免 Windows 上不同监听地址同时绑定相同端口而绕过冲突检测。`/api` 转发到 8080，不受前端端口变化影响。开发数据库为启动目录的 `studio-dev.db`；Flyway 自动迁移，JPA 校验账号表，业务图和运行记录使用 JDBC。SQLite 配置 WAL、外键和 busy timeout。会话在进程内，重启后需要登录；运行状态仍在数据库中，重启后运行恢复为暂停。

当前云环境工具：`JAVA_HOME=/workspace/jdk-21.0.9+10`、Maven `/workspace/apache-maven-3.9.11/bin/mvn`；Maven 参数 `-s /workspace/maven-settings.xml -Dmaven.repo.local=/workspace/cache/m2`，`MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`。pnpm 安装如遇不可写主目录，使用 `XDG_DATA_HOME=/workspace/cache pnpm install --store-dir /workspace/cache/pnpm --frozen-lockfile`。

## 无需密钥体验完整流程

1. 登录后点击“创建泄密演示”，进入“推演与回放”。点击“计算单步”或“继续计算”，查看数据库驱动的图谱、Agent工具/行动/裁定与状态变化。
2. 使用时间线播放、拖动或跳转事件。历史回放只读快照，不调用模型、不再次执行 Action。
3. 在任意历史位置输入分支名称并 Fork。切换执行 Agent，提交合法模拟干预，选择父运行查看实体/关系差异和指标。
4. 在“演示世界”选择银行世界，点击“创建银行演示”，计算单步；账户 A 从1000万元变为500万元、B 从200万元变为700万元，风险 Agent 执行 `ASSESS_RISK_CEL`，通过 CEL 计算模拟风险评分。后续单步不会重复处理同一个初始转账请求。
5. 在模型页也可单独点击“使用模拟模型”。模拟行为按最新观察和反馈选择行动，界面和事件明确标记 MOCK，不会访问外部 LLM 或真实银行。

启动应用后可运行：`python scripts/smoke_mock.py`。开发默认密码123456；生产脚本从 STUDIO_ADMIN_PASSWORD 读取登录密码。

## 维护对象 Action

进入“Action 维护”，选择领域及所属对象类型。可从内置定义载入并“新建修订草稿”，或新建 Action，填写名称、用途、参数、CEL 前置条件及声明式执行步骤。支持设置模拟属性、撤销向关联对象的关系，以及银行精确转账。参数、条件和效果提供中文说明；表达式与原始定义可展开查看。

先“校验定义”，选择世界、对象和参数“试算 Action”，确认结果后保存、发布，再“绑定到世界对象类型”。发布版本不可原位修改，删除仅适用于草稿；已绑定的 Action 可在确认弹窗后解除绑定。绑定、解除绑定和编辑世界均不改变已有运行与分支的冻结定义。

LLM 生成使用已保存的模型配置，只接收需求、领域和对象类型，不接收世界数据；合法结果保存为草稿，不会直接执行、发布或绑定。模拟模型返回明确标记 MOCK 的固定模板；生成自定义逻辑应选择真实 LLM。错误表达式或不支持的操作会被拒绝。

绑定后在“场景配置”勾选要额外授权给调查 / 银行 Agent 的自定义 Action；需要指定其他 Agent 时使用自定义 Agent JSON 的 `allowedActions`。新建运行才使用最新世界定义。具体格式、示例及限制见 [声明式 Action](docs/DECLARATIVE_ACTIONS.md)。

## 配置真实模型并验证

1. 登录，进入“模型配置”。填入 Base URL（通常以 `/v1` 结尾）、模型名及 API Key，或填写服务端 API Key 环境变量名；本地免密 Qwen/vLLM 可留空。
2. 保存后会在“模拟模型”下方的“已保存的模型”列表显示并高亮对应条目，每条均有“测试连接”和“编辑”。编辑保持模型 ID，API Key 留空保留原密钥；保存后密钥输入清空，后端不回传密钥。测试结果显示在对应模型卡片中。
3. 导入示例，选择世界和模型创建场景；也可在可选 JSON 中配置自定义 Agent。泄密和银行示例使用对应默认角色与初始事件。
4. 使用 [REST API](docs/API.md) 单步或恢复运行。真实模型验收可用以下命令（密钥仍保留在后端配置中）：

```sh
# 安全设置 STUDIO_ADMIN_PASSWORD 后执行；开发默认密码为 123456。
python scripts/verify_p3.py --model-id '<模型页面显示的 ID>'
```

该脚本调用真实 Java Runtime，不生成预设决策。结果不满足要求就返回失败；模型不一定会提出非法行动，不能用假事件补足拒绝条件。真实 LLM 成本、模型域名出站权限由实际配置决定。

API Key 以 AES-256-GCM 加密保存，开发主密钥位于启动目录的 `data/model.key`（权限 0600），可用 `studio.model-key-file` 覆盖。**数据库备份必须同时备份主密钥文件**；丢失后无法解密已有配置。生产路径 `/data/model.key` 与数据库一起持久化。生产初始密码变量仅首次建账号时使用，修改变量不会更新已有账号。

## Docker

```sh
export STUDIO_ADMIN_PASSWORD='replace-with-a-strong-password'
docker compose -f docker/compose.yml up --build -d
```

Dockerfile 将前后端构建进同一个镜像，Spring Boot 提供静态页面和 REST，数据持久化 `/data`。本地开发保持前后端独立运行。生产通过 HTTPS 反向代理访问；只有本地 HTTP 调试时设置 `STUDIO_COOKIE_SECURE=false`。

镜像导出：`docker save graph-agent-simulation-studio:local -o /tmp/studio.tar`。此前模拟推演版本已导出 `docker/studio-current-image.tar`，可以 `docker load -i docker/studio-current-image.tar` 后使用 `docker compose -f docker/compose.yml up --no-build -d`。该运行镜像使用已验证的本地产物组装，容器功能检查通过；标准多阶段依赖下载流程未重新验证。旧的 `docker/studio-image.tar` 是初始化版本，已保留且不包含本次功能。两个镜像文件均被 Git 忽略；本次模型列表与编辑改进未重新导出镜像，部署时请按当前源码重新构建。

## 工程与验证

`backend/{bootstart,app,common}` 保留原三模块。规范见 [AGENTS.md](AGENTS.md)、[代码规范](docs/CODING_STANDARDS.md)、[架构](docs/ARCHITECTURE.md)、[API](docs/API.md)。不得在 main 生成代码；推送时先合并 main。

自动化验证：后端 17 项（认证、SQLite/图、状态/隔离/幂等/反馈、事件注入、SDK、分支回放、银行约束与单连接回归），前端 9 项（认证、语言、模型配置、模拟模式、回放隔离与事件去重）。本地协议桩和 Mock Agent 的通过不等于真实 LLM 验收。
