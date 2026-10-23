package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题生成 workflow。
 *
 * <p>三个 LLM 调用和 code_scenes/code_profiles/code_merge 三个后处理，
 * 变量名与 DSL end 节点保持一致，消费端无需改解析。
 */
@SuppressWarnings("unchecked")
public class ThemeGenTask implements WorkflowTask {

    public static final String NAME = "theme-gen";

    private final ObjectMapper mapper;
    private final WorkflowLlm llmCaller;

    public ThemeGenTask(PromptProvider prompts, ObjectMapper mapper) {
        this.mapper = mapper;
        this.llmCaller = new WorkflowLlm(prompts, mapper);
    }

    @Override
    public String name() {
        return NAME;
    }

    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> scenesResult = llmCaller.structured(context, llm,
                "prompts/workflows/theme-gen/scenes.md",
                "prompts/workflows/theme-gen/scenes.schema.json");
        Map<String, Object> scenesNode = codeScenes(scenesResult);
        context.putNode("code_scenes", scenesNode);
        notify(listener, "code_scenes", "解析场景JSON", scenesNode);

        Map<String, Object> profilesResult = llmCaller.structured(context, llm,
                "prompts/workflows/theme-gen/profiles.md",
                "prompts/workflows/theme-gen/profiles.schema.json");
        Map<String, Object> profilesNode = codeProfiles(profilesResult);
        context.putNode("code_profiles", profilesNode);
        notify(listener, "code_profiles", "解析画像冰山JSON", profilesNode);

        Map<String, Object> criteria = llmCaller.structured(context, llm,
                "prompts/workflows/theme-gen/criteria.md",
                "prompts/workflows/theme-gen/criteria.schema.json");
        context.putNode("1789698610515", criteria);

