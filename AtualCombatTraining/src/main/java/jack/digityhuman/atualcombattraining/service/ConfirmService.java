package jack.digityhuman.atualcombattraining.service;

/**
 * 确认落库服务（FP-40）
 * <p>
 * 把工作区 artifact 翻译进业务表（场景单元 / 评估维度按场景展开 / 客户画像 / 案例剧本 / 材料），
 * 并同步主题冗余字段。幂等语义：
 * <ul>
 *   <li>已 confirmed 且内容摘要未变 → 直接回成功，不重复落库；</li>
 *   <li>工作区行用"条件更新 + 行锁"抢占（UPDATE ... WHERE status='working'），
 *       并发/重复点击时只有一个事务能抢到，其余走幂等分支返回成功。</li>
 * </ul>
 */
public interface ConfirmService {

    /**
     * 执行确认。
     *
     * @param sessionId 会话 ID（确定主题）
     * @param artifactId 确认的 artifact ID（action 参数 id，可空则取展示用 artifact）
     * @param version 确认的版本号（action 参数 version，可空）
     * @return 确认结果（用于推送 biz-side-manage-save-success）
     */
    ConfirmResult confirm(String sessionId, String artifactId, Integer version);

    /** 确认结果：artifactId / versionNo / themeId（ex_id） */
    record ConfirmResult(String artifactId, Integer versionNo, String themeId) {
    }
}