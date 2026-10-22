-- ============================================================
-- 在线课堂媒体处理与隐藏列增量脚本
-- 版本 v3.1 ｜ 2026-10-22 ｜ 数据库：final_dream
-- 说明：在 v3.0 课件管理建表脚本之后执行；所有增列均可重复执行。
-- ============================================================

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_courseware` ADD COLUMN `hidden` TINYINT NOT NULL DEFAULT 0 COMMENT ''隐藏标记，独立于status'' AFTER `status`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_courseware' AND column_name = 'hidden'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_courseware` ADD COLUMN `media_width` INT NULL COMMENT ''视频宽(px)'' AFTER `language`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_courseware' AND column_name = 'media_width'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_courseware` ADD COLUMN `media_height` INT NULL COMMENT ''视频高(px)'' AFTER `media_width`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_courseware' AND column_name = 'media_height'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_courseware` ADD COLUMN `media_codec` VARCHAR(20) NULL COMMENT ''编码格式'' AFTER `media_height`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_courseware' AND column_name = 'media_codec'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_courseware` ADD COLUMN `hls_path` VARCHAR(500) NULL COMMENT ''HLS m3u8相对路径'' AFTER `media_codec`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_courseware' AND column_name = 'hls_path'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_courseware` ADD COLUMN `subtitles_flag` TINYINT NOT NULL DEFAULT 0 COMMENT ''字幕已生成'' AFTER `hls_path`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_courseware' AND column_name = 'subtitles_flag'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `kng_setting` ADD COLUMN `media_conf` JSON NULL COMMENT ''媒体处理历史扩展位；运行时读取 application.properties'' AFTER `face_conf`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'kng_setting' AND column_name = 'media_conf'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `kng_media_job` (
  `id`            VARCHAR(32)  NOT NULL COMMENT '雪花ID',
  `job_type`      TINYINT      NOT NULL COMMENT '1 transcode 2 subtitle',
  `courseware_id` VARCHAR(32)  NOT NULL COMMENT '课件id',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 pending 1 running 2 success 3 failed',
  `stage`         VARCHAR(20)  NULL COMMENT 'subtitle管线: asr/srt/translate / transcode为NULL',
  `progress_lang` VARCHAR(20)  NULL COMMENT '翻译阶段已完成语言CSV,断点续翻',
  `error`         VARCHAR(2000) NULL COMMENT '失败原因(stderr尾部/异常)',
  `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '上限3',
  `created_by`    VARCHAR(32)  NULL COMMENT '操作人',
  `started_at`    DATETIME     NULL,
  `finished_at`   DATETIME     NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cw_type` (`courseware_id`, `job_type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-媒体处理任务';

-- 运行参数以 application.properties / 环境变量为准，media_conf 只保留历史兼容位。
