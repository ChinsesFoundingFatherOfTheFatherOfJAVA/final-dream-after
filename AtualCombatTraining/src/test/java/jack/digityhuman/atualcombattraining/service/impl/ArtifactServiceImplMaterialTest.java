package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.mapper.ActAiArtifactMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料「学员可见」开关单测（FP-42 前端 WS action 的服务侧实现）：
 * 定位兼容（id / fileServId / fileId）、可见性改写、补丁回传结构、非法入参拒绝。
 */
@ExtendWith(MockitoExtension.class)
class ArtifactServiceImplMaterialTest {

    @Mock
    private ActAiArtifactMapper artifactMapper;

    @InjectMocks
    private ArtifactServiceImpl artifactService;

    /** 工作区 artifact（两个材料：一个 id 命中、一个 fileServId 命中） */
    private static final String ARTIFACT_JSON = """
            {
              "materials": [
                {"id":"file-1","name":"订单运营部职能介绍.pptx","fileType":"pptx","fileSize":2638848,
                 "fileSizeUnit":"M","visibility":1,"fileServId":"srv-1","fileSource":2,"targetId":""},
                {"id":"file-2","name":"合规红线说明.pdf","fileType":"pdf","fileSize":102400,
                 "fileSizeUnit":"K","visibility":1,"fileServId":"srv-2","fileSource":2,"targetId":""}
              ],
              "scenarios": [],
              "customerProfiles": [],
              "icebergModel": {"visible":{"knowledge":[],"skills":[]},
                               "hidden":{"roles":[],"selfConcept":[],"personalities":[],"motives":[]}},
              "evaluationCriteria": {"passScore":70,"dimensions":[],"defaultDimensionWeights":[]},
              "professionalKnowledge": null,
              "productProfiles": [],
              "version": "v1"
            }""";

    private ActAiArtifact working;

