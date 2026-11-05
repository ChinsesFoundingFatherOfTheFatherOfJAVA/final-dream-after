package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActCustomerProfile;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAiArtifactMapper;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCustomerProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ContentVersionService;
import jack.digityhuman.atualcombattraining.service.PublishInitService;
import jack.digityhuman.atualcombattraining.service.impl.ArtifactServiceImpl;
import jack.digityhuman.atualcombattraining.service.impl.ConfirmServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 确认落库单测（FP-40）：
 * 1. 幂等 fast path（已确认且内容摘要未变不重复落库）；
 * 2. 并发/重复点击幂等（抢占工作区行的条件更新影响 0 行则直接回成功，不做任何翻译）；
 * 3. 维度引用展开（全局维度 + 每场景 dimensionWeights → 每场景独立维度行，权重取场景引用值）；
 * 4. 场景单元/案例/画像落库字段与顺序（案例挂到新建单元行下）。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmServiceImplTest {

    @Mock
    private ActAiArtifactMapper artifactMapper;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActEvaluationDimensionMapper dimensionMapper;
    @Mock
    private ActCustomerProfileMapper profileMapper;
    @Mock
    private ActCaseScriptMapper caseScriptMapper;
    @Mock
    private ActAiMaterialMapper materialMapper;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private AssistantSessionService sessionService;
    @Mock
    private ContentVersionService contentVersionService;
    @Mock
    private PublishInitService publishInitService;

    @InjectMocks
    private ConfirmServiceImpl confirmService;

    /** 注册实体元数据，保证 LambdaWrapper 在本类独立运行时也可用 */
    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    /** 两个场景的 artifact：维度 dim-01/dim-02/dim-03 全局定义，每场景权重合计 100 */
    private static final String ARTIFACT_JSON = """
            {
              "materials": [{"id":"file-1","name":"材料.pptx","fileType":"pptx","fileSize":100,"visibility":1}],
              "scenarios": [
                {"id":"sc-01","name":"开场接触：需求摸底","audiences":["订单运营专员"],
                 "businessGoal":"建立信任并摸清需求","behaviorGoals":["提出开放问题","复述确认需求"],
                 "relatedIcebergElements":{"knowledge":["订单处理规范"],"skills":["异常识别"]},
                 "businessImpact":"影响后续转化","failureCost":"需求误判导致返工",
                 "coreConflict":"客户急于下单 vs 需完整信息","failureReasons":["×未复核地址"],
                 "difficulties":["客户催促"],
                 "constraints":{"forbidBehaviors":["禁止跳过核对"],"maxTime":15},
                 "cases":[{"scriptName":"高压催单：精准录入","difficulty":2,"actorName":"王敏",
                           "actorPosition":"运营主管","actorCompany":"宏达科技","actorType":"客户方负责人",
                           "actorPersonality":"急躁、强势","actorCommunicationStyle":"语速快",
                           "scriptBackground":"背景","scriptOpening":"（拍桌）你们到底什么时候能处理？"}],
                 "dimensionWeights":[{"dimensionId":"dim-01","weight":60},{"dimensionId":"dim-02","weight":40}]},
                {"id":"sc-02","name":"异常处理：越级投诉","audiences":["客服主管"],
                 "businessGoal":"化解投诉","behaviorGoals":["情绪安抚"],
                 "relatedIcebergElements":{"knowledge":["投诉处理规范"]},
                 "businessImpact":"影响满意度","failureCost":"投诉升级",
                 "coreConflict":"客户施压 vs 合规红线","failureReasons":["×越权承诺"],
                 "difficulties":["客户施压","情绪激烈","扬言投诉"],
                 "constraints":{"forbidBehaviors":["禁止越权让价"],"maxTime":25},
                 "cases":[],
                 "dimensionWeights":[{"dimensionId":"dim-02","weight":30},{"dimensionId":"dim-03","weight":70}]}
              ],
              "customerProfiles": [
                {"id":"cp-01","type":"价格敏感型采购经理","description":"处境",
                 "personalities":["务实"],"decisionFactors":["价格"],"communicationStyle":"直接",
                 "painPoints":["预算紧"]}
              ],
              "icebergModel": {"visible":{"knowledge":[],"skills":[]},
                               "hidden":{"roles":[],"selfConcept":[],"personalities":[],"motives":[]}},
              "evaluationCriteria": {
                "passScore": 70,
                "dimensions": [
                  {"id":"dim-01","name":"流程规范","description":"考察流程合规",
                   "criteria":"优秀(90-100分)：完整复核；良好(70-89分)：基本复核；需改进(<70分)：跳过步骤"},
                  {"id":"dim-02","name":"沟通技巧","description":"考察沟通",
                   "criteria":"优秀(90-100分)：共情到位；良好(70-89分)：表达清楚；需改进(<70分)：生硬"},
                  {"id":"dim-03","name":"情绪控制","description":"考察情绪",
                   "criteria":"优秀(90-100分)：稳定；良好(70-89分)：可控；需改进(<70分)：失控"}
                ],
                "defaultDimensionWeights": [
                  {"dimensionId":"dim-01","weight":40},{"dimensionId":"dim-02","weight":30},
                  {"dimensionId":"dim-03","weight":30}]
              },
              "professionalKnowledge": null,
              "productProfiles": [],
              "version": "v1"
            }""";

    /** 会话（绑定主题） */
    private void stubSession() {
        ActAiSession session = new ActAiSession();
        session.setId("sess-1");
        session.setThemeId("theme-1");
        when(sessionService.requireSession("sess-1")).thenReturn(session);
    }

    /** 主题（未发布草稿：工作版本 2，内容确认写入该版本，不产生可见版本） */
    private void stubTheme() {
        ActTheme theme = new ActTheme();
        theme.setId("theme-1");
        theme.setCurrentVersionNo(2);
        theme.setWorkingVersionNo(2);
        theme.setPublishedVersionNo(null);
        when(themeMapper.selectById("theme-1")).thenReturn(theme);
    }

    /** 让单元插入回填自增 ID，便于校验维度/案例的挂靠关系 */
    private void stubUnitInsertWithIds() {
        AtomicInteger counter = new AtomicInteger();
        doAnswer(invocation -> {
            ActScenarioUnit unit = invocation.getArgument(0);
            unit.setId("unit-" + counter.incrementAndGet());
            return 1;
        }).when(unitMapper).insert(any(ActScenarioUnit.class));
    }

    @Test
    void 幂等_已确认且内容摘要未变_不重复落库() {
        stubSession();
        String content = ARTIFACT_JSON;
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-1");
        artifact.setVersionNo(1);
        artifact.setStatus(ArtifactServiceImpl.STATUS_CONFIRMED);
        artifact.setContent(content);
        artifact.setContentHash(JsonSupport.digest(content));
        when(artifactService.get("art-1", 1)).thenReturn(artifact);

        ConfirmService.ConfirmResult result = confirmService.confirm("sess-1", "art-1", 1);

        assertEquals("art-1", result.artifactId());
        assertEquals(1, result.versionNo());
        assertEquals("theme-1", result.themeId());
        // 幂等：不抢占、不翻译
        verifyNoInteractions(artifactMapper, themeMapper, unitMapper, dimensionMapper,
                profileMapper, caseScriptMapper, materialMapper);
    }

    @Test
    void 并发幂等_未抢占到工作区行_直接回成功且不翻译() {
        stubSession();
        String content = ARTIFACT_JSON;
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-2");
        artifact.setVersionNo(2);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(content);
        artifact.setContentHash("stale-hash-different");
        when(artifactService.get("art-2", 2)).thenReturn(artifact);
        // 条件更新影响 0 行 = 另一个事务已抢占
        when(artifactMapper.update(any(), any())).thenReturn(0);

        ConfirmService.ConfirmResult result = confirmService.confirm("sess-1", "art-2", 2);

        assertEquals("art-2", result.artifactId());
        assertEquals("theme-1", result.themeId());
        verifyNoInteractions(themeMapper, unitMapper, dimensionMapper,
                profileMapper, caseScriptMapper, materialMapper);
    }

    @Test
    void 维度引用展开_每场景独立维度行且权重取场景引用值() {
        stubSession();
        stubTheme();
        stubUnitInsertWithIds();
        String content = ARTIFACT_JSON;
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-3");
        artifact.setVersionNo(3);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(content);
        artifact.setContentHash("other");
        when(artifactService.get("art-3", 3)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-3", 3);

        ArgumentCaptor<ActEvaluationDimension> captor = ArgumentCaptor.forClass(ActEvaluationDimension.class);
        verify(dimensionMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        List<ActEvaluationDimension> inserted = captor.getAllValues();

        // 场景 1 的两行：dim-01(60) / dim-02(40)，挂在 unit-1
        assertEquals("unit-1", inserted.get(0).getScenarioUnitId());
        assertEquals("流程规范", inserted.get(0).getDimensionName());
        assertEquals(0, new BigDecimal("60").compareTo(inserted.get(0).getWeight()));
        assertEquals("考察流程合规", inserted.get(0).getDimensionDesc());
        assertEquals("完整复核", inserted.get(0).getExcellentStandard());
        assertEquals("基本复核", inserted.get(0).getGoodStandard());
        assertEquals("跳过步骤", inserted.get(0).getImproveStandard());
        assertEquals("unit-1", inserted.get(1).getScenarioUnitId());
        assertEquals(0, new BigDecimal("40").compareTo(inserted.get(1).getWeight()));
        // 场景 2 的两行：dim-02(30) / dim-03(70)，挂在 unit-2
        assertEquals("unit-2", inserted.get(2).getScenarioUnitId());
        assertEquals(0, new BigDecimal("30").compareTo(inserted.get(2).getWeight()));
        assertEquals("unit-2", inserted.get(3).getScenarioUnitId());
        assertEquals("情绪控制", inserted.get(3).getDimensionName());
        assertEquals(0, new BigDecimal("70").compareTo(inserted.get(3).getWeight()));
    }

    @Test
    void 场景单元与案例落库_案例挂到新建单元行且状态为draft() {
        stubSession();
        stubTheme();
        stubUnitInsertWithIds();
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-4");
        artifact.setVersionNo(4);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(ARTIFACT_JSON);
        artifact.setContentHash("other");
        when(artifactService.get("art-4", 4)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-4", 4);

        ArgumentCaptor<ActScenarioUnit> unitCaptor = ArgumentCaptor.forClass(ActScenarioUnit.class);
        verify(unitMapper, org.mockito.Mockito.times(2)).insert(unitCaptor.capture());
        ActScenarioUnit first = unitCaptor.getAllValues().get(0);
        assertEquals("theme-1", first.getThemeId());
        assertEquals(2, first.getVersionNo());
        assertEquals(1, first.getSortOrder());
        assertEquals("开场接触：需求摸底", first.getUnitName());
        assertEquals("订单运营专员", first.getAudience());
        assertEquals(15, first.getTimeLimitMinutes());
        assertEquals(70, first.getPassScore());
        assertEquals(List.of("提出开放问题", "复述确认需求"), first.getBehaviorGoals());
        assertEquals(List.of("订单处理规范", "异常识别"), first.getIcebergElements());
        assertEquals(List.of("×未复核地址"), first.getCommonFailureModes());
        assertEquals(List.of("禁止跳过核对"), first.getForbiddenBehaviors());
        // dimension_weights 留档列
        assertTrue(first.getDimensionWeights().contains("dim-01"));

        ArgumentCaptor<ActCaseScript> caseCaptor = ArgumentCaptor.forClass(ActCaseScript.class);
        verify(caseScriptMapper).insert(caseCaptor.capture());
        ActCaseScript script = caseCaptor.getValue();
        assertEquals("unit-1", script.getScenarioUnitId());
        assertEquals(2, script.getVersionNo());
        assertEquals("高压催单：精准录入", script.getScriptName());
        assertEquals(2, script.getDifficulty());
        assertEquals("draft", script.getStatus());
        assertEquals("王敏", script.getActorName());
        assertEquals("运营主管", script.getActorPosition());
    }

    /**
     * 案例 userRole 三字段（2026-11-05）：case-gen v2 给了就落库。
     * 这里直接改 ARTIFACT_JSON 里的第一条案例，验证三列都写进了实体。
     */
    @Test
    void 确认成功_案例带userRole时三列落库() {
        stubSession();
        stubTheme();
        stubUnitInsertWithIds();
        String content = ARTIFACT_JSON.replace(
                "\"scriptOpening\":\"（拍桌）你们到底什么时候能处理？\"",
                "\"scriptOpening\":\"（拍桌）你们到底什么时候能处理？\","
                        + "\"userRoleName\":\"陈默\",\"userRolePosition\":\"订单专员\","
                        + "\"userRoleBackground\":\"负责该客户全部订单录入与发货协调\"");
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-7");
        artifact.setVersionNo(7);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(content);
        artifact.setContentHash("other");
        when(artifactService.get("art-7", 7)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-7", 7);

        ArgumentCaptor<ActCaseScript> caseCaptor = ArgumentCaptor.forClass(ActCaseScript.class);
        verify(caseScriptMapper).insert(caseCaptor.capture());
        ActCaseScript script = caseCaptor.getValue();
        assertEquals("陈默", script.getUserRoleName());
        assertEquals("订单专员", script.getUserRolePosition());
        assertEquals("负责该客户全部订单录入与发货协调", script.getUserRoleBackground());
    }

    /**
     * 老 artifact（case-gen v1）没有 userRole：三列必须是 null。
     * <b>尤其 userRoleBackground 不能被 scriptBackground 顶替</b>——那是场景背景，不是学员小传。
     */
    @Test
    void 确认成功_案例无userRole时三列为空且不拿scriptBackground顶替() {
        stubSession();
        stubTheme();
        stubUnitInsertWithIds();
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-8");
        artifact.setVersionNo(8);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(ARTIFACT_JSON);
        artifact.setContentHash("other");
        when(artifactService.get("art-8", 8)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-8", 8);

        ArgumentCaptor<ActCaseScript> caseCaptor = ArgumentCaptor.forClass(ActCaseScript.class);
        verify(caseScriptMapper).insert(caseCaptor.capture());
        ActCaseScript script = caseCaptor.getValue();
        assertNull(script.getUserRoleName());
        assertNull(script.getUserRolePosition());
        assertNull(script.getUserRoleBackground());
        // 反面证据：scriptBackground 确实有值，但没有被塞进 userRoleBackground
        assertEquals("背景", script.getScriptBackground());
    }

    @Test
    void 确认时同步主题画像真实值与会话绑定() {
        stubSession();
        stubTheme();
        stubUnitInsertWithIds();
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-5");
        artifact.setVersionNo(5);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(ARTIFACT_JSON);
        artifact.setContentHash("other");
        when(artifactService.get("art-5", 5)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-5", 5);

        ArgumentCaptor<ActTheme> themeCaptor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(themeCaptor.capture());
        assertEquals(1, themeCaptor.getValue().getCustomerProfileCount());
        assertEquals("sess-1", themeCaptor.getValue().getConversationId());
    }

    @Test
    void 已发布主题_确认创建新版本并立即发布() {
        stubSession();
        // 已发布主题：published=2，AI 确认 → 新版本 3
        ActTheme theme = new ActTheme();
        theme.setId("theme-1");
        theme.setCurrentVersionNo(2);
        theme.setWorkingVersionNo(2);
        theme.setPublishedVersionNo(2);
        when(themeMapper.selectById("theme-1")).thenReturn(theme);
        when(contentVersionService.startNewVersionFromPublished("theme-1", "admin")).thenReturn(3);
        stubUnitInsertWithIds();
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-6");
        artifact.setVersionNo(6);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(ARTIFACT_JSON);
        artifact.setContentHash("other");
        when(artifactService.get("art-6", 6)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-6", 6);

        // 内容写到新版本 3
        ArgumentCaptor<ActScenarioUnit> unitCaptor = ArgumentCaptor.forClass(ActScenarioUnit.class);
        verify(unitMapper, org.mockito.Mockito.times(2)).insert(unitCaptor.capture());
        assertEquals(3, unitCaptor.getAllValues().get(0).getVersionNo());
        // 立即发布：切指针 + 异步编译该版本剧本
        verify(contentVersionService).markPublished("theme-1", 3, "admin");
        verify(publishInitService).compileNewVersionAsync("theme-1", 3);
    }

    @Test
    void 客户画像落库字段与排序() {
        stubSession();
        stubTheme();
        stubUnitInsertWithIds();
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-6");
        artifact.setVersionNo(6);
        artifact.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        artifact.setContent(ARTIFACT_JSON);
        artifact.setContentHash("other");
        when(artifactService.get("art-6", 6)).thenReturn(artifact);
        when(artifactMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(materialMapper.selectList(any())).thenReturn(new ArrayList<>());

        confirmService.confirm("sess-1", "art-6", 6);

        ArgumentCaptor<ActCustomerProfile> captor = ArgumentCaptor.forClass(ActCustomerProfile.class);
        verify(profileMapper).insert(captor.capture());
        ActCustomerProfile profile = captor.getValue();
        assertEquals("价格敏感型采购经理", profile.getProfileType());
        assertEquals(2, profile.getVersionNo());
        assertEquals(1, profile.getSortOrder());
        assertEquals(List.of("务实"), profile.getPersonalities());
        assertEquals(List.of("价格"), profile.getDecisionFactors());
        assertEquals(List.of("预算紧"), profile.getPainPoints());
    }

    @Test
    void 会话未绑定主题时拒绝确认() {
        ActAiSession session = new ActAiSession();
        session.setId("sess-2");
        when(sessionService.requireSession("sess-2")).thenReturn(session);

        BizException error = assertThrows(BizException.class,
                () -> confirmService.confirm("sess-2", null, null));
        assertTrue(error.getMessage().contains("请先创建主题"));
        verify(artifactService, never()).findDisplay(anyString());
    }

    @Test
    void 无工作区产物时拒绝确认() {
        stubSession();
        when(artifactService.findDisplay("theme-1")).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> confirmService.confirm("sess-1", null, null));
        assertTrue(error.getMessage().contains("没有可确认的内容"));
    }
}