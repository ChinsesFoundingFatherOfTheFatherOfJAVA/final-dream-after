package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.dto.req.AiGenDtos;
import jack.digityhuman.speechtraining.dto.resp.AiGenRespDtos;

import java.util.List;

/**
 * AI生成话术闭环三步服务（v2.0 SG-04/05/07）：转发 Dify 三工作流 + 会话留痕 + 步骤一致性校验。
 */
public interface AIGenService {

    /** 工作流①场景生成（SG-04）。 */
    AiGenRespDtos.GenSceneResp genScene(AiGenDtos.GenSceneReq req);

    /** 工作流②大纲生成（SG-05）；sceneHash 与留痕比对不一致抛业务异常。 */
    AiGenRespDtos.GenOutlineResp genOutline(AiGenDtos.GenOutlineReq req);

    /** 工作流③话术生成（SG-07）。 */
    AiGenRespDtos.GenScriptResp genScript(AiGenDtos.GenScriptReq req);

    /** SG-08 导入编排（Java 版 resolve_round_node 语义）。 */
    AiGenRespDtos.ImportScriptResp importScript(AiGenDtos.ImportScriptReq req);

    /** 素材列表 → 工作流 materialsText（文件名标注 + 文本拼接 + 截断标记）。 */
    String joinMaterialsText(List<AiGenDtos.MaterialItem> materials);
}
