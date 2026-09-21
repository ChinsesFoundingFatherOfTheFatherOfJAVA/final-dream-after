package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

/**
 * 建立/查找会话请求（03 文档 2.1）
 */
@Data
public class AssistantSessionReq {

    /** 主题 ID，可空=新建主题对话（由 create_topic 回填） */
    private String themeId;
}