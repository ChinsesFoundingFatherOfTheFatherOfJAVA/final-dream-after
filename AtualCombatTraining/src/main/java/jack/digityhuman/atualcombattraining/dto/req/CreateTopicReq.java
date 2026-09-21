package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

/**
 * create_topic 工具请求（03 文档 2.5.1）
 */
@Data
public class CreateTopicReq {

    /** 主题名称 */
    private String topic;
    /** 主题描述（目标+人群） */
    private String description;
}