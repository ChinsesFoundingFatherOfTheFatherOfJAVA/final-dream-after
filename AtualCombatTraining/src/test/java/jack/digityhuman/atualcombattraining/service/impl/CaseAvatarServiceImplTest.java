package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.req.CaseAvatarSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.CaseAvatarResp;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 案例形象设置单测（L0 上传覆盖写入侧，2026-11-05）。
 * <p>
 * 锁住的核心是<b>双写</b>与<b>抗确认冲掉</b>：
 * <ol>
 *   <li>写入后 artifact 的 {@code cases[].actorAvatarUrl} 有值；</li>
 *   <li>同时 {@code act_case_script.actor_avatar_url} 列被更新；</li>
 *   <li>补丁路径精确指向该案例（不是整体替换 scenarios）；</li>
 *   <li>清除覆盖 = 写 JSON null / 移除键（回落自动定图）；</li>
 *   <li>同名歧义、跨主题、找不到案例都要报错，不猜；</li>
 *   <li>案例尚未确认落库时列更新为 0，<b>不报错</b>（下次确认会带出来）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseAvatarServiceImplTest {

    private static final String THEME_ID = "theme-1";
    private static final String UNIT_NAME = "01：首次拜访";
    private static final String SCRIPT_NAME = "高压催单";

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private ActCaseScriptMapper caseScriptMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;

    @InjectMocks
    private CaseAvatarServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    // ------------------------------------------------------------------ fixtures

    /** 两个场景、每个一例；场景 1 的案例名叫「高压催单」 */
    private ObjectNode artifactRoot() {
        return JsonSupport.obj("""
                {"scenarios":[
                   {"name":"01：首次拜访","cases":[
                      {"scriptName":"高压催单","actorName":"王总",
                       "scriptBackground":"b","scriptOpening":"o"}]},
                   {"name":"02：续约谈判","cases":[
                      {"scriptName":"续约压价","actorName":"李总",
                       "scriptBackground":"b","scriptOpening":"o"}]}],
                 "materials":[]}
                """);
    }

    private ActAiArtifact artifact() {
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("artifact-1");
        artifact.setThemeId(THEME_ID);
        artifact.setVersionNo(1);
        artifact.setStatus("working");
        artifact.setContent(JsonSupport.toJson(artifactRoot()));
        return artifact;
    }

    private CaseAvatarSaveReq req(String scriptName, String scenarioName, String avatarUrl) {
        CaseAvatarSaveReq req = new CaseAvatarSaveReq();
        req.setScriptName(scriptName);
        req.setScenarioName(scenarioName);
        req.setAvatarUrl(avatarUrl);
        return req;
    }

    private void stubThemeAndArtifact() {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        when(artifactService.findWorking(anyString())).thenReturn(artifact());
        when(artifactService.findLatestConfirmed(anyString())).thenReturn(null);
        when(artifactService.applyFullReplacePatches(anyString(), any())).thenReturn(7);
    }

    // ------------------------------------------------------------------ 双写

    /** 写入后：artifact 补丁带上该值，且列被更新 */
    @Test
    void 设置形象_同时写artifact与列() {
        stubThemeAndArtifact();
        ActCaseScript row = new ActCaseScript();
        row.setId("case-1");
        row.setThemeId(THEME_ID);
        row.setScriptName(SCRIPT_NAME);
        row.setScenarioUnitId("unit-1");
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(row));
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId("unit-1");
        unit.setUnitName(UNIT_NAME);
        when(unitMapper.selectById("unit-1")).thenReturn(unit);

        CaseAvatarResp resp = service.saveAvatar(THEME_ID,
                req(SCRIPT_NAME, UNIT_NAME, "/avatar/m-A3-04.webp"));

        // 1) artifact 补丁：精确路径 + 正确的值
        ArgumentCaptor<ArrayNode> patchesCaptor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(artifactService).applyFullReplacePatches(anyString(), patchesCaptor.capture());
        ArrayNode patches = patchesCaptor.getValue();
        assertEquals(1, patches.size(), "应是单键补丁，不是整体替换 scenarios");
        ObjectNode patch = (ObjectNode) patches.get(0);
        assertEquals("full_replace", patch.path("op").asText());
        assertEquals("/scenarios/0/cases/0/actorAvatarUrl", patch.path("path").asText(),
                "场景 0 的案例 0 才是「高压催单」");
        assertEquals("/avatar/m-A3-04.webp", patch.path("value").asText());

        // 2) 列：按行 ID 更新 avatarUrl
        ArgumentCaptor<ActCaseScript> rowCaptor = ArgumentCaptor.forClass(ActCaseScript.class);
        verify(caseScriptMapper).updateById(rowCaptor.capture());
        assertEquals("case-1", rowCaptor.getValue().getId());
        assertEquals("/avatar/m-A3-04.webp", rowCaptor.getValue().getActorAvatarUrl());

        // 3) 响应回填实际命中的场景/案例
        assertEquals(1, resp.getColumnUpdated());
        assertEquals(UNIT_NAME, resp.getScenarioName());
        assertEquals(SCRIPT_NAME, resp.getScriptName());
        assertEquals(7, resp.getArtifactVersionNo());
    }

    /** 第二个场景的案例：补丁路径必须是 /scenarios/1/...，不能定位错场景 */
    @Test
    void 定位到第二个场景时补丁下标正确() {
        stubThemeAndArtifact();
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());

        service.saveAvatar(THEME_ID, req("续约压价", "02：续约谈判", "/avatar/f-A2-01.webp"));

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(artifactService).applyFullReplacePatches(anyString(), captor.capture());
        assertEquals("/scenarios/1/cases/0/actorAvatarUrl",
                ((ObjectNode) captor.getValue().get(0)).path("path").asText());
    }

    /** 中文/长 URL 原样写入（不做额外转义或改写） */
    @Test
    void url原样写入不做改写() {
        stubThemeAndArtifact();
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());

        service.saveAvatar(THEME_ID, req(SCRIPT_NAME, null, "https://cdn.example.com/a.webp?v=1"));

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(artifactService).applyFullReplacePatches(anyString(), captor.capture());
        assertEquals("https://cdn.example.com/a.webp?v=1",
                ((ObjectNode) captor.getValue().get(0)).path("value").asText());
    }

    // ------------------------------------------------------------------ 清除覆盖

    /** 空串/blank = 清除覆盖：写 JSON null（不写空串），发布时回落自动定图 */
    @Test
    void 空URL表示清除覆盖写作null() {
        stubThemeAndArtifact();
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());

        CaseAvatarResp resp = service.saveAvatar(THEME_ID, req(SCRIPT_NAME, null, "   "));

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(artifactService).applyFullReplacePatches(anyString(), captor.capture());
        ObjectNode patch = (ObjectNode) captor.getValue().get(0);
        assertTrue(patch.path("value").isNull(), "清除覆盖应写 JSON null，不是空串");
        assertEquals(null, resp.getAvatarUrl());
    }

    // ------------------------------------------------------------------ 不猜 / 不越权

    /** 同名案例分布在多个场景且未指定场景：报歧义，不猜 */
    @Test
    void 同名案例歧义时报错不猜() {
        stubThemeAndArtifact();
        ObjectNode root = JsonSupport.obj("""
                {"scenarios":[
                   {"name":"S1","cases":[{"scriptName":"同名案例"}]},
                   {"name":"S2","cases":[{"scriptName":"同名案例"}]}]}
                """);
        ActAiArtifact dup = artifact();
        dup.setContent(JsonSupport.toJson(root));
        when(artifactService.findWorking(anyString())).thenReturn(dup);

        BizException e = assertThrows(BizException.class,
                () -> service.saveAvatar(THEME_ID, req("同名案例", null, "/avatar/x.webp")));

        assertTrue(e.getMessage().contains("同名案例"), "错误信息要指明是哪个案例歧义");
        verify(artifactService, never()).applyFullReplacePatches(anyString(), any());
    }

    /** 指定场景后同名案例可正常定位（消歧生效） */
    @Test
    void 指定场景后同名案例可消歧() {
        stubThemeAndArtifact();
        ObjectNode root = JsonSupport.obj("""
                {"scenarios":[
                   {"name":"S1","cases":[{"scriptName":"同名案例"}]},
                   {"name":"S2","cases":[{"scriptName":"同名案例"}]}]}
                """);
        ActAiArtifact dup = artifact();
        dup.setContent(JsonSupport.toJson(root));
        when(artifactService.findWorking(anyString())).thenReturn(dup);
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());

        service.saveAvatar(THEME_ID, req("同名案例", "S2", "/avatar/x.webp"));

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(artifactService).applyFullReplacePatches(anyString(), captor.capture());
        assertEquals("/scenarios/1/cases/0/actorAvatarUrl",
                ((ObjectNode) captor.getValue().get(0)).path("path").asText());
    }

    /** 产物里没有这个案例：报错（不能"看起来成功"但什么都没改） */
    @Test
    void 案例不存在时报错() {
        stubThemeAndArtifact();

        BizException e = assertThrows(BizException.class,
                () -> service.saveAvatar(THEME_ID, req("不存在的案例", null, "/avatar/x.webp")));
        assertTrue(e.getMessage().contains("找不到该案例"));
        verify(artifactService, never()).applyFullReplacePatches(anyString(), any());
    }

    /** scriptId 跨主题：拒绝（避免改到别的主题的案例） */
    @Test
    void scriptId跨主题时拒绝() {
        stubThemeAndArtifact();
        ActCaseScript other = new ActCaseScript();
        other.setId("case-other");
        other.setThemeId("theme-2");
        other.setScriptName(SCRIPT_NAME);
        when(caseScriptMapper.selectById("case-other")).thenReturn(other);

        CaseAvatarSaveReq req = req(null, null, "/avatar/x.webp");
        req.setScriptId("case-other");

        BizException e = assertThrows(BizException.class, () -> service.saveAvatar(THEME_ID, req));
        assertTrue(e.getMessage().contains("不属于当前主题"));
    }

    /** scriptId 定位：先用行取 scriptName + 场景名，再回 artifact */
    @Test
    void scriptId定位到正确案例() {
        stubThemeAndArtifact();
        ActCaseScript row = new ActCaseScript();
        row.setId("case-9");
        row.setThemeId(THEME_ID);
        row.setScriptName("续约压价");
        row.setScenarioUnitId("unit-2");
        when(caseScriptMapper.selectById("case-9")).thenReturn(row);
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId("unit-2");
        unit.setUnitName("02：续约谈判");
        when(unitMapper.selectById("unit-2")).thenReturn(unit);
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(row));

        CaseAvatarSaveReq req = new CaseAvatarSaveReq();
        req.setScriptId("case-9");
        req.setAvatarUrl("/avatar/f-A2-01.webp");

        service.saveAvatar(THEME_ID, req);

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(artifactService).applyFullReplacePatches(anyString(), captor.capture());
        assertEquals("/scenarios/1/cases/0/actorAvatarUrl",
                ((ObjectNode) captor.getValue().get(0)).path("path").asText());
    }

    // ------------------------------------------------------------------ 尚未落库

    /** 案例尚未确认落库：列更新 0 行，但 artifact 已写，不报错（下次确认会带出来） */
    @Test
    void 案例尚未落库时列更新0且不报错() {
        stubThemeAndArtifact();
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());

        CaseAvatarResp resp = service.saveAvatar(THEME_ID,
                req(SCRIPT_NAME, null, "/avatar/m-A3-04.webp"));

        assertEquals(0, resp.getColumnUpdated(), "尚未落库时应为 0");
        assertNotNull(resp.getAvatarUrl(), "artifact 写入仍然成功");
        verify(caseScriptMapper, never()).updateById(any(ActCaseScript.class));
        verify(artifactService).applyFullReplacePatches(anyString(), any());
    }

    // ------------------------------------------------------------------ 入参校验

    /** 既没有 scriptName 也没有 scriptId：直接拒绝（无法定位） */
    @Test
    void 缺定位键时报错() {
        // 主题必须存在：否则先命中"主题不存在"，测不到定位键校验这一层
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        CaseAvatarSaveReq req = req(null, null, "/avatar/x.webp");

        BizException e = assertThrows(BizException.class,
                () -> service.saveAvatar(THEME_ID, req));
        assertTrue(e.getMessage().contains("scriptName") && e.getMessage().contains("scriptId"));
    }

    /** 主题不存在：拒绝 */
    @Test
    void 主题不存在时报错() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.saveAvatar(THEME_ID, req(SCRIPT_NAME, null, "/avatar/x.webp")));
        assertTrue(e.getMessage().contains("主题不存在"));
    }

    /** 产物还没有内容：拒绝（不能凭空建一条形象） */
    @Test
    void 无产物时报错() {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        when(artifactService.findWorking(anyString())).thenReturn(null);
        when(artifactService.findLatestConfirmed(anyString())).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.saveAvatar(THEME_ID, req(SCRIPT_NAME, null, "/avatar/x.webp")));
        assertTrue(e.getMessage().contains("无法设置形象"));
    }

    /** 超长 URL 截断到列宽 512，不因超长导致落库失败 */
    @Test
    void 超长URL截断到列宽() {
        stubThemeAndArtifact();
        ActCaseScript row = new ActCaseScript();
        row.setId("case-1");
        row.setThemeId(THEME_ID);
        row.setScriptName(SCRIPT_NAME);
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(row));
        String longUrl = "https://cdn.example.com/" + "a".repeat(600);

        CaseAvatarResp resp = service.saveAvatar(THEME_ID, req(SCRIPT_NAME, null, longUrl));

        assertEquals(512, resp.getAvatarUrl().length());
        assertFalse(resp.getAvatarUrl().isEmpty());
    }
}
