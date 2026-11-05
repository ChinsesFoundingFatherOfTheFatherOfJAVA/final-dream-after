package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端「数据分析」响应（03-接口文档 FP-20，真实统计）。
 * <p>
 * 前端原来是纯 mock（`dataAnalysisMock.ts`），本接口把同样的结构换成真实数据：
 * 总体概览 + 总分分布五段 + 维度掌握（均分与三档分布）+ 维度掌握变化（首次 → 终点）。
 * <p>
 * 口径约定（照原平台官方说明）：
 * <ul>
 *   <li>统计范围四档：first 首次 / last 最近一次 / all 全部记录 / max 个人最高；</li>
 *   <li>first、last、max 的分布单位是<b>人</b>，all 的单位是<b>百分比</b>；</li>
 *   <li>对比终点两档：last 最近一次 / max 最高；<b>最高时终点分取个人最高分</b>（不是最近一次）；</li>
 *   <li>总分分布五段：&lt;60 / 60-69 / 70-79 / 80-89 / 90-100；维度三档：&lt;70 / 70-89 / 90-100。</li>
 * </ul>
 * 无数据时不伪造：人数/次数为 0，均分为 null，分布全 0，维度项保留但 avg=null（前端显示"该维度暂无练习数据"）。
 */
@Data
public class ThemeAnalysisResp {

    private String themeId;
    /** 统计的场景单元（null = 主题下当前版本全部场景单元） */
    private String sceneUnitId;
    /** 生效的统计范围（first / last / all / max） */
    private String scope;
    /** 生效的对比终点（last / max） */
    private String compareEnd;
    /** 训练/考核筛选（1 训练、2 考核；null = 不限） */
    private Integer studyMode;

    private Overview overview;
    /**
     * 总分分布（当前 scope 口径）。
     * 单位随 scope 变化：first/last/max = 人，all = 百分比。
     */
    private Distribution distribution;
    /** 维度掌握情况（当前 scope 口径；无数据维度也在列表里，avg=null） */
    private List<Mastery> mastery;
    /** 维度掌握变化（首次 → 对比终点） */
    private List<Change> change;
    /** 当前版本该场景单元配置的维度（前端左侧详情与"暂无数据"占位都用它） */
    private List<Dimension> dimensions;

    /** 总体概览：已练习学员 / 总学员、练习总次数、场景通过率 */
    @Data
    public static class Overview {
        /** 已练习学员数（有已出分报告的学员） */
        private long practiced;
        /** 主题学员总数（participantCount 的别名，前端卡片"N / M"直接用） */
        private long totalUser;
        private long participantCount;
        /** 练习总次数（已出分报告条数；未生成报告的 attempt 不计） */
        private long totalCount;
        /** 通过次数（报告中 passed=1 的条数） */
        private long passCount;
        /** 场景通过率（百分比整数 0~100） */
        private int passRate;
    }

    /** 总分分布 */
    @Data
    public static class Distribution {
        /** person（人） / percent（百分比） */
        private String unit;
        /** 均分（无数据为 null） */
        private BigDecimal avg;
        private List<Segment> segments;
    }

    /** 五段中一段 */
    @Data
    public static class Segment {
        /** 区间文案：<60 / 60-69 / 70-79 / 80-89 / 90-100 */
        private String range;
        /** 该段数值（人 或 百分比，随 distribution.unit） */
        private long value;
        /** 与首次相比的增减（首次口径为 null，不显示） */
        private Long delta;
    }

    /** 维度掌握（一条维度） */
    @Data
    public static class Mastery {
        private String dimensionId;
        private String dimensionName;
        /** 权重（百分比） */
        private BigDecimal weight;
        /** 均分（无数据为 null） */
        private BigDecimal avg;
        /** <70 的人/百分比 */
        private long low;
        /** 70-89 的人/百分比 */
        private long mid;
        /** 90-100 的人/百分比 */
        private long high;
        /** person / percent（跟随 scope） */
        private String unit;
        /** 是否暂无练习数据（avg=null 时为 true，前端直接展示"该维度暂无练习数据"） */
        private boolean empty;
    }

    /** 维度掌握变化（首次 → 终点） */
    @Data
    public static class Change {
        private String dimensionName;
        /** 首次分（均分，无数据为 null） */
        private BigDecimal firstScore;
        /** 终点分（对比终点 last/max 的均分，无数据为 null） */
        private BigDecimal lastScore;
        /** 个人最高分（对比终点切到 max 时前端直接用，不必再请求） */
        private BigDecimal maxScore;
        /** 变化幅度 = 终点 − 首次 */
        private BigDecimal delta;
        /** 未练第 2 次（主题内没有任何学员练过两次，变化无从谈起） */
        private boolean notSecondTime;
    }

    /** 维度定义（当前版本场景单元配置） */
    @Data
    public static class Dimension {
        private String id;
        private String dimensionName;
        private BigDecimal weight;
        private String dimensionDesc;
        private Integer sortOrder;
    }
}
