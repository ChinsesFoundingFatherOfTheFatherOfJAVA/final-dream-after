package jack.digityhuman.atualcombattraining.dify;

import jack.digityhuman.atualcombattraining.entity.ActAiCallLog;
import jack.digityhuman.atualcombattraining.mapper.ActAiCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Dify 调用审计（act_ai_call_log）
 * <p>
 * 所有 Dify 调用（编排对话 / 主题生成 / 案例生成）都记一条：会话、应用类型、traceId、
 * 成功与否、错误摘要、耗时。审计写入失败不得影响主流程，因此整体兜住异常只记日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifyCallLogService {

    /** 应用类型：编排智能体对话 */
    public static final String APP_ORCHESTRATOR = "orchestrator";
    /** 应用类型：主题生成工作流 */
    public static final String APP_THEME_GEN = "theme_gen";
    /** 应用类型：案例生成工作流 */
    public static final String APP_CASE_GEN = "case_gen";

    /** 错误摘要入库长度上限（列 VARCHAR(500)） */
    private static final int ERROR_MAX_LENGTH = 500;

    private final ActAiCallLogMapper callLogMapper;

    /**
     * 记录一次 Dify 调用。
     *
     * @param sessionId 会话 ID（可空）
     * @param appKind orchestrator / theme_gen / case_gen
     * @param traceId 本轮 traceId（可空）
     * @param success 是否成功
     * @param errorMsg 失败原因（可空）
     * @param durationMs 耗时毫秒
     */
    public void record(String sessionId, String appKind, String traceId,
                       boolean success, String errorMsg, long durationMs) {
        try {
            ActAiCallLog entity = new ActAiCallLog();
            entity.setSessionId(sessionId);
            entity.setAppKind(appKind);
            entity.setTraceId(traceId);
            entity.setSuccess(success ? 1 : 0);
            entity.setErrorMsg(truncate(errorMsg));
            entity.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            entity.setCreatedAt(LocalDateTime.now());
            callLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写入 Dify 调用审计失败 appKind={} sessionId={}: {}", appKind, sessionId, e.getMessage());
        }
    }

    /** 错误摘要按列长截断 */
    private String truncate(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        return errorMsg.length() <= ERROR_MAX_LENGTH ? errorMsg : errorMsg.substring(0, ERROR_MAX_LENGTH);
    }
}