package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案例剧本实体（表 act_case_script，列数=18，与 DDL 逐列对应）
 * <p>
 * 确认落库产物：artifact.scenarios[].cases 翻译而来，挂在场景单元行下（scenario_unit_id）。
 * status 由发布初始化流程更新：draft（待编译）→ compiled / failed。
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
    /** draft / compiled / failed */
    private String status;
    /** 编译失败原因（重试仍失败时写入） */
    private String compileError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}