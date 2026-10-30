package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * attempt 响应（学员端创建/查询/开始/结束共用）
 * <p>
 * 快照 JSON 不原样下发：脚本给前端的是运行需要的裁剪视图（下一里程碑在运行时接口里组装），
 * 本响应只描述状态与进度，避免把设置/材料快照整包塞给页面。
 * <p>
 * 运行态「背景介绍」侧栏（契约 01 §F11）需要的场景名/角色/业务背景/沟通指南/评估标准，
 * 从 attempt 冻结的 scenarioSnapshotJson 与 scriptSnapshotJson 裁剪而来（契约 D07：
 * 回放当时的内容，不读当前版本），以只读摘要字段挂在这里，不额外开接口。
 */
@Data
public class AttemptResp {

    private String id;
    private String themeId;
    private String scenarioId;
    private Integer studyMode;
    private String practiceType;
    private String status;
    private Integer contentVersionNo;
    private String preparationStage;
    private Integer preparationPercent;
    private Integer currentRound;
    private Integer maxRound;
    private Integer targetProgress;
    private Integer maxDurationSeconds;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime endedAt;
    private String endReason;
    private Integer timedOut;
    private String errorCode;
    private String errorMessage;

    // ==== 运行态只读上下文（来自冻结快照） ====
    /** 主题名 */
    private String themeName;
    /** 场景名（准备页标题、侧栏大标题） */
    private String scenarioName;
    /** 场景旁白（运行态左上「场景」卡正文） */
    private String sceneNarration;
    /** AI 开场白（进入运行态先呈现的第一句台词） */
    private String openingLine;
    /** 业务背景段落（侧栏「业务背景」） */
    private String businessBackground;
    /** 对话目标（侧栏「对话目标」） */
    private String businessGoal;
    /** AI 演员名 */
    private String actorName;
    /** AI 演员身份与性格摘要（角色卡副标题） */
    private String actorDesc;
    /** 我的角色名 */
    private String userRoleName;
    /** 我的角色身份摘要 */
    private String userRoleDesc;
    /** 沟通指南（侧栏要点列表；优先脚本 goals.behaviorGuidance，缺失退回场景 behaviorGoals） */
    private List<String> behaviorGuidance;
    /** 通过分数 */
    private Integer passScore;
    /** 评估维度（侧栏「评估标准」：名称 + 权重） */
    private List<Dimension> dimensions;

    /** 评估维度摘要 */
    @Data
    public static class Dimension {
        private String name;
        private BigDecimal weight;
    }
}
