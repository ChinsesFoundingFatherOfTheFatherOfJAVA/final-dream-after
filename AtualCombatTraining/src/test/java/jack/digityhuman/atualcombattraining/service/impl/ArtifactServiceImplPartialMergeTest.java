package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.mapper.ActAiArtifactMapper;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 中间产物增量合并单测（#5 渐次出现）：只覆盖给定字段、其余保留、materials 永不被工作流覆盖。
 */
@ExtendWith(MockitoExtension.class)
class ArtifactServiceImplPartialMergeTest {

    @Mock
    private ActAiArtifactMapper artifactMapper;

    @InjectMocks
    private ArtifactServiceImpl artifactService;

    /** 已有工作区内容：材料 + 1 个场景 + 1 个画像（模拟"上一轮/上一段的产物"） */
    private static final String ARTIFACT_JSON = """
            {
              "materials": [{"id":"file-1","name":"材料.pptx","fileType":"pptx","visibility":1}],
              "scenarios": [{"id":"sc-old","name":"旧场景","cases":[]}],
              "customerProfiles": [{"id":"cp-old","type":"旧画像"}],
              "icebergModel": {"visible":{"knowledge":["旧知识"],"skills":[]},
                               "hidden":{"roles":[],"selfConcept":[],"personalities":[],"motives":[]}},
              "evaluationCriteria": {"passScore":70,"dimensions":[],"defaultDimensionWeights":[]},
              "professionalKnowledge": null,
              "productProfiles": [],
              "version": "v1"
            }""";

    private ActAiArtifact working;

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
        lenient().when(artifactMapper.selectOne(any())).thenReturn(working);
        lenient().when(artifactMapper.update(any(), any())).thenReturn(1);
    }

    /** 当前落库内容 */
    private ObjectNode stored() {
        return JsonSupport.obj(working.getContent());
    }

    @Test
    void 场景段中间产物只覆盖scenarios其余保留() {
        ObjectNode partial = JsonSupport.blankObject();
        ArrayNode scenarios = JsonSupport.blankArray();
        ObjectNode scenario = JsonSupport.blankObject();
        JsonSupport.putText(scenario, "id", "sc-01");
        JsonSupport.putText(scenario, "name", "开场接触：需求摸底");
        scenario.set("dimensionWeights", JsonSupport.blankArray());
        scenarios.add(scenario);
        partial.set("scenarios", scenarios);

        int versionNo = artifactService.mergeGeneratedPartial("theme-1", partial);

        assertEquals(1, versionNo, "工作区原地更新，版本号不变");
        ObjectNode root = stored();
        assertEquals(1, JsonSupport.children(root, "scenarios").size());
        assertEquals("开场接触：需求摸底",
                JsonSupport.text((ObjectNode) JsonSupport.children(root, "scenarios").get(0), "name"));
        // 未涉及的字段保留原值
        assertEquals("旧画像", JsonSupport.text(
                (ObjectNode) JsonSupport.children(root, "customerProfiles").get(0), "type"));
        ObjectNode visible = JsonSupport.child(JsonSupport.child(root, "icebergModel"), "visible");
        assertEquals("旧知识", JsonSupport.children(visible, "knowledge").get(0).asText());
        assertEquals(70, JsonSupport.integer(JsonSupport.child(root, "evaluationCriteria"), "passScore"));
        // 材料保留
        assertEquals("file-1", JsonSupport.text(
                (ObjectNode) JsonSupport.children(root, "materials").get(0), "id"));
    }

    @Test
    void 画像段中间产物覆盖画像与冰山保留场景() {
        ObjectNode partial = JsonSupport.blankObject();
        ArrayNode profiles = JsonSupport.blankArray();
        ObjectNode profile = JsonSupport.blankObject();
        JsonSupport.putText(profile, "type", "时限压迫型客户");
        profiles.add(profile);
        partial.set("customerProfiles", profiles);
        ObjectNode iceberg = JsonSupport.blankObject();
        ObjectNode visible = JsonSupport.blankObject();
        ArrayNode knowledge = JsonSupport.blankArray();
        knowledge.add("订单处理规范");
        visible.set("knowledge", knowledge);
        visible.set("skills", JsonSupport.blankArray());
        iceberg.set("visible", visible);
        iceberg.set("hidden", JsonSupport.blankObject());
        partial.set("icebergModel", iceberg);

        artifactService.mergeGeneratedPartial("theme-1", partial);

        ObjectNode root = stored();
        assertEquals("时限压迫型客户", JsonSupport.text(
                (ObjectNode) JsonSupport.children(root, "customerProfiles").get(0), "type"));
        assertEquals("订单处理规范", JsonSupport.children(
                JsonSupport.child(JsonSupport.child(root, "icebergModel"), "visible"), "knowledge").get(0).asText());
        // 场景仍是旧值（本段不产出场景）
        assertEquals("旧场景", JsonSupport.text(
                (ObjectNode) JsonSupport.children(root, "scenarios").get(0), "name"));
    }

    @Test
    void materials永不被中间产物覆盖() {
        ObjectNode partial = JsonSupport.blankObject();
        ArrayNode bogusMaterials = JsonSupport.blankArray();
        ObjectNode bogus = JsonSupport.blankObject();
        JsonSupport.putText(bogus, "id", "should-not-overwrite");
        bogusMaterials.add(bogus);
        partial.set("materials", bogusMaterials);
        partial.set("scenarios", JsonSupport.blankArray());

        artifactService.mergeGeneratedPartial("theme-1", partial);

        ArrayNode materials = JsonSupport.children(stored(), "materials");
        assertEquals(1, materials.size());
        assertEquals("file-1", JsonSupport.text((ObjectNode) materials.get(0), "id"),
                "materials 只保留工作区现值，工作流不能增删材料");
    }

    @Test
    void 中间产物中的null不覆盖现值() {
        ObjectNode partial = JsonSupport.blankObject();
        JsonSupport.putValue(partial, "icebergModel", null);
        JsonSupport.putValue(partial, "professionalKnowledge", null);

        artifactService.mergeGeneratedPartial("theme-1", partial);

        assertNotNull(JsonSupport.child(stored(), "icebergModel"), "null 不应把已有冰山模型抹掉");
    }

    @Test
    void 空对象中间产物为无操作() {
        String before = working.getContent();

        artifactService.mergeGeneratedPartial("theme-1", JsonSupport.blankObject());

        ObjectNode root = stored();
        assertEquals(1, JsonSupport.children(root, "scenarios").size());
        assertEquals("旧场景", JsonSupport.text((ObjectNode) JsonSupport.children(root, "scenarios").get(0), "name"));
        assertTrue(before.contains("sc-old"));
    }

    @Test
    void null中间产物被拒绝() {
        BizException error = assertThrows(BizException.class,
                () -> artifactService.mergeGeneratedPartial("theme-1", null));
        assertTrue(error.getMessage().contains("中间产物为空"));
    }
}