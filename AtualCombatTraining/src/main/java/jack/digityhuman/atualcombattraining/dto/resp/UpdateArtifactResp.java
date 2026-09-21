package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * update_artifact 工具响应（03 文档 2.5.2）
 */
@Data
public class UpdateArtifactResp {

    /** 更新后的产物版本号（字符串） */
    private String versionNo;
}