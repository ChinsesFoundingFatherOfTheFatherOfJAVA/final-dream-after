package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCustomerProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.impl.ContentVersionServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布流程复制产品档案与场景关联回归测试（契约 09 §4.2/§4.3）。
 * <p>
 * 病根：act_product_profile 用 content_version_no 区分工作区(0)与发布快照(&gt;0)，
 * 学员端只读 &gt;0 的行；但原发布流程只复制场景/维度/案例/客户画像/材料，
 * <b>从未复制产品档案</b>，导致已发布主题的学员端产品档案永远为空。
 * <p>
 * 这里锁四件事：
 * <ol>
 *   <li>首次发布 v0 → v1：档案字段逐字段一致，且新行 content_version_no=1；</li>
 *   <li>场景关联按 sort_order 从源单元 ID 重挂到目标单元 ID（关联表无版本列，靠单元 ID 归版本）；</li>
 *   <li>无档案时不报错、不建关联；</li>
 *   <li>重复发布幂等：先删目标版本行再插，不重复堆积。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentVersionProductProfileSnapshotTest {

    private static final String THEME_ID = "theme-pp-001";

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeVersionMapper versionMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActEvaluationDimensionMapper dimensionMapper;
    @Mock
    private ActCaseScriptMapper caseScriptMapper;
    @Mock
    private ActCustomerProfileMapper profileMapper;
    @Mock
    private ActProductProfileMapper productProfileMapper;
    @Mock
    private ActScenarioProductProfileMapper scenarioProductProfileMapper;
    @Mock
    private ActAiMaterialMapper materialMapper;
    @Mock
    private ActContentVersionMaterialMapper materialSnapshotMapper;
    @Mock
    private ScenarioUnitService scenarioUnitService;

    @InjectMocks
    private ContentVersionServiceImpl contentVersionService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    /**
     * 模拟 MyBatis-Plus 的 insert 回填雪花 ID。
     * <p>
     * 真实 insert 会把生成的 ID 写回实体；Mockito 的桩只返回 1，不回填。
     * 不回填的话 copyProductProfiles 里"源档案 ID → 新档案 ID"的映射拿到的全是 null，
     * 关联重挂会被判成脏数据而全部跳过——测试就会假失败。
     */
    private void stubProductProfileInsertWithBackfill(java.util.function.Function<ActProductProfile, String> idOf) {
        when(productProfileMapper.insert(any(ActProductProfile.class))).thenAnswer(invocation -> {
            ActProductProfile row = invocation.getArgument(0);
            row.setId(idOf.apply(row));
            return 1;
        });
    }

    // ------------------------------------------------------------------ fixtures

    private ActTheme theme(Integer published, Integer working) {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setThemeName("柔性智造销售话术训练");
        theme.setStatus(3);
        theme.setPublishedVersionNo(published);
        theme.setWorkingVersionNo(working);
        return theme;
    }

    private ActThemeVersion version(int no) {
        ActThemeVersion version = new ActThemeVersion();
        version.setThemeId(THEME_ID);
        version.setVersionNo(no);
        return version;
    }

    /** 工作区产品档案（content_version_no=0） */
    private ActProductProfile workspaceProfile(String id, String key, String name, int sortOrder) {
        ActProductProfile profile = new ActProductProfile();
        profile.setId(id);
        profile.setThemeId(THEME_ID);
        profile.setContentVersionNo(0);
        profile.setProfileKey(key);
        profile.setProfileName(name);
        profile.setCategory("平台类");
        profile.setDescription("一体化智能平台");
        profile.setDetailMarkdown("# 产品详情");
        profile.setCustomFields("[{\"key\":\"交付周期\",\"value\":\"6周\",\"sortOrder\":1}]");
        profile.setSourceArtifactId("artifact-1");
        profile.setSourceArtifactVersion(2);
        profile.setSortOrder(sortOrder);
        profile.setCreatedBy("admin");
        return profile;
    }

    private ActScenarioUnit unit(String id, int versionNo, int sortOrder, String name) {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(id);
        unit.setThemeId(THEME_ID);
        unit.setVersionNo(versionNo);
        unit.setSortOrder(sortOrder);
        unit.setUnitName(name);
        return unit;
    }

    private ActScenarioProductProfile link(String unitId, String profileId, int sortOrder) {
        ActScenarioProductProfile link = new ActScenarioProductProfile();
        link.setScenarioUnitId(unitId);
        link.setProductProfileId(profileId);
        link.setSortOrder(sortOrder);
        return link;
    }

    private ActAiMaterial material(String id) {
        ActAiMaterial material = new ActAiMaterial();
        material.setId(id);
        material.setThemeId(THEME_ID);
        material.setFileId("f10072e4.pdf");
        material.setName("solution.pdf");
        material.setFileType("pdf");
        material.setVisibility(1);
        return material;
    }

    /** 工作区案例行（含形象三列，2026-11-05） */
    private ActCaseScript caseRow(String id) {
        ActCaseScript row = new ActCaseScript();
        row.setId(id);
        row.setThemeId(THEME_ID);
        row.setVersionNo(0);
        row.setScenarioUnitId("unit-v0-1");
        row.setScriptName("01：首次拜访-价格异议");
        row.setDifficulty(2);
        row.setActorName("王总");
        row.setActorPosition("企业中层管理者");
        row.setActorGender("男");
        row.setActorAgeRange("40-45岁");
        row.setActorAvatarUrl("/avatar/admin-uploaded.png");
        row.setUserRoleName("陈默");
        row.setUserRolePosition("大客户销售");
        row.setUserRoleBackground("入职两年的销售");
        return row;
    }

    // ------------------------------------------------------------------ 首次发布

    /**
     * 首次发布：v0 的两个档案复制成 v1 两行，字段逐字段一致、新 ID、content_version_no=1。
     */
    @Test
    void 首次发布_产品档案从v0复制到v1且字段一致() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null, 0));
        when(versionMapper.selectOne(any())).thenReturn(version(0));
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectList(any())).thenReturn(List.of(
                workspaceProfile("pp-old-1", "pp_aaa111", "成套智联2.0智能化平台", 1),
                workspaceProfile("pp-old-2", "pp_bbb222", "云端运维平台", 2)));
        stubProductProfileInsertWithBackfill(profile -> "pp-new-" + profile.getProfileKey());
        // copyProductProfiles 会查两次单元表：先目标版本 v1，再源版本 v0
        when(unitMapper.selectList(any())).thenReturn(
                List.of(unit("unit-v1-1", 1, 1, "01：首次拜访")),
                List.of(unit("unit-v0-1", 0, 1, "01：首次拜访")));
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of(
                link("unit-v0-1", "pp-old-1", 1)));
        when(materialMapper.selectList(any())).thenReturn(List.of(material("m-1")));

        contentVersionService.firstPublish(THEME_ID, "admin");

        ArgumentCaptor<ActProductProfile> captor = ArgumentCaptor.forClass(ActProductProfile.class);
        verify(productProfileMapper, times(2)).insert(captor.capture());
        List<ActProductProfile> copies = captor.getAllValues();

        // 数量与版本号
        assertEquals(2, copies.size());
        copies.forEach(copy -> {
            assertEquals(THEME_ID, copy.getThemeId());
            assertEquals(1, copy.getContentVersionNo(), "必须落在 v1 快照行");
            assertNotEquals("pp-old-1", copy.getId());
            assertNotEquals("pp-old-2", copy.getId());
        });

        // 字段逐字段一致（契约 09 §3 的完整映射）
        ActProductProfile first = copies.get(0);
        assertEquals("pp_aaa111", first.getProfileKey());
        assertEquals("成套智联2.0智能化平台", first.getProfileName());
        assertEquals("平台类", first.getCategory());
        assertEquals("一体化智能平台", first.getDescription());
        assertEquals("# 产品详情", first.getDetailMarkdown());
        assertEquals("[{\"key\":\"交付周期\",\"value\":\"6周\",\"sortOrder\":1}]", first.getCustomFields());
        assertEquals("artifact-1", first.getSourceArtifactId());
        assertEquals(2, first.getSourceArtifactVersion());
        assertEquals(1, first.getSortOrder());
        assertEquals("admin", first.getCreatedBy());

        // 场景关联重挂：源单元 ID → 目标单元 ID，且指向新档案行
        ArgumentCaptor<ActScenarioProductProfile> linkCaptor =
                ArgumentCaptor.forClass(ActScenarioProductProfile.class);
        verify(scenarioProductProfileMapper).insert(linkCaptor.capture());
        ActScenarioProductProfile relinked = linkCaptor.getValue();
        assertEquals("unit-v1-1", relinked.getScenarioUnitId());
        assertEquals("pp-new-pp_aaa111", relinked.getProductProfileId());
        assertEquals(1, relinked.getSortOrder());
    }

    /** 场景关联的重挂依据是 sort_order：v1 单元行 ID 与 v0 不同，靠序号对齐 */
    @Test
    void 场景关联_按sortOrder从源单元重挂到目标单元() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null, 0));
        when(versionMapper.selectOne(any())).thenReturn(version(0));
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectList(any()))
                .thenReturn(List.of(workspaceProfile("pp-old-1", "pp_aaa111", "平台A", 1)));
        stubProductProfileInsertWithBackfill(profile -> "pp-new-" + profile.getProfileKey());
        // 先目标版本 v1 的两个单元（新 ID），再源版本 v0 的两个单元（旧 ID）
        when(unitMapper.selectList(any())).thenReturn(
                List.of(unit("unit-v1-1", 1, 1, "01：首次拜访"),
                        unit("unit-v1-2", 1, 2, "02：方案汇报")),
                List.of(unit("unit-v0-1", 0, 1, "01：首次拜访"),
                        unit("unit-v0-2", 0, 2, "02：方案汇报")));
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of(
                link("unit-v0-2", "pp-old-1", 1)));
        when(materialMapper.selectList(any())).thenReturn(List.of());

        contentVersionService.firstPublish(THEME_ID, "admin");

        ArgumentCaptor<ActScenarioProductProfile> captor =
                ArgumentCaptor.forClass(ActScenarioProductProfile.class);
        verify(scenarioProductProfileMapper).insert(captor.capture());
        // 源关联挂在 sort_order=2 的单元上 → 必须重挂到目标版本 sort_order=2 的单元
        assertEquals("unit-v1-2", captor.getValue().getScenarioUnitId());
    }

    // ------------------------------------------------------------------ 空态与幂等

    /** 无产品档案：不报错、不插档案行、不建关联（合法状态，不是异常） */
    @Test
    void 无产品档案_不报错且不建关联() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null, 0));
        when(versionMapper.selectOne(any())).thenReturn(version(0));
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectList(any())).thenReturn(List.of());
        when(unitMapper.selectList(any())).thenReturn(List.of());
        when(materialMapper.selectList(any())).thenReturn(List.of());

        contentVersionService.firstPublish(THEME_ID, "admin");

        verify(productProfileMapper, never()).insert(any(ActProductProfile.class));
        verify(scenarioProductProfileMapper, never()).insert(any(ActScenarioProductProfile.class));
    }

    /** 幂等：写目标版本行之前一定先删该版本旧行（重复发布不撞唯一键、不堆积） */
    @Test
    void 幂等_先删目标版本行再插() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null, 0));
        when(versionMapper.selectOne(any())).thenReturn(version(0));
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectList(any()))
                .thenReturn(List.of(workspaceProfile("pp-old-1", "pp_aaa111", "平台A", 1)));
        stubProductProfileInsertWithBackfill(profile -> "pp-new-" + profile.getProfileKey());
        when(unitMapper.selectList(any())).thenReturn(
                List.of(unit("unit-v1-1", 1, 1, "01：首次拜访")),
                List.of(unit("unit-v0-1", 0, 1, "01：首次拜访")));
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of());
        when(materialMapper.selectList(any())).thenReturn(List.of());

        contentVersionService.firstPublish(THEME_ID, "admin");

        // 档案表删除：按 theme + 目标版本；关联表删除：按目标单元 ID 集合
        verify(productProfileMapper).delete(any());
        verify(scenarioProductProfileMapper).delete(any());
        verify(productProfileMapper).insert(any(ActProductProfile.class));
    }

    // ------------------------------------------------------------------ markPublished（AI 确认升版）

    /** 已发布主题再次确认：新版本同样要固化产品档案，源是工作区 v0 */
    @Test
    void 已发布后确认升版_产品档案固化为目标版本行() {
        when(versionMapper.selectOne(any())).thenReturn(version(2));
        when(productProfileMapper.selectList(any()))
                .thenReturn(List.of(workspaceProfile("pp-old-1", "pp_aaa111", "平台A", 1)));
        stubProductProfileInsertWithBackfill(profile -> "pp-new-" + profile.getProfileKey());
        when(unitMapper.selectList(any())).thenReturn(
                List.of(unit("unit-v2-1", 2, 1, "01：首次拜访")),
                List.of(unit("unit-v0-1", 0, 1, "01：首次拜访")));
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of(
                link("unit-v0-1", "pp-old-1", 1)));
        when(materialMapper.selectList(any())).thenReturn(List.of(material("m-1")));

        contentVersionService.markPublished(THEME_ID, 2, "admin");

        ArgumentCaptor<ActProductProfile> captor = ArgumentCaptor.forClass(ActProductProfile.class);
        verify(productProfileMapper).insert(captor.capture());
        ActProductProfile copy = captor.getValue();
        assertEquals(2, copy.getContentVersionNo(), "必须落在 v2 快照行");
        assertEquals("pp_aaa111", copy.getProfileKey());
        assertNotNull(copy.getId());

        ArgumentCaptor<ActScenarioProductProfile> linkCaptor =
                ArgumentCaptor.forClass(ActScenarioProductProfile.class);
        verify(scenarioProductProfileMapper).insert(linkCaptor.capture());
        assertEquals("unit-v2-1", linkCaptor.getValue().getScenarioUnitId());
    }

    /** 老数据兼容：关联指向的档案不在源版本里 → 跳过该关联，不凭空造档案 */
    @Test
    void 脏关联指向不存在的档案_跳过不造假() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null, 0));
        when(versionMapper.selectOne(any())).thenReturn(version(0));
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectList(any()))
                .thenReturn(List.of(workspaceProfile("pp-old-1", "pp_aaa111", "平台A", 1)));
        stubProductProfileInsertWithBackfill(profile -> "pp-new-" + profile.getProfileKey());
        when(unitMapper.selectList(any())).thenReturn(
                List.of(unit("unit-v1-1", 1, 1, "01：首次拜访")),
                List.of(unit("unit-v0-1", 0, 1, "01：首次拜访")));
        // 关联指向 pp-missing，源版本里没有这个档案
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of(
                link("unit-v0-1", "pp-missing", 1)));
        when(materialMapper.selectList(any())).thenReturn(List.of());

        contentVersionService.firstPublish(THEME_ID, "admin");

        verify(scenarioProductProfileMapper, never()).insert(any(ActScenarioProductProfile.class));
        // 档案本身照常复制（脏数据只影响那一条关联）
        verify(productProfileMapper).insert(any(ActProductProfile.class));
    }

    // -------------------------------------------------- 案例形象三列（升版复制）

    /**
     * 升版复制案例行必须带上形象三列（2026-11-05）。
     * <p>
     * <b>这是刚踩过的坑</b>：{@code ContentVersionServiceImpl.copyOf} 是逐字段 setter 复制，
     * 新增列不显式加一行就会静默丢失——新版本案例丢掉性别/年龄 → 定图降级到 L3/L4（换脸），
     * 上传覆盖（L0）也随之失效。所以这里锁死三列必须逐字段相等。
     */
    @Test
    void 升版复制案例行带上形象与属性三列() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null, 0));
        when(versionMapper.selectOne(any())).thenReturn(version(0));
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectList(any())).thenReturn(List.of());
        when(materialMapper.selectList(any())).thenReturn(List.of());
        when(unitMapper.selectList(any())).thenReturn(
                List.of(unit("unit-v1-1", 1, 1, "01：首次拜访")),
                List.of(unit("unit-v0-1", 0, 1, "01：首次拜访")));
        // copyCases 按版本取样：源 v0 行 → 目标 v1 行
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(caseRow("case-old-1")));

        contentVersionService.firstPublish(THEME_ID, "admin");

        ArgumentCaptor<ActCaseScript> captor = ArgumentCaptor.forClass(ActCaseScript.class);
        verify(caseScriptMapper, times(1)).insert(captor.capture());
        ActCaseScript copy = captor.getValue();
        assertEquals(1, copy.getVersionNo());
        assertEquals("draft", copy.getStatus(), "新版本案例必须重置为待编译");
        // 形象三列逐字段相等（漏一列 = 新版本换脸 / 上传覆盖失效）
        assertEquals("男", copy.getActorGender());
        assertEquals("40-45岁", copy.getActorAgeRange());
        assertEquals("/avatar/admin-uploaded.png", copy.getActorAvatarUrl());
        // userRole 三列同样不能丢
        assertEquals("陈默", copy.getUserRoleName());
        assertEquals("大客户销售", copy.getUserRolePosition());
        assertEquals("入职两年的销售", copy.getUserRoleBackground());
    }
}
