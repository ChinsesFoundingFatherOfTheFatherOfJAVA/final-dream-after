package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.impl.ThemeVersionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主题设置保存/回滚单测（FP-18 / FP-19 验收标准）
 * - 保存后新版本号=max+1、场景单元复制被触发、指针切换与冗余同步走共用方法
 * - 回滚仅切指针并同步冗余字段，不新增版本记录
 * - 校验拦截：名称/描述/演练模式/教练点评/练习方式/已结束状态
 */
@ExtendWith(MockitoExtension.class)
class ThemeVersionServiceImplTest {

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeVersionMapper versionMapper;
    @Mock
    private ThemeService themeService;
    @Mock
    private ScenarioUnitService scenarioUnitService;

    @InjectMocks
    private ThemeVersionServiceImpl versionService;

    /** 构造主题（默认已发布、当前版本 2） */
    private ActTheme theme(int status, int currentVersionNo) {
        ActTheme theme = new ActTheme();
        theme.setId("seed-theme-001");
        theme.setStatus(status);
        theme.setCurrentVersionNo(currentVersionNo);
        return theme;
    }

    /** 构造合法保存请求（训练开考核关、教练点评开、三种练习方式） */
    private ThemeSettingsSaveReq validReq() {
        ThemeSettingsSaveReq req = new ThemeSettingsSaveReq();
        ThemeSettingsSaveReq.Basic basic = new ThemeSettingsSaveReq.Basic();
        basic.setThemeName("柔性智造销售话术训练");
        basic.setThemeDesc("基于六大场景知识库，训练销售精准讲解方案价值并应对客户质疑");
        req.setBasic(basic);

        ThemeSettingsSaveReq.Access access = new ThemeSettingsSaveReq.Access();
        access.setTotalDrillCount(3708);
        access.setPerSceneLimit(null);
        access.setJoinMode(1);
        access.setGuestAccessEnabled(false);
        req.setAccess(access);

        ThemeSettingsSaveReq.Strategy strategy = new ThemeSettingsSaveReq.Strategy();
        strategy.setAiResponseMode(1);
        strategy.setTrainingModeEnabled(true);
        strategy.setAssessmentModeEnabled(false);
        strategy.setCoachReviewEnabled(true);
        strategy.setSuggestionEnabled(false);
        strategy.setPracticeMethods(List.of("case", "custom", "random"));
        strategy.setDifficulty(2);
        strategy.setAllowCustomDifficulty(true);
        req.setStrategy(strategy);

        ThemeSettingsSaveReq.PassStandard passStandard = new ThemeSettingsSaveReq.PassStandard();
        passStandard.setScenePassType(1);
        passStandard.setScenePassCount(1);
        req.setPassStandard(passStandard);
        return req;
    }

    @Test
    void 保存设置_产生新版本并复制场景单元() {
        ActTheme theme = theme(3, 2);
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme);
        // 当前最大版本号 = 2 → 新版本号 3
        ActThemeVersion v2 = new ActThemeVersion();
        v2.setVersionNo(2);
        when(versionMapper.selectList(any())).thenReturn(List.of(v2));
        // 冗余同步后主题模式/分配模式由 mock 模拟设置（真实推导已由 ThemeServiceImplTest 覆盖）
        org.mockito.Mockito.doAnswer(inv -> {
            ActTheme t = inv.getArgument(0);
            t.setThemeMode(1);
            t.setAssignMode(1);
            return null;
        }).when(themeService).syncRedundantFields(any(), any());

        ThemeSaveResp resp = versionService.saveSettings("seed-theme-001", validReq());

        assertEquals("3", resp.getVersionNo());
        assertEquals(1, resp.getThemeMode());
        // 新版本行：versionNo=3、creator=管理员、练习方式 CSV、教练点评开启
        ArgumentCaptor<ActThemeVersion> versionCaptor = ArgumentCaptor.forClass(ActThemeVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        ActThemeVersion inserted = versionCaptor.getValue();
        assertEquals(3, inserted.getVersionNo());
        assertEquals("管理员", inserted.getCreator());
        assertEquals("case,custom,random", inserted.getPracticeMethods());
        assertEquals(1, inserted.getCoachReviewEnabled());
        // 场景单元+维度从旧版本(2)复制到新版本(3)
        verify(scenarioUnitService).copyUnitsToVersion("seed-theme-001", 2, 3);
        // 指针切换与冗余同步（共用方法）
        verify(themeService).syncRedundantFields(eq(theme), any());
    }

    @Test
    void 保存设置_训练模式开教练点评关_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3, 2));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setCoachReviewEnabled(false);

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings("seed-theme-001", req));
        assertEquals("训练模式开启时必须开启教练点评", e.getMessage());
        verify(versionMapper, never()).insert(any(ActThemeVersion.class));
    }

    @Test
    void 保存设置_两种演练模式都关_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3, 2));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setTrainingModeEnabled(false);
        req.getStrategy().setAssessmentModeEnabled(false);

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings("seed-theme-001", req));
        assertEquals("至少启用一种演练模式", e.getMessage());
    }

    @Test
    void 保存设置_练习方式为空_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3, 2));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setPracticeMethods(List.of());

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings("seed-theme-001", req));
        assertEquals("至少保留一种练习方式", e.getMessage());
    }

    @Test
    void 保存设置_名称为空_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3, 2));
        ThemeSettingsSaveReq req = validReq();
        req.getBasic().setThemeName("  ");

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings("seed-theme-001", req));
        assertEquals("主题名称不能为空", e.getMessage());
    }

    @Test
    void 保存设置_名称超50字_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3, 2));
        ThemeSettingsSaveReq req = validReq();
        req.getBasic().setThemeName("超".repeat(51));

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings("seed-theme-001", req));
        assertEquals("主题名称不能超过50字", e.getMessage());
    }

    @Test
    void 保存设置_主题已结束_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(5, 2));

        BizException e = assertThrows(BizException.class,
                () -> versionService.saveSettings("seed-theme-001", validReq()));
        assertEquals("主题已结束，无法保存设置", e.getMessage());
        verify(versionMapper, never()).insert(any(ActThemeVersion.class));
    }

    @Test
    void 回滚_切换指针并同步冗余字段() {
        ActTheme theme = theme(3, 2);
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme);
        ActThemeVersion v1 = new ActThemeVersion();
        v1.setThemeId("seed-theme-001");
        v1.setVersionNo(1);
        v1.setThemeName("旧版本名称");
        when(versionMapper.selectOne(any())).thenReturn(v1);

        var resp = versionService.rollback("seed-theme-001", 1);

        assertEquals("1", resp.getCurrentVersionNo());
        // 回滚不新增/修改任何版本记录，仅通过共用方法切指针+同步冗余
        verify(versionMapper, never()).insert(any(ActThemeVersion.class));
        verify(themeService).syncRedundantFields(theme, v1);
    }

    @Test
    void 回滚_目标版本不存在_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3, 2));
        when(versionMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> versionService.rollback("seed-theme-001", 99));
        assertEquals("目标版本不存在", e.getMessage());
    }

    @Test
    void 回滚_主题已结束_拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(5, 2));

        BizException e = assertThrows(BizException.class,
                () -> versionService.rollback("seed-theme-001", 1));
        assertEquals("主题已结束，无法回滚", e.getMessage());
    }
}
