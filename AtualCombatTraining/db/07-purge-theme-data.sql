-- =====================================================================
-- AI实战训练场 · 清理脚本：删除"主题 + AI 设计助手"的全部业务数据
-- 版本：v1.0  日期：2026-09-20
-- 用法（本机无 mysql CLI，可用任意 MySQL 客户端 / Navicat / IDEA 数据源执行）：
--   mysql -h 10.100.26.200 -uroot -p final_dream < 07-purge-theme-data.sql
-- 用途：联调 / 演示前把库清成"从零开始"，便于走完整业务链路。
--
-- ⚠️ 这是不可逆的数据删除（只删数据、不动表结构与列）。
-- ⚠️ 范围仅限本模块的表；同库其它模块（SpeechTraining 等）的表一行不动。
-- ⚠️ 若只想清某几个主题，请改用带 WHERE id IN (...) 的版本——注意用参数/字面量，不要拼接用户输入。
--
-- 说明：全部为字面量 DELETE，不含任何字符串拼接；删除顺序按"子表在先"，便于人工核对每张表的行数。
-- =====================================================================

-- 执行前先看一眼各表现有行数（可选）
-- SELECT 'act_theme' AS t, COUNT(*) AS n FROM act_theme
-- UNION ALL SELECT 'act_theme_version', COUNT(*) FROM act_theme_version
-- UNION ALL SELECT 'act_scenario_unit', COUNT(*) FROM act_scenario_unit
-- UNION ALL SELECT 'act_evaluation_dimension', COUNT(*) FROM act_evaluation_dimension
-- UNION ALL SELECT 'act_ai_session', COUNT(*) FROM act_ai_session
-- UNION ALL SELECT 'act_ai_message', COUNT(*) FROM act_ai_message
-- UNION ALL SELECT 'act_ai_artifact', COUNT(*) FROM act_ai_artifact
-- UNION ALL SELECT 'act_ai_material', COUNT(*) FROM act_ai_material
-- UNION ALL SELECT 'act_customer_profile', COUNT(*) FROM act_customer_profile
-- UNION ALL SELECT 'act_case_script', COUNT(*) FROM act_case_script
-- UNION ALL SELECT 'act_drill_script', COUNT(*) FROM act_drill_script
-- UNION ALL SELECT 'act_ai_call_log', COUNT(*) FROM act_ai_call_log;

-- ---------------------------------------------------------------------
-- 1) AI 助手侧：消息 → 审计 → 产物 → 材料
-- ---------------------------------------------------------------------
DELETE FROM act_ai_message;
DELETE FROM act_ai_call_log;
DELETE FROM act_ai_artifact;
DELETE FROM act_ai_material;

-- ---------------------------------------------------------------------
-- 2) 确认落库产物：客户画像 / 案例剧本 / 编译产物
-- ---------------------------------------------------------------------
DELETE FROM act_customer_profile;
DELETE FROM act_case_script;
DELETE FROM act_drill_script;

-- ---------------------------------------------------------------------
-- 3) 主题内容：评估维度 → 场景单元 → 版本 → 主题
-- ---------------------------------------------------------------------
DELETE FROM act_evaluation_dimension;
DELETE FROM act_scenario_unit;
DELETE FROM act_theme_version;
DELETE FROM act_theme;

-- ---------------------------------------------------------------------
-- 4) 会话（放在最后；它与主题通过 act_ai_session.theme_id 关联）
-- ---------------------------------------------------------------------
DELETE FROM act_ai_session;

-- ---------------------------------------------------------------------
-- 执行后校验：以下应全部为 0
-- ---------------------------------------------------------------------
-- SELECT 'act_theme' AS t, COUNT(*) AS remain FROM act_theme
-- UNION ALL SELECT 'act_theme_version', COUNT(*) FROM act_theme_version
-- UNION ALL SELECT 'act_scenario_unit', COUNT(*) FROM act_scenario_unit
-- UNION ALL SELECT 'act_evaluation_dimension', COUNT(*) FROM act_evaluation_dimension
-- UNION ALL SELECT 'act_customer_profile', COUNT(*) FROM act_customer_profile
-- UNION ALL SELECT 'act_case_script', COUNT(*) FROM act_case_script
-- UNION ALL SELECT 'act_drill_script', COUNT(*) FROM act_drill_script
-- UNION ALL SELECT 'act_ai_session', COUNT(*) FROM act_ai_session
-- UNION ALL SELECT 'act_ai_message', COUNT(*) FROM act_ai_message
-- UNION ALL SELECT 'act_ai_artifact', COUNT(*) FROM act_ai_artifact
-- UNION ALL SELECT 'act_ai_material', COUNT(*) FROM act_ai_material
-- UNION ALL SELECT 'act_ai_call_log', COUNT(*) FROM act_ai_call_log;

-- 另需手工清理上传材料文件（与 act_ai_material 对应，删库不会删文件）：
--   删除 AtualCombatTraining 运行目录下 act-files/ 内的全部文件