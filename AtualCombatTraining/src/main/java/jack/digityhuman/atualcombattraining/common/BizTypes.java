package jack.digityhuman.atualcombattraining.common;

/**
 * bizType 事件字典（03 文档 §3.4）
 * <p>
 * data part 的完整 JSON 形如 {@code {"bizType":"...","params":{...}}}，
 * 前端在 part-end 时整体解析后按 bizType 派发（右面板更新 / 消息卡片状态 / 成功提示）。
 */
public final class BizTypes {

    /** 文件解析状态（status 0 解析中 / 1 完成） */
    public static final String PARSE_FILE = "biz-common-parse-file";
    /** 建草稿 / 改名（右面板展开、主题名更新、列表可见） */
    public static final String TOPIC_CHANGED = "biz-side-manage-topic-changed";
    /** JSON-Patch 更新（右面板按 path 局部更新） */
    public static final String ARTIFACT_UPDATED = "biz-side-manage-artifact-updated";
    /** 主题生成（in_progress → completed / failed） */
    public static final String ARTIFACT_GENERATED = "biz-side-manage-artifact-generated";
    /** 单案例生成完成 */
    public static final String CASE_GENERATED = "biz-side-manage-case-generated";
    /** 确认落库成功 */
    public static final String SAVE_SUCCESS = "biz-side-manage-save-success";

    private BizTypes() {
    }
}