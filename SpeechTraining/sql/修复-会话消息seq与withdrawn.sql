-- ======================================================
-- 修复 2026-09-15 之前旧代码写入的会话消息数据
-- 背景：旧版 FlowEngineService 落库陪练者消息时参数错位，
--       导致 seq 重复错乱、陪练者消息 withdrawn 被误写为 4/5/6/7，
--       报告页"对话记录"会过滤掉第 2 轮起的陪练者消息。
-- 说明：按消息写入顺序（id 升序）重排 seq 为 0..N；
--       陪练者消息 withdrawn 统一置 0（陪练者消息永不被撤回）。
--       可重复执行，仅影响 role=1 或 seq 不连续的行。
-- ======================================================

-- 1. 重排 seq：每个会话内按 id 升序从 0 开始
UPDATE training_session_message m
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY id) - 1 AS new_seq
    FROM training_session_message
) t ON m.id = t.id
SET m.seq = t.new_seq
WHERE m.seq <> t.new_seq;

-- 2. 陪练者消息 withdrawn 一律置 0
UPDATE training_session_message
SET withdrawn = 0
WHERE role = 1 AND (withdrawn IS NULL OR withdrawn <> 0);

-- 验证（修复后应看到：seq 每会话内 0..N 连续无重复，陪练者消息 wd 全为 0）
SELECT session_id, id, seq, role, round_no, passed, withdrawn, LEFT(content, 20) AS content
FROM training_session_message
ORDER BY session_id, seq, id;
