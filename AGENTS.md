# 工作约定

- 禁止在 main 分支生成或修改代码；先创建功能或修复分支。
- 需要推送服务器时，必须先将工作分支合并到 main，再推送 main。未经用户指示不要主动推送。
- 每项功能先编写测试，再编写实现。后端使用 JUnit Jupiter，前端使用 Vitest + jsdom。
- Java 类及公共方法必须有注释，包含 @author，值使用生成时当前系统用户名（whoami）。Lombok 生成方法由字段和类文档说明。
- 包前缀 com.trs；遵循 docs/CODING_STANDARDS.md。
- 每个云任务已经隔离，使用已有 checkout，不主动创建 Git worktree。
- 不提交凭证、SQLite 文件、依赖目录或构建产物。
- 运行 backend Maven test 和 frontend pnpm test、pnpm build；记录失败，不跳过断言。
