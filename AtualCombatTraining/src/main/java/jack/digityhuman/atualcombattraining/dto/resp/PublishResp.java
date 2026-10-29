package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 发布响应（03 文档 2.6）
 */
@Data
public class PublishResp {

    /** 主题状态：1=发布中。无案例在进入本响应前已被拒绝 */
    private Integer status;
    /** 待编译案例数（进度接口的 total） */
    private Integer totalCases;
}