package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import jack.digityhuman.atualcombattraining.dify.DifyProperties;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowClient;
import jack.digityhuman.atualcombattraining.dto.req.GenerateCasesReq;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ArtifactSupport;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.CaseGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 案例生成服务实现（FP-37）
 * <p>
 * 单场景单次调用工作流（04c 设计要点：单次输出 &lt;3KB、耗时 &lt;60s，稳定且单场景失败不拖垮全局）。
 * 并发 2 由 {@link Semaphore} 精确控制（线程池容量只作兜底），每完成一个场景立即推事件，
 * 实现"右侧面板逐个出现案例"的渐进体验。
 */
@Slf4j
@Service
public class CaseGenerationServiceImpl implements CaseGenerationService {

    /** 单场景最大尝试次数（首次 + 重试 1 次） */
    private static final int MAX_ATTEMPTS = 2;
    /** 逐场景并发度（04c 要求并发 2） */
    private static final int CONCURRENCY = 2;
    /** 开场白最短长度（与 04c 代码节点校验一致） */
    private static final int OPENING_MIN_LENGTH = 40;
    /** 困难场景判定：时长阈值（分钟） */
    private static final int HARD_MAX_TIME = 20;
    /** 困难场景判定：困难挑战条数阈值 */
    private static final int HARD_DIFFICULTY_COUNT = 3;
    /** 中等场景关键词 */
    private static final List<String> MEDIUM_KEYWORDS = List.of("质疑", "施压");
    /** goal 入参长度上限（DSL start 节点 max_length=500） */
    private static final int GOAL_MAX_LENGTH = 500;

    private final DifyWorkflowClient workflowClient;
    private final DifyProperties difyProperties;
    private final ArtifactService artifactService;
    private final AssistantSessionService sessionService;
    private final AssistantEventPublisher eventPublisher;
    private final ActThemeMapper themeMapper;
    private final ThreadPoolTaskExecutor generateExecutor;
    private final Semaphore caseSemaphore = new Semaphore(CONCURRENCY);

    public CaseGenerationServiceImpl(DifyWorkflowClient workflowClient,
                                     DifyProperties difyProperties,
                                     ArtifactService artifactService,
                                     AssistantSessionService sessionService,
                                     AssistantEventPublisher eventPublisher,
                                     ActThemeMapper themeMapper,
                                     @Qualifier("generateExecutor") ThreadPoolTaskExecutor generateExecutor) {
        this.workflowClient = workflowClient;
        this.difyProperties = difyProperties;
        this.artifactService = artifactService;
        this.sessionService = sessionService;
        this.eventPublisher = eventPublisher;
        this.themeMapper = themeMapper;
        this.generateExecutor = generateExecutor;
    }

