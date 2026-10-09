-- ============================================================
-- v1.2 旁白节点（AI生成话术闭环）：training_node 增 narration 列
-- 日期：2026-10-09 ｜ 数据库：final_dream ｜ 幂等
-- ============================================================

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'training_node' AND column_name = 'narration'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `training_node` ADD COLUMN `narration` TEXT NULL COMMENT ''旁白内容(node_type=5 旁白节点专用)'' AFTER `analysis`',
  'SELECT ''narration exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node_type 注释口径更新（5=旁白）；无需 ALTER，列已 TINYINT 可容纳
