package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 建立/查找会话响应（03 文档 2.1）
 * artifact 返回工作区版本（无工作区则 confirmed 最新），无产物时 content 为 null。
 */
@Data
public class AssistantSessionResp {

    private String sessionId;
    private String themeId;
    /** true=新建会话，false=复用既有会话 */
    private Boolean isNew;
    private Artifact artifact;

    /** 产物快照（工作区优先） */
    @Data
    public static class Artifact {
        private String artifactId;
        /** 版本号（字符串，前端 ID/版本号一律 string） */
        private String versionNo;
        /** working / confirmed */
        private String status;
        /** artifact JSON 全量（已解析为 JSON 结构，非字符串） */
        private Object content;
    }
}