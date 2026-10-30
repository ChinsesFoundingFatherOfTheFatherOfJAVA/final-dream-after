package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;

/**
 * 主题全局设置服务（2026-10-30 契约：设置与内容版本彻底拆分）
 * <p>
 * 保存设置只更新 act_theme_settings 一行，不创建内容版本（D17）；
 * 训练/考核模式开关在首次发布后锁定（D18），AI 辅助与防作弊配置可改但只影响新 attempt（D08）。
 */
public interface ThemeSettingsService {

    /** 读全局设置行；不存在则按契约默认值落一行再返回（迁移漏网时兜底）。 */
    ActThemeSettings require(String themeId);

    /**
     * 整体保存设置（覆盖式更新，不创建内容版本）。
     *
     * @param themeId 主题 ID
     * @param req     五表单请求
     * @param themeName    主题名（冗余同步到 act_theme）
     * @param themeDesc    主题描述（冗余同步到 act_theme）
     * @param publishedVersionNo 当前公开版本（非空=已发布，训练/考核开关锁定）
     * @return 由设置推导出的冗余字段：themeMode/assignMode/difficulty
     */
    SaveResult save(String themeId, ThemeSettingsSaveReq req, String themeName, String themeDesc,
                    Integer publishedVersionNo);

    /** 保存结果：由设置推导、需要同步到 act_theme 冗余列的值 */
    record SaveResult(Integer themeMode, Integer assignMode, Integer difficulty) {
    }
}
