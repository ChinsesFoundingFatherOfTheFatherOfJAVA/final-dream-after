-- ======================================================
-- v1.1 语音对话与陪练者形象 - 表结构扩展 DDL
-- 日期: 2026-09-15
-- 目标库: final_dream (10.100.26.200:3306)
-- 状态: 已于 2026-09-15 执行（幂等脚本，重复执行安全）
-- 对应: 02-技术实现方案.md 4.3 节
-- ======================================================

-- 1. 虚拟陪练者表：新增形象字段
ALTER TABLE virtual_trainer ADD COLUMN avatar_image VARCHAR(500) NULL
  COMMENT '陪练者形象图片URL(AI生成或上传, 空则学员端显示默认占位图)' AFTER trainer_name;

-- 2. 会话消息表：新增语音字段（文字消息为 NULL）
ALTER TABLE training_session_message ADD COLUMN (
  audio_url VARCHAR(500) NULL COMMENT '音频URL(学员录音)',
  audio_duration_ms INT NULL COMMENT '音频时长(毫秒)',
  input_mode TINYINT DEFAULT 1 COMMENT '输入方式: 1=文字, 2=语音'
);

-- 验证（已执行确认）：
-- SELECT input_mode, COUNT(*) FROM training_session_message GROUP BY input_mode;
-- 存量 21 条消息 input_mode 均为 1（默认值生效，v1.0 数据不受影响）
