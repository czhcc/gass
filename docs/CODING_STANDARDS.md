# 代码规范

## 后端
JDK 21、Spring Boot 4、Maven 多模块。bootstart 负责启动和运行配置，app 负责业务与 JPA，common 存放共享类型；依赖方向 bootstart → app → common。包名以 com.trs 开头。
Java 类和公共方法使用 Javadoc，并以当前系统用户名填写 @author。使用 Lombok 消除样板代码，SLF4J 记录日志；禁止记录密码、哈希或会话标识。REST 返回正确 HTTP 状态，不能向客户端暴露持久化实体或密码。

## 数据库
Flyway 按 V数字__描述.sql 追加迁移，禁止修改已经执行的迁移。SQL 关键字大写，表名和字段名使用小写 snake_case。SQLite 不支持 COMMENT ON TABLE/COLUMN，表和字段含义使用 -- 注释紧邻定义保留，不执行不受支持的语句。开启 JPA validate，禁止自动建表替代迁移。

## 前端
TypeScript strict；Svelte 组件配合 Tailwind，界面遵循 DESIGN.md。所有用户文案提供中英文。表单必须有标签、焦点反馈、错误状态和重复提交保护。

## 测试与交付
先写测试，再实现功能。认证至少验证错误密码、会话恢复和注销失效。前端验证语言切换、登录、注销、错误反馈。使用锁文件安装依赖。生产环境通过 HTTPS 反向代理访问，并保留 Secure、HttpOnly、SameSite 会话 Cookie。默认密码只用于初始化，生产使用安全配置覆盖；修改初始化变量不会更新已有账号。
