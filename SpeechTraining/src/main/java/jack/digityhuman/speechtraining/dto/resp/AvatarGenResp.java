package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

/**
 * AI 生成陪练者形象响应（接口文档 5.3，v1.1）
 */
@Data
public class AvatarGenResp {

    /** 转存后的本地图片 URL，如 /avatar/{uuid}.png */
    private String imageUrl;
}
