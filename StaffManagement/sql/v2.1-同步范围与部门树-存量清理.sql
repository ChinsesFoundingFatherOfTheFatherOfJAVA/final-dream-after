-- ======================================================
-- 组织架构与角色权限模块 v2.1 - 存量清理
-- 日期: 2026-09-28
-- 对应文档: 《02-技术实现方案》§6.4.1
-- 说明: 执行前先跑 v2.1-同步范围与部门树-变更.sql；本脚本保留 MANUAL 用户。
--       清理后通过手动 full=true 同步重建 sys_department 与范围内在职用户。
-- ======================================================

CREATE TABLE IF NOT EXISTS sys_user_bak_20260928 AS SELECT * FROM sys_user;
CREATE TABLE IF NOT EXISTS sys_user_role_bak_20260928 AS SELECT * FROM sys_user_role;
CREATE TABLE IF NOT EXISTS sys_department_bak_20260928 AS SELECT * FROM sys_department;
CREATE TABLE IF NOT EXISTS project_student_bak_20260928 AS SELECT * FROM project_student;

CREATE TEMPORARY TABLE IF NOT EXISTS tmp_sync_cleanup_users AS
SELECT id, account
FROM sys_user
WHERE source = 'ORACLE'
  AND (
    dept_path IS NULL
    OR (dept_path <> '杰克集团_杰克科技股份' AND dept_path NOT LIKE '杰克集团\_杰克科技股份\_%' ESCAPE '\\')
    OR status = 0
  );

CREATE TEMPORARY TABLE IF NOT EXISTS tmp_sync_cleanup_protected AS
SELECT u.id
FROM tmp_sync_cleanup_users u
WHERE EXISTS (
  SELECT 1
  FROM training_project p
  WHERE p.responsible_person = u.account OR p.created_by = u.account
);

DELETE ps
FROM project_student ps
JOIN tmp_sync_cleanup_users u ON u.account = ps.user_id
LEFT JOIN tmp_sync_cleanup_protected p ON p.id = u.id
WHERE p.id IS NULL;

DELETE ur
FROM sys_user_role ur
JOIN tmp_sync_cleanup_users u ON u.id = ur.user_id
LEFT JOIN tmp_sync_cleanup_protected p ON p.id = u.id
WHERE p.id IS NULL;

DELETE u
FROM sys_user u
JOIN tmp_sync_cleanup_users c ON c.id = u.id
LEFT JOIN tmp_sync_cleanup_protected p ON p.id = c.id
WHERE p.id IS NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_sync_cleanup_protected;
DROP TEMPORARY TABLE IF EXISTS tmp_sync_cleanup_users;

TRUNCATE TABLE sys_department;
