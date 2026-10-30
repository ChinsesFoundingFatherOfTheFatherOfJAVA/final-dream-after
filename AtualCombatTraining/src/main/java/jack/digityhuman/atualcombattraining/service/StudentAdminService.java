package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.common.api.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 主题学员管理服务（契约 04 §7，API-AD01~AD06）
 * <p>
 * 参与关系（act_theme_participant）+ 场景统计 + 报告的组装视图；
 * 用户姓名/部门等 sys_* 信息本工程不直读（AGENTS 铁律 1），响应里 name 回落 userId、
 * departmentName 置 null，由前端按已有用户服务映射（偏差记录见提交说明）。
 */
public interface StudentAdminService {

    /** AD01 学员列表：汇总 + 分页（keyword 匹配 userId 前缀；source/status/studyMode 过滤） */
    PageResult<Map<String, Object>> listStudents(String themeId, String keyword, String source,
                                                 String status, Integer studyMode,
                                                 int page, int size);

    /** AD02 添加学员：唯一键防重复；已移除用户恢复 active。返回 successCount/skippedCount */
    Map<String, Integer> addStudents(String themeId, List<String> userIds, String source);

    /** AD03 调整考核次数：绝对值设置 + 审计（delta 记账）。仅影响之后的考核 attempt */
    void adjustExamQuota(String themeId, String userId, int extraAttemptCount, String reason);

    /** AD04 移出主题：软删除；存在活动 attempt 时拒绝（ACTIVE_ATTEMPT_EXISTS） */
    void removeStudent(String themeId, String userId);

    /** AD05 学员详情：汇总分析 + 场景分布 + 最近活跃 */
    Map<String, Object> studentDetail(String themeId, String userId);

    /** AD06 学员对练记录：attempt 分页（含报告摘要） */
    PageResult<Map<String, Object>> studentRecords(String themeId, String userId,
                                                   Integer studyMode, int page, int size);
}