        Map<String, Object> merged = codeMerge(context, scenesNode, profilesNode, criteria);
        context.putNode("code_merge", merged);
        notify(listener, "code_merge", "聚合校验输出", merged);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> codeScenes(Map<String, Object> result) {
        Object raw = result.get("scenes");
        if (!(raw instanceof List<?> scenes)) {
            throw new IllegalArgumentException("scenes is not a list");
        }
        List<String> names = new ArrayList<>();
        for (Object item : scenes) {
            if (!(item instanceof Map<?, ?> scene)) {
                throw new IllegalArgumentException("scene item is not object");
            }
            Object name = scene.get("name");
            names.add(name == null ? "" : String.valueOf(name));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scenes_json", json(scenes));
        output.put("scene_names", json(names));
        output.put("scene_count", scenes.size());
        return output;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> codeProfiles(Map<String, Object> result) {
        if (!(result.get("profiles") instanceof List<?> profiles)
                || !(result.get("iceberg") instanceof Map<?, ?> iceberg)) {
            throw new IllegalArgumentException("profiles/iceberg missing");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("profiles_json", json(profiles));
        output.put("iceberg_json", json(iceberg));
        return output;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> codeMerge(WorkflowContext context,
                                          Map<String, Object> scenesNode,
                                          Map<String, Object> profilesNode,
                                          Map<String, Object> criteria) {
        List<Map<String, Object>> scenes = readList(scenesNode.get("scenes_json"));
        List<Map<String, Object>> profiles = readList(profilesNode.get("profiles_json"));
        Map<String, Object> iceberg = readMap(profilesNode.get("iceberg_json"));
        List<Map<String, Object>> dimensions = castList(criteria.get("dimensions"));
        List<Map<String, Object>> defaultWeights = castList(criteria.get("defaultDimensionWeights"));
        Map<String, List<Map<String, Object>>> sceneWeights = new LinkedHashMap<>();
        for (Map<String, Object> item : castList(criteria.get("sceneWeights"))) {
            sceneWeights.put(String.valueOf(item.get("sceneId")),
                    castList(item.get("weights")));
        }

        ArrayNode artifactScenes = mapper.createArrayNode();
        for (Map<String, Object> scene : scenes) {
            String id = String.valueOf(scene.get("id"));
            List<Map<String, Object>> weights = sceneWeights.getOrDefault(id, defaultWeights);
            ObjectNode mapped = mapper.createObjectNode();
            mapped.set("id", value(scene.get("id")));
            mapped.set("name", value(scene.get("name")));
            mapped.set("audiences", value(scene.get("audiences")));
            mapped.set("businessGoal", value(scene.get("businessGoal")));
            mapped.set("behaviorGoals", value(scene.get("behaviorGoals")));
            mapped.set("relatedIcebergElements", mapper.createObjectNode());
            mapped.set("businessImpact", value(scene.get("businessImpact")));
            mapped.set("failureCost", value(scene.get("failureCost")));
            mapped.set("coreConflict", value(scene.get("coreConflict")));
            mapped.set("failureReasons", value(scene.get("failureReasons")));
            mapped.set("difficulties", value(scene.get("difficulties")));
            ObjectNode constraints = mapper.createObjectNode();
            constraints.set("forbidBehaviors", value(scene.get("forbidBehaviors")));
            constraints.set("maxTime", value(scene.get("maxTime")));
            mapped.set("constraints", constraints);
            for (String field : List.of("relatedKnowledgeIds", "relatedScriptIds",
                    "relatedProductProfileArtifactIds", "caseArtifactIds", "cases")) {
                mapped.set(field, mapper.createArrayNode());
            }
            mapped.set("dimensionWeights", value(weights));
            artifactScenes.add(mapped);
        }

        ObjectNode artifact = mapper.createObjectNode();
        artifact.set("materials", materials(context.input("material_summary")));
        artifact.set("scenarios", artifactScenes);
        artifact.set("customerProfiles", value(profiles));
        artifact.set("icebergModel", value(iceberg));
        ObjectNode evaluation = mapper.createObjectNode();
        evaluation.set("passScore", value(criteria.get("passScore")));
        evaluation.set("dimensions", value(dimensions));
        evaluation.set("defaultDimensionWeights", value(defaultWeights));
        artifact.set("evaluationCriteria", evaluation);
        artifact.putNull("professionalKnowledge");
        artifact.set("productProfiles", mapper.createArrayNode());
        artifact.put("version", "v1");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("artifact_json", artifact.toString());
        return output;
    }

    private ArrayNode materials(Object input) {
        ArrayNode materials = mapper.createArrayNode();
        if (input == null || String.valueOf(input).isBlank()) {
            return materials;
        }
        String text = String.valueOf(input).trim();
        if (text.startsWith("[")) {
            try {
                JsonNode parsed = mapper.readTree(text);
                if (parsed.isArray()) {
                    return (ArrayNode) parsed;
                }
            } catch (Exception ignored) {
                // fall through to summary material, matching DSL _parse_materials
            }
        }
        ObjectNode material = mapper.createObjectNode();
        material.put("summary", text);
        materials.add(material);
        return materials;
    }

    private List<Map<String, Object>> readList(Object value) {
        try {
            return mapper.readValue(String.valueOf(value),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            throw new IllegalArgumentException("场景列表 JSON 解析失败", e);
        }
    }

    private Map<String, Object> readMap(Object value) {
        try {
            return mapper.readValue(String.valueOf(value),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalArgumentException("冰山 JSON 解析失败", e);
        }
    }

    private List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("期望对象数组");
                }
                result.add((Map<String, Object>) map);
            }
            return result;
        }
        throw new IllegalArgumentException("期望数组");
    }

    private JsonNode value(Object value) {
        return mapper.valueToTree(value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    private void notify(WorkflowProgressListener listener, String nodeId, String title,
                        Map<String, Object> outputs) {
        if (listener != null) {
            listener.nodeFinished(nodeId, title, outputs);
        }
    }
}
