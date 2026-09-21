package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import jack.digityhuman.atualcombattraining.dify.DifyProperties;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowClient;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowEventParser;
import jack.digityhuman.atualcombattraining.dto.req.GenerateThemeReq;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.service.ArtifactSchemaValidator;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ArtifactSupport;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.ThemeGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题生成服务实现（FP-36）
 * <p>
 * 时序（02 方案 §5.2）：
 * 1. 工具端点收到调用 → 先推 artifact-generated {status:in_progress}（保证落在当前轮次）；
 * 2. 提交 generateExecutor 异步执行：调 Dify 主题生成工作流（blocking 300s）；
 * 3. Java 侧 ArtifactSchemaValidator 校验；失败把错误拼进 material_summary 重试 1 次；
 * 4. 成功后回填 relatedIcebergElements（关键词匹配）→ 应用到工作区 artifact（materials 保留）
 *    → 推 {status:completed, content}；两次失败 → 推 {status:failed, error}。
 * <p>
 * 事件到达时原对话轮次通常已 session-finish，故 completed/failed 由事件发布器独立成轮推送。
 */
@Slf4j
@Service
public class ThemeGenerationServiceImpl implements ThemeGenerationService {

    /** 校验失败重试次数（共 2 次尝试 = 首次 + 重试 1 次） */
    private static final int MAX_ATTEMPTS = 2;
    /** 重试时拼进 material_summary 的错误提示模板（04b 对接要点 4） */
    private static final String RETRY_HINT = "\n【上次输出校验失败：%s，请修正】";
    /** in_progress 事件 status 值 */
    private static final String STATUS_IN_PROGRESS = "in_progress";
    /** completed 事件 status 值 */
    private static final String STATUS_COMPLETED = "completed";
    /** failed 事件 status 值 */
    private static final String STATUS_FAILED = "failed";

    private final DifyWorkflowClient workflowClient;
    private final DifyProperties difyProperties;
    private final ArtifactService artifactService;
    private final AssistantSessionService sessionService;
    private final AssistantEventPublisher eventPublisher;
    private final ThreadPoolTaskExecutor generateExecutor;

    public ThemeGenerationServiceImpl(DifyWorkflowClient workflowClient,
                                      DifyProperties difyProperties,
                                      ArtifactService artifactService,
                                      AssistantSessionService sessionService,
                                      AssistantEventPublisher eventPublisher,
                                      @Qualifier("generateExecutor") ThreadPoolTaskExecutor generateExecutor) {
        this.workflowClient = workflowClient;
        this.difyProperties = difyProperties;
        this.artifactService = artifactService;
        this.sessionService = sessionService;
        this.eventPublisher = eventPublisher;
        this.generateExecutor = generateExecutor;
    }

    /**
     * 受理主题生成：同步推 in_progress（并入当前轮次），随后异步执行生成。
     * themeId 缺失属调用顺序错误（应先 create_topic），同步抛出让 Dify 工具返回错误。
     */
    @Override
    public void accept(String sessionId, String traceId, GenerateThemeReq req) {
        ActAiSession session = sessionService.requireSession(sessionId);
        String themeId = session.getThemeId();
        if (themeId == null || themeId.isBlank()) {
            // 错误文案要"可执行"：直接告诉模型下一步该调哪个工具，便于它在下一轮自我纠正，
            // 而不是反复重试同一个工具（早期只写"请先创建主题"时，模型不知道该调 create_topic）。
            throw new BizException("当前会话还没有主题：请先调用 create_topic 创建草稿主题，再调用本工具生成内容");
        }
        if (req == null || req.getGoal() == null || req.getGoal().isBlank()) {
            throw new BizException("goal 不能为空");
        }
        ActAiArtifact working = artifactService.ensureWorking(themeId);
        // 1. 生成中先推（此刻仍在 Dify 工具调用的同步链路上，事件并入当前助手消息）
        eventPublisher.push(sessionId, traceId, BizTypes.ARTIFACT_GENERATED,
                generatedParams(working, STATUS_IN_PROGRESS, null, null));
        // 2. 异步生成
        generateExecutor.execute(() -> runGeneration(session, traceId, req, working));
    }

