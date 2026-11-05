package jack.digityhuman.atualcombattraining.service.impl;

import jack.digityhuman.atualcombattraining.dto.req.CreateTopicReq;
import jack.digityhuman.atualcombattraining.dto.resp.CreateTopicResp;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantMaterialService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.CaseGenerationService;
import jack.digityhuman.atualcombattraining.service.ThemeGenerationService;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * create_topic 建版本行单测（2026-11-05）。
 * <p>
 * 背景：端到端验证（真实生成案例 → 确认 → 发布）发现首次发布必失败：
 * {@code ContentVersionServiceImpl#firstPublish} 要读隐藏工作版本 {@code version_no=0} 作为复制源，
 * 而 create_topic 当时只建了 v1 行，于是 {@code requireVersion(themeId, 0)} 抛
 * 「内容版本不存在 version=0」，发布永远停在 status=1。
 * <p>
 * 本测试锁住"新建主题必须同时有 v0 与 v1 两行"：v0 是工作快照源，v1 是设置页直读的 legacy 可见行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantToolServiceImplCreateTopicVersionTest {

    @Mock
    private AssistantSessionService sessionService;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private AssistantEventPublisher eventPublisher;
    @Mock
    private ThemeGenerationService themeGenerationService;
    @Mock
    private CaseGenerationService caseGenerationService;
    @Mock
    private AssistantMaterialService materialService;
    @Mock
    private AssistantRoundRegistry roundRegistry;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeVersionMapper versionMapper;

    @InjectMocks
    private AssistantToolServiceImpl toolService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private void stubNewSession() {
        when(roundRegistry.sessionIdOf("trace-new")).thenReturn("sess-new");
        ActAiSession session = new ActAiSession();
        session.setId("sess-new");
        session.setThemeId(null);
        when(sessionService.requireSession("sess-new")).thenReturn(session);
        // 插入回填雪花 ID（真实 MyBatis-Plus 会回填，Mockito 不会）
        when(themeMapper.insert(any(ActTheme.class))).thenAnswer(inv -> {
            ActTheme theme = inv.getArgument(0);
            theme.setId("theme-new");
            return 1;
        });
    }

    @Test
    void 新建主题同时建v0工作行与v1可见行() {
        stubNewSession();
        CreateTopicReq req = new CreateTopicReq();
        req.setTopic("订单运营实战演练");
        req.setDescription("提升订单处理规范");

        toolService.createTopic("trace-new", "sess-new", req);

        ArgumentCaptor<ActThemeVersion> captor = ArgumentCaptor.forClass(ActThemeVersion.class);
        verify(versionMapper, times(2)).insert(captor.capture());
        List<ActThemeVersion> inserted = captor.getAllValues();

        // v0 必须存在：首次发布以它为复制源（缺了就会抛「内容版本不存在 version=0」）
        ActThemeVersion working = inserted.stream()
                .filter(v -> v.getVersionNo() == 0).findFirst().orElse(null);
        assertNotNull(working, "必须插入 version_no=0 的隐藏工作行，否则首次发布必失败");
        assertEquals("theme-new", working.getThemeId());
        assertEquals("订单运营实战演练", working.getThemeName());

        // v1 也必须在：主题设置页直接读它
        ActThemeVersion visible = inserted.stream()
                .filter(v -> v.getVersionNo() == 1).findFirst().orElse(null);
        assertNotNull(visible, "version_no=1 的可见行仍要插入");
        assertEquals("theme-new", visible.getThemeId());
    }

    @Test
    void 已有主题时不重复建版本行() {
        when(roundRegistry.sessionIdOf("trace-old")).thenReturn("sess-old");
        ActAiSession session = new ActAiSession();
        session.setId("sess-old");
        session.setThemeId("theme-old");
        when(sessionService.requireSession("sess-old")).thenReturn(session);
        ActTheme existing = new ActTheme();
        existing.setId("theme-old");
        when(themeMapper.selectById("theme-old")).thenReturn(existing);

        CreateTopicReq req = new CreateTopicReq();
        req.setTopic("改名后的主题");
        CreateTopicResp resp = toolService.createTopic("trace-old", "sess-old", req);

        assertEquals("theme-old", resp.getThemeId());
        assertEquals(false, resp.getCreated());
        verify(versionMapper, org.mockito.Mockito.never()).insert(any(ActThemeVersion.class));
    }
}
