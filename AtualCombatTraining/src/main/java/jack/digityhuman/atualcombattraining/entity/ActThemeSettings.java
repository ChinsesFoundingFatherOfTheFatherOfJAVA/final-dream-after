package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题全局设置实体（表 act_theme_settings，一主题一行）
 * <p>
 * 与内容版本彻底拆分（2026-10-30 契约 D17/D28）：设置保存只更新本表，
 * 不产生内容版本；attempt 启动时冻结一份设置快照，后续修改只影响新 attempt。
 * <p>
 * 迁移说明：旧行为把五表单存在 act_theme_version 并"保存即升版"，迁移器已把
 * 当前版本的设置值回填到本表；旧版本行的同名字段仅作历史留档。
 */
@Data
@TableName("act_theme_settings")
public class ActThemeSettings {

    @TableId
    private String themeId;
    private String ownerUserId;
    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    /** 企业/主题总容量：0=不限 */
    private Integer totalDrillCount;
    /** 单场景次数上限：null/0=不限 */
    private Integer perSceneLimit;
    /** 加入方式：1 管理员指定 2 指定+完全公开 */
    private Integer joinMode;
    /** 游客访问（预留） */
    private Integer guestAccessEnabled;
    private Integer trainingModeEnabled;
    private Integer assessmentModeEnabled;
    /** AI 响应模式：1 专家 2 快速 */
    private Integer aiResponseMode;
    private Integer coachReviewEnabled;
    private Integer suggestionEnabled;
    /** 逗号分隔子集：voice,text */
    private String dialogModes;
    /** 练习方式 CSV：case,custom,random */
    private String practiceMethods;
    private Integer difficulty;
    private Integer allowCustomDifficulty;
    private Integer faceCheckBeforeEnabled;
    private Integer faceCheckOngoingEnabled;
    private Integer faceCheckIntervalSeconds;
    /** forbid/allow */
    private String screenSwitchMode;
    private Integer screenSwitchLimit;
    /** 场景通过类型：1 累计达标 2 连续达标 */
    private Integer scenePassType;
    private Integer scenePassCount;
    /** 混合模式：解锁考核所需的最少训练次数 */
    private Integer minTrainCountToUnlockExam;
    /** 最大考核次数：0=不限 */
    private Integer maxExamCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
