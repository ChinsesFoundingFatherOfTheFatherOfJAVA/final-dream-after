package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题版本实体（表 act_theme_version，列数=32，与 DDL 逐列对应）
 * <p>
 * 每个版本一条快照记录，五个表单（基本信息/访问控制/训练策略/防作弊/通过标准）的全部可编辑字段都在这里。
 * 主题设置读=读 current_version_no 对应行；写=插入新行（version_no = max+1）。
 */
@Data
@TableName("act_theme_version")
public class ActThemeVersion {

    /** 雪花 ID（VARCHAR(32)） */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    /** 版本号（uk: theme_id + version_no） */
    private Integer versionNo;
    // ---- 01 基本信息 ----
    private String themeName;
    private String themeDesc;
    /** 占位负责人，当前写死 admin */
    private String ownerUserId;
    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    // ---- 02 访问控制 ----
    private Integer totalDrillCount;
    /** NULL=不限 */
    private Integer perSceneLimit;
    /** 加入方式：1仅指派 2指派+完全公开 */
    private Integer joinMode;
    /** 占位：游客访问开关（只展示不做逻辑） */
    private Integer guestAccessEnabled;
    // ---- 03 训练策略 ----
    /** AI 响应模式：1专家 2快速 */
    private Integer aiResponseMode;
    private Integer trainingModeEnabled;
    private Integer assessmentModeEnabled;
    /** 教练点评（训练模式开启时强制为 1） */
    private Integer coachReviewEnabled;
    private Integer suggestionEnabled;
    /** 练习方式 CSV：case,custom,random（接口层与数组互转） */
    private String practiceMethods;
    private Integer difficulty;
    private Integer allowCustomDifficulty;
    // ---- 防作弊（占位，只展示不做逻辑） ----
    private Integer faceCheckEnabled;
    /** before/ongoing */
    private String faceCheckStage;
    /** forbid/allow */
    private String screenSwitchMode;
    private Integer screenSwitchLimit;
    // ---- 04 通过标准 ----
    /** 场景通过类型：1累计达标 2连续达标 */
    private Integer scenePassType;
    private Integer scenePassCount;
    // ---- 版本元数据 ----
    private String creator;
    /** 占位统计 */
    private Integer usedUserCount;
    /** 占位统计 */
    private Integer completedCount;
    /** 占位统计 */
    private Integer inProgressCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
