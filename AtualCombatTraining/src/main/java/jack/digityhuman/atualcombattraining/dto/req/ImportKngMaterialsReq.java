package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

import java.util.List;

/**
 * 导入在线课堂培训材料请求（03-接口文档 §3 API-04）
 * <p>
 * {@code sessionId}：当前助手会话（建主题前材料挂会话，建主题后由 linkSessionMaterials 归集）；
 * {@code coursewareIds}：在线课堂课件 id，1~5 个，去重后按序导入。
 */
@Data
public class ImportKngMaterialsReq {

    private String sessionId;
    private List<String> coursewareIds;
}
