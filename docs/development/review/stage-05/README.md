# 第五阶段源码与架构评审

第五阶段第三次独立验收未通过，`STAGE05-003` 仍为 OPEN。该阶段的缺陷修复已暂停，继续开发的准入条件尚未满足。

- 评审分支为 `review/stage-05`。
- 评审对应的候选代码为 `c5293b03c59a5f4909a87d24d52ce1fcdb3ec259`。
- 第五阶段开发分支 `feat/knowledge-graph-expansion` 保留在 `6285e58184b677da9ec0304bb19ad2f9a7f16316`。此提交在候选之后登记了第三次验收的候选信息。
- 本目录随第三次验收的正式文件一起提交。评审分支的文档提交用于归档，三次验收仍分别对应各自的候选 SHA。

## 正式记录

| 验收 | 候选 SHA | 独立报告 | 结果 | 证据索引 | Stage Gate |
| --- | --- | --- | --- | --- | --- |
| 第一次 | `28b903253a2f73da33b60a9a0e836eaf8daae92e` | [报告](../../acceptance/stage-05/attempt-01/review.md) | [结果](../../acceptance/stage-05/attempt-01/result.json) | [索引](../../acceptance/stage-05/attempt-01/evidence-index.json) | [门禁](../../acceptance/stage-05/attempt-01/gate-report.json) |
| 第二次 | `177f62657eb800760a9263ed23989f1fb30596af` | [报告](../../acceptance/stage-05/attempt-02/review.md) | [结果](../../acceptance/stage-05/attempt-02/result.json) | [索引](../../acceptance/stage-05/attempt-02/evidence-index.json) | [门禁](../../acceptance/stage-05/attempt-02/gate-report.json) |
| 第三次 | `c5293b03c59a5f4909a87d24d52ce1fcdb3ec259` | [报告](../../acceptance/stage-05/attempt-03/review.md) | [结果](../../acceptance/stage-05/attempt-03/result.json) | [索引](../../acceptance/stage-05/attempt-03/evidence-index.json) | [门禁](../../acceptance/stage-05/attempt-03/gate-report.json) |

[冻结清单](../../acceptance/stage-05/check-manifest.json)的 `approval` 字段保存了项目负责人的批准记录，获批及冻结材料提交为 `6f18fe2123ba006bba7da2c75cc9f298ca3ef5bf`。[冻结摘要](../../acceptance/stage-05/check-manifest.sha256)记录原始 SHA-256 为 `4a9da357bc353a32c41635f86b7dad51f767106b2380d39905728bcb4e71fbad`。

- [开发报告及两轮修复记录](../../acceptance/stage-05/development.md)
- [候选登记](../../acceptance/stage-05/candidate.json)
- [项目进度](../../PROGRESS.md)
- [STAGE05-003 源码与架构评审说明](STAGE05-003.md)

## 评审归档

[归档索引](artifact-index.json)按轮次和证据编号关联四份[独立测试夹具](fixtures/)以及二十份[原始 JSON 证据](evidence/)，分别保存原文件和归档副本的 SHA-256。JSON 保留原始字节，第三次验收的 14 条用例轨迹和结果汇总都在其中。三份 HTTP 夹具改用运行时生成的临时测试管理员口令，测试数据、请求和断言沿用独立验收夹具。外部点查对照夹具逐字节归档。

正式报告仍引用验收时的 `target/evidence/` 路径。评审以归档索引中的仓库相对路径查找这些关键证据。夹具使用演示图谱、内存数据库、本机环回测试服务和模拟决策。

## 隔离复现

在拥有对应提交历史的评审分支检出目录中准备 JDK 21、Python 3、Git 和可解析项目依赖的 Maven。以下命令从仓库根目录执行。若 Maven 未加入 PATH，可加 `--maven` 并指定可执行文件。

```powershell
python docs/development/review/stage-05/reproduce.py --case attempt-03 --output backend/target/stage05-review-repro
```

脚本从对应的候选 SHA 导出源码到临时目录，加入该轮夹具并运行 Maven，结果单独写入 `--output` 下的本次运行目录。查看其中的 JSON、Surefire 报告和 Maven 退出码。历史缺陷用例预期退出 1，出错或编译失败应结合日志核对。

| `--case` | 独立夹具 | 历史预期 |
| --- | --- | --- |
| `attempt-01` | `ReviewerStage5GlobalClaimTest` | 1 项失败，图内排名误完成外部目标 |
| `attempt-02` | `ReviewerStage5ScopeMatrixTest` | 4 项中 1 项失败，T0 外部目标误完成 |
| `attempt-03` | `ReviewerStage5NaturalScopeTest` | 14 项中 4 项失败，1 项外部目标误完成、3 项图内目标误延后 |
| `point-witness` | `ReviewerStage5ProofTest` | 通过，校验外部记录点查及拒绝过期或无关证据 |

复现的运行 ID、事件时间和耗时随运行生成。比较目标状态、证据范围、事件类型与终止原因。

本次归档在 JDK 21 和 Maven 3.9.16 下运行了表中的四个用例组。三轮缺陷复现分别产生 1、1、4 个预期的断言失败，均无执行错误或跳过。点查对照通过。仓库后端 160 项、前端 86 项测试通过。前端原输出目录遇到权限错误，改用独立输出目录后类型检查和构建通过，Vite 仍提示 Comparison 分块超过 500 kB。
