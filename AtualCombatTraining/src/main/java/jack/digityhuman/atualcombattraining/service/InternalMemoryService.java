package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.MessagePageResp;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 为 AIGentService 的 L3 记忆重建提供只读快照。 */
@Service
@RequiredArgsConstructor
public class InternalMemoryService {

    private final AssistantSessionService sessions;
    private final AssistantContextService contexts;

    public MemorySnapshot load(String sessionId) {
        ActAiSession session = sessions.requireSession(sessionId);
        MessagePageResp page = sessions.listMessages(sessionId, 1000, null);
        List<MemoryMessage> messages = new ArrayList<>();
        if (page != null && page.getMessages() != null) {
            for (MessagePageResp.Message message : page.getMessages()) {
                String text = textOf(message.getContent());
                if (text != null && !text.isBlank()) {
                    messages.add(new MemoryMessage(message.getRole(), text));
                }
            }
        }
        return new MemorySnapshot(messages, contexts.buildInputs(session));
    }

    private String textOf(Object content) {
        if (!(content instanceof List<?> blocks)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object item : blocks) {
            if (!(item instanceof Map<?, ?> block)) {
                continue;
            }
            if ("text".equals(block.get("type"))) {
                Object value = block.get("text");
                if (value != null) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(value);
                }
            }
        }
        return text.toString();
    }

    public record MemoryMessage(String role, String content) {
    }

    public record MemorySnapshot(List<MemoryMessage> messages,
                                 Map<String, String> inputs) {
    }
}