    /**
     * 异步生成主体（流式 + 渐次出现）：
     * 1. 流式调工作流，按节点完成事件逐段落地中间产物并推 in_progress（场景 → 画像+冰山）；
     * 2. 流结束取最终产物做 schema 校验（失败把错误拼进 material_summary 重试 1 次）；
     * 3. 成功后整体替换工作区 artifact（materials 保留）→ 推 completed（带全量 content）；
     * 4. 两次失败 → 推 failed（error），此时工作区可能残留已生成的中间产物——与"渐次出现"一致。
     */
    private void runGeneration(ActAiSession session, String traceId, GenerateThemeReq req,
                               ActAiArtifact workingBefore) {
        String sessionId = session.getId();
        String themeId = session.getThemeId();
        try {
            List<String> existingSceneNames = existingSceneNames(workingBefore);
            List<String> lastErrors = List.of();
            ObjectNode artifact = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                String materialSummary = buildMaterialSummary(req, lastErrors, attempt);
                // 节点完成即落地中间产物并推 in_progress，实现右面板"渐次出现"
                DifyWorkflowEventParser.Listener listener = (nodeId, title, outputs) -> {
                    ObjectNode partial = buildPartialFromNode(outputs);
                    if (partial == null) {
                        return;
                    }
                    artifactService.mergeGeneratedPartial(themeId, partial);
                    pushInProgress(sessionId, traceId, themeId, outputs, title);
                };
                ObjectNode outputs = workflowClient.runStreaming(DifyCallLogService.APP_THEME_GEN,
                        difyProperties.getThemeGenKey(), sessionId, traceId,
                        buildInputs(req, materialSummary, existingSceneNames),
                        difyProperties.getThemeTimeoutMs(), listener);
                artifact = parseArtifact(outputs);
                lastErrors = ArtifactSchemaValidator.validate(artifact);
                if (lastErrors.isEmpty()) {
                    break;
                }
                log.warn("主题生成 schema 校验失败（第 {} 次）themeId={}：{}", attempt, themeId, lastErrors);
                artifact = null;
            }
            if (artifact == null) {
                pushFailed(sessionId, traceId, themeId, "生成结果校验未通过：" + String.join("；", lastErrors));
                return;
            }
            backfillIcebergElements(artifact);
            int versionNo = artifactService.replaceGenerated(themeId, artifact);
            ActAiArtifact applied = artifactService.get(workingBefore.getId(), versionNo);
            Object content = applied != null
                    ? JsonSupport.plain(applied.getContent())
                    : JsonSupport.plainNode(artifact);
            eventPublisher.push(sessionId, traceId, BizTypes.ARTIFACT_GENERATED,
                    generatedParams(applied, STATUS_COMPLETED, content, null));
            log.info("主题生成完成 themeId={} version={} 场景数={}", themeId, versionNo,
                    JsonSupport.children(artifact, "scenarios").size());
        } catch (Exception e) {
            log.error("主题生成失败 themeId={}", themeId, e);
            pushFailed(sessionId, traceId, themeId, e.getMessage());
        }
    }

    /**
     * 推 in_progress（携带"当前工作区已生成部分"的全量内容）：
     * 右面板据此渐次渲染已就绪的 Tab 数据，未生成的部分保持原样。
     */
    private void pushInProgress(String sessionId, String traceId, String themeId,
                                ObjectNode nodeOutputs, String nodeTitle) {
        ActAiArtifact working = artifactService.findWorking(themeId);
        if (working == null) {
            return;
        }
        Map<String, Object> params = generatedParams(working, STATUS_IN_PROGRESS,
                JsonSupport.plain(working.getContent()), null);
        // stage 便于前端/日志区分是哪一段就绪（scenes / profiles / final）
        params.put("stage", nodeTitle);
        eventPublisher.push(sessionId, traceId, BizTypes.ARTIFACT_GENERATED, params);
        log.info("主题生成中间产物已推送 themeId={} stage={} 字段={}",
                themeId, nodeTitle, DifyWorkflowEventParser.keys(nodeOutputs));
    }

    /**
     * 依节点输出识别"哪一段完成了"，并构造对应的中间产物（其它节点返回 null 不推送）。
     * 按输出变量名识别（不依赖节点 id/标题，DSL 改名也不受影响）：
     * <ul>
     *   <li>{@code scenes_json} → 场景段：产出 scenarios（维度权重待第三段）</li>
     *   <li>{@code profiles_json} / {@code iceberg_json} → 画像与冰山段</li>
     * </ul>
     */
    private ObjectNode buildPartialFromNode(ObjectNode nodeOutputs) {
        if (nodeOutputs == null) {
            return null;
        }
        if (nodeOutputs.has("scenes_json")) {
            ArrayNode scenes = JsonSupport.arr(JsonSupport.text(nodeOutputs, "scenes_json"));
            if (scenes == null || scenes.isEmpty()) {
                return null;
            }
            ObjectNode partial = JsonSupport.blankObject();
            ArrayNode mapped = JsonSupport.blankArray();
            int index = 1;
            for (JsonNode item : scenes) {
                if (item != null && item.isObject()) {
                    mapped.add(mapSceneForPartial((ObjectNode) item, index++));
                }
            }
            partial.set("scenarios", mapped);
            return partial;
        }
        if (nodeOutputs.has("profiles_json") || nodeOutputs.has("iceberg_json")) {
            ObjectNode partial = JsonSupport.blankObject();
            ArrayNode profiles = JsonSupport.arr(JsonSupport.text(nodeOutputs, "profiles_json"));
            if (profiles != null) {
                partial.set("customerProfiles", profiles);
            }
            ObjectNode iceberg = JsonSupport.obj(JsonSupport.text(nodeOutputs, "iceberg_json"));
            if (iceberg != null) {
                partial.set("icebergModel", iceberg);
            }
            return partial.isEmpty() ? null : partial;
        }
        return null;
    }

    /**
     * 场景段输出 → artifact.scenarios 形状（与 04b DSL 的聚合节点同构）：
     * 展平的 forbidBehaviors/maxTime 收进 constraints，补齐 artifact 必备的空数组字段，
     * dimensionWeights 留空（第三段才产出），cases 留空（案例生成时填充）。
     */
    private ObjectNode mapSceneForPartial(ObjectNode scene, int sortNo) {
        ObjectNode mapped = JsonSupport.blankObject();
        String fallbackId = String.format("sc-%02d", sortNo);
        JsonSupport.putText(mapped, "id", orDefault(JsonSupport.text(scene, "id"), fallbackId));
        JsonSupport.putText(mapped, "name", JsonSupport.text(scene, "name"));
        mapped.set("audiences", JsonSupport.children(scene, "audiences"));
        JsonSupport.putText(mapped, "businessGoal", JsonSupport.text(scene, "businessGoal"));
        mapped.set("behaviorGoals", JsonSupport.children(scene, "behaviorGoals"));
        // 冰山关联待回填（最终产物由 backfillIcebergElements 统一回填）
        ObjectNode icebergElements = JsonSupport.blankObject();
        for (String layer : List.of("knowledge", "skills", "roles", "selfConcept", "personalities", "motives")) {
            icebergElements.set(layer, JsonSupport.blankArray());
        }
        mapped.set("relatedIcebergElements", icebergElements);
        JsonSupport.putText(mapped, "businessImpact", JsonSupport.text(scene, "businessImpact"));
        JsonSupport.putText(mapped, "failureCost", JsonSupport.text(scene, "failureCost"));
        JsonSupport.putText(mapped, "coreConflict", JsonSupport.text(scene, "coreConflict"));
        mapped.set("failureReasons", JsonSupport.children(scene, "failureReasons"));
        mapped.set("difficulties", JsonSupport.children(scene, "difficulties"));
        ObjectNode constraints = JsonSupport.blankObject();
        constraints.set("forbidBehaviors", JsonSupport.children(scene, "forbidBehaviors"));
        JsonSupport.putInt(constraints, "maxTime", JsonSupport.integer(scene, "maxTime"));
        mapped.set("constraints", constraints);
        for (String emptyArrayField : List.of("relatedKnowledgeIds", "relatedScriptIds",
                "relatedProductProfileArtifactIds", "caseArtifactIds", "cases", "dimensionWeights")) {
            mapped.set(emptyArrayField, JsonSupport.blankArray());
        }
        return mapped;
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 推 failed 事件（error 字段供前端提示、编排智能体下轮解释） */
    private void pushFailed(String sessionId, String traceId, String themeId, String error) {
        ActAiArtifact working = artifactService.findWorking(themeId);
        eventPublisher.push(sessionId, traceId, BizTypes.ARTIFACT_GENERATED,
                generatedParams(working, STATUS_FAILED, null, error));
    }

    /** 组装 artifact-generated 事件参数 {id, version, status, content?, error?} */
    private Map<String, Object> generatedParams(ActAiArtifact artifact, String status,
                                                Object content, String error) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", artifact == null ? null : artifact.getId());
        params.put("version", artifact == null ? null : String.valueOf(artifact.getVersionNo()));
        params.put("status", status);
        if (content != null) {
            params.put("content", content);
        }
        if (error != null) {
            params.put("error", error);
        }
        return params;
    }

    /** 构造工作流 inputs（04b 对接要点 1：audiences/existing_scenes 传 JSON 数组字符串） */
    private ObjectNode buildInputs(GenerateThemeReq req, String materialSummary,
                                   List<String> existingSceneNames) {
        ObjectNode inputs = JsonSupport.blankObject();
        JsonSupport.putText(inputs, "goal", req.getGoal());
        JsonSupport.putText(inputs, "audiences",
                JsonSupport.toJson(req.getAudiences() == null ? List.of() : req.getAudiences()));
        JsonSupport.putText(inputs, "material_summary", materialSummary == null ? "" : materialSummary);
        JsonSupport.putText(inputs, "keep_existing", Boolean.TRUE.equals(req.getKeepExisting()) ? "true" : "false");
        JsonSupport.putText(inputs, "existing_scenes", JsonSupport.toJson(existingSceneNames));
        return inputs;
    }

    /** 重试时把校验错误拼进 material_summary 尾部（04b 对接要点 4） */
    private String buildMaterialSummary(GenerateThemeReq req, List<String> lastErrors, int attempt) {
        String base = req.getMaterialSummary() == null ? "" : req.getMaterialSummary();
        if (attempt == 1 || lastErrors.isEmpty()) {
            return base;
        }
        return base + String.format(RETRY_HINT, String.join("；", lastErrors));
    }

    /** 取工作区产物里现有场景名（更新模式供工作流保留结构） */
    private List<String> existingSceneNames(ActAiArtifact artifact) {
        List<String> names = new ArrayList<>();
        if (artifact == null || artifact.getContent() == null) {
            return names;
        }
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            return names;
        }
        for (JsonNode item : JsonSupport.children(root, "scenarios")) {
            if (item != null && item.isObject()) {
                String name = JsonSupport.text((ObjectNode) item, "name");
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 解析工作流输出（04b 对接要点 2：outputs.artifact_json 是字符串，需再 parse） */
    private ObjectNode parseArtifact(ObjectNode outputs) {
        String artifactJson = JsonSupport.text(outputs, "artifact_json");
        if (artifactJson == null || artifactJson.isBlank()) {
            throw new BizException("主题生成工作流未返回 artifact_json");
        }
        ObjectNode artifact = JsonSupport.obj(artifactJson);
        if (artifact == null) {
            throw new BizException("artifact_json 解析失败");
        }
        return artifact;
    }

    /**
     * 回填场景的 relatedIcebergElements（04b 对接要点 3）：
     * 工作流不填该字段，Java 用场景 businessGoal/behaviorGoals 与冰山 knowledge/skills 做关键词包含匹配，
     * 匹配不上的留空数组，不阻塞生成。
     */
    private void backfillIcebergElements(ObjectNode artifact) {
        ObjectNode iceberg = JsonSupport.child(artifact, "icebergModel");
        if (iceberg == null) {
            return;
        }
        ObjectNode visible = JsonSupport.child(iceberg, "visible");
        if (visible == null) {
            return;
        }
        ArrayNode knowledge = JsonSupport.children(visible, "knowledge");
        ArrayNode skills = JsonSupport.children(visible, "skills");
        for (JsonNode item : JsonSupport.children(artifact, "scenarios")) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode scenario = (ObjectNode) item;
            String haystack = safe(JsonSupport.text(scenario, "businessGoal")) + " "
                    + String.join(" ", JsonSupport.strings(JsonSupport.children(scenario, "behaviorGoals")));
            ObjectNode elements = JsonSupport.blankObject();
            elements.set("knowledge", matchByKeyword(knowledge, haystack));
            elements.set("skills", matchByKeyword(skills, haystack));
            for (String layer : List.of("roles", "selfConcept", "personalities", "motives")) {
                elements.set(layer, JsonSupport.blankArray());
            }
            scenario.set("relatedIcebergElements", elements);
        }
    }

    /**
     * 关键词包含匹配：冰山条目形如"名称: 掌握标准"，用名称部分在场景目标文本里找包含关系；
     * 整串匹配不上时退化为按名称前 2 字匹配，避免全部落空。
     */
    private ArrayNode matchByKeyword(ArrayNode candidates, String haystack) {
        ArrayNode matched = JsonSupport.blankArray();
        for (JsonNode item : candidates) {
            if (item == null || item.isNull()) {
                continue;
            }
            String text = item.asText();
            String name = text.split("[:：]")[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            boolean hit = haystack.contains(name)
                    || (name.length() >= 2 && haystack.contains(name.substring(0, 2)));
            if (hit) {
                matched.add(text);
            }
        }
        return matched;
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}