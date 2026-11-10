package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学员练习 attempt 实体（表 act_practice_attempt，列与 05-建表SQL §7 逐列对应）
 * <p>
 * 一次正式训练/考核 = 一行。状态机：preparing → ready → in_progress → report_generating → completed，
 * failed/cancelled 为终态。JSON 列用 String 原样承载（项目先例，见 ActAiArtifact.content）。
 * <p>
 * 关键不变式：
 * <ul>
 *   <li>active_key 唯一索引 = 同一用户同主题同时最多一场活动 attempt；所有终态把它置 null；</li>
 *   <li>attempt 创建时冻结内容/设置/场景/档案/材料快照，运行中不再读实时表（D07/D08）；</li>
 *   <li>次数在正式开始（in_progress）时扣减，准备失败不扣（D06）。</li>
 * </ul>
 */
@Data
@TableName("act_practice_attempt")
public class ActPracticeAttempt {

    /** attempt ID（VARCHAR(40)，前端按字符串处理） */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private String scenarioId;
    private String userId;
    /** 1 训练 2 考核 */
    private Integer studyMode;
    /** case/random/custom */
    private String practiceType;
    /** preparing/ready/in_progress/paused/report_generating/completed/failed/cancelled（paused 仅训练模式） */
    private String status;
    /** 活动 attempt 唯一键 themeId:userId；结束后置 null */
    private String activeKey;
    /** 冻结的内容版本号 */
    private Integer contentVersionNo;
    /** 全局设置快照（JSON 字符串） */
    private String settingsSnapshotJson;
    /** 场景快照（场景单元 + 维度，JSON 字符串） */
    private String scenarioSnapshotJson;
    /** 练习脚本（drill-script-v1 JSON 字符串；案例练习来自发布快照，随机/自定义来自 AIGentService） */
    private String scriptSnapshotJson;
    /** 产品档案快照（JSON 数组字符串） */
    private String productProfilesSnapshotJson;
    /** 学习材料快照（JSON 数组字符串） */
    private String materialsSnapshotJson;
    /** 自定义练习配置（JSON 字符串；随机/案例为 null） */
    private String customConfigJson;
    /** 难度 1~3（案例练习取脚本难度） */
    private Integer difficulty;
    /** AIGentService 侧会话 ID（= attemptId，显式存列便于审计） */
    private String agentSessionId;
    /** 准备阶段：analyze_scene/build_script/build_actor/ready */
    private String preparationStage;
    /** 准备进度 0~100 */
    private Integer preparationPercent;
    /** 次数是否已扣减 */
    private Integer quotaDeducted;
    /** 当前对话轮次 */
    private Integer currentRound;
    private Integer maxRound;
    /** 目标达成度 0~100 */
    private Integer targetProgress;
    /** 最大时长（秒） */
    private Integer maxDurationSeconds;
    /** 暂停累计毫秒 */
    private Long pausedDurationMs;
    /** 当前写者 token（WS 单写者） */
    private String clientWriterId;
    private LocalDateTime createdAt;
    private LocalDateTime preparedAt;
    private LocalDateTime startedAt;
    /** started_at + max_duration - paused_duration */
    private LocalDateTime deadlineAt;
    private LocalDateTime pausedAt;
    private LocalDateTime endedAt;
    /** timeout/manual_exit/target_reached/round_limit/screen_violation/plan_ended/system_failed */
    private String endReason;
    /** 是否因时长到点结束（独立事实，D04） */
    private Integer timedOut;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime updatedAt;
}
