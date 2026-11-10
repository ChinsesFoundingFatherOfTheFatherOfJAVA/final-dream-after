-- 填空题「按空得分」：原平台 2026-11 新增功能，当时契约未记录（2026-11-10 补录）
-- 开关在题库侧（该题是否允许每空自定义分值），具体每空分值在组卷侧（同一题在不同卷子里分值可不同）。
-- 可重复执行：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断后动态执行。

SET @db := DATABASE();

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_question'
        AND column_name = 'blank_score_mark') = 0,
    'ALTER TABLE `exam_question` ADD COLUMN `blank_score_mark` tinyint NOT NULL DEFAULT 0 COMMENT ''填空题按空得分开关（1=可自定义每空得分，具体分值存试卷侧 blank_scores）'' AFTER `no_order_mark`',
    'SELECT ''exam_question.blank_score_mark already exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_paper_question'
        AND column_name = 'blank_scores') = 0,
    'ALTER TABLE `exam_paper_question` ADD COLUMN `blank_scores` varchar(2048) DEFAULT NULL COMMENT ''填空题按空得分 JSON：[{blankIndex,score}]（与答案空同序；未开启按空得分为空）'' AFTER `miss_score_type`',
    'SELECT ''exam_paper_question.blank_scores already exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 多选题「自定义各选项得分」：原平台 2026-11 新增（2026-11-10 补录）
-- miss_score_type=3 时启用，每个正确选项单独设分，学员选对哪几个就累加哪几个。
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'exam_paper_question'
        AND column_name = 'option_scores') = 0,
    'ALTER TABLE `exam_paper_question` ADD COLUMN `option_scores` varchar(2048) DEFAULT NULL COMMENT ''多选题自定义各选项得分 JSON：[{option,score}]（仅 miss_score_type=3）'' AFTER `blank_scores`',
    'SELECT ''exam_paper_question.option_scores already exists'''
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