    /** 注册实体元数据，保证 LambdaWrapper 在本类独立运行时也可用 */
    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        working = new ActAiArtifact();
        working.setId("art-1");
        working.setThemeId("theme-1");
        working.setVersionNo(1);
        working.setStatus(ArtifactServiceImpl.STATUS_WORKING);
        working.setContent(ARTIFACT_JSON);
        working.setContentHash(JsonSupport.digest(ARTIFACT_JSON));
        // ensureWorking → findWorking → selectOne；saveContent → update
        lenient().when(artifactMapper.selectOne(any())).thenReturn(working);
        lenient().when(artifactMapper.update(any(), any())).thenReturn(1);
    }

    /** 取落库内容里指定材料的 visibility */
    private Integer visibilityOf(String materialId) {
        ObjectNode root = JsonSupport.obj(working.getContent());
        for (var item : JsonSupport.children(root, "materials")) {
            if (item.isObject() && materialId.equals(JsonSupport.text((ObjectNode) item, "id"))) {
                return JsonSupport.integer((ObjectNode) item, "visibility");
            }
        }
        throw new AssertionError("未找到材料：" + materialId);
    }

    @Test
    void 关闭学员可见_按id定位并回传full_replace补丁() {
        ArtifactService.MaterialVisibilityResult result =
                artifactService.updateMaterialVisibility("theme-1", "file-1", 0);

        assertEquals("art-1", result.artifactId());
        assertEquals(1, result.versionNo());
        assertEquals("file-1", result.materialId());
        assertEquals(0, result.visibility());
        // 落库内容已改写：目标材料 0，未涉及的材料保持 1
        assertEquals(0, visibilityOf("file-1"));
        assertEquals(1, visibilityOf("file-2"));
        // 补丁结构与 Dify update_artifact 同构，前端可按 path 局部更新
        ArrayNode patches = result.patchContent();
        assertEquals(1, patches.size());
        ObjectNode patch = (ObjectNode) patches.get(0);
        assertEquals("full_replace", JsonSupport.text(patch, "op"));
        assertEquals("/materials", JsonSupport.text(patch, "path"));
        ArrayNode value = JsonSupport.children(patch, "value");
        assertEquals(2, value.size(), "补丁 value 为完整 materials 数组");
        assertEquals(0, JsonSupport.integer((ObjectNode) value.get(0), "visibility"));
        verify(artifactMapper).update(any(), any());
    }

    @Test
    void 开启学员可见_可见性改写为1() {
        ArtifactService.MaterialVisibilityResult result =
                artifactService.updateMaterialVisibility("theme-1", "file-2", 0);
        assertEquals(0, visibilityOf("file-2"));

        ArtifactService.MaterialVisibilityResult restored =
                artifactService.updateMaterialVisibility("theme-1", "file-2", 1);
        assertEquals(1, restored.visibility());
        assertEquals(1, visibilityOf("file-2"));
        assertTrue(JsonSupport.text((ObjectNode) restored.patchContent().get(0), "reason").contains("visibility"));
    }

    @Test
    void 兼容fileServId定位() {
        ArtifactService.MaterialVisibilityResult result =
                artifactService.updateMaterialVisibility("theme-1", "srv-2", 0);

        assertEquals("srv-2", result.materialId());
        assertEquals(0, visibilityOf("file-2"));
    }

    @Test
    void 材料不存在时拒绝() {
        BizException error = assertThrows(BizException.class,
                () -> artifactService.updateMaterialVisibility("theme-1", "missing-id", 1));
        assertTrue(error.getMessage().contains("材料不存在于当前产物"));
    }

    @Test
    void 可见性取值非法时拒绝() {
        BizException error = assertThrows(BizException.class,
                () -> artifactService.updateMaterialVisibility("theme-1", "file-1", 2));
        assertTrue(error.getMessage().contains("visibility 仅支持"));
        assertThrows(BizException.class,
                () -> artifactService.updateMaterialVisibility("theme-1", "file-1", null));
    }

    @Test
    void materialId为空时拒绝() {
        BizException error = assertThrows(BizException.class,
                () -> artifactService.updateMaterialVisibility("theme-1", "  ", 1));
        assertTrue(error.getMessage().contains("materialId 不能为空"));
    }

    /** 新增材料：以 id 为键追加到 materials 末尾 */
    @Test
    void 追加新材料() {
        ObjectNode material = JsonSupport.blankObject();
        JsonSupport.putText(material, "id", "file-3");
        JsonSupport.putText(material, "name", "新增规范.md");
        JsonSupport.putText(material, "fileType", "md");
        JsonSupport.putInt(material, "fileSize", 1024);
        JsonSupport.putText(material, "fileSizeUnit", "B");
        JsonSupport.putInt(material, "visibility", 1);
        JsonSupport.putInt(material, "fileSource", 2);

        int versionNo = artifactService.appendOrUpdateMaterial("theme-1", material);

        assertEquals(1, versionNo);
        ArrayNode materials = JsonSupport.children(JsonSupport.obj(working.getContent()), "materials");
        assertEquals(3, materials.size());
        assertEquals("file-3", JsonSupport.text((ObjectNode) materials.get(2), "id"));
    }

    /** 同 id 材料再次写入：原地替换，不追加 */
    @Test
    void 同id材料原地替换不追加() {
        ObjectNode material = JsonSupport.blankObject();
        JsonSupport.putText(material, "id", "file-1");
        JsonSupport.putText(material, "name", "订单运营部职能介绍.pptx（已解析）");
        JsonSupport.putInt(material, "visibility", 0);

        artifactService.appendOrUpdateMaterial("theme-1", material);

        ArrayNode materials = JsonSupport.children(JsonSupport.obj(working.getContent()), "materials");
        assertEquals(2, materials.size(), "不新增条目");
        ObjectNode first = (ObjectNode) materials.get(0);
        assertEquals("订单运营部职能介绍.pptx（已解析）", JsonSupport.text(first, "name"));
        assertEquals(0, JsonSupport.integer(first, "visibility"));
        assertEquals("file-2", JsonSupport.text((ObjectNode) materials.get(1), "id"), "顺序保持不变");
    }

    @Test
    void 材料缺少id时拒绝() {
        BizException error = assertThrows(BizException.class,
                () -> artifactService.appendOrUpdateMaterial("theme-1", JsonSupport.blankObject()));
        assertTrue(error.getMessage().contains("材料缺少 id"));
    }

    @Test
    void 空材料对象被拒绝() {
        BizException error = assertThrows(BizException.class,
                () -> artifactService.appendOrUpdateMaterial("theme-1", null));
        assertTrue(error.getMessage().contains("材料缺少 id"));
    }
}