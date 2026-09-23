-- ======================================================
-- 话术训练模块 - 数据库初始化脚本
-- ======================================================

-- 1. 训练项目表（如已存在则扩展字段）
CREATE TABLE IF NOT EXISTS training_project (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL COMMENT '项目名称',
  description TEXT COMMENT '项目描述',
  status TINYINT DEFAULT 0 COMMENT '状态: 0=未发布, 1=已发布',
  project_type TINYINT DEFAULT 0 COMMENT '项目类型: 0=日常训练, 1=通关考试',
  assign_type TINYINT DEFAULT 0 COMMENT '安排类型: 0=项目安排, 1=单独安排',
  code VARCHAR(50) COMMENT '项目编号',
  start_time DATETIME COMMENT '开始时间',
  end_time DATETIME COMMENT '结束时间',
  completion_standard INT DEFAULT 1 COMMENT '完成标准(日常训练): ≥N次训练',
  pass_score INT COMMENT '达标分数(通关考试)',
  pass_strategy TINYINT COMMENT '通关策略: 1=单次达标, 2=累计达标N次, 3=连续达标N次',
  pass_strategy_n INT COMMENT '通关策略N值',
  responsible_person VARCHAR(100) COMMENT '负责人ID',
  responsible_person_name VARCHAR(100) COMMENT '负责人姓名',
  cover_image VARCHAR(500) COMMENT '封面图片URL',
  dialogue_mode TINYINT DEFAULT 0 COMMENT '对话方式: 0=语音对话, 1=文字对话',
  created_by VARCHAR(100) COMMENT '创建人ID',
  created_by_name VARCHAR(100) COMMENT '创建人姓名',
  updated_by VARCHAR(100) COMMENT '更新人ID',
  updated_by_name VARCHAR(100) COMMENT '更新人姓名',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练项目表';

-- 2. 项目流程节点表
CREATE TABLE IF NOT EXISTS training_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL COMMENT '项目ID',
  node_type TINYINT NOT NULL COMMENT '节点类型: 1=开始, 2=陪练者问学员, 3=学员问陪练者, 4=结束',
  node_name VARCHAR(100) COMMENT '节点名称',
  sort_order INT DEFAULT 0 COMMENT '排序',
  position_x FLOAT DEFAULT 0 COMMENT 'X坐标',
  position_y FLOAT DEFAULT 0 COMMENT 'Y坐标',
  parent_node_id BIGINT COMMENT '父节点ID(大节点中的子节点)',
  trainer_question TEXT COMMENT '陪练者问(陪练者节点)',
  standard_script TEXT COMMENT '标准话术(学员节点)',
  key_points JSON COMMENT '要点列表(学员节点)',
  analysis TEXT COMMENT '解析(学员节点)',
  pass_condition_type TINYINT COMMENT '流转条件类型: 1=命中任意N个, 2=命中指定',
  pass_condition_n INT COMMENT '流转条件N值',
  pass_condition_points JSON COMMENT '流转条件指定要点ID列表',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目流程节点表';

-- 3. 项目流程连线表
CREATE TABLE IF NOT EXISTS training_edge (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL COMMENT '项目ID',
  source_node_id BIGINT NOT NULL COMMENT '源节点ID',
  target_node_id BIGINT NOT NULL COMMENT '目标节点ID',
  weight INT DEFAULT 1 COMMENT '权重',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目流程连线表';

-- 4. 评分维度表
CREATE TABLE IF NOT EXISTS scoring_dimension (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL COMMENT '项目ID',
  dimension_name VARCHAR(50) NOT NULL COMMENT '维度名称',
  weight INT DEFAULT 0 COMMENT '权重(百分比)',
  enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0=禁用, 1=启用',
  rule_type TINYINT DEFAULT 1 COMMENT '规则类型: 1=精准逐字匹配, 2=要点表达, 3=混合模式, 4=分级评分, 5=公式计算, 6=扣分制',
  scoring_rule TEXT COMMENT '评分规则描述',
  evidence_type TINYINT DEFAULT 1 COMMENT '评分依据: 1=文字, 2=语音, 3=文字+语音。语音依据维度在本次训练无语音数据时不参与评分',
  metric_config VARCHAR(500) COMMENT '量化指标配置(JSON): {"metric":"speech_speed","params":{"bestMin":150,"bestMax":250,"step":10,"deductPerStep":5}}。空=该维度由AI评分；非空=得分由后端规则计算，AI只写评语',
  is_custom TINYINT DEFAULT 0 COMMENT '是否自定义维度',
  sort_order INT DEFAULT 0 COMMENT '排序',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评分维度表';

-- 5. 虚拟陪练者设置表
CREATE TABLE IF NOT EXISTS virtual_trainer (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL COMMENT '项目ID',
  trainer_name VARCHAR(20) COMMENT '陪练者名称',
  avatar_image VARCHAR(500) NULL COMMENT '陪练者形象图片URL(AI生成或上传, 空则学员端显示默认占位图)',
  language VARCHAR(20) DEFAULT 'mandarin' COMMENT '陪练者朗读语言: mandarin=普通话, cantonese=粤语',
  voice_id VARCHAR(100) COMMENT '声音角色ID',
  speed INT DEFAULT 30 COMMENT '语速(0-60)',
  input_language VARCHAR(100) DEFAULT 'mandarin' COMMENT '学员录入语言(逗号分隔)',
  forbidden_words TEXT COMMENT '违禁词(逗号分隔)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟陪练者设置表';

-- 6. 话术模板表
CREATE TABLE IF NOT EXISTS speech_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL COMMENT '模板名称',
  description TEXT COMMENT '模板说明',
  permission TINYINT DEFAULT 0 COMMENT '权限: 0=仅自己, 1=公开',
  status TINYINT DEFAULT 0 COMMENT '状态: 0=未发布, 1=已发布, 2=已撤回',
  created_by VARCHAR(100) COMMENT '创建人ID',
  created_by_name VARCHAR(100) COMMENT '创建人姓名',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话术模板表';

-- 7. 模板流程节点表
CREATE TABLE IF NOT EXISTS template_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL COMMENT '模板ID',
  node_type TINYINT NOT NULL COMMENT '节点类型: 1=开始, 2=陪练者问学员, 3=学员问陪练者, 4=结束',
  node_name VARCHAR(100) COMMENT '节点名称',
  sort_order INT DEFAULT 0 COMMENT '排序',
  position_x FLOAT DEFAULT 0 COMMENT 'X坐标',
  position_y FLOAT DEFAULT 0 COMMENT 'Y坐标',
  trainer_question TEXT COMMENT '陪练者问(陪练者节点)',
  standard_script TEXT COMMENT '标准话术(学员节点)',
  key_points JSON COMMENT '要点列表(学员节点)',
  analysis TEXT COMMENT '解析(学员节点)',
  pass_condition_type TINYINT COMMENT '流转条件类型: 1=命中任意N个, 2=命中指定',
  pass_condition_n INT COMMENT '流转条件N值',
  pass_condition_points JSON COMMENT '流转条件指定要点ID列表',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板流程节点表';

-- 8. 模板流程连线表
CREATE TABLE IF NOT EXISTS template_edge (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL COMMENT '模板ID',
  source_node_id BIGINT NOT NULL COMMENT '源节点ID',
  target_node_id BIGINT NOT NULL COMMENT '目标节点ID',
  weight INT DEFAULT 1 COMMENT '权重',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板流程连线表';

-- ======================================================
-- 学员端 AI对练-话术训练模块（2026-09-14 新增）
-- ======================================================

-- 9. 练习会话表（一次"开始"到结束的完整训练）
CREATE TABLE IF NOT EXISTS training_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL COMMENT '项目ID',
  user_id VARCHAR(100) NOT NULL COMMENT '学员ID(mock: mock_student_001)',
  user_name VARCHAR(100) COMMENT '学员姓名',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0=进行中, 1=已完成, 2=未完成退出',
  report_status TINYINT DEFAULT 0 COMMENT '报告状态: 0=未开始, 1=生成中, 2=已生成, 3=失败',
  current_node_id BIGINT COMMENT '当前学员节点ID(大节点)',
  finished_rounds INT DEFAULT 0 COMMENT '已完成轮次',
  total_rounds INT DEFAULT 0 COMMENT '总轮次(学员节点数)',
  start_time DATETIME NOT NULL COMMENT '开始时间',
  end_time DATETIME COMMENT '结束时间',
  duration_seconds INT COMMENT '练习时长(秒)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_project_user (project_id, user_id),
  INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='练习会话表';

-- 10. 会话消息表（含撤回消息）
CREATE TABLE IF NOT EXISTS training_session_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL COMMENT '会话ID',
  node_id BIGINT COMMENT '所属大节点ID',
  round_no INT COMMENT '轮次号(1起,学员消息所属轮)',
  role TINYINT NOT NULL COMMENT '角色: 1=陪练者, 2=学员',
  content TEXT NOT NULL COMMENT '消息内容',
  hit_points JSON COMMENT '命中要点内容列表(学员消息)',
  passed TINYINT COMMENT '是否通过流转条件(学员消息): 0/1',
  withdrawn TINYINT DEFAULT 0 COMMENT '是否被撤回: 0/1',
  like_count INT DEFAULT 0 COMMENT '点赞数',
  seq INT NOT NULL COMMENT '会话内序号(在会话行锁内取 MAX+1,并发下不得重复)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  audio_url VARCHAR(500) NULL COMMENT '音频URL(学员录音)',
  audio_duration_ms INT NULL COMMENT '音频时长(毫秒)',
  input_mode TINYINT DEFAULT 1 COMMENT '输入方式: 1=文字, 2=语音',
  INDEX idx_session (session_id),
  UNIQUE INDEX uk_session_seq (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';

-- 11. 训练报告表
CREATE TABLE IF NOT EXISTS training_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL COMMENT '会话ID',
  project_id BIGINT NOT NULL COMMENT '项目ID',
  user_id VARCHAR(100) NOT NULL COMMENT '学员ID',
  total_score DECIMAL(5,1) COMMENT '总分=Σ(维度分×权重)',
  rank_no INT COMMENT '项目内排名',
  exceed_percent INT COMMENT '超过同学百分比',
  score_change DECIMAL(5,1) COMMENT '较上次变化(正=上升)',
  duration_seconds INT COMMENT '练习时长(秒)',
  round_count INT COMMENT '对话轮次',
  round_suggestions JSON COMMENT '逐轮改进建议(JSON数组)',
  status TINYINT DEFAULT 1 COMMENT '状态: 1=生成中, 2=已生成, 3=失败',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_session (session_id),
  INDEX idx_project_user (project_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练报告表';

-- 12. 报告维度明细表
CREATE TABLE IF NOT EXISTS training_report_dimension (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id BIGINT NOT NULL COMMENT '报告ID',
  dimension_name VARCHAR(50) NOT NULL COMMENT '维度名称',
  weight INT COMMENT '权重(%)',
  score DECIMAL(5,1) COMMENT '维度得分',
  comment TEXT COMMENT '总评描述',
  good_points JSON COMMENT '做得好的点(数组)',
  improvements JSON COMMENT '待改进点与建议(数组)',
  sort_order INT DEFAULT 0 COMMENT '排序',
  INDEX idx_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告维度明细表';

-- 13. 消息点赞记录表（保证同一学员对同一消息点赞幂等）
CREATE TABLE IF NOT EXISTS training_message_like (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id BIGINT NOT NULL COMMENT '消息ID',
  user_id VARCHAR(100) NOT NULL COMMENT '学员ID',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_message_user (message_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息点赞记录表';

-- 14. 每轮语音声学特征表（v1.2：只留存不参与评分，一条语音学员消息一行）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每轮语音声学特征表(留存,暂不参与评分)';
