-- ============================================================
-- 在线课堂（管理端）分类管理 + 课程设置
-- 目标库：final_dream（10.100.26.200）
-- 说明：04 号文档中的 sys_permission 种子按现有表结构做了兼容修正。
-- ============================================================

CREATE TABLE IF NOT EXISTS `kng_catalog` (
  `id`                  VARCHAR(32)  NOT NULL COMMENT '雪花ID(字符串)',
  `parent_id`           VARCHAR(32)  NULL COMMENT '父分类id；NULL=一级分类',
  `name`                VARCHAR(50)  NOT NULL COMMENT '分类名称',
  `level`               INT          NOT NULL COMMENT '层级，一级=1，最大10',
  `order_index`         INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
  `order_type`          TINYINT      NOT NULL DEFAULT 1 COMMENT '默认课程顺序：1发布时间 2更新时间 3名称 4学习人数',
  `prefix_enabled`      TINYINT      NOT NULL DEFAULT 0 COMMENT '标题自动加前缀开关',
  `prefix_content`      VARCHAR(50)  NULL COMMENT '前缀内容',
  `date_suffix_enabled` TINYINT      NOT NULL DEFAULT 0 COMMENT '标题自动加日期开关',
  `date_suffix_format`  VARCHAR(20)  NULL COMMENT '日期格式',
  `inherit_parent`      TINYINT      NOT NULL DEFAULT 0 COMMENT '继承父分类权限标志',
  `kng_count`           INT          NOT NULL DEFAULT 0 COMMENT '本级课件数(预留)',
  `course_count`        INT          NOT NULL DEFAULT 0 COMMENT '本级课程数(预留)',
  `created_by`          VARCHAR(32)  NULL COMMENT '创建人 sys_user.id',
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课程分类';

CREATE TABLE IF NOT EXISTS `kng_catalog_permission` (
  `id`           VARCHAR(32)  NOT NULL COMMENT '雪花ID(字符串)',
  `catalog_id`   VARCHAR(32)  NOT NULL COMMENT '分类id',
  `subject_type` TINYINT      NOT NULL COMMENT '1超管&创建者 2课堂管理员 3课堂成员 4部门 5岗位 6角色',
  `subject_id`   VARCHAR(64)  NULL COMMENT '内置行NULL；部门/角色ID；岗位名',
  `subject_name` VARCHAR(100) NOT NULL COMMENT '展示名快照',
  `permissions`  VARCHAR(32)  NOT NULL COMMENT '权限码CSV',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_catalog` (`catalog_id`),
  KEY `idx_subject` (`subject_type`, `subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-分类权限';

CREATE TABLE IF NOT EXISTS `kng_setting` (
  `id`                     TINYINT      NOT NULL DEFAULT 1 COMMENT '恒为1',
  `credit_mode`            TINYINT      NOT NULL DEFAULT 1 COMMENT '学分发放模式',
  `anti_idle_enabled`      TINYINT      NOT NULL DEFAULT 0 COMMENT '防挂机开关',
  `anti_idle_mode`         TINYINT      NOT NULL DEFAULT 1 COMMENT '防挂机模式',
  `check_interval_minutes` INT          NOT NULL DEFAULT 3 COMMENT '确认在线间隔分钟',
  `confirm_seconds`        INT          NOT NULL DEFAULT 120 COMMENT '确认时限秒',
  `prompt_text`            VARCHAR(250) NOT NULL DEFAULT '已触发防挂机验证，请点击"继续学习"，否则学习将被停止',
  `updated_by`             VARCHAR(32)  NULL,
  `updated_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课程设置';

INSERT INTO `kng_setting` (`id`) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM `kng_setting` WHERE `id` = 1);

-- sys_permission 真实结构为 id BIGINT 自增、parent_id、sort，无 created_at。
INSERT INTO `sys_permission` (`code`, `name`, `parent_id`, `route_path`, `sort`)
SELECT 'kng:courseware', '课件管理', 0, '/kng/courseware', 41
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'kng:courseware');
INSERT INTO `sys_permission` (`code`, `name`, `parent_id`, `route_path`, `sort`)
SELECT 'kng:course', '课程管理', 0, '/kng/course', 42
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'kng:course');
INSERT INTO `sys_permission` (`code`, `name`, `parent_id`, `route_path`, `sort`)
SELECT 'kng:catalog', '分类管理', 0, '/kng/catalog', 43
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'kng:catalog');
INSERT INTO `sys_permission` (`code`, `name`, `parent_id`, `route_path`, `sort`)
SELECT 'kng:setting', '课程设置', 0, '/kng/setting', 44
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'kng:setting');
