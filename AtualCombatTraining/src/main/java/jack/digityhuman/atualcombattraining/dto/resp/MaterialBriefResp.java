package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 材料解析结果（AI 助手 GET /api/act/assistant/sessions/{sessionId}/materials）
 * <p>
 * 供"文件解析完成"卡片展开查看用：preview 只给提取文本前 {@link #PREVIEW_CHARS} 字，
 * 避免把整篇材料（可达数万字）一次塞进前端；truncated 标记是否被截断。
 */
@Data
public class MaterialBriefResp {

    /** 提取文本预览长度（前端文案与此保持一致） */
    public static final int PREVIEW_CHARS = 2000;

    /** act_ai_material.id */
    private String id;
    /** 存储层文件 ID（act-files/ 下的文件名） */
    private String fileId;
    private String name;
    private String fileType;
    private Long fileSize;
    /** 1 学员可见 / 0 内部可见 */
    private Integer visibility;
    /** 提取文本总字数 */
    private Integer extractLength;
    /** preview 是否为截断后的前 N 字 */
    private Boolean truncated;
    private String preview;

    /** 按材料行装配（extractText 可为 null：解析失败或无文本） */
    public static MaterialBriefResp of(String id, String fileId, String name, String fileType, Long fileSize,
                                       Integer visibility, String extractText) {
        MaterialBriefResp resp = new MaterialBriefResp();
        resp.setId(id);
        resp.setFileId(fileId);
        resp.setName(name);
        resp.setFileType(fileType);
        resp.setFileSize(fileSize);
        resp.setVisibility(visibility);
        String text = extractText == null ? "" : extractText;
        resp.setExtractLength(text.length());
        resp.setTruncated(text.length() > PREVIEW_CHARS);
        resp.setPreview(text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) : text);
        return resp;
    }
}