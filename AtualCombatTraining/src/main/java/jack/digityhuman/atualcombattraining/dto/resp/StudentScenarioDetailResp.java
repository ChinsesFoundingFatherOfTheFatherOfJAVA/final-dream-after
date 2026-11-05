package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学员端「场景准备详情」响应（契约 04 §API-S05）。
 * <p>
 * 这是 F09 场景准备弹窗的唯一数据来源：弹窗里要展示的九类信息
 * （场景名/状态/适用人群、练习次数/最高分/限时/通过分、实战案例与角色背景、
 * 涉及产品档案、业务目标/影响/失败代价/核心冲突、常见失败模式/困难挑战/禁止行为、
 * 评估维度与权重、可用练习方式）必须由后端一次给全。
 * <p>
 * 设计取舍：字段按"原平台弹窗实测分区"组织而不是按数据库列名，前端拿到就能直接渲染；
 * 任一数据源缺失时给空数组/空值，<b>不造占位假数据</b>（无案例就是 {@code cases=[]}，
 * 前端据此显示空态）。
 */
@Data
public class StudentScenarioDetailResp {

    private String themeId;
    private String themeName;
    private String scenarioId;
    private Integer sortOrder;
    /** 场景名（act_scenario_unit.unit_name，"A：B" 格式） */
    private String name;

    /** passed / in_progress / not_started（与主题详情场景卡同口径） */
    private String status;
    private String statusText;

    /** 适用人群标签（按 / 、 ， 切分后的完整列表，前端按需取前两个） */
    private List<String> audience;

    /** 限时（分钟，来自场景单元） */
    private Integer timeLimitMinutes;
    /** 限时（秒，与 preflight.maxDurationSeconds 同口径，弹窗头部直接用） */
    private Integer maxDurationSeconds;
    /** 通过分（场景单元口径） */
    private Integer passScore;

    /** 练习次数合计（训练 + 考核的正式次数） */
    private long attemptCount;
    private long myTrainingCount;
    private long myExamCount;
    /** 训练最高分（无报告时为 null） */
    private BigDecimal myHighestScore;
    /** 考核最高分（无报告时为 null） */
    private BigDecimal myHighestExamScore;

    /** 主题难度（1 简单 / 2 中等 / 3 困难） */
    private Integer difficulty;
    private String difficultyText;

    /** 是否能开始新 attempt（计划期外/主题暂停时 false） */
    private boolean startable;
    /** 不能开始的原因（startable=true 时为 null） */
    private String stopReason;
    /** 训练动作可用性（不可用带中文原因） */
    private Action trainingAction;
    /** 考核动作可用性（不可用带中文原因） */
    private Action examAction;

    /** 已开启的练习方式子集（case / random / custom，按设置 CSV 过滤后） */
    private List<String> practiceMethods;
    /** 三种练习方式全部不可用的原因（可用时为 null；前端直接展示） */
    private String unavailableReason;

    // ---------- 场景描述（弹窗中段分区） ----------
    private String businessGoal;
    private String businessImpact;
    private String failureCost;
    private String coreConflict;
    /** 行为目标（AI 确认落库时带入） */
    private List<String> behaviorGoals;
    /** 关联冰山模型要素 */
    private List<String> icebergElements;
    /** 常见失败模式 */
    private List<String> commonFailureModes;
    /** 困难挑战 */
    private List<String> difficulties;
    /** 禁止行为 */
    private List<String> forbiddenBehaviors;

    /** 评估维度与权重（act_evaluation_dimension，按 sort_order） */
    private List<Dimension> dimensions;
    /** 实战案例（来自发布快照 act_drill_script；老数据回退 act_case_script） */
    private List<CaseSummary> cases;
    /** 涉及产品档案（发布快照行；无关联时为空数组） */
    private List<ProductProfileBrief> productProfiles;

    /** 动作可用性（与主题详情场景卡结构一致，前端复用同一渲染分支） */
    @Data
    public static class Action {
        private boolean enabled;
        private String reason;

        public static Action of(boolean enabled, String reason) {
            Action action = new Action();
            action.setEnabled(enabled);
            action.setReason(enabled ? null : reason);
            return action;
        }
    }

    /** 评估维度（含三档评分标准，弹窗"场景评估维度和权重"直接展示） */
    @Data
    public static class Dimension {
        private String id;
        private String name;
        private BigDecimal weight;
        private String description;
        private String excellentStandard;
        private String goodStandard;
        private String improveStandard;
        private Integer sortOrder;
    }

    /**
     * 实战案例卡：角色名/身份/性格 + 案例标题 + 角色背景 + 开场白原话。
     * <p>
     * 字段优先取发布快照 compiled_payload（生成时的案例正文原话），
     * 快照字段缺失时回退 act_case_script 落库列（老数据可能只有列、没有 payload）。
     */
    @Data
    public static class CaseSummary {
        /** 案例行 ID（act_case_script.id，前端点"开始练习"时用于定位） */
        private String caseId;
        /** 发布快照行 ID（act_drill_script.id，无快照时为 null） */
        private String drillScriptId;
        private String scriptName;
        /** 1 简单 / 2 中等 / 3 困难 */
        private Integer difficulty;
        /** 难度星级（1~3，弹窗"★"直接用） */
        private Integer difficultyStars;
        private String actorName;
        /** 身份/岗位（原平台卡片的"企业中层管理者"一类） */
        private String actorPosition;
        private String actorCompany;
        private String actorType;
        private String actorPersonality;
        private String actorCommunicationStyle;
        private String scriptBackground;
        /** 开场白原话（弹窗必须展示，不允许截断重写） */
        private String scriptOpening;
    }

    /** 涉及产品档案（弹窗里以标签展示名称；点击可打开档案详情弹窗） */
    @Data
    public static class ProductProfileBrief {
        private String profileId;
        private String profileKey;
        private String name;
        private String category;
        private String description;
    }
}
