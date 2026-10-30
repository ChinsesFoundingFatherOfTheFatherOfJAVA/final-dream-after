package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;

/**
 * attempt 快照服务（契约 03 §4.4 / D07）
 * <p>
 * attempt 创建时把后续运行需要的所有实时数据冻结为 JSON 快照：
 * 设置、场景（含维度）、产品档案、学习材料。运行中不再读这些实时表（D08）。
 * JSON 列为 String 原样承载，快照内容由本服务用 Jackson 组装（不经手客户端原文）。
 */
public interface AttemptSnapshotService {

    /** 设置快照：act_theme_settings 行的 JSON 形式（时间转字符串） */
    String settingsSnapshot(ActThemeSettings settings);

    /** 场景快照：场景单元 + 该单元的评估维度（按 sort_order） */
    String scenarioSnapshot(String themeId, int contentVersionNo, String scenarioUnitId);

    /**
     * 产品档案快照：场景关联的档案（content_version_no = published）；
     * 场景未关联档案时返回空数组。
     */
    String productProfilesSnapshot(String themeId, int contentVersionNo, String scenarioUnitId);

    /**
     * 学习材料快照：已发布版本中 visibility=student 的材料（按 sort_order）。
     */
    String materialsSnapshot(String themeId, int contentVersionNo);
}
