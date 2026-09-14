-- ======================================================
-- 学员端 AI对练-话术训练模块 - 新增表 DDL
-- 日期: 2026-09-14
-- 目标库: final_dream (10.100.26.200:3306)
-- 说明: 全部为 CREATE TABLE IF NOT EXISTS，可重复执行；
--       内容已同步至 src/main/resources/schema.sql（第 9~13 张表）
-- ======================================================

-- 1. 练习会话表（一次"开始"到结束的完整训练）
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

-- 2. 会话消息表（含撤回消息）
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
  seq INT NOT NULL COMMENT '会话内序号',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';

-- 3. 训练报告表
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

-- 4. 报告维度明细表
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

-- 5. 消息点赞记录表（保证同一学员对同一消息点赞幂等，接口文档 4.4 "后端幂等" 的支撑表）
CREATE TABLE IF NOT EXISTS training_message_like (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id BIGINT NOT NULL COMMENT '消息ID',
  user_id VARCHAR(100) NOT NULL COMMENT '学员ID',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_message_user (message_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息点赞记录表';
