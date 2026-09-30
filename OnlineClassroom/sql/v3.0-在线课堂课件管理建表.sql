-- ============================================================
-- 在线课堂（管理端）课件管理 建表脚本
-- 版本 v3.0 ｜ 2026-09-30 ｜ 数据库：final_dream
-- 配套：01-需求文档、02-技术实现方案、03-接口文档
-- ============================================================

CREATE TABLE IF NOT EXISTS `kng_courseware` (
  `id`                       VARCHAR(32)  NOT NULL             COMMENT '雪花ID(字符串)',
  `title`                    VARCHAR(200) NOT NULL             COMMENT '名称',
  `type`                     TINYINT      NOT NULL             COMMENT '1文档 2视频 3音频 4压缩 5微课(占位) 6SCORM 7HTML 8外链',
  `catalog_id`               VARCHAR(32)  NOT NULL             COMMENT '所属分类 kng_catalog.id',
  `status`                   TINYINT      NOT NULL DEFAULT 0   COMMENT '0未发布 1发布中 2已发布 3已下架',
  `on_sale`                  TINYINT      NOT NULL DEFAULT 1   COMMENT '上架位：1上架 0下架',
  `standard_minutes`         INT          NOT NULL DEFAULT 0   COMMENT '标准时长-分',
  `standard_seconds`         INT          NOT NULL DEFAULT 0   COMMENT '标准时长-秒',
  `standard_duration_follow` TINYINT      NOT NULL DEFAULT 0   COMMENT '0单独设置 1跟随学分策略',
  `study_score`              DECIMAL(5,2) NULL                 COMMENT '单独学分；NULL=跟随学分策略',
  `finish_standard_percent`  INT          NOT NULL DEFAULT 100 COMMENT '完课标准(10~100)',
  `description`              TEXT         NULL                 COMMENT '简介(富文本HTML)',
  `cover_url`                VARCHAR(500) NULL                 COMMENT '封面相对路径',
  `code`                     VARCHAR(100) NULL                 COMMENT '编码',
  `version_str`              VARCHAR(100) NULL                 COMMENT '版本',
  `platform_no`              VARCHAR(32)  NULL                 COMMENT '平台课程编号 ZXKTyyyyMMddNNNN',
  `view_permission`          TINYINT      NOT NULL DEFAULT 1   COMMENT '1跟随分类浏览权限 2单独设置允许浏览',
  `download_enabled`         TINYINT      NOT NULL DEFAULT 1   COMMENT '允许下载 0/1',
  `download_permission`      TINYINT      NOT NULL DEFAULT 1   COMMENT '1跟随分类 2单独设置',
  `app_save_enabled`         TINYINT      NOT NULL DEFAULT 1   COMMENT '允许App保存 0/1',
  `app_save_permission`      TINYINT      NOT NULL DEFAULT 1   COMMENT '1跟随分类 2单独设置',
  `speed_enabled`            TINYINT      NOT NULL DEFAULT 1   COMMENT '倍速播放开关',
  `speed_hours_calc`         TINYINT      NOT NULL DEFAULT 1   COMMENT '学时倍速计算开关',
  `ai_subtitle_enabled`      TINYINT      NOT NULL DEFAULT 0   COMMENT 'AI字幕开关',
  `copy_of`                  VARCHAR(32)  NULL                 COMMENT '源课件id',
  `contributor_id`           VARCHAR(32)  NULL                 COMMENT '贡献者 sys_user.id',
  `language`                 VARCHAR(20)  NULL                 COMMENT '课件级语种',
  `catalog_name`             VARCHAR(50)  NOT NULL DEFAULT ''  COMMENT '分类名冗余',
  `created_by`               VARCHAR(32)  NULL                 COMMENT '上传者 sys_user.id',
  `created_by_name`          VARCHAR(50)  NULL                 COMMENT '上传者姓名冗余',
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传日期',
  `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日期',
  PRIMARY KEY (`id`),
  KEY `idx_catalog` (`catalog_id`),
  KEY `idx_status` (`status`),
  KEY `idx_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课件';

CREATE TABLE IF NOT EXISTS `kng_courseware_file` (
  `id`            VARCHAR(32)  NOT NULL,
  `courseware_id` VARCHAR(32)  NOT NULL,
  `file_name`     VARCHAR(255) NOT NULL,
  `storage_path`  VARCHAR(500) NULL,
  `file_size`     BIGINT       NOT NULL DEFAULT 0,
  `language`      VARCHAR(20)  NULL,
  `sort_order`    INT          NOT NULL DEFAULT 0,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_courseware` (`courseware_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课件文件/外链';

CREATE TABLE IF NOT EXISTS `kng_courseware_attachment` (
  `id`            VARCHAR(32)  NOT NULL,
  `courseware_id` VARCHAR(32)  NOT NULL,
  `file_name`     VARCHAR(255) NOT NULL,
  `storage_path`  VARCHAR(500) NOT NULL,
  `file_size`     BIGINT       NOT NULL DEFAULT 0,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_courseware` (`courseware_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课件附件';

CREATE TABLE IF NOT EXISTS `kng_upload_chunk` (
  `upload_id`    VARCHAR(64)  NOT NULL,
  `chunk_index`  INT          NOT NULL,
  `total_chunks` INT          NOT NULL,
  `file_name`    VARCHAR(255) NOT NULL,
  `file_size`    BIGINT       NOT NULL,
  `md5`          VARCHAR(64)  NOT NULL,
  `rel_dir`      VARCHAR(200) NOT NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`upload_id`, `chunk_index`),
  KEY `idx_md5` (`md5`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-上传分片暂存';

CREATE TABLE IF NOT EXISTS `kng_study_record` (
  `id`                VARCHAR(32) NOT NULL,
  `courseware_id`     VARCHAR(32) NOT NULL,
  `user_id`           VARCHAR(32) NOT NULL,
  `dept_id`           VARCHAR(32) NULL,
  `post`              VARCHAR(50) NULL,
  `study_status`      TINYINT     NOT NULL,
  `start_study_time`  DATETIME    NULL,
  `end_study_time`    DATETIME    NULL,
  `study_len_hours`   DECIMAL(8,2) NOT NULL DEFAULT 0,
  `finish_cost_days`  INT         NULL,
  `finish_method`     TINYINT     NULL,
  `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_courseware` (`courseware_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课件学习记录';

CREATE TABLE IF NOT EXISTS `kng_ref` (
  `id`            VARCHAR(32) NOT NULL,
  `courseware_id` VARCHAR(32) NOT NULL,
  `ref_type`      TINYINT     NOT NULL,
  `ref_id`        VARCHAR(32) NOT NULL,
  `ref_name`      VARCHAR(200) NOT NULL,
  `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_courseware` (`courseware_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课件引用关系';
