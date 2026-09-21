package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 文件上传响应（03 文档 2.3）
 */
@Data
public class FileUploadResp {

    /** 存储层文件 ID（随 file 块 uri 入会话） */
    private String fileId;
    private String name;
    private String fileType;
    private Long fileSize;
}