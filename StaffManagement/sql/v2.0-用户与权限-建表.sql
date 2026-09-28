-- ======================================================
-- 组织架构与角色权限模块 v2.0 - 用户与权限中心建表 DDL
-- 日期: 2026-09-23
-- 目标库: final_dream (10.100.26.200:3306)
-- 说明: 全部为 CREATE TABLE IF NOT EXISTS，可重复执行，不会改动已有的
--       training_* / act_* / speech_* 等业务表。
--       注意本库所在实例上还有 gulimall_amdin 库也带 sys_user/sys_role，
--       两者不同 schema，互不影响。
-- 对应文档: 《02-技术实现方案》§4 数据模型设计
-- ======================================================

-- 1. 用户表（FP-08/17）：Oracle 员工视图同步而来 + 内置 admin
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  account VARCHAR(64) NOT NULL COMMENT '登录账号：有工号=工号，无工号=手机号',
  employee_no VARCHAR(64) COMMENT '工号（Oracle GH），无则 NULL',
  name VARCHAR(128) NOT NULL COMMENT '姓名',
  mobile VARCHAR(32) COMMENT '手机号（Oracle FNCELL）',
  email VARCHAR(128) COMMENT '邮箱：视图当前无该列，恒为 NULL（待验证 C-1）',
  dept_code VARCHAR(64) COMMENT '直属部门编码（Oracle 组织编号）',
  dept_name VARCHAR(255) COMMENT '直属部门名称',
  dept_path VARCHAR(1024) COMMENT '部门完整层级路径，下划线分隔，如 杰克集团_杰克科技股份_公共关系部',
  position_name VARCHAR(128) COMMENT '岗位名称（Oracle POSINAME）',
  job_grade VARCHAR(64) COMMENT '职级（Oracle JOBGRADE）',
  entry_date DATE COMMENT '入职时间（Oracle HIREDATE）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=启用, 0=禁用（仅在职员工的管理员手动启停）',
  is_super_admin TINYINT NOT NULL DEFAULT 0 COMMENT '是否超级管理员: 1=是，拥有全部菜单权限',
  password VARCHAR(128) NOT NULL COMMENT 'BCrypt 哈希；同步任务永不覆盖',
  source VARCHAR(16) NOT NULL DEFAULT 'ORACLE' COMMENT '来源: ORACLE=同步, MANUAL=手工（内置 admin）',
  last_sync_time DATETIME COMMENT '最近一次被同步任务更新的时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_account (account),
  INDEX idx_employee_no (employee_no),
  INDEX idx_name (name),
  INDEX idx_dept_code (dept_code),
  INDEX idx_dept_path (dept_path(255)),
  INDEX idx_position_name (position_name),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 2. 部门表（FP-01/17）：同步任务按员工部门全路径合成而来
CREATE TABLE IF NOT EXISTS sys_department (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dept_code VARCHAR(64) COMMENT '部门编码（Oracle 组织编号，合成中间层为 NULL）',
  dept_name VARCHAR(255) NOT NULL COMMENT '部门名',
  parent_path VARCHAR(1024) COMMENT '父节点完整路径，根为 NULL',
  dept_path VARCHAR(1024) NOT NULL COMMENT '完整层级路径，下划线分隔',
  dept_level INT COMMENT '相对股份树层级，根为 1',
  sort INT DEFAULT 0 COMMENT '同级排序',
  user_count INT DEFAULT 0 COMMENT '该部门（含子部门）全部用户数，同步任务重算',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_dept_code (dept_code),
  INDEX idx_parent_path (parent_path(255)),
  UNIQUE KEY uk_dept_path (dept_path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- 3. 角色表（FP-12~14）
CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL COMMENT '角色名称，1-20 字，全局唯一',
  description VARCHAR(500) COMMENT '角色说明，详情页展示为"角色描述"',
  remark VARCHAR(500) COMMENT '备注',
  built_in TINYINT NOT NULL DEFAULT 0 COMMENT '内置角色标记（预留，本期恒 0）',
  created_by VARCHAR(64) NOT NULL COMMENT '创建人账号',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_role_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 4. 用户-角色关联（FP-15/16）
CREATE TABLE IF NOT EXISTS sys_user_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '用户ID',
  role_id BIGINT NOT NULL COMMENT '角色ID',
  added_by VARCHAR(64) NOT NULL COMMENT '添加人账号',
  added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
  UNIQUE KEY uk_user_role (user_id, role_id),
  INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 5. 权限点（FP-14）：静态菜单树，应用启动时 seeding
CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '权限码，如 org:employee',
  name VARCHAR(64) NOT NULL COMMENT '菜单名',
  parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父节点ID，0=根',
  route_path VARCHAR(128) COMMENT '前端路由，如 /enterprise/staff；分组节点为 NULL',
  sort INT DEFAULT 0 COMMENT '同级排序',
  UNIQUE KEY uk_perm_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点表（菜单级）';

-- 6. 角色-权限关联（FP-14）：勾选即时写入
CREATE TABLE IF NOT EXISTS sys_role_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id BIGINT NOT NULL COMMENT '角色ID',
  permission_id BIGINT NOT NULL COMMENT '权限点ID',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_role_perm (role_id, permission_id),
  INDEX idx_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- 7. 同步水位与日志（FP-17）：每次执行一条，增量水位取最近一条 SUCCESS
CREATE TABLE IF NOT EXISTS sys_sync_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sync_type VARCHAR(16) NOT NULL COMMENT 'FULL=全量, INCREMENT=增量',
  watermark DATETIME NOT NULL COMMENT '本轮结束后的 Oracle LASTCHANGE 水位',
  total INT COMMENT '本轮处理的 Oracle 记录数',
  inserted INT COMMENT '新增用户数',
  updated INT COMMENT '更新用户数',
  deleted INT COMMENT '因离职/调出范围被删除的用户数',
  skipped INT COMMENT '因仍是训练项目负责人/创建者而跳过删除的用户数',
  status VARCHAR(16) NOT NULL COMMENT 'SUCCESS / FAILED；FAILED 不推进水位',
  error_msg VARCHAR(2000) COMMENT '失败原因',
  started_at DATETIME NOT NULL,
  finished_at DATETIME,
  INDEX idx_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Oracle 同步日志与水位表';
