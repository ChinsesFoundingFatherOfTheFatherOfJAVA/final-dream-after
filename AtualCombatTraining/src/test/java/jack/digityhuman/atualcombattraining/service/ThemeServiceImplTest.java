package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.impl.ThemeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主题状态机与冗余字段同步单测（FP-09 / FP-18 / FP-19）
 * 状态机：3已发布⇄4已暂停；3/4→5已结束（终态）；其余流转一律拒绝
 */
@ExtendWith(MockitoExtension.class)
class ThemeServiceImplTest {

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeVersionMapper versionMapper;
    @Mock
    private ScenarioUnitService scenarioUnitService;

    @InjectMocks
    private ThemeServiceImpl themeService;

    /** 构造指定状态的主题 */
    private ActTheme theme(int status) {
        ActTheme theme = new ActTheme();
        theme.setId("seed-theme-001");
        theme.setStatus(status);
        theme.setCurrentVersionNo(2);
        return theme;
    }

    @Test
    void 暂停_已发布变已暂停() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3));

        themeService.pause("seed-theme-001");

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getUpdatedAt());
    }

    @Test
    void 恢复_已暂停变已发布() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(4));

        themeService.pause("seed-theme-001");

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getStatus());
    }

    @Test
    void 暂停_已结束拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(5));

        BizException e = assertThrows(BizException.class, () -> themeService.pause("seed-theme-001"));
        assertEquals("当前状态不允许暂停或恢复", e.getMessage());
    }

    @Test
    void 暂停_草稿拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(0));

        assertThrows(BizException.class, () -> themeService.pause("seed-theme-001"));
    }

    @Test
    void 结束_已发布变已结束() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(3));

        themeService.finish("seed-theme-001");

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        assertEquals(5, captor.getValue().getStatus());
    }

    @Test
    void 结束_已暂停也可结束() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(4));

        themeService.finish("seed-theme-001");

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        assertEquals(5, captor.getValue().getStatus());
    }

    @Test
    void 结束_已结束重复调用拒绝() {
        when(themeMapper.selectById("seed-theme-001")).thenReturn(theme(5));

        BizException e = assertThrows(BizException.class, () -> themeService.finish("seed-theme-001"));
        assertEquals("当前状态不允许结束", e.getMessage());
    }

    @Test
    void 冗余字段同步_按版本切换指针并推导模式() {
        // 目标版本 v1：训练开+考核关→主题模式1；joinMode=2→全体学员
        ActThemeVersion v1 = new ActThemeVersion();
        v1.setThemeId("seed-theme-001");
        v1.setVersionNo(1);
        v1.setThemeName("旧版本名称");
        v1.setThemeDesc("旧版本描述");
        v1.setTrainingModeEnabled(1);
        v1.setAssessmentModeEnabled(0);
        v1.setJoinMode(2);
        v1.setDifficulty(3);
        ActTheme theme = theme(3);

        themeService.syncRedundantFields(theme, v1);

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        ActTheme updated = captor.getValue();
        assertEquals(1, updated.getCurrentVersionNo());
        assertEquals("旧版本名称", updated.getThemeName());
        assertEquals(1, updated.getThemeMode());
        assertEquals(2, updated.getAssignMode());
        assertEquals(3, updated.getDifficulty());
        assertNotNull(updated.getUpdatedAt());
    }

    @Test
    void 冗余字段同步_双开训考一体() {
        ActThemeVersion version = new ActThemeVersion();
        version.setVersionNo(3);
        version.setTrainingModeEnabled(1);
        version.setAssessmentModeEnabled(1);
        version.setJoinMode(1);

        themeService.syncRedundantFields(theme(3), version);

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getThemeMode());
        assertEquals(1, captor.getValue().getAssignMode());
    }
}
