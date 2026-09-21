package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

import java.util.List;

/**
 * generate_cases 工具请求（03 文档 2.5.4）
 */
@Data
public class GenerateCasesReq {

    /** 要生成的场景 ID 列表，空=全部场景 */
    private List<String> scenarioIds;
    /** 0=按场景特点自定 1简单 2中等 3困难 */
    private Integer difficulty;
}