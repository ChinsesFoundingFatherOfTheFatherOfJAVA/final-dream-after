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

    /**
     * AD01 学员列表：汇总 + 分页。
     * <p>
     * 过滤参数全部走服务端（原来部门/状态/搜索只在前端过滤，翻页会漏人）：
     * <ul>
     *   <li>{@code keyword}：模糊匹配 userId（本工程不直读 sys_*，姓名/工号只能由前端先经 SM 解析）；</li>
     *   <li>{@code userIds}：前端用姓名/账号/工号搜索后解析出的 userId 集合，传了就按集合过滤；</li>
     *   <li>{@code source}：assigned / public；</li>
     *   <li>{@code status}：参与关系状态 active / removed；</li>
     *   <li>{@code practiceStatus}：练习状态 in_progress（有进行中 attempt）/ idle（没有）；</li>
     *   <li>{@code studyMode}：1 训练 / 2 考核，只影响卡片里的分模式均分口径。</li>
     * </ul>
     */
    PageResult<Map<String, Object>> listStudents(String themeId, String keyword, List<String> userIds,
                                                 String source, String status, String practiceStatus,
                                                 Integer studyMode, int page, int size);

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
