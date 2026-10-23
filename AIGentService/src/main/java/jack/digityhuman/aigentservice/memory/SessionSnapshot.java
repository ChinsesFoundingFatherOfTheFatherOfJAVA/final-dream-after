package jack.digityhuman.aigentservice.memory;

import jack.digityhuman.aigentservice.llm.LlmMessage;

import java.util.List;

/** 可跨进程保存的会话快照（L2/L3 共用）。 */
public record SessionSnapshot(List<LlmMessage> messages, boolean scenePlanDelivered) {

    public SessionSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
