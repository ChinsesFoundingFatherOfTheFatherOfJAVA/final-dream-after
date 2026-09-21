package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 材料实体（表 act_ai_material，列数=11，与 DDL 逐列对应）
 * <p>
 * 记录上传到 ./act-files/ 的材料文件与其解析产物（extract_text 全文截断、summary 供编排智能体消费）。
 * <p>
 * theme_id 可空：编排智能体第 1 阶段允许"先上传材料再定主题"，此时材料先挂 session_id，
 * create_topic 建主题后回填 theme_id 并追加进 artifact.materials。
 */
@Data
@TableName("act_ai_material")
public class ActAiMaterial {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 绑定主题（建主题前为 null） */
    private String themeId;
    /** 绑定会话（建主题前用于归集材料） */
    private String sessionId;
    /** 存储层文件 ID（act-files/ 下的文件名） */
    private String fileId;
    private String name;
    private String fileType;
    private Long fileSize;
    /** 1 学员可见 / 0 内部可见 */
    private Integer visibility;
    /** 提取文本（截断存储） */
    private String extractText;
    /** 材料摘要（喂给编排智能体） */
    private String summary;
    private LocalDateTime createdAt;
}