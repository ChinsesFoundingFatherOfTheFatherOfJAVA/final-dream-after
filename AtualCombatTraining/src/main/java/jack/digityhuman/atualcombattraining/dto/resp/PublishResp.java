package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 发布响应（03 文档 2.6）
 */
@Data
public class PublishResp {

    /** 主题状态：1=发布中（初始化中）；无案例时直接 3=已发布 */
    private Integer status;
    /** 待编译案例数（进度接口的 total） */
    private Integer totalCases;
}