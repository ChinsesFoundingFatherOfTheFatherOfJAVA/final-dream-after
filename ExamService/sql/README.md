# ExamService 建库脚本

MySQL 8.x，库 `final_dream`。按顺序执行：

| 顺序 | 脚本 | 内容 |
|---|---|---|
| 1 | `v1.0-题库管理建表.sql` | 题库域 9 张表 |
| 2 | `v1.1-试卷管理建表.sql` | 试卷域 8 张表 + 默认分类/分值种子 |
| 3 | `v1.2-考试管理建表.sql` | 考试域 15 张表 + `exam_paper_use` 增量（`ref_id`/`ref_type`/`idx_ref`） |
| 4 | `v1.4-填空题按空得分.sql` | `exam_question.blank_score_mark`、`exam_paper_question.blank_scores`/`option_scores` |
| 5 | `v1.5-学员端考试与批阅.sql` | 07 附录 C：`exam_attempt` 三列、`exam_attempt_answer.marked`、`uk_attempt_ques`、新表 `exam_face_baseline` |
| 6 | `v1.6-批阅评语.sql` | `exam_attempt.overall_comment`、`exam_attempt_answer.grade_comment`（04 §11B.3/§11B.4 的评语，07 制表时缺这两列） |

合计 **33 张 `exam_*` 表**（9 + 8 + 15 + 1）。

## 幂等性

- 所有建表均为 `CREATE TABLE IF NOT EXISTS`，可重复执行。
- `v1.2` 对 `exam_paper_use` 的三个增量（两列一索引）先用 `information_schema` 判断，再 `PREPARE/EXECUTE`，
  重复执行不会报 duplicate column / duplicate key。
- `v1.1` 的种子数据用 `WHERE NOT EXISTS` 保护。

### v1.5 的查重硬闸门

`v1.5-学员端考试与批阅.sql` 第 0 步先查 `exam_attempt_answer` 是否有重复 `(attempt_id, ques_id)`：
**有重复就让脚本在那一行主动报错中断**（报 `Table ... doesn't exist`，表名写成
`__abort__clean_duplicate_exam_attempt_answer_first__` 以自解释），绝不带着重复数据去加唯一键。
清理重复后原样重跑即可；本机实测重复 0 组、脚本连续执行两次结果一致。

### v1.6 的部署顺序（重要）

`v1.6-批阅评语.sql` **必须在部署本版本代码之前执行**。
原因：MyBatis-Plus 的 `selectById` 会带上实体全部列，`exam_attempt.overall_comment` /
`exam_attempt_answer.grade_comment` 缺失时，这两张表的所有读写都会报
`Unknown column` ——不只是批阅接口，学员交卷与结果页也会一起挂。

脚本幂等：重复执行输出 `column exists` 而非报错，本机实测连续两次结果一致。

## 验收脚本（非建表，按需手动执行）

- `verify-t4-批阅待批阅计数.sql`：t4「批阅后待批阅计数减少」的**真库**验证。
  造一份带 2 道 `correct_flag=3` 的答卷 → 用 `MarkServiceImpl` 的同一口径算
  `pendingQty`（期望 1）→ 模拟批阅写入 → 再算（期望 0）→ 清理。
  只触碰 `id LIKE 'e2et4%'` 的行，其余数据不动；末尾打印残留计数，期望 0。

## 不在本目录的表

通知域三张表属 StaffManagement：

- `sys_notice_event`
- `sys_notice_recipient`
- `sys_device_token`

脚本见 `after-end/StaffManagement/sql/v2.2-通用通知通道-建表.sql`。

## 约定

- 字符集统一 `utf8mb4`。
- 时间统一 `datetime`；连接串必须带 `forceConnectionTimeZoneToSession=true`（与各服务一致）。
- 运维对账 SQL 附在 `v1.1` 末尾（注释形式），需要时手动执行。
