package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.ThemeAnalysisResp;

/**
 * 管理端数据分析服务（03-接口文档 FP-20 真实统计；原来前端是纯 mock）。
 * <p>
 * 口径铁律（与契约一致，改动前先读 01-需求文档 FP-20 + 本节注释）：
 * <ul>
 *   <li>分数只有 act_practice_report 有（totalScore）；<b>未生成报告的 attempt 不计分</b>，
 *       但仍计入"练习总次数"以外的口径要看清楚——本接口 totalCount 只数已出分报告；</li>
 *   <li>统计范围四档 first/last/all/max 只作用在总分分布、维度掌握、维度掌握变化上；
 *       总体概览三卡是"全量历史"口径，不随 scope 变化（原平台文案"统计全部历史版本"）；</li>
 *   <li>对比终点 last/max 只影响"维度掌握变化"的终点列；<b>max 取个人最高分</b>，不是最近一次。</li>
 * </ul>
 * 只读；无数据一律 0/null/空数组，不造假。
 */
public interface ThemeAnalysisService {

    /**
     * 主题数据分析。
     *
     * @param themeId     主题 ID
     * @param sceneUnitId 场景单元 ID；null/空 = 当前版本全部场景单元合并统计
     * @param scope       first / last / all / max（缺省 last）
     * @param compareEnd  last / max（缺省 last）
     * @param studyMode   1 训练 / 2 考核 / null 不限
     */
    ThemeAnalysisResp analyse(String themeId, String sceneUnitId, String scope,
                              String compareEnd, Integer studyMode);
}
