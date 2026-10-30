package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学员端主题详情响应（契约 04 §API-S04）
 * <p>
 * 结构按契约分组：theme（页头）/ studyPolicy（学习策略）/ scenarios（全部场景单元）/
 * evaluationCriteria（评估标准）/ materials（学习资料）/ productProfiles（产品档案）/
 * recentRecords（我的训练记录）。
 * <p>
 * 兼容说明：早期前端直接读顶层扁平字段（themeId/name/trainingModeEnabled/...），
 * 保留这些字段不改，避免已有调用方被动；新页面一律用分组结构。
 */
@Data
public class StudentThemeDetailResp {

    // ---------- 分组结构（契约 04 §API-S04） ----------
    private Theme theme;
    private StudyPolicy studyPolicy;
    private List<Criteria> evaluationCriteria;
    private List<ProductProfile> productProfiles;
    /** 我的最近训练记录（契约 D09：详情抽屉 10 条） */
    private List<Record> recentRecords;

    // ---------- 扁平兼容字段（保持向后兼容） ----------
    private String themeId;
    private String name;
    private String description;
    private Integer publishedVersionNo;
    private Integer trainingModeEnabled;
    private Integer assessmentModeEnabled;
    private String practiceMethods;
    private Integer difficulty;
    private Integer allowCustomDifficulty;
    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    private List<Scenario> scenarios;
    private List<Material> materials;

    /** 页头：模式、难度、名称、描述、场景数、已通过数、进度 */
    @Data
    public static class Theme {
        private String id;
        private String name;
        private String description;
        /** 1 日常训练 / 2 通关考核 / 3 训考一体 */
        private Integer mode;
        private String modeText;
        /** 1 简单 / 2 中等 / 3 困难 */
        private Integer difficulty;
        private String difficultyText;
        private long scenarioCount;
        private long passedScenarioCount;
        /** 0~100 */
        private int progress;
        /** 混合模式考核是否已解锁 */
        private boolean examUnlocked;
        private LocalDateTime planStartTime;
        private LocalDateTime planEndTime;
        private Integer publishedVersionNo;
        /** 是否还能开始新 attempt（计划期外/主题暂停时 false，页面只读） */
        private boolean startable;
        /** 不能开始的原因（startable=true 时为 null） */
        private String stopReason;
    }

    /** 学习策略（来自 act_theme_settings，不进内容版本） */
    @Data
    public static class StudyPolicy {
        private boolean trainingEnabled;
        private boolean examEnabled;
        private List<String> dialogModes;
        private List<String> practiceMethods;
        private boolean allowCustomDifficulty;
        private Integer minTrainCountToUnlockExam;
        private Integer maxExamCount;
    }

    /** 场景单元（含我的统计与可用动作） */
    @Data
    public static class Scenario {
        private String scenarioId;
        private String name;
        private Integer sortOrder;
        /** 适用人群标签（后端切分后的结果，契约要求前端取前 2 个） */
        private List<String> audience;
        private String businessGoal;
        private Integer timeLimitMinutes;
        private Integer passScore;
        /** 评估维度摘要（AC-04：名称 + 权重，供详情页展示评分口径） */
        private List<DimensionBrief> dimensions;
        /** 训练 + 考核的正式次数合计（场景卡展示"N 次"） */
        private long attemptCount;
        /** 我在该场景训练正式次数 */
        private long myTrainingCount;
        /** 我在该场景考核正式次数 */
        private long myExamCount;
        /** 是否已通过（训练口径：passed_count > 0） */
        private boolean myTrainingPassed;
        private boolean myExamPassed;
        /** 训练最高分 */
        private BigDecimal myHighestScore;
        private BigDecimal myHighestExamScore;
        /** not_started / in_progress / passed */
        private String status;
        private String statusText;
        /** start / continue / retry */
        private String nextAction;
        private String nextActionText;
        private Action trainingAction;
        private Action examAction;
    }

    /** 动作可用性（不可用时带中文原因，前端直接展示） */
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

    @Data
    public static class DimensionBrief {
        private String name;
        private BigDecimal weight;
    }

    /** 评估标准（契约 F07：通过分 + 维度口径） */
    @Data
    public static class Criteria {
        private Integer passScore;
        private List<DimensionBrief> dimensions;
    }

    @Data
    public static class Material {
        private String materialId;
        private String name;
        private String fileType;
        private String renderMode;
        private String sourceType;
        private String sourceId;
        private String previewFileId;
        /** 展示用类型文案（PDF / 在线课堂 / ...） */
        private String typeText;
    }

    /** 产品档案（契约 F08：名称、分类、简介；点击弹窗看自定义字段 + Markdown） */
    @Data
    public static class ProductProfile {
        private String profileKey;
        private String name;
        private String category;
        private String description;
        private String detailMarkdown;
        private List<CustomField> customFields;
    }

    @Data
    public static class CustomField {
        private String key;
        private String value;
        private Integer sortOrder;
    }

    /** 我的训练记录行 */
    @Data
    public static class Record {
        private String attemptId;
        private String scenarioId;
        private String scenarioName;
        private Integer studyMode;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private BigDecimal totalScore;
        private Integer passed;
    }
}
