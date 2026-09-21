package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.dto.resp.PublishResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScriptProgressResp;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.impl.PublishInitServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布与初始化单测（FP-41）：
 * 进度计算（total/completed/failed/progress）、无案例时直接完成、发布前置校验（未确认内容 / 状态不允许）、
 * 重试无失败项时拒绝。
 */
@ExtendWith(MockitoExtension.class)
class PublishInitServiceImplTest {

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActCaseScriptMapper caseScriptMapper;
    @Mock
    private ActDrillScriptMapper drillScriptMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActEvaluationDimensionMapper dimensionMapper;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private ThemeVersionService themeVersionService;
    @Mock
    private ThreadPoolTaskExecutor publishExecutor;

    @InjectMocks
    private PublishInitServiceImpl publishInitService;

    /** 注册实体元数据，保证 LambdaWrapper 在本类独立运行时也可用 */
    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    /** 主题（当前版本 1） */
    private ActTheme theme(int status, int versionNo) {
        ActTheme theme = new ActTheme();
        theme.setId("theme-1");
        theme.setStatus(status);
        theme.setCurrentVersionNo(versionNo);
        return theme;
    }

    /** 案例（指定状态） */
    private ActCaseScript caseScript(String id, String status) {
        ActCaseScript script = new ActCaseScript();
        script.setId(id);
        script.setThemeId("theme-1");
        script.setVersionNo(1);
        script.setStatus(status);
        return script;
    }

    private void stubConfirmedArtifact() {
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-1");
        artifact.setVersionNo(1);
        artifact.setStatus("confirmed");
        when(artifactService.findLatestConfirmed("theme-1")).thenReturn(artifact);
    }

    @Test
    void 进度计算_按案例状态统计并四舍五入() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(1, 1));
        List<ActCaseScript> cases = new ArrayList<>();
        cases.add(caseScript("c1", "compiled"));
        cases.add(caseScript("c2", "compiled"));
        cases.add(caseScript("c3", "failed"));
        cases.add(caseScript("c4", "draft"));
        when(caseScriptMapper.selectList(any())).thenReturn(cases);

        ScriptProgressResp progress = publishInitService.scriptProgress("theme-1");

        assertEquals(4, progress.getTotal());
        assertEquals(2, progress.getCompleted());
        assertEquals(1, progress.getFailed());
        assertEquals(50, progress.getProgress());
        assertEquals(1, progress.getStatus());
    }

    @Test
    void 进度计算_无案例时进度为100() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(3, 1));
        when(caseScriptMapper.selectList(any())).thenReturn(new ArrayList<>());

        ScriptProgressResp progress = publishInitService.scriptProgress("theme-1");

        assertEquals(0, progress.getTotal());
        assertEquals(100, progress.getProgress());
        assertEquals(3, progress.getStatus());
    }

    @Test
    void 进度计算_三取一完成时向上取整为33() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(1, 1));
        List<ActCaseScript> cases = new ArrayList<>();
        cases.add(caseScript("c1", "compiled"));
        cases.add(caseScript("c2", "draft"));
        cases.add(caseScript("c3", "draft"));
        when(caseScriptMapper.selectList(any())).thenReturn(cases);

        assertEquals(33, publishInitService.scriptProgress("theme-1").getProgress());
    }

    @Test
    void 发布_未确认内容时拒绝() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(0, 1));
        when(artifactService.findLatestConfirmed("theme-1")).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> publishInitService.publish("theme-1"));
        assertTrue(error.getMessage().contains("请先在 AI 助手中确认内容"));
    }

    @Test
    void 发布_状态不允许时拒绝() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(1, 1));
        stubConfirmedArtifact();
        when(caseScriptMapper.selectList(any())).thenReturn(new ArrayList<>());
        // 条件更新影响 0 行 = 状态已不是草稿/已发布
        when(themeMapper.update(any(), any())).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> publishInitService.publish("theme-1"));
        assertTrue(error.getMessage().contains("当前状态不允许发布"));
    }

    @Test
    void 发布_受理后返回发布中与案例总数() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(0, 1));
        stubConfirmedArtifact();
        List<ActCaseScript> cases = new ArrayList<>();
        cases.add(caseScript("c1", "draft"));
        cases.add(caseScript("c2", "draft"));
        when(caseScriptMapper.selectList(any())).thenReturn(cases);
        when(themeMapper.update(any(), any())).thenReturn(1);

        PublishResp resp = publishInitService.publish("theme-1");

        assertEquals(1, resp.getStatus());
        assertEquals(2, resp.getTotalCases());
        verify(publishExecutor).execute(any(Runnable.class));
    }

    @Test
    void 发布_无案例时直接完成并新建版本() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(0, 1));
        stubConfirmedArtifact();
        when(caseScriptMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(themeMapper.update(any(), any())).thenReturn(1);
        when(themeVersionService.createVersionFromCurrent("theme-1")).thenReturn(2);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(drillScriptMapper.selectList(any())).thenReturn(new ArrayList<>());

        PublishResp resp = publishInitService.publish("theme-1");

        assertEquals(3, resp.getStatus());
        assertEquals(0, resp.getTotalCases());
        verify(themeVersionService).createVersionFromCurrent("theme-1");
    }

    @Test
    void 重试_无失败项时拒绝() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(1, 1));
        when(caseScriptMapper.selectList(any())).thenReturn(new ArrayList<>());

        BizException error = assertThrows(BizException.class, () -> publishInitService.retry("theme-1"));
        assertTrue(error.getMessage().contains("没有需要重试的失败项"));
    }

    @Test
    void 重试_失败项置回待编译并重新排队() {
        when(themeMapper.selectById("theme-1")).thenReturn(theme(1, 1));
        List<ActCaseScript> failed = new ArrayList<>();
        failed.add(caseScript("c3", "failed"));
        when(caseScriptMapper.selectList(any())).thenReturn(failed);
        when(themeMapper.update(any(), any())).thenReturn(1);

        publishInitService.retry("theme-1");

        verify(caseScriptMapper).updateById(any(ActCaseScript.class));
        verify(publishExecutor).execute(any(Runnable.class));
    }

    @Test
    void 主题不存在时抛业务异常() {
        when(themeMapper.selectById(anyString())).thenReturn(null);
        BizException error = assertThrows(BizException.class, () -> publishInitService.scriptProgress("missing"));
        assertTrue(error.getMessage().contains("主题不存在"));
    }
}