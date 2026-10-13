package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.AiGenDtos;
import jack.digityhuman.examservice.dto.resp.AiGenRespDtos;
import org.springframework.web.multipart.MultipartFile;

/** AI 建题（FP-11）：素材 → Dify AI出题工作流 → 题目草稿；以及配套的文件解析。 */
public interface AiGenService {

    /** 调 Dify 生成题目草稿（03 §7.1）。 */
    AiGenRespDtos.AiGenResult generate(AiGenDtos.AiGenReq req);

    /** 上传文件解析为纯文本（03 §7.2，本地 Tika，不走 LLM）。 */
    String parseDocument(MultipartFile file);
}