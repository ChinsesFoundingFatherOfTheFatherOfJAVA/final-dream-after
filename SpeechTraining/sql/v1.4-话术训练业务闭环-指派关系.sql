CREATE TABLE IF NOT EXISTS project_student (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL COMMENT '训练项目ID(training_project.id)',
  user_id VARCHAR(64) NOT NULL COMMENT '学员账号(sys_user.account)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(64) COMMENT '添加人账号',
  UNIQUE INDEX uk_project_user (project_id, user_id),
  INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练项目指派学员';
