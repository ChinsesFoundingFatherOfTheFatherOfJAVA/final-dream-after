package jack.digityhuman.atualcombattraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主题设置读取响应（FP-13~17，当前版本五表单 + 内容概览）
 */
@Data
public class ThemeSettingsResp {

    /** 当前版本号（字符串序列化防前端精度丢失） */
    private String versionNo;
    /** 01 基本信息 */
    private Basic basic;
    /** 02 访问控制 */
    private Access access;
    /** 03 训练策略 */
    private Strategy strategy;
    /** 04 通过标准 */
    private PassStandard passStandard;
    /** 05 内容设计-当前内容概览 */
    private ContentOverview contentOverview;

    /** 01 基本信息 */
    @Data
    public static class Basic {
        private String themeName;
        private String themeDesc;
        /** 占位负责人，固定返回 admin（前端展示为"管理员"标签） */
        private String ownerUserId;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime planStartTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime planEndTime;
    }

    /** 02 访问控制 */
    @Data
    public static class Access {
        private Integer totalDrillCount;
        /** 剩余企业容量（企业级占位常量，后端固定返回，不落库不扣减） */
        private Integer remainingEnterpriseCapacity;
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
        /** 练习方式：case / custom / random */
        private List<String> practiceMethods;
        /** 难度：1简单 2正常 3困难 */
        private Integer difficulty;
        private Boolean allowCustomDifficulty;
        /** 防作弊占位分组 */
        private AntiCheat antiCheat;
    }

    /** 防作弊占位分组 */
    @Data
    public static class AntiCheat {
        private Boolean faceCheckEnabled;
        /** before / ongoing */
        private String faceCheckStage;
        /** forbid / allow */
        private String screenSwitchMode;
        private Integer screenSwitchLimit;
    }

    /** 04 通过标准 */
    @Data
    public static class PassStandard {
        /** 1累计达标 2连续达标 */
        private Integer scenePassType;
        private Integer scenePassCount;
    }

    /** 05 内容概览（后端计算/读占位字段） */
    @Data
    public static class ContentOverview {
        /** 场景单元数（当前版本实时 count） */
        private Integer scenarioUnitCount;
        /** 客户画像数（读主题表占位字段） */
        private Integer customerProfileCount;
        /** 冰山模型要素数（当前版本所有单元 icebergElements 合并去重计数） */
        private Integer icebergElementCount;
        /** 评估维度数（当前版本所有单元维度名合并去重计数） */
        private Integer evaluationDimensionCount;
    }
}
