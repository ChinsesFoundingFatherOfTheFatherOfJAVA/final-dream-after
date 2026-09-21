package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

import java.util.List;

/**
 * generate_theme 工具请求（03 文档 2.5.3）
 */
@Data
public class GenerateThemeReq {

    /** 核心训练目标一句话 */
    private String goal;
    /** 训练人群 */
    private List<String> audiences;
    /** 材料要点摘要（无材料传空串） */
    private String materialSummary;
    /** true=在现有内容基础上调整（更新模式） */
    private Boolean keepExisting;
}