    /**
     * 受理案例生成：解析目标场景后逐场景异步提交（并发 2）。
     * 场景 ID 不存在、无产物等受理期错误同步抛出，让 Dify 工具立刻拿到错误。
     */
    @Override
    public void accept(String sessionId, String traceId, GenerateCasesReq req) {
        ActAiSession session = sessionService.requireSession(sessionId);
        String themeId = session.getThemeId();
        if (themeId == null || themeId.isBlank()) {
            // 同上：告知下一步该调什么工具，便于模型自我纠正而不是反复重试
            throw new BizException("当前会话还没有主题：请先调用 create_topic 创建草稿主题、"
                    + "并用 generate_theme 生成场景后再调用本工具");
        }
        ActAiArtifact artifact = artifactService.findDisplay(themeId);
        ObjectNode root = artifact == null ? null : JsonSupport.obj(artifact.getContent());
        if (root == null) {
            throw new BizException("当前没有可生成案例的场景");
        }
        ArrayNode scenarios = JsonSupport.children(root, "scenarios");
        if (scenarios.isEmpty()) {
            throw new BizException("当前没有可生成案例的场景");
        }
        Map<String, ObjectNode> scenarioById = new LinkedHashMap<>();
        for (JsonNode item : scenarios) {
            if (item != null && item.isObject()) {
                ObjectNode scenario = (ObjectNode) item;
                String id = JsonSupport.text(scenario, "id");
                if (id != null && !id.isBlank()) {
                    scenarioById.put(id, scenario);
                }
            }
        }
        List<String> targets = new ArrayList<>();
        if (req == null || req.getScenarioIds() == null || req.getScenarioIds().isEmpty()) {
            targets.addAll(scenarioById.keySet());
        } else {
            for (String id : req.getScenarioIds()) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (!scenarioById.containsKey(id)) {
                    throw new BizException("场景不存在：" + id);
                }
                targets.add(id);
            }
        }
        if (targets.isEmpty()) {
            throw new BizException("没有可生成案例的场景");
        }
        int difficulty = req == null || req.getDifficulty() == null ? 0 : req.getDifficulty();
        String goal = resolveGoal(themeId);
        String customerProfiles = JsonSupport.toJson(JsonSupport.children(root, "customerProfiles"));
        log.info("受理案例生成 themeId={} 场景数={} difficulty={}", themeId, targets.size(), difficulty);
        for (String scenarioId : targets) {
            ObjectNode scenario = scenarioById.get(scenarioId);
            generateExecutor.execute(() -> runSingleScenario(sessionId, traceId, artifact.getId(),
                    scenarioId, scenario, difficulty, goal, customerProfiles));
        }
    }

    /** 单场景生成：并发闸门 → 调用（失败重试 1 次）→ 校验 → 合入 artifact → 推事件 */
    private void runSingleScenario(String sessionId, String traceId, String themeArtifactId,
                                   String scenarioId, ObjectNode scenario, int difficulty,
                                   String goal, String customerProfiles) {
        boolean acquired = false;
        try {
            caseSemaphore.acquire();
            acquired = true;
            int effectiveDifficulty = difficulty > 0 ? difficulty : mapDifficulty(scenario);
            ObjectNode caseJson = null;
            String lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    ObjectNode outputs = workflowClient.run(DifyCallLogService.APP_CASE_GEN,
                            difyProperties.getCaseGenKey(), sessionId, traceId,
                            buildInputs(scenario, customerProfiles, effectiveDifficulty, goal),
                            difyProperties.getCaseTimeoutMs());
                    caseJson = parseCase(outputs);
                    String validationError = validateCase(caseJson);
                    if (validationError == null) {
                        break;
                    }
                    lastError = validationError;
                    caseJson = null;
                    log.warn("案例生成校验失败（第 {} 次）scenarioId={}：{}", attempt, scenarioId, validationError);
                } catch (BizException e) {
                    lastError = e.getMessage();
                    caseJson = null;
                    log.warn("案例生成调用失败（第 {} 次）scenarioId={}：{}", attempt, scenarioId, e.getMessage());
                }
            }
            if (caseJson == null) {
                pushCaseEvent(sessionId, traceId, scenarioId, themeArtifactId, null, effectiveDifficulty,
                        "failed", lastError);
                return;
            }
            JsonSupport.putInt(caseJson, "difficulty", effectiveDifficulty);
            String themeId = resolveThemeId(sessionId, scenarioId);
            ArtifactService.CaseMergeResult merged = artifactService.mergeCase(themeId, scenarioId, caseJson);
            pushCaseEvent(sessionId, traceId, scenarioId, themeArtifactId, merged, effectiveDifficulty,
                    "completed", null);
            log.info("案例生成完成 scenarioId={} scriptName={} difficulty={}",
                    scenarioId, merged.scriptName(), effectiveDifficulty);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("案例生成被中断 scenarioId={}", scenarioId);
        } catch (Exception e) {
            log.error("案例生成异常 scenarioId={}", scenarioId, e);
            pushCaseEvent(sessionId, traceId, scenarioId, themeArtifactId, null, difficulty,
                    "failed", e.getMessage());
        } finally {
            if (acquired) {
                caseSemaphore.release();
            }
        }
    }

    /** 会话主题 ID（合入 artifact 需要） */
    private String resolveThemeId(String sessionId, String scenarioId) {
        ActAiSession session = sessionService.requireSession(sessionId);
        if (session.getThemeId() == null || session.getThemeId().isBlank()) {
            throw new BizException("会话未绑定主题，无法合入案例：" + scenarioId);
        }
        return session.getThemeId();
    }

    /** 推 case-generated 事件（params 见 03 文档 §3.4） */
    private void pushCaseEvent(String sessionId, String traceId, String scenarioId, String themeArtifactId,
                               ArtifactService.CaseMergeResult merged, Integer difficulty,
                               String status, String error) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", merged == null ? null : merged.caseArtifactId());
        params.put("jsonArtifactId", merged == null ? null : merged.caseArtifactId());
        params.put("scenarioId", scenarioId);
        params.put("subjectId", themeArtifactId);
        params.put("scriptName", merged == null ? null : merged.scriptName());
        params.put("difficulty", difficulty);
        params.put("status", status);
        if (error != null) {
            params.put("error", error);
        }
        eventPublisher.push(sessionId, traceId, BizTypes.CASE_GENERATED, params);
    }

    /** 工作流 inputs（04c 对接要点 1） */
    private ObjectNode buildInputs(ObjectNode scenario, String customerProfiles,
                                   int difficulty, String goal) {
        ObjectNode inputs = JsonSupport.blankObject();
        JsonSupport.putText(inputs, "scenario_json", JsonSupport.toJson(scenario));
        JsonSupport.putText(inputs, "customer_profiles", customerProfiles);
        JsonSupport.putText(inputs, "difficulty", String.valueOf(difficulty));
        JsonSupport.putText(inputs, "goal", goal);
        return inputs;
    }

    /** 解析工作流输出（04c：outputs.case_json 为字符串） */
    private ObjectNode parseCase(ObjectNode outputs) {
        String caseJson = JsonSupport.text(outputs, "case_json");
        if (caseJson == null || caseJson.isBlank()) {
            throw new BizException("案例生成工作流未返回 case_json");
        }
        ObjectNode parsed = JsonSupport.obj(caseJson);
        if (parsed == null) {
            throw new BizException("case_json 解析失败");
        }
        return parsed;
    }

    /** Java 校验兜底（04c 对接要点 5）：actorName 非空、scriptOpening ≥40 字 */
    private String validateCase(ObjectNode caseJson) {
        String actorName = JsonSupport.text(caseJson, "actorName");
        if (actorName == null || actorName.isBlank()) {
            return "actorName 为空";
        }
        String opening = JsonSupport.text(caseJson, "scriptOpening");
        if (opening == null || opening.trim().length() < OPENING_MIN_LENGTH) {
            return "scriptOpening 少于 " + OPENING_MIN_LENGTH + " 字";
        }
        return null;
    }

    /**
     * difficulty=0（智能体按场景自定）时的 Java 映射（04c 对接要点 4）：
     * maxTime ≥20 或 difficulties ≥3 条 → 3；否则文本含"质疑/施压" → 2；默认 1。
     * 纯函数（便于单测）：避免为定难度再调一次 LLM。
     */
    static int mapDifficulty(ObjectNode scenario) {
        if (scenario == null) {
            return 1;
        }
        ObjectNode constraints = JsonSupport.child(scenario, "constraints");
        Integer maxTime = constraints == null ? null : JsonSupport.integer(constraints, "maxTime");
        if (maxTime != null && maxTime >= HARD_MAX_TIME) {
            return 3;
        }
        if (JsonSupport.children(scenario, "difficulties").size() >= HARD_DIFFICULTY_COUNT) {
            return 3;
        }
        String text = safe(JsonSupport.text(scenario, "coreConflict")) + " "
                + String.join(" ", JsonSupport.strings(JsonSupport.children(scenario, "difficulties"))) + " "
                + String.join(" ", JsonSupport.strings(JsonSupport.children(scenario, "failureReasons")));
        for (String keyword : MEDIUM_KEYWORDS) {
            if (text.contains(keyword)) {
                return 2;
            }
        }
        return 1;
    }

    /** 主题描述作为工作流 goal（create_topic 写入的"目标+人群"一句话） */
    private String resolveGoal(String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        String desc = theme == null ? null : theme.getThemeDesc();
        if (desc == null || desc.isBlank()) {
            desc = theme == null ? "实战演练" : theme.getThemeName();
        }
        return ArtifactSupport.truncate(desc, GOAL_MAX_LENGTH);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}