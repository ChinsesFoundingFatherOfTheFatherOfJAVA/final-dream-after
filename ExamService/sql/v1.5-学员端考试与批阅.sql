-- 学员端考试与批阅 · 表结构变更（07-建表SQL.md 附录 C，2026-11-10）
-- 契约：07 附录 C1~C5、03 §10B.3/§10B.7/§10B.8
-- 全部为增量变更，不改既有列语义；MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
-- 统一用 information_schema 判断后 PREPARE/EXECUTE，可重复执行。
--
-- 执行前必读：C3 的唯一键 uk_attempt_ques 在存在重复 (attempt_id, ques_id) 时会失败，
-- 因此本脚本把「查重」做成硬闸门：step 0 一旦查到重复就主动报错中断，脚本不会往下跑。

SET @db := DATABASE();

-- ============ 0. 查重闸门（附录 C3 前置） ============
-- 有重复 → 引用一张不存在的表让本行报错，整个脚本中断（需先人工清理重复再重跑）。
-- 无重复 → 输出提示，继续。
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM (
        SELECT `attempt_id`, `ques_id` FROM `exam_attempt_answer`
        GROUP BY `attempt_id`, `ques_id` HAVING COUNT(*) > 1
    ) d) = 0,
    'SELECT ''ok: no duplicate (attempt_id, ques_id) in exam_attempt_answer''',
    'SELECT * FROM `__abort__clean_duplicate_exam_attempt_answer_first__`'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ C1. exam_attempt 新增 3 列 ============
-- config_snapshot：开考瞬间的配置快照（时长/通过分/作答样式/交卷限制），
--                  进行中的答卷沿用快照，避免管理员中途改配置导致「时间突然变短」（03 §10B.7 / DER-06）
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_attempt'
        AND column_name = 'config_snapshot') = 0,
    'ALTER TABLE `exam_attempt` ADD COLUMN `config_snapshot` mediumtext COMMENT ''开考时配置快照'' AFTER `analysis_version`',
    'SELECT ''exam_attempt.config_snapshot exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- identity_status：人脸核验结果 0未核验 1通过 2不通过 3无法核验（失败一律放行，03 §10B.8）
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_attempt'
        AND column_name = 'identity_status') = 0,
    'ALTER TABLE `exam_attempt` ADD COLUMN `identity_status` tinyint NOT NULL DEFAULT 0 COMMENT ''0未核验 1通过 2不通过 3无法核验'' AFTER `config_snapshot`',
    'SELECT ''exam_attempt.identity_status exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- identity_evidence：核验抓拍图 URL，复用 /exam/files/**
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_attempt'
        AND column_name = 'identity_evidence') = 0,
    'ALTER TABLE `exam_attempt` ADD COLUMN `identity_evidence` varchar(512) DEFAULT NULL COMMENT ''核验抓拍图 URL（/exam/files/**）'' AFTER `identity_status`',
    'SELECT ''exam_attempt.identity_evidence exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ C2. exam_attempt_answer 新增 marked ============
-- 学员标记（不确定的题），答题卡可快速定位
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_attempt_answer'
        AND column_name = 'marked') = 0,
    'ALTER TABLE `exam_attempt_answer` ADD COLUMN `marked` tinyint NOT NULL DEFAULT 0 COMMENT ''学员标记'' AFTER `grade_mode`',
    'SELECT ''exam_attempt_answer.marked exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ C3. exam_attempt_answer 唯一键 uk_attempt_ques ============
-- 逐题保存需要 upsert，必须保证 (attempt_id, ques_id) 唯一；
-- 组合题子题各自一行（ques_id 为子题 id），天然不冲突（03 §10B.5）。
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = @db AND table_name = 'exam_attempt_answer'
        AND index_name = 'uk_attempt_ques') = 0,
    'ALTER TABLE `exam_attempt_answer` ADD UNIQUE KEY `uk_attempt_ques` (`attempt_id`,`ques_id`)',
    'SELECT ''uk_attempt_ques exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ C4. 人脸基准照表（CompreFace 首次建档） ============
-- DEF-01：首次建档防不住首次替考，能力边界见 03 §10B.8 / 补充待讨论点。
CREATE TABLE IF NOT EXISTS `exam_face_baseline` (
  `id` varchar(32) NOT NULL,
  `user_id` varchar(32) NOT NULL,
  `image_url` varchar(512) NOT NULL COMMENT '基准照 URL（/exam/files/**）',
  `source` tinyint NOT NULL DEFAULT 1 COMMENT '1首次核验建档 2管理员预录',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学员人脸基准照（DEF-01：首次建档防不住首次替考）';

-- ============ 迁移后自检 ============
-- 期望输出 4 行（exam_attempt 3 列 + exam_attempt_answer 1 列）与 1 行（1 张新表）。
SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = @db
  AND ((table_name = 'exam_attempt' AND column_name IN ('config_snapshot','identity_status','identity_evidence'))
    OR (table_name = 'exam_attempt_answer' AND column_name = 'marked'))
ORDER BY table_name, ordinal_position;

SELECT table_name FROM information_schema.tables
WHERE table_schema = @db AND table_name = 'exam_face_baseline';
