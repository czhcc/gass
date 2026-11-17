# Graph Agent Simulation Studio

基于图结构的推演平台初始化工程。当前范围为账号密码登录、中英文切换、空白主页及注销；尚未实现图编辑和推演业务。

## 目录
- backend：JDK 21 / Spring Boot 4.1.2 / Maven，bootstart、common、app 三模块
- frontend：Svelte 5 / Vite / TypeScript / Tailwind CSS 4，pnpm
- docs：代码规范
- docker：前后端同一镜像与 Compose 部署

## 本地开发
需要 JDK 21、Maven 3.9.11、Node 24、pnpm 11.25.0。

```sh
cd backend
mvn test
mvn -pl bootstart -am package
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

Vite 默认端口 5173，将 /api 转发至后端 8080。开发数据库在后端启动工作目录的 studio-dev.db。Flyway 创建表，JPA 验证结构。初始账号 admin / 123456；数据库仅保存 BCrypt 哈希。会话保存在服务端内存，重启后需重新登录。

若云环境主目录不可写，Maven 增加 `-Dmaven.repo.local=/workspace/cache/m2`，pnpm 安装使用 `XDG_DATA_HOME=/workspace/cache pnpm install --store-dir /workspace/cache/pnpm --frozen-lockfile`。

## REST
| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | /api/auth/login | JSON username/password；成功 200、错误 401，设置会话 Cookie |
| GET | /api/auth/me | 返回 username；未登录 401 |
| POST | /api/auth/logout | 注销并返回 204 |

## Docker
```sh
export STUDIO_ADMIN_PASSWORD='replace-with-a-strong-password'
docker compose -f docker/compose.yml up --build -d
```

镜像内 Spring Boot 同时提供静态前端和 REST 服务，SQLite 数据持久化至 /data。生产需配置 HTTPS 反向代理；仅在本地 HTTP 测试时设置 STUDIO_COOKIE_SECURE=false。prod 必须提供初始管理员密码；此变量仅首次建账号使用。不要将真实凭证写入仓库。镜像导出：`docker save graph-agent-simulation-studio:local -o /tmp/studio.tar`。

## 开发约定
遵循 AGENTS.md、DESIGN.md 与 docs/CODING_STANDARDS.md；不得在 main 上生成代码。功能分支测试通过后先合并 main，再按授权推送。

当前初始认证尚无账号管理、密码修改、登录限流或多实例会话存储；对公网部署前需按产品需求补充。

## 本次验证记录
后端 Maven verify 通过（1 项 REST 集成测试，包含完整认证流程）；前端 2 项测试、类型检查与生产构建通过。prod 运行容器通过首页、JS 静态资源、SQLite 登录、会话查询和注销检查。Compose 配置校验通过。

当前云环境的多阶段镜像构建在容器内 npm 下载处受阻；本次运行镜像使用本机已验证的前端和后端产物组装，并未宣称完整多阶段构建通过。已导出 `docker/studio-image.tar`（已忽略，不应提交到 Git），可用 `docker load -i docker/studio-image.tar` 导入，然后使用 Compose 的 `up -d --no-build`。其他环境应按上面的标准 Dockerfile 重新构建。
