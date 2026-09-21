package jack.digityhuman.atualcombattraining.websocket;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单轮对话上下文（消息主链路与工具端点的共享载体）
 * <p>
 * 持有本轮帧写入器（seq/part 状态）+ 助手消息内容块缓冲（落库用）。
 * 关键点：帧与落库内容必须一致——工具端点异步推的 bizType data part 会同时
 * 进入"WS 帧序列"和"助手消息 content 块数组"，保证多端实时看到的内容与历史重放一致。
 * <p>
 * 锁：本类方法均 synchronized（与 {@link WsFrameEncoder.RoundWriter} 的帧锁配合），
 * 保证"文本聚合线程"与"工具事件线程"不会把内容块交错误写。
 */
public class AssistantRoundContext {

    /** content 中文本块的 type 值 */
    public static final String BLOCK_TEXT = "text";
    /** content 中事件块的 type 值（data.data 为 {bizType, params}） */
    public static final String BLOCK_DATA = "data";

    private final WsSessionRegistry wsRegistry;
    private final WsFrameEncoder.RoundWriter writer;
    private final String sessionId;
    private final String traceId;
    /** 助手消息内容块（按产出顺序：text / data 交织） */
    private final List<ObjectNode> contentBlocks = Collections.synchronizedList(new ArrayList<>());

    private String assistantMessageId;
    private String agentMessageDbId;
    private String taskId;
    private String contextId;
    /** 本轮是否仍在进行（false 后异步事件需独立成轮） */
    private volatile boolean open = true;

    AssistantRoundContext(WsSessionRegistry wsRegistry, WsFrameEncoder.RoundWriter writer,
                          String sessionId, String traceId) {
        this.wsRegistry = wsRegistry;
        this.writer = writer;
        this.sessionId = sessionId;
        this.traceId = traceId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String traceId() {
        return traceId;
    }

    public String assistantMessageId() {
        return assistantMessageId;
    }

    public String agentMessageDbId() {
        return agentMessageDbId;
    }

    public boolean isOpen() {
        return open;
    }

    /** 本轮是否已开启助手消息（异常兜底时判断是否需要补 message-start） */
    public boolean messageOpened() {
        return writer.messageOpened();
    }

    /** 该会话当前是否有活跃 WS 连接 */
    public boolean hasConnection() {
        return wsRegistry.hasSession(sessionId);
    }

    /** 推送 user-message-ack */
    public synchronized void ack(String userMessageDbId, String userMessageId) {
        writer.ack(userMessageDbId, userMessageId);
    }

    /**
     * 开启本轮助手消息：推 session-start → message-extra-info → message-start，
     * 并记住助手消息 ID 与落库行 ID。
     *
     * @param agentId 智能体标识（sender.id）
     * @param agentMessageDbId 助手消息落库行 ID（调用方预生成）
     * @param agentMessageCreatedAt 助手消息创建时间文本
     */
    public synchronized String startAssistantMessage(String agentId, String agentMessageDbId,
                                                     String agentMessageCreatedAt,
                                                     String taskId, String contextId) {
        this.agentMessageDbId = agentMessageDbId;
        this.taskId = taskId;
        this.contextId = contextId;
        // session-start 由 writer.startAssistantMessage 内部按协议顺序发齐
        this.assistantMessageId = writer.startAssistantMessage(agentId, agentMessageDbId,
                agentMessageCreatedAt, taskId, contextId);
        return this.assistantMessageId;
    }

    /**
     * 追加一段助手正文文本：帧侧一个完整 text part，落库侧并入相邻文本块。
     */
    public synchronized void addText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = writer.openPart(WsFrameEncoder.CONTENT_TYPE_TEXT);
        writer.writeText(index, text);
        writer.closePart(index);
        appendTextBlock(text);
    }

    /**
     * 推送一个 bizType 事件块：帧侧一个完整 data part，落库侧一个 data 块。
     *
     * @param bizType 事件类型（如 biz-side-manage-case-generated）
     * @param params 事件参数
     */
    public synchronized void pushBizEvent(String bizType, Map<String, Object> params) {
        writer.pushBizEvent(bizType, params);
        appendDataBlock(bizType, params);
    }

    /** 结束本轮（session-finish） */
    public synchronized void finish() {
        writer.finish();
        open = false;
    }

    /**
     * 助手消息 content（块数组 JSON）；无内容时返回空数组保持 JSON 列合法。
     */
    public synchronized String contentJson() {
        return JsonSupport.toJson(new ArrayList<>(contentBlocks));
    }

    /** 已产出内容块数（排查用） */
    public synchronized int blockCount() {
        return contentBlocks.size();
    }

    public String taskId() {
        return taskId;
    }

    public String contextId() {
        return contextId;
    }

    /** 文本块合并进相邻文本块（同一轮连续文本只留一个块，减少历史重放碎片） */
    private void appendTextBlock(String text) {
        synchronized (contentBlocks) {
            if (!contentBlocks.isEmpty()) {
                ObjectNode last = contentBlocks.get(contentBlocks.size() - 1);
                if (BLOCK_TEXT.equals(JsonSupport.text(last, "type"))) {
                    JsonSupport.putText(last, "text", JsonSupport.text(last, "text") + text);
                    return;
                }
            }
            ObjectNode block = JsonSupport.blankObject();
            JsonSupport.putText(block, "type", BLOCK_TEXT);
            JsonSupport.putText(block, "text", text);
            JsonSupport.putValue(block, "extendedData", new java.util.LinkedHashMap<>());
            contentBlocks.add(block);
        }
    }

    /** 事件块：{type:"data", data:{bizType, params}}（与 WS data part 载荷同构，历史重放一致） */
    private void appendDataBlock(String bizType, Map<String, Object> params) {
        ObjectNode data = JsonSupport.blankObject();
        JsonSupport.putText(data, "bizType", bizType);
        JsonSupport.putValue(data, "params", params == null ? new java.util.LinkedHashMap<>() : params);
        ObjectNode block = JsonSupport.blankObject();
        JsonSupport.putText(block, "type", BLOCK_DATA);
        block.set("data", data);
        contentBlocks.add(block);
    }
}