package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 产物实体（表 act_ai_artifact，列数=9，与 DDL 逐列对应）
 * <p>
 * 一个主题一条 artifact 主记录（id 稳定），版本自增：(id, version_no) 为物理主键，
 * MyBatis-Plus 不支持复合主键，故 @TableId 标在 id 上且用 IdType.INPUT——插入新版本行时
 * 必须显式设置 id（沿用主题既有 artifact id），不能依赖自动生成。
 * <p>
 * 语义：status=working 的行为"工作区"（对话中原地更新，version_no 不变）；
 * 确认后该行 status=confirmed 并生成下一版工作区行。
 * content 为 JSON 列，String 原样承载（无 JacksonTypeHandler，避免二次转义）。
 */
@Data
@TableName("act_ai_artifact")
public class ActAiArtifact {

    /** artifact ID（每主题稳定，手动赋值：IdType.INPUT） */
    @TableId(type = IdType.INPUT)
    private String id;
    private String themeId;
    /** 版本号（确认周期递增） */
    private Integer versionNo;
    /** 完整 artifact JSON（8 顶级字段） */
    private String content;
    /** working / confirmed */
    private String status;
    /** content 的内容摘要（SHA-256 hex，确认幂等比对用） */
    private String contentHash;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}