package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.RollbackResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSettingsResp;
import jack.digityhuman.atualcombattraining.dto.resp.VersionResp;

import java.util.List;

/**
 * 主题版本/设置门面（FP-13~19，2026-10-30 版本语义重构）
 * <p>
 * 设置读/写已委托 {@link ThemeSettingsService}（act_theme_settings，不升版）；
 * 内容版本的创建与发布走 {@link ContentVersionService}（首次发布 v1、AI 确认升版）。
 * 本接口保留给控制器层使用，方法语义以各方法注释为准。
 */
public interface ThemeVersionService {

    /** 设置读取：五表单来自全局设置行 + 内容概览按 working_version_no 统计 */
    ThemeSettingsResp getSettings(String themeId);

    /** 设置保存：只更新 act_theme_settings，不创建内容版本；已发布后训练/考核开关锁定 */
    ThemeSaveResp saveSettings(String themeId, ThemeSettingsSaveReq req);

    /** 版本历史：只列已发布版本（1..published_version_no），version_no 倒序，公开版本 current=true */
    List<VersionResp> listVersions(String themeId);

    /** 回滚：公开指针与工作指针一起切到目标已发布版本，不新增版本记录 */
    RollbackResp rollback(String themeId, Integer versionNo);
}
