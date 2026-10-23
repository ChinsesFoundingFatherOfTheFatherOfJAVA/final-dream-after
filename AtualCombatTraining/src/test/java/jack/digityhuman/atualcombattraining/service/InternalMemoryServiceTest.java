package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.MessagePageResp;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalMemoryServiceTest {

    @Test
    void flattensTextBlocksAndReturnsTheCurrentContextInputs() {
        AssistantSessionService sessions = mock(AssistantSessionService.class);
        AssistantContextService contexts = mock(AssistantContextService.class);
        ActAiSession session = new ActAiSession();
        when(sessions.requireSession("s1")).thenReturn(session);
        MessagePageResp page = new MessagePageResp();
        MessagePageResp.Message user = new MessagePageResp.Message();
        user.setRole("user");
        user.setContent(List.of(Map.of("type", "text", "text", "你好")));
        MessagePageResp.Message assistant = new MessagePageResp.Message();
        assistant.setRole("assistant");
        assistant.setContent(List.of(
                Map.of("type", "text", "text", "场景规划"),
                Map.of("type", "data", "data", Map.of("bizType", "x"))));
        page.setMessages(List.of(user, assistant));
        when(sessions.listMessages("s1", 1000, null)).thenReturn(page);
        when(contexts.buildInputs(session)).thenReturn(Map.of(
                "theme_status", "draft", "artifact_summary", "场景单元(1)"));

        InternalMemoryService.MemorySnapshot snapshot =
                new InternalMemoryService(sessions, contexts).load("s1");

        assertEquals(List.of(
                new InternalMemoryService.MemoryMessage("user", "你好"),
                new InternalMemoryService.MemoryMessage("assistant", "场景规划")),
                snapshot.messages());
        assertEquals("draft", snapshot.inputs().get("theme_status"));
    }
}
