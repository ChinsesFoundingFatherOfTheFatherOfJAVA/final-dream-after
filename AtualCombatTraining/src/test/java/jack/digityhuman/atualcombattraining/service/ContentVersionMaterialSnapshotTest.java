package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCustomerProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布材料快照回归测试（契约 11 §8）。
 * <p>
 * 病根：材料原本只存在主题级 act_ai_material，发布时不写 act_content_version_material，
 * 学员端「学习资料」与材料查看页因此永远空白。这里固定"发布必须把学员可见材料固化成快照"。
 */
@ExtendWith(MockitoExtension.class)
class ContentVersionMaterialSnapshotTest {

    private static final String THEME_ID = "theme-material-001";

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
    private ActAiMaterialMapper materialMapper;
    @Mock
    private ActContentVersionMaterialMapper materialSnapshotMapper;
    @Mock
    private ScenarioUnitService scenarioUnitService;

    @InjectMocks
    private ContentVersionServiceImpl contentVersionService;

    /** LambdaWrapper 需要实体 TableInfo，单跑本类时也要显式注册 */
    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    @Test
    void publishCopiesStudentVisibleMaterialsIntoSnapshot() {
        when(versionMapper.selectOne(any())).thenReturn(publishedVersion(1));

        ActAiMaterial localPdf = material("m-local", "f10072e4.pdf", "solution.pdf", 1, "p-preview");
        // mock 不会执行 query wrapper 的 visibility 过滤，这里只返回「学员可见」这一行，
        // 与真实库行为一致；过滤条件本身由 copyMaterials 的 LambdaQueryWrapper 保证。
        when(materialMapper.selectList(any())).thenReturn(List.of(localPdf));

        contentVersionService.markPublished(THEME_ID, 1, "admin");

        ArgumentCaptor<ActContentVersionMaterial> captor =
                ArgumentCaptor.forClass(ActContentVersionMaterial.class);
        verify(materialSnapshotMapper).insert(captor.capture());
        ActContentVersionMaterial snapshot = captor.getValue();
        assertEquals("m-local", snapshot.getMaterialId());
        assertEquals(1, snapshot.getContentVersionNo());
        assertEquals("student", snapshot.getVisibility());
        // 已转换 PDF → 版式预览；没有 previewFileId 的材料走文本回退
        assertEquals("pdf", snapshot.getRenderMode());
        assertEquals("act_local", snapshot.getSourceType());
    }

    private jack.digityhuman.atualcombattraining.entity.ActThemeVersion publishedVersion(int no) {
        var version = new jack.digityhuman.atualcombattraining.entity.ActThemeVersion();
        version.setThemeId(THEME_ID);
        version.setVersionNo(no);
        return version;
    }

    private ActAiMaterial material(String id, String fileId, String name, int visibility, String previewFileId) {
        ActAiMaterial row = new ActAiMaterial();
        row.setId(id);
        row.setThemeId(THEME_ID);
        row.setFileId(fileId);
        row.setName(name);
        row.setFileType("pdf");
        row.setVisibility(visibility);
        row.setPreviewFileId(previewFileId);
        return row;
    }
}
