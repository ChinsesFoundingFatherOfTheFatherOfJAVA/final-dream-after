package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.common.AssistantIdentity;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundContext;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundRegistry;
import jack.digityhuman.atualcombattraining.websocket.WsFrameEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 助手事件推送（bizType data part 的统一出口）
 * <p>
 * 两种落点：
 * <ol>
 *   <li><b>并入当前轮次</b>：工具端点被 Dify 同步回调时（create_topic / update_artifact /
 *       generate_* 的"受理中/生成中"事件），traceId 对应的工作区消息还开着，
 *       事件直接追加进该助手消息的 part 序列与 content 块数组；</li>
 *   <li><b>独立成一轮</b>：生成类任务异步完成时（artifact-generated completed / case-generated），
 *       原轮次早已 session-finish。此时新开一个短轮次（session-start → message-start →
 *       data part → session-finish）并单独落一条助手消息，保证多端实时可见、历史可重放。</li>
 * </ol>
 * 不依赖客户端 ack：无 WS 连接时照样落库（前端重进按历史消息恢复）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantEventPublisher {

    private final AssistantRoundRegistry roundRegistry;
    private final AssistantSessionService sessionService;

    /**
     * 推送 bizType 事件到会话。
     *
     * @param sessionId 会话 ID
     * @param traceId 触发该事件的轮次 traceId（可空则直接独立成轮）
     * @param bizType 事件类型（见 {@link BizTypes}）
     * @param params 事件参数
     */
    public void push(String sessionId, String traceId, String bizType, Map<String, Object> params) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("事件推送缺少会话，丢弃 bizType={}", bizType);
            return;
        }
        AssistantRoundContext context = roundRegistry.find(traceId);
        if (context != null && sessionId.equals(context.sessionId())) {
            context.pushBizEvent(bizType, params);
            return;
        }
        pushStandalone(sessionId, bizType, params);
    }

    /** 独立成一轮：新开短轮次推送事件并落一条助手消息 */
    private void pushStandalone(String sessionId, String bizType, Map<String, Object> params) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        AssistantRoundContext context = roundRegistry.open(sessionId, traceId);
        String dbId = sessionService.newDbId();
        try {
            context.startAssistantMessage(AssistantIdentity.AGENT_ID, dbId, WsFrameEncoder.nowText(), null, null);
            context.pushBizEvent(bizType, params);
            context.finish();
            sessionService.appendAssistantMessage(sessionId, context.assistantMessageId(), dbId,
                    context.contentJson(), traceId, null, null);
        } finally {
            roundRegistry.close(traceId);
        }
        log.info("异步事件独立成轮推送 sessionId={} bizType={}", sessionId, bizType);
    }
}