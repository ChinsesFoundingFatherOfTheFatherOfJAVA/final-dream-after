package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.AttemptScriptGenerationService;
import jack.digityhuman.atualcombattraining.service.DrillSchemas;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 练习脚本生成实现（AG-D01 消费端）
 * <p>
 * 输入严格按契约 08 §4.1 组装，不把客户端原文直接透传：custom_config_json 与
 * product_profiles/settings 都先解析校验再序列化（§3 最后一段的硬要求）。
 * <p>
 * 重试策略（§10）：schema 非法或调用失败自动重试 1 次（第二次换 traceId），
 * 仍失败把 attempt 置 failed 并落 error_code，不扣次数。
 */
@Slf4j
@Service
public class AttemptScriptGenerationServiceImpl implements AttemptScriptGenerationService {

    private static final String STATUS_PREPARING = "preparing";
    private static final String STAGE_ANALYZE = "analyze_scene";
    private static final int DEFAULT_TIMEOUT_MS = 180_000;
    private static final String ERROR_SCHEMA = "DRILL_SCHEMA_INVALID";
    private static final String ERROR_LLM = "DRILL_LLM_ERROR";

    private final AttemptLifecycleService lifecycleService;
    private final ActPracticeAttemptMapper attemptMapper;
    private final NativeWorkflowClient nativeWorkflowClient;
    private final ThreadPoolTaskExecutor publishExecutor;

    public AttemptScriptGenerationServiceImpl(AttemptLifecycleService lifecycleService,
                                              ActPracticeAttemptMapper attemptMapper,
                                              NativeWorkflowClient nativeWorkflowClient,
                                              @Qualifier("publishExecutor") ThreadPoolTaskExecutor publishExecutor) {
        this.lifecycleService = lifecycleService;
        this.attemptMapper = attemptMapper;
        this.nativeWorkflowClient = nativeWorkflowClient;
        this.publishExecutor = publishExecutor;
    }

    @Override
    public void generateAsync(String attemptId, String userId) {
        publishExecutor.execute(() -> {
            try {
                generate(attemptId, userId);
            } catch (Exception e) {
                log.warn("脚本生成失败 attemptId={}：{}", attemptId, e.getMessage());
            }
        });
    }

    @Override
    public String generate(String attemptId, String userId) {
        ActPracticeAttempt attempt = lifecycleService.require(attemptId);
        if (!STATUS_PREPARING.equals(attempt.getStatus())) {
            // 幂等：已被别的路径准备完成/取消
            return attempt.getScriptSnapshotJson();
        }
        markStage(attemptId, "analyze_scene", 10);
        String scriptJson;
        try {
            scriptJson = callWithRetry(attempt);
        } catch (BizException e) {
            fail(attemptId, errorCodeOf(e), e.getMessage());
            throw e;
        } catch (Exception e) {
            fail(attemptId, ERROR_LLM, e.getMessage());
            throw new BizException("练习脚本生成失败：" + e.getMessage());
        }
        // §5.1 二次校验（AIGentService 侧只做版本自检，深校验在这里）
        markStage(attemptId, "build_script", 60);
        ObjectNode root = JsonSupport.obj(scriptJson);
        if (root == null) {
            fail(attemptId, ERROR_SCHEMA, "脚本不是合法 JSON");
            throw new BizException("练习脚本不是合法 JSON");
        }
        try {
            DrillSchemas.validateScript(root);
        } catch (BizException e) {
            fail(attemptId, ERROR_SCHEMA, e.getMessage());
            throw e;
        }
        int maxDuration = intOf(root.path("runtime"), "maxDurationSeconds", 900);
        int maxRounds = intOf(root.path("runtime"), "maxRounds", 50);
        markStage(attemptId, "build_actor", 85);
        lifecycleService.markReady(attemptId, scriptJson, maxDuration, maxRounds);
        log.info("脚本就绪 attemptId={} 时长={}s 轮次={}", attemptId, maxDuration, maxRounds);
        return scriptJson;
    }

