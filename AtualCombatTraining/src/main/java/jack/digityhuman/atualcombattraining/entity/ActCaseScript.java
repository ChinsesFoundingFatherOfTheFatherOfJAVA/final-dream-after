package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案例剧本实体（表 act_case_script，列数=21，与 DDL 逐列对应）
 * <p>
 * 确认落库产物：artifact.scenarios[].cases 翻译而来，挂在场景单元行下（scenario_unit_id）。
 * status 由发布初始化流程更新：draft（待编译）→ compiled / failed。
 * <p>
 * userRole 三列（2026-11-05 新增）来自 case-gen v2 的可选字段：案例生成的 prompt 会要求模型
 * 给出「学员角色」，但字段是可选的（先测缺失率），所以三列全部可空，取不到就留 null，
 * 禁止用 scriptBackground/场景旁白顶替 userRoleBackground。
 */
@Data
@TableName("act_case_script")
public class ActCaseScript {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer versionNo;
    /** 所属场景单元行 ID（当前版本） */
    private String scenarioUnitId;
    /** "A：B" 式案例标题 */
    private String scriptName;
    /** 1 简单 / 2 中等 / 3 困难 */
    private Integer difficulty;
    private String actorName;
    private String actorPosition;
    private String actorCompany;
    private String actorType;
    private String actorPersonality;
    private String actorCommunicationStyle;
    private String scriptBackground;
    private String scriptOpening;
    /**
     * 学员角色名（case-gen v2 可选字段 userRoleName；模型没给就是 null）
     * <p>
     * 为什么可空：案例 prompt 只是「尽量给」，本期先测缺失率、不强制；写空串会让下游
     * 分不清"没生成"和"生成了空"，所以取不到一律 null。
     */
    private String userRoleName;
    /** 学员岗位（userRolePosition）。映射到 AttemptResp.userRoleIdentity（身份摘要口径） */
    private String userRolePosition;
    /** 学员人物背景（userRoleBackground）。禁止用 scriptBackground 顶替 */
    private String userRoleBackground;
    /** draft / compiled / failed */
    private String status;
    /** 编译失败原因（重试仍失败时写入） */
    private String compileError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}