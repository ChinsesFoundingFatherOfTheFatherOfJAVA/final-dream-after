-- ======================================================
-- 组织架构与角色权限模块 v2.1 - 同步范围与部门树变更
-- 日期: 2026-09-28
-- 对应文档: 《02-技术实现方案》§4.2 / §4.7 / §9
-- 说明: 幂等增量脚本；sys_department 整表清空并切换为 deptPath 合成树结构。
-- ======================================================

CREATE TABLE IF NOT EXISTS sys_department_bak_20260928 AS SELECT * FROM sys_department;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND column_name = 'parent_path'
    ),
    'SELECT 1',
    'ALTER TABLE sys_department ADD COLUMN parent_path VARCHAR(1024) NULL COMMENT ''父节点完整路径，根为 NULL'' AFTER dept_name'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND index_name = 'uk_dept_code'
    ),
    'ALTER TABLE sys_department DROP INDEX uk_dept_code',
    'SELECT 1'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND index_name = 'idx_parent_code'
    ),
    'ALTER TABLE sys_department DROP INDEX idx_parent_code',
    'SELECT 1'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND index_name = 'idx_dept_path'
    ),
    'ALTER TABLE sys_department DROP INDEX idx_dept_path',
    'SELECT 1'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND column_name = 'parent_code'
    ),
    'ALTER TABLE sys_department DROP COLUMN parent_code',
    'SELECT 1'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE sys_department
  MODIFY COLUMN dept_code VARCHAR(64) NULL COMMENT '部门编码（Oracle 组织编号，合成中间层为 NULL）',
  MODIFY COLUMN dept_path VARCHAR(1024) NOT NULL COMMENT '完整层级路径，下划线分隔',
  MODIFY COLUMN dept_level INT NULL COMMENT '相对股份树层级，根为 1',
  MODIFY COLUMN user_count INT DEFAULT 0 COMMENT '该部门（含子部门）全部用户数，同步任务重算';

TRUNCATE TABLE sys_department;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND index_name = 'idx_dept_code'
    ),
    'SELECT 1',
    'ALTER TABLE sys_department ADD INDEX idx_dept_code (dept_code)'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND index_name = 'uk_dept_path'
    ),
    'SELECT 1',
    'ALTER TABLE sys_department ADD UNIQUE KEY uk_dept_path (dept_path(255))'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'sys_department' AND index_name = 'idx_parent_path'
    ),
    'SELECT 1',
    'ALTER TABLE sys_department ADD INDEX idx_parent_path (parent_path(255))'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'sys_sync_log' AND column_name = 'deleted'
    ),
    'SELECT 1',
    'ALTER TABLE sys_sync_log ADD COLUMN deleted INT NULL COMMENT ''因离职/调出范围被删除的用户数'' AFTER updated'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'sys_sync_log' AND column_name = 'skipped'
    ),
    'SELECT 1',
    'ALTER TABLE sys_sync_log ADD COLUMN skipped INT NULL COMMENT ''因项目负责人保护而跳过删除的用户数'' AFTER deleted'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE sys_user
  MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
  COMMENT '状态: 1=启用, 0=禁用（仅在职员工的管理员手动启停）';

-- v2.1 权限点固定为 9 条：清掉旧话术模块遗留的 sparring:manage 及其角色关联。
DELETE rp
FROM sys_role_permission rp
JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.code = 'sparring:manage';

DELETE FROM sys_permission WHERE code = 'sparring:manage';
