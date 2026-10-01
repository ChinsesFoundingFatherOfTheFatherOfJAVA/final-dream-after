package jack.digityhuman.speechtraining.dify;

import jack.digityhuman.speechtraining.dto.resp.AiScriptGenResp;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dify 话术生成智能体调用：根据场景和轮次生成对话流程。
 */
public interface DifyScriptGenService {

    /**
     * 调用Dify话术生成智能体，根据场景和轮次生成对话流程。
     *
     * @param description  场景描述
     * @param rounds       对话轮次
     * @param firstSpeaker 先说话的一方：trainer=陪练者先说（默认），student=学员先说
     */
    AiScriptGenResp generateScript(String description, Integer rounds, String firstSpeaker);

    /**
     * 上传文件生成话术：提取文件文本内容（PDF/docx），拼接到描述中，再调用Dify生成。
     *
     * @param firstSpeaker 先说话的一方：trainer=陪练者先说（默认），student=学员先说
     */
    AiScriptGenResp generateScriptWithFile(String description, MultipartFile file, String firstSpeaker);
}
