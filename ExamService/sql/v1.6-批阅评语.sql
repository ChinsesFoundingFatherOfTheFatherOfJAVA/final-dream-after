-- =====================================================================
-- v1.6 批阅评语（t4 批阅模块，契约 04 §11B.3 / §11B.4）
--
-- 为什么需要这张迁移：
--   契约 §11B.4 的提交体里有 `grades[].comment`（逐题评语）与 `overallComment`（总评），
--   §11B.3 的详情响应也要把它们回显出来（「重新批阅」时必须看到上次写了什么，
--   否则改分就是盲改）。但 `07-建表SQL` 与 v1.5 迁移都没有承载评语的列，
--   逐题明细只有 answer_json/correct_flag/score/max_score/grade_mode。
--
-- 为什么不用 exam_arrange_operation_log.detail_json 存评语：
--   操作日志是"谁在什么时候做了什么"的审计流水，写入路径用 try/catch 吞异常
--   （见 ArrangeServiceImpl.log），一旦落库失败评语会静默消失，
--   而批阅评语是学员要看到的业务数据，不能挂在"可以不成功"的链路上。
--
-- 依赖：无。幂等：重复执行安全（先查 information_schema 再加列）。
-- 部署要求：**本迁移必须在部署本版本代码之前执行**——MyBatis-Plus 的 selectById
--   会带上实体全部列，列不存在时整张表的读写都会报 Unknown column。
-- =====================================================================

SET @db := DATABASE();

-- ============ C1. exam_attempt_answer 新增 grade_comment ============
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_attempt_answer'
        AND column_name = 'grade_comment') = 0,
    'ALTER TABLE `exam_attempt_answer` ADD COLUMN `grade_comment` varchar(500) DEFAULT NULL COMMENT ''批阅评语（人工批阅，逐题）'' AFTER `grade_mode`',
    'SELECT ''exam_attempt_answer.grade_comment exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ C2. exam_attempt 新增 overall_comment ============
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_attempt'
        AND column_name = 'overall_comment') = 0,
    'ALTER TABLE `exam_attempt` ADD COLUMN `overall_comment` varchar(500) DEFAULT NULL COMMENT ''批阅总评（整卷）'' AFTER `graded_at`',
    'SELECT ''exam_attempt.overall_comment exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ 迁移后自检：期望输出 2 行 ============
SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = @db
  AND ((table_name = 'exam_attempt' AND column_name = 'overall_comment')
    OR (table_name = 'exam_attempt_answer' AND column_name = 'grade_comment'))
ORDER BY table_name, column_name;
