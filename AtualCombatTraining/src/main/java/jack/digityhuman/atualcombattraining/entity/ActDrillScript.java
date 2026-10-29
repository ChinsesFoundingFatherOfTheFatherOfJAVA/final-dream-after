package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编译产物实体（表 act_drill_script，列数=7，与 DDL 逐列对应）
 * <p>
 * 发布初始化产物：每个已确认案例原样落一行，不在发布时再组装一份。
 * 线上学员端用的是案例生成时的正文，本地生成只有一份 JSON，所以这里只存这一份。
 * compiled_payload 为 JSON 列，String 原样承载（无 JacksonTypeHandler，避免二次转义）。
 */
@Data
@TableName("act_drill_script")
public class ActDrillScript {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer versionNo;
    /** 来源案例行 ID */
    private String caseScriptId;
    private String scenarioUnitId;
    /** 自包含剧本 JSON */
    private String compiledPayload;
    private LocalDateTime compiledAt;
}