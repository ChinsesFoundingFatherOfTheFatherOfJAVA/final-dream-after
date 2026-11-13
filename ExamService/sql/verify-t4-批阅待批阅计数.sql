-- =====================================================================
-- t4 验收验证：批阅后待批阅计数减少（真实库、真实列、真实 SQL 语义）
--
-- 目的：单测用的是 mock mapper，能证明 Java 分支对，但证明不了
--   「pendingQty 的 SQL 在真库真列上成立」。本脚本在真库上：
--     1) 造一份答卷：2 道主观题 correct_flag=3（待批阅）
--     2) 用 MarkServiceImpl.list() 的同一口径算 pendingQty → 期望 1（1 份答卷待批）
--     3) 模拟提交批阅：correct_flag→1、score 落库、grade_mode=2、grade_comment 落库
--     4) 再算一次 pendingQty → 期望 0
--     5) 清理全部临时行（id 前缀 e2et4），不残留
--
-- 只影响 id LIKE 'e2et4%' 的行，其余数据概不触碰。
-- =====================================================================

SET @attempt := 'e2et4attempt0001';
SET @ans1 := 'e2et4ans0000001';
SET @ans2 := 'e2et4ans0000002';
SET @arrange := '2112371048779218946';   -- 真实已发布考试（create_user_id=13）
SET @snap := '2112422915433529346';
SET @q1 := '2112374768329949185';

-- ---------- 清理上次残留（幂等） ----------
DELETE FROM exam_attempt_answer WHERE attempt_id = @attempt;
DELETE FROM exam_attempt WHERE id = @attempt;

-- ---------- 1) 造卷：未批阅态 ----------
INSERT INTO exam_attempt
  (id, arrange_id, student_id, user_id, paper_id, snapshot_id, attempt_no, status, score,
   pass_status, start_at, submit_at, submit_type, ip, duration_sec, screen_switch_count,
   grader_names, graded_at, overall_comment, rank_no, analysis_version, deleted)
VALUES
  (@attempt, @arrange, 'e2et4student001', '7', '2112371047936163841', @snap, 901, 2, 0.00,
   -1, '2026-11-11 10:00:00', '2026-11-11 10:05:00', 1, '', 300, 0,
   '', NULL, NULL, NULL, 0, 0);

INSERT INTO exam_attempt_answer
  (id, attempt_id, ques_id, section_index, order_index, answer_json, correct_flag, score,
   max_score, answer_time, grade_mode, grade_comment, marked)
VALUES
  (@ans1, @attempt, @q1, 0, 1, '{"answer":["我的作答一"]}', 3, 0.00, 1.00,
   '2026-11-11 10:03:00', 0, NULL, 0),
  (@ans2, @attempt, 'e2et4ques-fin', 0, 2, '{"answer":["我的作答二"]}', 3, 0.00, 1.00,
   '2026-11-11 10:04:00', 0, NULL, 0);

-- ---------- 2) 批阅前：MarkServiceImpl.list() 的 pendingQty 口径 ----------
--     口径 = 该考试下「存在 correct_flag=3 的题 或 status=2」的答卷数
SELECT 'BEFORE_pendingQty' AS step, count(DISTINCT a.id) AS v
FROM exam_attempt a
WHERE a.arrange_id = @arrange
  AND a.deleted = 0
  AND a.submit_at IS NOT NULL
  AND a.status IN (2, 3)
  AND (a.status = 2 OR EXISTS (
        SELECT 1 FROM exam_attempt_answer x
         WHERE x.attempt_id = a.id AND x.correct_flag = 3));

SELECT 'BEFORE_pending_flags' AS step, count(*) AS v
FROM exam_attempt_answer WHERE attempt_id = @attempt AND correct_flag = 3;