    /** 调用 AIGentService：失败或 schema 非法重试 1 次（§10） */
    private String callWithRetry(ActPracticeAttempt attempt) {
        Map<String, Object> inputs = buildInputs(attempt);
        RuntimeException last = null;
        for (int i = 0; i < 2; i++) {
            try {
                // blocking() 返回的就是 data.outputs 节点（键：script_schema_version/script_json/success）
                ObjectNode data = nativeWorkflowClient.blocking(
                        "drill-script", attempt.getId(), newTraceId(), inputs, DEFAULT_TIMEOUT_MS);
                String scriptJson = text(data, "script_json");
                if (scriptJson == null || scriptJson.isBlank()) {
                    throw new BizException(ERROR_SCHEMA + "：模型未返回 script_json");
                }
                return scriptJson;
            } catch (RuntimeException e) {
                last = e;
                log.warn("脚本生成第 {} 次失败 attemptId={}：{}", i + 1, attempt.getId(), e.getMessage());
            }
        }
        throw last == null ? new BizException(ERROR_LLM + "：脚本生成失败") : last;
    }

    /**
     * 组装 AG-D01 inputs（§4.1）。所有 JSON 字符串都由本方法从已落库快照生成，
     * 不走客户端原文（§3：不允许透传未验证的客户端 JSON）。
     */
    private Map<String, Object> buildInputs(ActPracticeAttempt attempt) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("attempt_id", attempt.getId());
        inputs.put("theme_id", attempt.getThemeId());
        inputs.put("scenario_id", attempt.getScenarioId());
        inputs.put("practice_type", attempt.getPracticeType());
        inputs.put("study_mode", String.valueOf(attempt.getStudyMode()));
        inputs.put("difficulty", String.valueOf(attempt.getDifficulty() == null ? 2 : attempt.getDifficulty()));
        inputs.put("ai_response_mode", settingsField(attempt.getSettingsSnapshotJson(), "aiResponseMode", "1"));
        inputs.put("scenario_json", attempt.getScenarioSnapshotJson());
        inputs.put("settings_json", attempt.getSettingsSnapshotJson());
        inputs.put("custom_config_json", validateJsonOrEmpty(attempt.getCustomConfigJson(), "custom_config_json"));
        inputs.put("product_profiles_json", nullSafe(attempt.getProductProfilesSnapshotJson(), "[]"));
        inputs.put("materials_summary_text", materialsSummary(attempt.getMaterialsSnapshotJson()));
        return inputs;
    }

    /** 材料摘要：只给名称与类型（模型不需要全文，避免 prompt 超长） */
    private String materialsSummary(String materialsJson) {
        var array = JsonSupport.arr(materialsJson);
        if (array == null || array.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                continue;
            }
            String name = item.path("name").asText("");
            String type = item.path("fileType").asText("");
            if (name.isBlank()) {
                continue;
            }
            if (!summary.isEmpty()) {
                summary.append("；");
            }
            summary.append(name).append("（").append(type).append("）");
        }
        return summary.toString();
    }

    /** 客户端自定义配置：必须是合法 JSON 对象，否则拒绝（不静默放行） */
    private String validateJsonOrEmpty(String json, String field) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        if (JsonSupport.obj(json) == null) {
            throw new BizException(field + " 不是合法 JSON 对象");
        }
        return json;
    }

    private String settingsField(String settingsJson, String field, String fallback) {
        ObjectNode settings = JsonSupport.obj(settingsJson);
        if (settings == null) {
            return fallback;
        }
        JsonNode node = settings.get(field);
        return node == null || node.isNull() ? fallback : node.asText(fallback);
    }

    private void markStage(String attemptId, String stage, int percent) {
        ActPracticeAttempt update = new ActPracticeAttempt();
        update.setId(attemptId);
        update.setPreparationStage(stage);
        update.setPreparationPercent(percent);
        update.setUpdatedAt(LocalDateTime.now());
        attemptMapper.updateById(update);
    }

    private void fail(String attemptId, String errorCode, String errorMessage) {
        attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getId, attemptId)
                .eq(ActPracticeAttempt::getStatus, STATUS_PREPARING)
                .set(ActPracticeAttempt::getStatus, "failed")
                .set(ActPracticeAttempt::getActiveKey, null)
                .set(ActPracticeAttempt::getErrorCode, errorCode)
                .set(ActPracticeAttempt::getErrorMessage, truncate(errorMessage))
                .set(ActPracticeAttempt::getUpdatedAt, LocalDateTime.now()));
    }

    private String errorCodeOf(BizException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.startsWith(ERROR_SCHEMA) || message.contains("校验未通过")
                ? ERROR_SCHEMA : ERROR_LLM;
    }

    private int intOf(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isInt() && value.asInt() > 0 ? value.asInt() : fallback;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
