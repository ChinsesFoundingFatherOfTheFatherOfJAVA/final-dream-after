package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import jack.digityhuman.atualcombattraining.dto.resp.MaterialBriefResp;

import java.util.List;

/**
 * 材料解析服务（FP-42）
 * <p>
 * 链路：上传成功（带 sessionId）即异步解析 → 本地 Tika 提取文本 → 规则摘要（&gt;3 万字首尾截断）
 * → 落 act_ai_material（建主题前先挂 session_id）→ 有主题则追加进工作区 artifact.materials
 * → 推 `biz-common-parse-file`（status 0 解析中 → 1 完成）。
 * <p>
 * 关键设计（2026-09-18 确认）：编排智能体第 1 阶段允许"先上传材料再定主题"，
 * 因此解析产物必须在无主题时就可用（材料摘要进入 material_summary），故材料先挂会话，
 * create_topic 建主题后由 {@link #linkSessionMaterials} 回填 theme_id 并追加进 artifact。
 */
public interface AssistantMaterialService {

    /**
     * 受理解析（立即返回，后台执行）。
     *
     * @param sessionId 会话 ID（上传时前带上）
     * @param file 上传结果（fileId/name/fileType/fileSize）
     */
    void acceptParse(String sessionId, FileUploadResp file);

    /**
     * 主题创建后归集：把该会话在建主题前上传的材料回填 theme_id，并追加进工作区 artifact.materials。
     *
     * @param sessionId 会话 ID
     * @param themeId 新建主题 ID
     */
    void linkSessionMaterials(String sessionId, String themeId);

    /**
     * 会话材料解析结果（"文件解析完成"卡片展开时按需拉取）。
     * <p>
     * 只返回该会话的材料，按上传时间升序；提取文本按 {@code MaterialBriefResp.PREVIEW_CHARS} 截断为预览。
     *
     * @param sessionId 会话 ID
     * @return 材料列表（无材料返回空列表，不报错）
     */
    List<MaterialBriefResp> listSessionMaterials(String sessionId);
}