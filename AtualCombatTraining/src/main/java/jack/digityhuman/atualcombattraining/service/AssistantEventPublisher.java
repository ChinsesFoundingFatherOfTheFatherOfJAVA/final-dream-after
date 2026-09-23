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
 * 三种落点：
 * <ol>
 *   <li><b>并入当前轮次</b>：工具端点被 Dify 同步回调时（create_topic / update_artifact /
 *       generate_* 的"受理中/生成中"事件），traceId 对应的工作区消息还开着，
 *       事件直接追加进该助手消息的 part 序列与 content 块数组；</li>
 *   <li><b>独立成一轮</b>：生成类任务异步完成时（artifact-generated completed / case-generated），
 *       原轮次早已 session-finish。此时新开一个短轮次（session-start → message-start →
 *       data part → session-finish）并单独落一条助手消息，保证多端实时可见、历史可重放。</li>
 *   <li><b>只广播不落库</b>：中间进度事件（artifact-generated in_progress）经
 *       {@link #pushTransient} 推送，无活跃轮次时同样开一个短轮次广播，但不写消息行——
 *       一次生成的进度点很多，逐条落库会在消息流里堆出成排空气泡（2026-09-21 修复）。</li>
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
     * 推送 bizType 事件到会话（结果类事件：无活跃轮次时独立成轮并落库，历史可重放）。
     * <p>
     * 纯中间进度事件不要用本方法，用 {@link #pushTransient}。
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
        pushStandalone(sessionId, bizType, params, true);
    }

    /**
     * 推送"瞬时事件"：只广播给在线端，不独立成部落库。
     * <p>
     * 用于中间进度类事件（如主题生成的 in_progress 分段）。此前它们走 {@link #push}，
     * 异步生成时原轮次早已结束 → 每个节点完成都新开一部落一条助手消息，
     * 一次生成在消息流里留下十余条只含一张进度卡的记录：
     * 前端把它们归并成一张卡后，其余消息被清空 → 聊天框出现成排"只有头像"的空气泡，
     * 且历史重放会再复现一次。进度本就是一次性的，不落库不影响最终状态
     * （completed / failed 仍走 {@link #push} 落库，重进会话看得到结果）。
     *
     * @param sessionId 会话 ID
     * @param traceId 触发该事件的轮次 traceId（可空）
     * @param bizType 事件类型
     * @param params 事件参数
     */
    public void pushTransient(String sessionId, String traceId, String bizType, Map<String, Object> params) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("瞬时事件推送缺少会话，丢弃 bizType={}", bizType);
            return;
        }
        AssistantRoundContext context = roundRegistry.find(traceId);
        if (context != null && sessionId.equals(context.sessionId())) {
            // 当前轮次还开着：并入本轮助手消息（随该轮一次性落库，不会额外产生消息行）
            context.pushBizEvent(bizType, params);
            return;
        }
        pushStandalone(sessionId, bizType, params, false);
    }

    /**
     * 独立成一轮：新开短轮次推送事件。
     *
     * @param persist true 时同时落一条助手消息（历史可重放）；false 时只广播、不落库
     */
    private void pushStandalone(String sessionId, String bizType, Map<String, Object> params, boolean persist) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        AssistantRoundContext context = roundRegistry.open(sessionId, traceId);
        String dbId = sessionService.newDbId();
        try {
            context.startAssistantMessage(AssistantIdentity.AGENT_ID, dbId, WsFrameEncoder.nowText(), null, null);
            context.pushBizEvent(bizType, params);
            context.finish();
            if (persist) {
                sessionService.appendAssistantMessage(sessionId, context.assistantMessageId(), dbId,
                        context.contentJson(), traceId, null, null);
            }
        } finally {
            roundRegistry.close(traceId);
        }
        log.info("异步事件独立成轮推送 sessionId={} bizType={} 落库={}", sessionId, bizType, persist);
    }
}