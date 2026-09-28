-- v1.2 评分维度与声学特征表结构扩展
-- 开发库已于 2026-09-23 直接执行过 ①②③；本脚本供新环境/重建环境使用，可重复执行。
-- 前置结构：v1.1（学员端话术训练-新增表.sql + v1.1-语音与形象-表结构扩展.sql）。
-- 执行前检查：若 training_session_message 存在重复 (session_id, seq)，先人工裁决，否则 ② 会失败。

DELIMITER $$

DROP PROCEDURE IF EXISTS p_v12_upgrade$$
CREATE PROCEDURE p_v12_upgrade()
BEGIN
  -- ① scoring_dimension: evidence_type / metric_config
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_dimension' AND COLUMN_NAME = 'evidence_type') THEN
    ALTER TABLE scoring_dimension
      ADD COLUMN evidence_type TINYINT DEFAULT 1
        COMMENT '评分依据: 1=文字, 2=语音, 3=文字+语音。语音依据维度在本次训练无语音数据时不参与评分'
        AFTER scoring_rule;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_dimension' AND COLUMN_NAME = 'metric_config') THEN
    ALTER TABLE scoring_dimension
      ADD COLUMN metric_config VARCHAR(500)
        COMMENT '量化指标配置(JSON): {"metric":"speech_speed","params":{"bestMin":150,"bestMax":250}} 或 {"metric":"forbidden_words","params":{"deductPerHit":20}}。空=该维度由AI评分；非空=得分由后端规则计算，AI只写评语'
        AFTER evidence_type;
  END IF;

  -- ① 回填：rule_type 5=公式计算=语速，6=扣分制=违禁词；条件排除已回填行。
  UPDATE scoring_dimension SET evidence_type = 2, metric_config = '{"metric":"speech_speed"}'
   WHERE rule_type = 5 AND metric_config IS NULL;
  UPDATE scoring_dimension SET metric_config = '{"metric":"forbidden_words"}'
   WHERE rule_type = 6 AND metric_config IS NULL;

  -- ② training_session_message: uk_session_seq
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'training_session_message' AND INDEX_NAME = 'uk_session_seq') THEN
    ALTER TABLE training_session_message
      ADD UNIQUE INDEX uk_session_seq (session_id, seq),
      MODIFY COLUMN seq INT NOT NULL COMMENT '会话内序号(在会话行锁内取 MAX+1,并发下不得重复)';
  END IF;
END$$

CALL p_v12_upgrade()$$

DROP PROCEDURE IF EXISTS p_v12_upgrade$$
DELIMITER ;

-- ③ training_round_acoustic（CREATE IF NOT EXISTS 天然幂等）
CREATE TABLE IF NOT EXISTS training_round_acoustic (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL COMMENT '会话ID',
  message_id BIGINT NOT NULL COMMENT '学员语音消息ID(撤回的消息同样留存)',
  round_no INT COMMENT '轮次号(1起)',
  audio_ms INT COMMENT '录音时长(毫秒,本端WAV头,含静音)',
  speech_ms INT COMMENT '净发声时长(毫秒,各分句首尾跨度之和)',
  utterance_count INT COMMENT '分句数',
  pause_count INT COMMENT '停顿次数(相邻分句间隔≥300ms才计)',
  pause_total_ms INT COMMENT '停顿总时长(毫秒,与pause_count同口径)',
  max_pause_ms INT COMMENT '最长一次分句间隔(毫秒,不设阈值)',
  text_length INT COMMENT '识别文本长度(含标点)',
  lid_lang VARCHAR(32) COMMENT '豆包检出语种(粤语=speech_dia_cant,未检出为空)',
  definite TINYINT COMMENT '末分句是否终结结果: 0/1, NULL=无分句',
  words_json JSON COMMENT '豆包逐字时间戳原文(跨分句拼接的JSON数组)',
  server_audio_ms INT COMMENT '豆包回传音频时长(毫秒),与audio_ms不符即WAV封装头有问题',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_message (message_id),
  INDEX idx_session_round (session_id, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每轮语音声学特征表(v1.3起语速评分读speech_ms,停顿供混合维度参考)';
