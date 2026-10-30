package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.StudentHistoryResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHomeResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHomeSummaryResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentPracticeRecordsResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentThemeDetailResp;
import jack.digityhuman.common.api.PageResult;

import java.util.List;

/**
 * 学员端只读查询服务（契约 04 §学生端）
 * <p>
 * 三个页面一个报告：
 * <ul>
 *   <li>home：我参加的主题列表（首页卡片：模式开关/练习方式/计划时间/我的统计摘要）；</li>
 *   <li>themeDetail：主题详情（已发布版本的场景列表 + 学员可见材料 + 我在该场景的次数/通过情况）；</li>
 *   <li>history：练习历程（attempt 流水 + 关联报告摘要，支持主题/模式过滤分页）；</li>
 *   <li>report：单份报告详情（维度明细/建议/关键对话）。</li>
 * </ul>
 * 全部只读；"不跨表 JOIN"铁律下由本服务组装（先查主表再批量查关联表）。
 */
public interface StudentQueryService {

    /** 首页：我参加的主题（含计划时间与我的统计摘要） */
    StudentHomeResp home(String userId);

    /**
     * 学员首页聚合（契约 04 §3 API-S01）：user + 四项统计 + 我的主题 + 可加入主题 + 最近 1 条练习。
     * <p>
     * 与 {@link #home(String)} 的区别：一次给全整页数据（原平台首页三栏所有区块），
     * 避免前端并发 4 个请求再各自处理 loading/error。
     */
    StudentHomeSummaryResp homeSummary(String userId);

    /** 更多学习机会（AC-03）：公开加入（join_mode=public）+ 我未参加 + 计划期内 + 已发布 */
    List<StudentHomeResp.Card> opportunities(String userId, int limit);

    /**
     * 加入公开主题（契约 04 §3 API-S03）。
     * <p>
     * 放在查询服务里是因为它只维护"我的参与关系"这一条最小写入，不涉及内容/配额，
     * 单独建一个 JoinService 徒增一层；幂等语义与失败枚举按契约原文实现。
     */
    void joinTheme(String userId, String themeId);

    /** 主题详情：场景列表 + 材料 + 我的统计 */
    StudentThemeDetailResp themeDetail(String userId, String themeId);

    /** 练习历程：分页 attempt 流水（含报告摘要） */
    PageResult<StudentHistoryResp> history(String userId, String themeId,
                                           Integer studyMode, int page, int size);

    /**
     * 练习历程页整页数据（契约 04 §API-S07 / 01 §F20）：四项统计 + 三 Tab 计数 + 主题下拉 + 分页记录。
     * <p>
     * {@code status} 取 all / passed / failed / in_progress；统计固定按"该学员全部 attempt"算，
     * 不随筛选变化（否则 Tab 上的数字会跟着筛选自己跳）。
     */
    StudentPracticeRecordsResp practiceRecords(String userId, String status, String themeId,
                                               Integer studyMode, int page, int size);
}
