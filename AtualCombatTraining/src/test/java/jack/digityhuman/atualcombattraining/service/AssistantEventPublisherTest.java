package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.websocket.AssistantRoundContext;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantEventPublisher} 落库行为测试
 * <p>
 * 锁住 2026-09-21 的修复边界：中间进度事件（pushTransient）在无活跃轮次时只广播、
 * 不独立成部落助手消息——否则一次主题生成会落十几条只含一张进度卡的消息行，
 * 前端把它们归并成一张卡后剩下一排"只有头像"的空气泡。
 */
@ExtendWith(MockitoExtension.class)
class AssistantEventPublisherTest {

    private static final String SESSION = "sess-1";
    private static final String TRACE = "trace-1";
    private static final String BIZ = "biz-side-manage-artifact-generated";

    @Mock
    private AssistantRoundRegistry roundRegistry;
    @Mock
    private AssistantSessionService sessionService;
    @Mock
    private AssistantRoundContext roundContext;

    private Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", "art-1");
        params.put("status", "in_progress");
        return params;
    }

    /** 独立成轮所需的公共桩：open 返回新上下文、消息行 ID 预生成（落库用的 ID 与内容按需再补） */
    private void stubStandaloneRound() {
        when(roundRegistry.open(eq(SESSION), anyString())).thenReturn(roundContext);
        when(sessionService.newDbId()).thenReturn("db-1");
    }

    @Test
    @DisplayName("有活跃轮次：事件并入本轮助手消息，不另开轮次也不落库")
    void push_withOpenRound_appendsIntoRound() {
        AssistantEventPublisher publisher = new AssistantEventPublisher(roundRegistry, sessionService);
        when(roundRegistry.find(TRACE)).thenReturn(roundContext);
        when(roundContext.sessionId()).thenReturn(SESSION);

        publisher.push(SESSION, TRACE, BIZ, params());

        verify(roundContext).pushBizEvent(BIZ, params());
        verify(roundRegistry, never()).open(anyString(), anyString());
        verifyNeverAppend();
    }

    @Test
    @DisplayName("无活跃轮次：普通事件独立成轮并落一条助手消息（历史可重放）")
    void push_withoutRound_persistsStandaloneMessage() {
        AssistantEventPublisher publisher = new AssistantEventPublisher(roundRegistry, sessionService);
        stubStandaloneRound();
        when(roundContext.assistantMessageId()).thenReturn("msg-1");
        when(roundContext.contentJson()).thenReturn("[]");

        publisher.push(SESSION, null, BIZ, params());

        verify(roundContext).pushBizEvent(BIZ, params());
        verify(sessionService, times(1))
                .appendAssistantMessage(eq(SESSION), eq("msg-1"), eq("db-1"), eq("[]"), anyString(), any(), any());
    }

    @Test
    @DisplayName("无活跃轮次：瞬时进度事件只广播，不落库（空气泡源头修复）")
    void pushTransient_withoutRound_broadcastsWithoutPersist() {
        AssistantEventPublisher publisher = new AssistantEventPublisher(roundRegistry, sessionService);
        stubStandaloneRound();

        publisher.pushTransient(SESSION, null, BIZ, params());

        verify(roundContext).pushBizEvent(BIZ, params());
        verifyNeverAppend();
    }

    @Test
    @DisplayName("有活跃轮次：瞬时事件仍并入本轮，随该轮一次性落库")
    void pushTransient_withOpenRound_appendsIntoRound() {
        AssistantEventPublisher publisher = new AssistantEventPublisher(roundRegistry, sessionService);
        when(roundRegistry.find(TRACE)).thenReturn(roundContext);
        when(roundContext.sessionId()).thenReturn(SESSION);

        publisher.pushTransient(SESSION, TRACE, BIZ, params());

        verify(roundContext).pushBizEvent(BIZ, params());
        verify(roundRegistry, never()).open(anyString(), anyString());
        verifyNeverAppend();
    }

    @Test
    @DisplayName("缺少会话 ID 的瞬时事件直接丢弃")
    void pushTransient_withoutSession_drops() {
        AssistantEventPublisher publisher = new AssistantEventPublisher(roundRegistry, sessionService);

        publisher.pushTransient(" ", TRACE, BIZ, params());

        verify(roundRegistry, never()).open(anyString(), anyString());
        verifyNeverAppend();
    }

    private void verifyNeverAppend() {
        verify(sessionService, never()).appendAssistantMessage(any(), any(), any(), any(), any(), any(), any());
    }
}