-- ---------- 3) 模拟提交批阅（MarkServiceImpl.applyGrades 的写入形态） ----------
--     grade_mode=2 是硬要求：ExamGradingService 的幂等靠 grade_mode != 0 跳过，
--     漏写会把人工分重新判成机器分。
UPDATE exam_attempt_answer
   SET correct_flag = 1,
       score = 1.00,
       max_score = 1.00,
       grade_mode = 2,
       grade_comment = CASE id WHEN @ans1 THEN '回答完整' ELSE '关键词缺失' END,
       updated_at = NOW()
 WHERE attempt_id = @attempt;

-- 收口：无待批题 → status=3、总分逐题求和（不累加）、写批阅人/批阅时间/总评
UPDATE exam_attempt a
   SET a.score = (SELECT COALESCE(SUM(x.score), 0) FROM exam_attempt_answer x
                   WHERE x.attempt_id = a.id),
       a.status = 3,
       a.pass_status = CASE WHEN (SELECT COALESCE(SUM(x.score), 0)
                                   FROM exam_attempt_answer x WHERE x.attempt_id = a.id) >= 1.00
                            THEN 1 ELSE 0 END,
       a.grader_names = '杨君野',
       a.graded_at = NOW(),
       a.overall_comment = '总体良好',
       a.updated_at = NOW()
 WHERE a.id = @attempt;
-- ---------- 4) 批阅后：同一口径再算 ----------
SELECT 'AFTER_pendingQty' AS step, count(DISTINCT a.id) AS v
FROM exam_attempt a
WHERE a.arrange_id = @arrange
  AND a.deleted = 0
  AND a.submit_at IS NOT NULL
  AND a.status IN (2, 3)
  AND (a.status = 2 OR EXISTS (
        SELECT 1 FROM exam_attempt_answer x
         WHERE x.attempt_id = a.id AND x.correct_flag = 3));

SELECT 'AFTER_attempt' AS step,
       status, score, pass_status, grader_names, overall_comment IS NOT NULL AS has_comment
FROM exam_attempt WHERE id = @attempt;

SELECT 'AFTER_answers' AS step, ques_id, correct_flag, score, grade_mode, grade_comment
FROM exam_attempt_answer WHERE attempt_id = @attempt ORDER BY order_index;

-- ---------- 5) 清理 ----------
DELETE FROM exam_attempt_answer WHERE attempt_id = @attempt;
DELETE FROM exam_attempt WHERE id = @attempt;
SELECT 'CLEANUP_leftover' AS step, count(*) AS v
FROM exam_attempt WHERE id = @attempt;

-- ---------- 6) 作废路径：显式 set rank_no=null 是否真的把库里的名次清掉 ----------
--     这是 DER-22 记的那个坑：entity.setRankNo(null)+updateById 在真库里是空操作。
INSERT INTO exam_attempt
  (id, arrange_id, student_id, user_id, paper_id, snapshot_id, attempt_no, status, score,
   pass_status, start_at, submit_at, submit_type, ip, duration_sec, screen_switch_count,
   grader_names, graded_at, overall_comment, rank_no, analysis_version, deleted)
VALUES
  ('e2et4attempt0002', @arrange, 'e2et4student002', '8', '2112371047936163841', @snap, 902, 3, 2.00,
   1, '2026-11-11 11:00:00', '2026-11-11 11:05:00', 1, '', 300, 0,
   '杨君野', NOW(), NULL, 2, 0, 0);

SELECT 'INVALID_before_rank' AS step, rank_no FROM exam_attempt WHERE id='e2et4attempt0002';

-- 模拟 clearRankNo：UpdateWrapper 的 set(rank_no, null)
UPDATE exam_attempt SET rank_no = NULL, updated_at = NOW() WHERE id='e2et4attempt0002';

SELECT 'INVALID_after_rank_is_null' AS step, rank_no IS NULL AS cleared
FROM exam_attempt WHERE id='e2et4attempt0002';

DELETE FROM exam_attempt WHERE id IN ('e2et4attempt0002');
SELECT 'CLEANUP_leftover_2' AS step, count(*) AS v FROM exam_attempt WHERE id LIKE 'e2et4%';
