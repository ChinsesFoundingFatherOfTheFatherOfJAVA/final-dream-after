package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对练的一轮生成（契约 08 §6，AG-D02 的模型部分）。
 * <p>
 * 与 workflow task 的差异：需要带最近对话作为上下文，所以自己拼 LlmRequest（system + 历史 + 本轮 query），
 * 但结构化输出仍走同一份 schema 与解析规则。
 * <p>
 * 两条硬约束在这里兜底（不依赖模型自觉）：
 * <ul>
 *   <li>考核模式（study_mode=2）不产出教练点评：coachReview 强制清空；</li>
 *   <li>targetProgress 单调不降：低于当前进度时抬到当前值（契约 08 §11）。</li>
 * </ul>
 */
public class DrillChatTurn {

    /** 送入模型的历史消息上限（更早的靠 transcript_summary 概括） */
    private static final int MAX_HISTORY = 20;
    private static final String SCHEMA_RESOURCE = "prompts/workflows/drill-chat/chat-output.schema.json";
    private static final String PROMPT_RESOURCE = "prompts/workflows/drill-chat/chat.md";

    private final PromptProvider prompts;
    private final ObjectMapper mapper;

    public DrillChatTurn(PromptProvider prompts, ObjectMapper mapper) {
        this.prompts = prompts;
        this.mapper = mapper;
    }

    /**
     * 生成一轮。
     *
     * @param context workflow 上下文（start.* 为契约 08 §6.1 的 inputs）
     * @param llm     模型客户端
     * @return {reply, coachReview, targetProgress, deltaReason, behaviorHits}
     */
    public Map<String, Object> turn(WorkflowContext context, LlmClient llm) {
        String systemPrompt = context.render(prompts.load(PROMPT_RESOURCE));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        messages.addAll(historyOf(context));
        messages.add(LlmMessage.user(String.valueOf(context.input("query"))));

        LlmResponse response = llm.complete(new LlmRequest(messages, List.of(), schema()));
        if (response == null || response.error() != null) {
            throw new IllegalStateException(response == null
                    ? "对练模型无响应" : response.error());
        }
        Map<String, Object> parsed = parse(response.content());

        String reply = text(parsed.get("reply"));
        if (reply.isBlank()) {
            throw new IllegalStateException("对练模型未返回台词");
        }
        boolean exam = "2".equals(String.valueOf(context.input("study_mode")));
        String coachReview = exam ? "" : text(parsed.get("coachReview"));
        int progress = clampProgress(parsed.get("targetProgress"), currentProgress(context));
        String deltaReason = text(parsed.get("deltaReason"));
        Object behaviorHits = parsed.get("behaviorHits");

        return Map.of(
                "reply", reply,
                "coachReview", coachReview,
                "targetProgress", progress,
                "deltaReason", deltaReason,
                "behaviorHits", behaviorHits instanceof List<?> ? behaviorHits : List.of());
    }

    /** 历史消息 → LlmMessage（只认 user/assistant；非文本或超限跳过） */
    private List<LlmMessage> historyOf(WorkflowContext context) {
        Object raw = context.input("recent_messages_json");
        if (!(raw instanceof String json) || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> history;
        try {
            history = mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("recent_messages_json 不是合法 JSON", e);
        }
        List<LlmMessage> messages = new ArrayList<>();
        int from = Math.max(0, history.size() - MAX_HISTORY);
        for (Map<String, Object> item : history.subList(from, history.size())) {
            String role = text(item.get("role"));
            String text = text(item.get("text"));
            if (text.isBlank()) {
                continue;
            }
            if ("user".equals(role)) {
                messages.add(LlmMessage.user(text));
            } else if ("assistant".equals(role)) {
                messages.add(LlmMessage.assistant(text, List.of()));
            }
        }
        return messages;
    }

    /** 当前进度：progress_json.currentProgress（缺省 0） */
    private int currentProgress(WorkflowContext context) {
        Object raw = context.input("progress_json");
        if (!(raw instanceof String json) || json.isBlank()) {
            return 0;
        }
        try {
            Map<String, Object> progress = mapper.readValue(json, new TypeReference<>() {
            });
            return clampProgress(progress.get("currentProgress"), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 单调不降 + 0~100 边界 */
    private int clampProgress(Object value, int floor) {
        int progress = value instanceof Number number ? number.intValue() : floor;
        progress = Math.max(progress, floor);
        return Math.min(100, Math.max(0, progress));
    }

    private Map<String, Object> schema() {
        try {
            return mapper.readValue(
                    new org.springframework.core.io.ClassPathResource(SCHEMA_RESOURCE).getInputStream(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("加载对练输出 schema 失败", e);
        }
    }

    private Map<String, Object> parse(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            return mapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("对练模型输出不是合法 JSON：" + content, e);
        }
    }

    private String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }
}
