package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;

import java.util.List;
import java.util.Map;

/**
 * 产品档案服务（契约 09）
 * <p>
 * 双平面模型：artifact 用稳定 profileKey（pp_ 前缀，主题生命周期内不变），
 * 数据库行带 content_version_no（0=工作区，&gt;0=发布快照）。
 * <p>
 * 硬规则：
 * <ul>
 *   <li>已发布版本的历史行不可改写（§4.4）；用户编辑永远落在 v0 工作区；</li>
 *   <li>删除仍被场景引用的档案时拒绝并返回引用场景清单（§2.2）；</li>
 *   <li>每主题最多 20 个工作档案（§6）；</li>
 *   <li>编辑名称不改 profileKey，不打断场景关联。</li>
 * </ul>
 */
public interface ProductProfileService {

    /** 工作区档案列表（v0，管理端五 Tab） */
    List<ActProductProfile> listWorking(String themeId);

    /** 新建工作档案（v0）：生成 profileKey；校验结构与 20 个上限 */
    ActProductProfile create(String themeId, ProductProfileUpsertReq req);

    /** 更新工作档案（v0）：profileKey 不可变；不存在的 v0 行（发布快照）拒绝 */
    ActProductProfile update(String themeId, String profileId, ProductProfileUpsertReq req);

    /**
     * 删除工作档案：被场景引用时拒绝，返回引用场景清单（scenarioId → 名称）。
     *
     * @return 被拒绝时抛 BizException，message 携带引用场景名
     */
    void delete(String themeId, String profileId);

    /**
     * 覆盖式设置场景关联（PUT 语义）：写入 act_scenario_product_profile。
     * profileKeys 必须存在于工作区（v0）；空数组 = 清空关联。
     */
    void linkScenario(String themeId, String scenarioUnitId, List<String> profileKeys);

    /** 场景已关联的档案（工作区口径，管理端回显） */
    List<ActProductProfile> listByScenario(String themeId, String scenarioUnitId);

    /** AI 候选生成（契约 §5.1）：只返回候选不落库 */
    List<Map<String, Object>> generateCandidates(String themeId, ProductProfileGenerateReq req);

    /** 学员端详情（§7）：只读发布快照 + 参与关系校验（跨主题拒绝） */
    ActProductProfile studentProfile(String userId, String profileId);

    /** 请求体：创建/更新共用（source 由服务端按入口判定，不收客户端） */
    record ProductProfileUpsertReq(String profileName, String category, String description,
                                   String detailMarkdown, String customFieldsJson) {
    }

    /** 请求体：AI 候选生成（§5.1） */
    record ProductProfileGenerateReq(List<String> materialIds, String profileNameHint,
                                     String instructions, List<String> customFieldKeys) {
    }

    /** 场景占位（避免实现层暴露 unit mapper） */
    ActScenarioUnit scenarioOf(String scenarioUnitId);
}
