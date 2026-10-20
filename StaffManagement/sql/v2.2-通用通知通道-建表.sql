-- =============================================================================
-- v2.2 通用通知通道（学员端通知）建表
-- 设计依据：D:\JACK\plan\测练中心考试管理\08-通用通知通道设计.md
-- 落点决策：不新建 MessageCenter 微服务，按 08 §2「过渡方案」落在 StaffManagement
--           notification 域，对外暴露 Dubbo NotificationPublishService + /api/sm/notices。
-- 偏差记录：见 D:\JACK\plan\测练中心考试管理\09-实施偏差与决策记录.md
-- =============================================================================

-- 1. 通知事件：一次业务事件一行，业务服务通过 Dubbo 投递
CREATE TABLE IF NOT EXISTS `sys_notice_event` (
  `id` varchar(32) NOT NULL,
  `source_service` varchar(64) NOT NULL COMMENT '来源服务：exam/course/training/...',
  `event_code` varchar(64) NOT NULL COMMENT '事件码：ARRANGE_CHANGE 等',
  `biz_type` varchar(64) NOT NULL DEFAULT '' COMMENT '业务类型：ARRANGE/COURSE/TASK',
  `biz_id` varchar(64) NOT NULL DEFAULT '' COMMENT '业务主键，如 arrangeId',
  `title` varchar(200) NOT NULL DEFAULT '',
  `content` text NOT NULL,
  `payload_json` mediumtext COMMENT '事件参数快照',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '0普通 1重要 2紧急',
  `channels` varchar(200) NOT NULL DEFAULT 'IN_APP' COMMENT '逗号分隔的渠道',
  `dedup_key` varchar(191) NOT NULL COMMENT '幂等键，唯一',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0正常 2已撤销',
  `publish_at` datetime DEFAULT NULL COMMENT '计划发布时间',
  `expire_at` datetime DEFAULT NULL,
  `created_by` varchar(64) DEFAULT '',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notice_event_dedup` (`dedup_key`),
  KEY `idx_notice_event_biz` (`biz_type`,`biz_id`),
  KEY `idx_notice_event_source` (`source_service`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用通知事件';

-- 2. 通知接收人投递记录：一次事件 × 一个用户 × 一个渠道 = 一行
--    title/content 冗余自事件：本工程持久层铁律禁止跨表 JOIN，列表查询必须单表完成
CREATE TABLE IF NOT EXISTS `sys_notice_recipient` (
  `id` varchar(32) NOT NULL,
  `event_id` varchar(32) NOT NULL,
  `user_id` varchar(32) NOT NULL,
  `channel` varchar(32) NOT NULL DEFAULT 'IN_APP' COMMENT 'IN_APP/DINGTALK/SMS/EMAIL',
  `source_service` varchar(64) NOT NULL DEFAULT '',
  `biz_type` varchar(64) NOT NULL DEFAULT '',
  `biz_id` varchar(64) NOT NULL DEFAULT '',
  `title` varchar(200) NOT NULL DEFAULT '',
  `content` text NOT NULL,
  `delivered_status` tinyint NOT NULL DEFAULT 0 COMMENT '0待送 1成功 2失败 3已撤销',
  `read_status` tinyint NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `read_at` datetime DEFAULT NULL,
  `delivered_at` datetime DEFAULT NULL,
  `failure_reason` varchar(1000) DEFAULT '',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notice_recipient` (`event_id`,`user_id`,`channel`),
  KEY `idx_notice_recipient_user` (`user_id`,`delivered_status`,`read_status`,`created_at`),
  KEY `idx_notice_recipient_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用通知接收人投递记录';

-- 3. 移动端设备推送令牌：首期只建表不投递，为个推/极光/FCM 与短信渠道留位
CREATE TABLE IF NOT EXISTS `sys_device_token` (
  `id` varchar(32) NOT NULL,
  `user_id` varchar(32) NOT NULL,
  `platform` varchar(16) NOT NULL DEFAULT '' COMMENT 'ANDROID/IOS',
  `provider` varchar(32) NOT NULL DEFAULT '' COMMENT 'GETUI/JPUSH/FCM/APNS',
  `token` varchar(255) NOT NULL,
  `app_version` varchar(32) DEFAULT '',
  `enabled` tinyint NOT NULL DEFAULT 1,
  `last_active_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_token` (`provider`,`token`),
  KEY `idx_device_user` (`user_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移动端设备推送令牌（预留）';
