-- ============================================================
-- 在线课堂（管理端）课程设置扩展 —— kng_setting 重构脚本
-- 版本 v2.0 ｜ 2026-09-30 ｜ 数据库：final_dream
-- ============================================================

DROP TABLE IF EXISTS `kng_setting`;

CREATE TABLE `kng_setting` (
  `id`          TINYINT      NOT NULL DEFAULT 1     COMMENT '恒为1',
  `score_conf`  JSON         NULL                   COMMENT '学分策略（FP-02b）',
  `cheat_conf`  JSON         NULL                   COMMENT '防挂机+特殊设置（FP-02c/02d）',
  `play_conf`   JSON         NULL                   COMMENT '课程播放（FP-02e）',
  `face_conf`   JSON         NULL                   COMMENT '人脸识别（FP-02f）',
  `updated_by`  VARCHAR(32)  NULL,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在线课堂-课程设置(单行,JSON配置)';

INSERT INTO `kng_setting` (`id`, `score_conf`, `cheat_conf`, `play_conf`, `face_conf`) VALUES (
  1,
  '{"videoAudio":{"standardMinutes":10,"score":1.0,"applyToAll":false},
    "document":{"pagesPerScore":1,"score":0.1,"standardMinutes":1,"maxScore":6.0,"applyToAll":false},
    "microcourse":{"standardMinutes":10,"score":1.0,"maxScore":10.0,"applyToAll":false},
    "scorm":{"standardMinutes":10,"score":1.0,"applyToAll":false},
    "html":{"standardMinutes":10,"score":1.0,"applyToAll":false},
    "rewardType":0}',
  '{"antiIdleEnabled":true,"mode":1,"checkMinutes":3,"confirmSeconds":120,
    "promptText":"已触发防挂机验证，请点击\"继续学习\"，否则学习将被停止",
    "multiCourseEnabled":true,"forbidDragEnabled":true,"audioBackgroundPlay":false}',
  '{"speedEnabled":true,"speedScope":2,"speedHoursCalc":true}',
  '{"enabled":false}'
);
