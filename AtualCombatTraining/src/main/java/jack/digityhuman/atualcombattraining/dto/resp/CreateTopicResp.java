package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * create_topic 工具响应（03 文档 2.5.1）
 */
@Data
public class CreateTopicResp {

    private String themeId;
    /** true=新建草稿，false=已有主题改名/改描述 */
    private Boolean created;
}