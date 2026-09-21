package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

/**
 * AI 生成陪练者形象请求（接口文档 5.3，v1.1）
 */
@Data
public class AvatarGenReq {

    /** 形象提示词，2~200 字 */
    private String prompt;
}
