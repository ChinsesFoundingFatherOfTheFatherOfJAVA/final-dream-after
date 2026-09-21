package jack.digityhuman.atualcombattraining.common;

import java.util.List;

/**
 * 客户端 → 服务端 WS action 名（03 文档 §3.2）
 * <p>
 * 帧结构：{@code {type:"chat", payload:{message:{id,role,content:[{type:"data",data:{type:"action",actionName,params}}]}}, traceId}}
 */
public final class AssistantActions {

    /** 连接建立后自动发送：触发编排智能体开场白 */
    public static final String SESSION_START = "common-session-start";

    /** 右面板「确认」按钮：把工作区 artifact 翻译落库（FP-40） */
    public static final String CONFIRM = "biz-side-manage-confirm";

    /**
     * 右面板「培训材料」Tab 的「学员可见」开关（FP-42，03 文档 §3.2 定稿名）。
     * <p>
     * params：{@code {id: artifactId, version: N, materialId, visibility}}（`id`/`version` 为前端上下文，
     * 服务端只做日志提示不参与定位；定位以会话绑定的主题工作区产物为准）。
     * visibility：1 学员可见 / 0 内部可见，也接受布尔 true/false。
     * <p>
     * 服务端行为：改写工作区 artifact 的 {@code materials[].visibility} 并推
     * {@link BizTypes#ARTIFACT_UPDATED}（content 为 {@code [{op:"full_replace", path:"/materials", value:[...]}]}）。
     * materialId 兼容 {@code materials[].id}（上传返回的 fileId）/ {@code fileServId} / {@code fileId} 命中。
     */
    public static final String UPDATE_MATERIAL = "biz-side-manage-update-material";

    /**
     * 前端更新 artifact 的等价别名（兼容写法）。
     * 文档定稿名为 {@link #UPDATE_MATERIAL}；此处额外接受通用补丁语义的名称，便于前端灰度切换，
     * 定稿稳定后可收敛为单一名称（只改这一处）。
     */
    private static final List<String> FRONTEND_UPDATE_ALIASES = List.of(
            UPDATE_MATERIAL,
            "biz-side-manage-material-visibility",
            "biz-side-manage-update-artifact",
            "biz-side-manage-artifact-update");

    private AssistantActions() {
    }

    /** 是否为"前端更新 artifact"动作（材料开关及其别名），含通用补丁写法 */
    public static boolean isFrontendUpdate(String actionName) {
        return actionName != null && FRONTEND_UPDATE_ALIASES.contains(actionName);
    }
}