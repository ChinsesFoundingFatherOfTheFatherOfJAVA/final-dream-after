package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.dto.resp.AiGenRespDtos;

/**
 * 在线课堂素材服务（SG-03b/03c）：跨应用只读同库 kng_courseware/file。
 */
public interface KngMaterialService {

    /** 文档类课件分页列表（已发布+上架+存在文档类源文件；抽屉四列）。 */
    AiGenRespDtos.KngMaterialItem[] page(int page, int pageSize, String keyword);

    /** 取课件第一个文档类源文件并抽取文本（SG-03c）。 */
    AiGenRespDtos.MaterialExtractResp extractByCourseware(String coursewareId);
}
