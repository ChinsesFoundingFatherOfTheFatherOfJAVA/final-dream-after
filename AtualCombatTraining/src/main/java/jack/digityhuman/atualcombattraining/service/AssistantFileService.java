package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import org.springframework.web.multipart.MultipartFile;

/**
 * 材料文件服务（FP-42）
 * <p>
 * 上传件落到配置目录（默认 ./act-files/），仅允许白名单类型：
 * ppt / pptx / doc / docx / pdf / txt / md（前后端双重校验）。
 * 存储文件名由服务端生成（不使用客户端文件名做路径），避免路径穿越。
 */
public interface AssistantFileService {

    /**
     * 保存上传文件。
     *
     * @param file multipart 文件（表单字段 file）
     * @return {fileId, name, fileType, fileSize}
     */
    FileUploadResp store(MultipartFile file);
}