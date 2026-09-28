-- v1.3 口型驱动：陪练者视频资产表
-- 设计见 08 §1.3.2；S3 阶段才启用，本脚本仅入库版本管理，暂不执行。

-- 部署顺序：本表建好前不得部署启用了 speech.lipsync.enabled=true 的后端。
-- 时钟纪律：updated_at 由数据库写入，后续超时回收必须使用 CURRENT_TIMESTAMP，不得使用 JVM 时钟相减。
CREATE TABLE IF NOT EXISTS trainer_video_asset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  asset_key CHAR(32) NOT NULL COMMENT '缓存键 md5(text|voice_id|speed|avatar_image|model),五要素任一变化即重新生成',
  text TEXT COMMENT '生成用台词原文(排查冗余,不参与逻辑)',
  voice_id VARCHAR(64) COMMENT '生成用音色',
  speed INT COMMENT '生成用语速刻度',
  avatar_image VARCHAR(255) COMMENT '生成用形象图URL',
  model VARCHAR(32) COMMENT '唇形模型(wav2lip/sadtalker)',
  status TINYINT DEFAULT 0 COMMENT '0=待生成 1=生成中 2=成功 3=失败(可重试)',
  file_path VARCHAR(255) COMMENT '成功后=/video/lip/{asset_key}.mp4',
  duration_ms INT COMMENT '视频时长(毫秒,与TTS durationMs同源)',
  error_msg VARCHAR(500) COMMENT '失败原因(如 no_face_detected=形象图不适合驱动)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_asset_key (asset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪练者口型视频资产(发布时预生成,运行时只读)';
