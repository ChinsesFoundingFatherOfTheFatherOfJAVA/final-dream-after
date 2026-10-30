-- =====================================================================
-- 历史数据回填：已发布主题的学员可见材料 → 当前发布版本快照
-- 库：final_dream
-- 日期：2026-11-03
-- 背景：ContentVersionServiceImpl 原先发布时只复制场景/案例/画像，漏了材料快照，
--       导致 act_content_version_material 全表为空，学员端「学习资料」与材料查看页永远空白。
--       代码已修（发布即写快照），此脚本只为修复前已发布的历史主题补一次。
-- 幂等：先按 (theme_id, content_version_no) 删除再插入；可重复执行。
-- =====================================================================

DELETE v
  FROM act_content_version_material v
  JOIN act_theme t
    ON t.id = v.theme_id
   AND t.published_version_no IS NOT NULL
   AND v.content_version_no = t.published_version_no;

INSERT INTO act_content_version_material (
  id, theme_id, content_version_no, material_id, name, file_type, file_size,
  source_type, source_id, preview_file_id, render_mode, visibility, sort_order, created_at
)
SELECT
  CONCAT('vm', LPAD(@row := @row + 1, 20, '0')) AS id,
  m.theme_id,
  t.published_version_no,
  m.id,
  m.name,
  m.file_type,
  m.file_size,
  CASE WHEN m.file_id LIKE 'kng\_%' THEN 'online_classroom' ELSE 'act_local' END,
  CASE WHEN m.file_id LIKE 'kng\_%' THEN SUBSTRING(m.file_id, 5) ELSE NULL END,
  m.preview_file_id,
  CASE WHEN m.preview_file_id IS NULL THEN 'text' ELSE 'pdf' END,
  'student',
  1,
  NOW()
FROM act_ai_material m
JOIN act_theme t
  ON t.id = m.theme_id
 AND t.published_version_no IS NOT NULL
WHERE m.visibility = 1
ORDER BY m.theme_id, m.created_at;
