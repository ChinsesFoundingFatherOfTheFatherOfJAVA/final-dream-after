package jack.digityhuman.atualcombattraining.service;

/**
 * 内容版本服务（2026-10-30 学员端契约：版本语义重构）
 * <p>
 * 新版本模型（契约 00 §6.1 / 03 §4.2/§5）：
 * <ul>
 *   <li>act_theme_version 只代表可发布内容快照；v0 是从未发布主题的内部工作版本，不对学员返回；</li>
 *   <li>首次发布产生 v1 并公开；之后 AI 确认直接创建并发布下一版本，不存在用户可见草稿版本；</li>
 *   <li>设置与版本彻底拆分：设置走 {@link ThemeSettingsService}，保存不升版。</li>
 * </ul>
 */
public interface ContentVersionService {

    /**
     * 首次发布（仅草稿主题）：把隐藏 v0 的工作内容固化为公开 v1。
     * <p>
     * 复制范围：版本行（五表单快照）、场景单元与维度、案例剧本、客户画像；
     * 若迁移遗留了旧 v1 行（legacy_content），原地复活为 initial_publish，不产生新行。
     *
     * @return 公开版本号（恒为 1）
     */
    int firstPublish(String themeId, String userId);

    /**
     * 已发布主题的 AI 确认：创建下一个内容版本行（version_no = max+1，五表单照抄当前公开版本，
     * source_type=ai_confirm），返回新版本号。内容行由调用方（ConfirmService）写到该版本号下，
     * 写完后再调 {@link #markPublished} 切换指针——两步分开是为了让内容写入和指针切换在同一事务内完成。
     */
    int startNewVersionFromPublished(String themeId, String userId);

    /** 切换公开指针：published_version_no = working_version_no = versionNo，并写发布元数据。 */
    void markPublished(String themeId, int versionNo, String userId);

    /** 主题是否已发布过（published_version_no 非空） */
    boolean isPublished(String themeId);
}
