package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.RollbackResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSettingsResp;
import jack.digityhuman.atualcombattraining.dto.resp.VersionResp;

import java.util.List;

/**
 * 主题版本服务（FP-13~17 设置读取 / FP-18 保存 / FP-19 版本列表与回滚）
 */
public interface ThemeVersionService {

    /** 读取当前版本五表单 + 内容概览（contentOverview 后端计算） */
    ThemeSettingsResp getSettings(String themeId);

    /**
     * 整体保存主题设置（事务内）：
     * 校验 → 插入新版本(max+1) → 复制场景单元+维度到新版本 → 切 current_version_no → 同步主题冗余字段。
     */
    ThemeSaveResp saveSettings(String themeId, ThemeSettingsSaveReq req);

    /** 版本列表（version_no 倒序，新在上，回滚不改变排序；当前版本带 current=true） */
    List<VersionResp> listVersions(String themeId);

    /** 回滚：仅切 current_version_no 指针并同步冗余字段，不新增/修改任何版本记录 */
    RollbackResp rollback(String themeId, Integer versionNo);

    /**
     * AI 路径专用：按当前版本复制出下一版本（version_no=max+1，五表单字段照抄、
     * 场景单元与维度整表复制、切换 current_version_no 并同步主题冗余字段）。
     * 对应"确认只落内容、发布时才产生业务版本"的版本语义（AI 路径发布=第一版）。
     *
     * @return 新版本号
     */
    int createVersionFromCurrent(String themeId);
}
