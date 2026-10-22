# ExamService 建库脚本

MySQL 8.x，库 `final_dream`。按顺序执行：

| 顺序 | 脚本 | 内容 |
|---|---|---|
| 1 | `v1.0-题库管理建表.sql` | 题库域 9 张表 |
| 2 | `v1.1-试卷管理建表.sql` | 试卷域 8 张表 + 默认分类/分值种子 |
| 3 | `v1.2-考试管理建表.sql` | 考试域 15 张表 + `exam_paper_use` 增量（`ref_id`/`ref_type`/`idx_ref`） |

合计 **32 张 `exam_*` 表**（9 + 8 + 15）。

## 幂等性

- 所有建表均为 `CREATE TABLE IF NOT EXISTS`，可重复执行。
- `v1.2` 对 `exam_paper_use` 的三个增量（两列一索引）先用 `information_schema` 判断，再 `PREPARE/EXECUTE`，
  重复执行不会报 duplicate column / duplicate key。
- `v1.1` 的种子数据用 `WHERE NOT EXISTS` 保护。

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
