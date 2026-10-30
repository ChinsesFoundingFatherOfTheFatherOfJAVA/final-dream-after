package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSettingsResp;
import jack.digityhuman.atualcombattraining.dto.resp.VersionResp;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.impl.ThemeVersionServiceImpl;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主题版本门面单测（2026-10-30 版本语义重构后的验收口径）
 * - 设置保存委托 ThemeSettingsService，不产生版本行，冗余字段同步到 act_theme
 * - 版本历史只列已发布区间（1..published_version_no），v0 不暴露
 * - 回滚把公开/工作指针一起切到目标已发布版本，不新增版本行
 */
@ExtendWith(MockitoExtension.class)
class ThemeVersionServiceImplTest {

    private static final String THEME_ID = "seed-theme-001";

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeVersionMapper versionMapper;
    @Mock
    private ThemeService themeService;
    @Mock
    private ScenarioUnitService scenarioUnitService;
    @Mock
    private ThemeSettingsService themeSettingsService;

    @InjectMocks
    private ThemeVersionServiceImpl versionService;

    /** 已发布主题：working=published=2 */
    private ActTheme publishedTheme(int status) {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setStatus(status);
        theme.setThemeName("柔性智造销售话术训练");
        theme.setThemeDesc("描述");
        theme.setCurrentVersionNo(2);
        theme.setWorkingVersionNo(2);
        theme.setPublishedVersionNo(2);
        return theme;
    }

    /** 从未发布主题：working=0，published=null */
    private ActTheme draftTheme(int status) {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setStatus(status);
        theme.setCurrentVersionNo(1);
        theme.setWorkingVersionNo(0);
        theme.setPublishedVersionNo(null);
        return theme;
    }

    private ActThemeSettings settingsRow() {
        ActThemeSettings settings = new ActThemeSettings();
        settings.setThemeId(THEME_ID);
        settings.setTotalDrillCount(3708);
        settings.setJoinMode(1);
        settings.setTrainingModeEnabled(1);
        settings.setAssessmentModeEnabled(0);
        settings.setCoachReviewEnabled(1);
        settings.setSuggestionEnabled(0);
        settings.setDialogModes("voice,text");
        settings.setPracticeMethods("case,custom,random");
        settings.setDifficulty(2);
        settings.setAllowCustomDifficulty(1);
        settings.setFaceCheckBeforeEnabled(0);
        settings.setFaceCheckOngoingEnabled(0);
        settings.setScreenSwitchMode("forbid");
        settings.setScreenSwitchLimit(0);
        settings.setScenePassType(1);
        settings.setScenePassCount(1);
        return settings;
    }

    private ActThemeVersion versionRow(int versionNo) {
        ActThemeVersion version = new ActThemeVersion();
        version.setThemeId(THEME_ID);
        version.setVersionNo(versionNo);
        version.setThemeName("柔性智造销售话术训练");
        version.setCreator("管理员");
        return version;
    }

    @Test
    void 保存设置_委托设置服务且不产生版本行() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(publishedTheme(3));
        when(themeSettingsService.save(eq(THEME_ID), any(), any(), any(), eq(2)))
                .thenReturn(new ThemeSettingsService.SaveResult(1, 1, 2));

        ThemeSaveResp resp = versionService.saveSettings(THEME_ID, new ThemeSettingsSaveReq());

        // 响应 versionNo 语义 = 当前公开版本
        assertEquals("2", resp.getVersionNo());
        assertEquals(1, resp.getThemeMode());
        // 关键：设置保存不再插入版本行（冗余同步在 ThemeSettingsServiceImpl 内部，另有测试）
        verify(versionMapper, never()).insert(any(ActThemeVersion.class));
    }

    @Test
    void 保存设置_主题已结束_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(publishedTheme(5));

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings(THEME_ID, new ThemeSettingsSaveReq()));
        assertEquals("主题已结束，无法保存设置", e.getMessage());
    }

    @Test
    void 设置读取_五表单来自设置行_概览按工作版本统计() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(publishedTheme(3));
        when(themeSettingsService.require(THEME_ID)).thenReturn(settingsRow());
        when(scenarioUnitService.countUnits(THEME_ID, 2)).thenReturn(3L);
        when(scenarioUnitService.countDistinctIcebergElements(THEME_ID, 2)).thenReturn(5);
        when(scenarioUnitService.countDistinctDimensionNames(THEME_ID, 2)).thenReturn(4);

        ThemeSettingsResp resp = versionService.getSettings(THEME_ID);

        assertEquals("2", resp.getVersionNo());
        assertEquals(3708, resp.getAccess().getTotalDrillCount());
        assertEquals(List.of("case", "custom", "random"), resp.getStrategy().getPracticeMethods());
        assertEquals(3, resp.getContentOverview().getScenarioUnitCount());
    }

    @Test
    void 版本列表_只列已发布区间且v0不暴露() {
        ActTheme theme = publishedTheme(3);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        // 查询条件本身必须圈定 1..published（v0 与越界行不可返回）
        when(versionMapper.selectList(any())).thenReturn(List.of(versionRow(2), versionRow(1)));

        List<VersionResp> versions = versionService.listVersions(THEME_ID);

        assertEquals(2, versions.size());
        assertEquals("2", versions.get(0).getVersionNo());
        assertEquals(Boolean.TRUE, versions.get(0).getCurrent());
        assertEquals(Boolean.FALSE, versions.get(1).getCurrent());
    }

    @Test
    void 版本列表_未发布主题返回空() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(draftTheme(0));

        assertTrue(versionService.listVersions(THEME_ID).isEmpty());
    }

    @Test
    void 回滚_公开与工作指针一起切到目标版本() {
        ActTheme theme = publishedTheme(3);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1));

        var resp = versionService.rollback(THEME_ID, 1);

        assertEquals("1", resp.getCurrentVersionNo());
        // 回滚不新增版本行
        verify(versionMapper, never()).insert(any(ActThemeVersion.class));
        // 指针切换 + 冗余同步
        verify(themeMapper).update(any(), any());
        verify(themeService).syncRedundantFields(theme, versionRow(1));
    }

    @Test
    void 回滚_未发布主题_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(draftTheme(0));

        BizException e = assertThrows(BizException.class,
                () -> versionService.rollback(THEME_ID, 1));
        assertEquals("主题尚未发布，无可回滚版本", e.getMessage());
    }

    @Test
    void 回滚_目标超出已发布范围_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(publishedTheme(3));

        BizException e = assertThrows(BizException.class,
                () -> versionService.rollback(THEME_ID, 3));
        assertEquals("目标版本不在已发布范围内", e.getMessage());
    }

    @Test
    void 回滚_主题已结束_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(publishedTheme(5));

        BizException e = assertThrows(BizException.class,
                () -> versionService.rollback(THEME_ID, 1));
        assertEquals("主题已结束，无法回滚", e.getMessage());
    }
}
