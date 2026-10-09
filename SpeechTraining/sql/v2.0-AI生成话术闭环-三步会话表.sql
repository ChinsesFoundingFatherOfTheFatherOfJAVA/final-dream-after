-- ============================================================
-- AI生成话术闭环升级 三步会话留痕表（03 §3）
-- 版本 v2.0 ｜ 2026-10-08 ｜ 数据库：final_dream（SpeechTraining 应用所属）
-- 幂等脚本：重复执行安全（MySQL 8.0 无 IF NOT EXISTS 的表判断走 information_schema，此处直接 IF NOT EXISTS）
-- ============================================================

CREATE TABLE IF NOT EXISTS `sparring_ai_gen_record` (
  `id`           VARCHAR(32)  NOT NULL COMMENT '雪花ID',
  `session_id`   VARCHAR(64)  NOT NULL COMMENT '前端生成会话uuid',
  `step`         TINYINT      NOT NULL COMMENT '1场景 2大纲 3话术',
  `request_hash` VARCHAR(64)  NOT NULL COMMENT '上一步result摘要(步骤一致性校验)',
  `request_json` TEXT         NULL COMMENT '该步入参快照',
  `result_json`  MEDIUMTEXT   NULL COMMENT '该步出参(场景五字段/环节数组/话术数组)',
  `created_by`   VARCHAR(32)  NULL COMMENT '操作人',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`, `step`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话术训练-AI生成三步会话留痕';
