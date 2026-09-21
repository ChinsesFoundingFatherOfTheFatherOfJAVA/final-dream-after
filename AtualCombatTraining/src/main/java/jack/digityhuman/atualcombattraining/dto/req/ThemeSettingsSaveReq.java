package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保存主题设置请求（FP-18，PUT /settings）
 * 五个分区（基本信息/访问控制/训练策略/通过标准）整体提交；
 * 防作弊字段嵌套在训练策略的 antiCheat 分组内，仅落库占位不做业务逻辑。
 */
@Data
public class ThemeSettingsSaveReq {

    /** 01 基本信息 */
    private Basic basic;
    /** 02 访问控制 */
    private Access access;
    /** 03 训练策略 */
    private Strategy strategy;
    /** 04 通过标准 */
    private PassStandard passStandard;

    /** 01 基本信息 */
    @Data
    public static class Basic {
        private String themeName;
        private String themeDesc;
        /** 占位负责人（前端不传，传了也仅落库不生效） */
        private String ownerUserId;
        private LocalDateTime planStartTime;
        private LocalDateTime planEndTime;
    }

    /** 02 访问控制 */
    @Data
    public static class Access {
        private Integer totalDrillCount;
        /** NULL=不限 */
        private Integer perSceneLimit;
        /** 1仅指派 2指派+完全公开 */
        private Integer joinMode;
        private Boolean guestAccessEnabled;
    }

    /** 03 训练策略 */
    @Data
    public static class Strategy {
        /** AI 响应模式：1专家 2快速 */
        private Integer aiResponseMode;
        private Boolean trainingModeEnabled;
        private Boolean assessmentModeEnabled;
        private Boolean coachReviewEnabled;
        private Boolean suggestionEnabled;
        /** 练习方式：case 案例练习 / custom 自定义练习 / random 随机练习 */
        private List<String> practiceMethods;
        /** 难度：1简单 2正常 3困难 */
        private Integer difficulty;
        private Boolean allowCustomDifficulty;
        /** 防作弊（占位，只展示不做逻辑） */
        private AntiCheat antiCheat;
    }

    /** 防作弊占位分组 */
    @Data
    public static class AntiCheat {
        private Boolean faceCheckEnabled;
        /** before=考核前检测 / ongoing=考核中持续检测 */
        private String faceCheckStage;
        /** forbid=不允许切屏 / allow=允许 N 次 */
        private String screenSwitchMode;
        private Integer screenSwitchLimit;
    }

    /** 04 通过标准 */
    @Data
    public static class PassStandard {
        /** 场景通过类型：1累计达标 2连续达标 */
        private Integer scenePassType;
        private Integer scenePassCount;
    }
}
