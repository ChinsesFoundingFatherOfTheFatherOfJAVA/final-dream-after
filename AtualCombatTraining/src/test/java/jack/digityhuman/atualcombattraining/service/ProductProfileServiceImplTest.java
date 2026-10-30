package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.ProductProfileService.ProductProfileGenerateReq;
import jack.digityhuman.atualcombattraining.service.ProductProfileService.ProductProfileUpsertReq;
import jack.digityhuman.atualcombattraining.service.impl.ProductProfileServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产品档案服务单测（契约 09）
 * - CRUD：v0 工作区隔离；发布快照行不可编辑；profileKey 服务端生成且不可变
 * - 删除：被场景引用拒绝并带场景名
 * - 关联：keys 必须存在；覆盖式替换
 * - 学员只读：发布快照 + 参与关系校验
 * - 候选生成：材料上限 5；候选不落库
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductProfileServiceImplTest {

    private static final String THEME_ID = "theme-1";
    private static final String PROFILE_ID = "profile-1";
    private static final String SCENARIO_ID = "sc-1";
    private static final String USER_ID = "user-1";

    @Mock
    private ActProductProfileMapper profileMapper;
    @Mock
    private ActScenarioProductProfileMapper scenarioProfileMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActThemeParticipantMapper participantMapper;
    @Mock
    private NativeWorkflowClient nativeWorkflowClient;

    private ProductProfileServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private ProductProfileServiceImpl service() {
        if (service == null) {
            service = new ProductProfileServiceImpl(profileMapper, scenarioProfileMapper,
                    unitMapper, participantMapper, nativeWorkflowClient);
        }
        return service;
    }

    private ProductProfileUpsertReq req(String name) {
        return new ProductProfileUpsertReq(name, "分类", "描述",
                "## 概述", "[{\"key\":\"品牌\",\"value\":\"杰克\",\"sortOrder\":1}]");
    }

    private ActProductProfile workingProfile() {
        ActProductProfile profile = new ActProductProfile();
        profile.setId(PROFILE_ID);
        profile.setThemeId(THEME_ID);
        profile.setContentVersionNo(0);
        profile.setProfileKey("pp_abcd123456");
        profile.setProfileName("成套智联2.0");
        profile.setCustomFields("[]");
        return profile;
    }

    @Test
    void 创建_生成profileKey并写入v0() {
        when(profileMapper.selectCount(any())).thenReturn(0L);

        ActProductProfile created = service().create(THEME_ID, req("新产品"));

        assertTrue(created.getProfileKey().startsWith("pp_"));
        assertTrue(created.getProfileKey().length() >= "pp_".length() + 8);
        assertEquals(0, created.getContentVersionNo());
        ArgumentCaptor<ActProductProfile> captor = ArgumentCaptor.forClass(ActProductProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertEquals("新产品", captor.getValue().getProfileName());
    }

    @Test
    void 创建_超过20个上限_拒绝() {
        when(profileMapper.selectCount(any())).thenReturn(20L);

        BizException e = assertThrows(BizException.class,
                () -> service().create(THEME_ID, req("第21个")));
        assertTrue(e.getMessage().contains("最多 20 个"));
    }

    @Test
    void 更新_发布快照行_拒绝编辑() {
        ActProductProfile published = workingProfile();
        published.setContentVersionNo(1);
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(published);

        assertThrows(BizException.class,
                () -> service().update(THEME_ID, PROFILE_ID, req("改名")));
        verify(profileMapper, never()).updateById(any(ActProductProfile.class));
    }

    @Test
    void 更新_工作区行_名称可改但profileKey不变() {
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(workingProfile());

        ActProductProfile updated = service().update(THEME_ID, PROFILE_ID, req("改名不改键"));

        assertEquals("改名不改键", updated.getProfileName());
        assertEquals("pp_abcd123456", updated.getProfileKey());
    }

    @Test
    void 删除_被场景引用_拒绝并带场景名() {
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(workingProfile());
        ActScenarioProductProfile link = new ActScenarioProductProfile();
        link.setScenarioUnitId(SCENARIO_ID);
        link.setProductProfileId(PROFILE_ID);
        when(scenarioProfileMapper.selectList(any())).thenReturn(List.of(link));
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(SCENARIO_ID);
        unit.setUnitName("初次拜访");
        when(unitMapper.selectById(SCENARIO_ID)).thenReturn(unit);

        BizException e = assertThrows(BizException.class,
                () -> service().delete(THEME_ID, PROFILE_ID));
        assertTrue(e.getMessage().contains("初次拜访"));
    }

    @Test
    void 关联_keys必须存在于工作区() {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(SCENARIO_ID);
        unit.setThemeId(THEME_ID);
        when(unitMapper.selectById(SCENARIO_ID)).thenReturn(unit);
        when(profileMapper.selectList(any())).thenReturn(List.of(workingProfile()));

        assertThrows(BizException.class,
                () -> service().linkScenario(THEME_ID, SCENARIO_ID, List.of("pp_not_exist")));
    }

    @Test
    void 关联_覆盖式替换() {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(SCENARIO_ID);
        unit.setThemeId(THEME_ID);
        when(unitMapper.selectById(SCENARIO_ID)).thenReturn(unit);
        when(profileMapper.selectList(any())).thenReturn(List.of(workingProfile()));

        service().linkScenario(THEME_ID, SCENARIO_ID, List.of("pp_abcd123456"));

        verify(scenarioProfileMapper).delete(any());
        ArgumentCaptor<ActScenarioProductProfile> captor =
                ArgumentCaptor.forClass(ActScenarioProductProfile.class);
        verify(scenarioProfileMapper).insert(captor.capture());
        assertEquals(PROFILE_ID, captor.getValue().getProductProfileId());
        assertEquals(1, captor.getValue().getSortOrder());
    }

    @Test
    void 学员只读_工作区行_拒绝() {
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(workingProfile());

        assertThrows(BizException.class, () -> service().studentProfile(USER_ID, PROFILE_ID));
    }

    @Test
    void 学员只读_跨主题学员_拒绝() {
        ActProductProfile published = workingProfile();
        published.setContentVersionNo(1);
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(published);
        // 无参与关系
        when(participantMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service().studentProfile(USER_ID, PROFILE_ID));
    }

    @Test
    void 学员只读_发布快照与参与关系齐备_返回() {
        ActProductProfile published = workingProfile();
        published.setContentVersionNo(1);
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(published);
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setThemeId(THEME_ID);
        participant.setUserId(USER_ID);
        participant.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(participant);

        ActProductProfile result = service().studentProfile(USER_ID, PROFILE_ID);

        assertEquals("成套智联2.0", result.getProfileName());
    }

    @Test
    void 候选生成_材料超5个_拒绝() {
        assertThrows(BizException.class, () -> service().generateCandidates(THEME_ID,
                new ProductProfileGenerateReq(
                        List.of("m1", "m2", "m3", "m4", "m5", "m6"), "提示", "要求", List.of())));
        verify(nativeWorkflowClient, never()).blocking(anyString(), anyString(), anyString(),
                any(), anyInt());
    }

    @Test
    void 候选生成_返回候选且不落库() {
        // blocking() 契约：直接返回 data.outputs 节点
        ObjectNode outputs = JsonSupport.blankObject();
        outputs.put("profiles_json", """
                [{"profileKey":"pp_candidate_1","profileName":"候选1","source":{"type":"ai_generated"}}]
                """);
        when(nativeWorkflowClient.blocking(eq("product-profile-gen"), eq(THEME_ID), anyString(),
                any(), anyInt())).thenReturn(outputs);

        List<Map<String, Object>> candidates = service().generateCandidates(THEME_ID,
                new ProductProfileGenerateReq(List.of("m1"), "成套智联", "突出一体化", List.of("品牌")));

        assertEquals(1, candidates.size());
        assertEquals("候选1", candidates.get(0).get("profileName"));
        // 候选不落库
        verify(profileMapper, never()).insert(any(ActProductProfile.class));
    }
}
