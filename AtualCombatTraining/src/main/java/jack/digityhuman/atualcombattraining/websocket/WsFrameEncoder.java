package jack.digityhuman.atualcombattraining.websocket;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket 帧构造器（FP-34，协议照抄线上 HAR 实证格式）
 * <p>
 * 服务端帧两类：
 * <ul>
 *   <li>事件帧：{@code {"type":"event","event":"part-start","data":{...,"seq":N}}}</li>
 *   <li>心跳帧：{@code {"type":"ping","ts":1789627134712}}</li>
 * </ul>
 * 每轮对话的帧序列：user-message-ack → session-start → message-extra-info → message-start
 * → N × [part-start → part-content × M → part-end] → session-finish。
 * <p>
 * 状态规则（单轮）：seq 从 1 递增且贯穿本轮全部帧；part index 从 0 递增（一个 part 一种 contentType）；
 * 助手 messageId 由服务端生成，格式 {@code <32位hex>_0}。
 * 一帧的 data 同时携带 seq、timestamp（yyyy-MM-dd HH:mm:ss）与 type（事件名大写+下划线）。
 * <p>
 * 采用"每轮一个 RoundWriter"的状态模型：工具端点（create_topic/update_artifact/generate_*）
 * 通过 traceId 取出当前轮的 writer，把 bizType data part 追加进当前助手消息的 part 序列，
 * 实现正文 text part 与事件 data part 自然交织。
 */
public final class WsFrameEncoder {

    /** 服务端事件帧的 type 常量 */
    public static final String TYPE_EVENT = "event";
    /** 服务端心跳帧的 type 常量 */
    public static final String TYPE_PING = "ping";
    /** part contentType：文本 */
    public static final String CONTENT_TYPE_TEXT = "text";
    /** part contentType：事件数据 */
    public static final String CONTENT_TYPE_DATA = "data";
    /** 文本 part 的 delta 类型 */
    public static final String DELTA_TEXT = "text_delta";
    /** 数据 part 的 delta 类型 */
    public static final String DELTA_DATA = "data_delta";

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WsFrameEncoder() {
    }

    /** 事件名 → 线上 data.type 值（part-start → PART_START） */
    public static String upperType(String event) {
        return event.replace('-', '_').toUpperCase();
    }

    /** 生成助手消息 ID：32 位无横线 uuid + "_0"（照线上后缀格式） */
    public static String newAssistantMessageId() {
        return UUID.randomUUID().toString().replace("-", "") + "_0";
    }

    /** 当前时间戳字符串（yyyy-MM-dd HH:mm:ss，协议统一格式） */
    public static String nowText() {
        return LocalDateTime.now().format(TS_FORMAT);
    }

    /**
     * 构造事件帧：{"type":"event","event":<event>,"data":<data>}
     */
    public static String eventFrame(String event, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_EVENT);
        frame.put("event", event);
        frame.put("data", data);
        return JsonSupport.toJson(frame);
    }

    /**
     * 构造心跳帧：{"type":"ping","ts":<epochMillis>}
     */
    public static String pingFrame(long ts) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_PING);
        frame.put("ts", ts);
        return JsonSupport.toJson(frame);
    }

    /**
     * 开启一轮对话的帧写入器。
     *
     * @param registry 会话注册表（多端广播）
     * @param sessionId 会话 ID
     * @param traceId 本轮 traceId（工具端点据此关联当前轮）
     */
    public static RoundWriter newRound(WsSessionRegistry registry, String sessionId, String traceId) {
        return new RoundWriter(registry, sessionId, traceId);
    }

    /**
     * 单轮帧写入器：持有 seq 计数器、part index 计数器与助手 messageId。
     * <p>
     * 线程模型：帧写入方法均为 synchronized——助手正文由对话线程流式写入，
     * 而工具端点的 bizType 事件可能从生成线程池异步推送，加锁保证"一个 part 的
     * part-start → part-content → part-end"不会被另一线程穿插。
     */
    public static class RoundWriter {

        private final WsSessionRegistry registry;
        private final String sessionId;
        private final String traceId;
        /** 单轮 seq 计数器（首帧为 1） */
        private int seq;
        /** part index 计数器（首个 part 为 0） */
        private int partIndex = -1;
        /** 助手消息 ID（message-start 前生成） */
        private String assistantMessageId;
        /** session-start 是否已发出（保证单轮只发一次） */
        private boolean roundStarted;

        RoundWriter(WsSessionRegistry registry, String sessionId, String traceId) {
            this.registry = registry;
            this.sessionId = sessionId;
            this.traceId = traceId;
        }

        public String sessionId() {
            return sessionId;
        }

        public String traceId() {
            return traceId;
        }

        /** 当前已分配的 seq（测试与排查用） */
        public int currentSeq() {
            return seq;
        }

        /** 当前 part index（未开 part 时为 -1） */
        public int currentPartIndex() {
            return partIndex;
        }

        public String assistantMessageId() {
            return assistantMessageId;
        }

        /**
         * 推送 user-message-ack：用户消息落库回执（本轮第一帧，seq=1）。
         *
         * @param userMessageDbId 服务端行 ID
         * @param userMessageId 客户端消息 ID
         */
        public synchronized void ack(String userMessageDbId, String userMessageId) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userMessageDbId", userMessageDbId);
            data.put("userMessageId", userMessageId);
            send("user-message-ack", data);
        }

        /**
         * 推送 session-start（轮次开始）。由 {@link #startAssistantMessage} 内部调用，
         * 也对外保留以便单独发送；重复调用只发一次。
         */
        public synchronized void sessionStart() {
            if (roundStarted) {
                return;
            }
            roundStarted = true;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("traceId", traceId);
            send("session-start", data);
        }

        /**
         * 开启助手消息：session-start → message-extra-info → message-start（协议顺序，一次性发齐，
         * 避免调用方漏发 session-start 导致前端轮次不完整）。
         *
         * @param agentId 助手智能体标识（发件人 id）
         * @param agentMessageDbId 助手消息落库行 ID
         * @param agentMessageCreatedAt 助手消息创建时间（yyyy-MM-dd HH:mm:ss）
         * @param taskId 任务 ID（Dify 任务，可空）
         * @param contextId 上下文 ID（可空）
         * @return 服务端生成的助手消息 ID
         */
        public synchronized String startAssistantMessage(String agentId, String agentMessageDbId,
                                                         String agentMessageCreatedAt,
                                                         String taskId, String contextId) {
            sessionStart();
            this.assistantMessageId = newAssistantMessageId();

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("messageId", assistantMessageId);
            Map<String, Object> extraInfo = new LinkedHashMap<>();
            extraInfo.put("taskId", taskId);
            extraInfo.put("contextId", contextId);
            extra.put("extraInfo", extraInfo);
            send("message-extra-info", extra);

            Map<String, Object> start = new LinkedHashMap<>();
            start.put("messageId", assistantMessageId);
            Map<String, Object> sender = new LinkedHashMap<>();
            sender.put("id", agentId);
            start.put("sender", sender);
            start.put("agentMessageDbId", agentMessageDbId);
            start.put("agentMessageCreatedAt", agentMessageCreatedAt);
            send("message-start", start);
            return assistantMessageId;
        }

        /**
         * 开启一个 part（part-start）。
         *
         * @param contentType text / data
         * @return part index（从 0 递增）
         */
        public synchronized int openPart(String contentType) {
            int index = ++partIndex;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", index);
            data.put("contentType", contentType);
            Map<String, Object> partExtendedData = new LinkedHashMap<>();
            partExtendedData.put("partType", contentType);
            data.put("partExtendedData", partExtendedData);
            Map<String, Object> messageExtendedData = new LinkedHashMap<>();
            messageExtendedData.put("version", 1);
            data.put("messageExtendedData", messageExtendedData);
            data.put("messageId", assistantMessageId);
            send("part-start", data);
            return index;
        }

        /** 推送文本增量（part-content，delta.type=text_delta） */
        public synchronized void writeText(int index, String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("type", DELTA_TEXT);
            delta.put("text", text);
            content(index, delta);
        }

        /** 推送事件数据增量（part-content，delta.type=data_delta，data 为 bizType JSON 片段） */
        public synchronized void writeData(int index, String dataJson) {
            if (dataJson == null || dataJson.isEmpty()) {
                return;
            }
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("type", DELTA_DATA);
            delta.put("data", dataJson);
            content(index, delta);
        }

        /** 结束 part（part-end） */
        public synchronized void closePart(int index) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", index);
            data.put("messageId", assistantMessageId);
            send("part-end", data);
        }

        /**
         * 推送 session-finish：轮次结束（前端据此解除输入禁用）。
         * <p>
         * 携带 traceId 是给前端做"轮次归属"判断用的：连接抖动/重连期间可能出现重复的握手帧，
         * 服务端对重复握手只回一条 ack + session-finish；前端必须能识别"这条 finish 不是当前正在
         * 生成的那一轮"，否则会把正在流式的轮次提前收尾（表现为助手消息库里有、页面不显示）。
         */
        public synchronized void finish() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("traceId", traceId);
            send("session-finish", data);
        }

        /**
         * 便捷方法：完整推送一个 bizType 事件 data part（一个 part 一个 JSON，前端在 part-end 整体解析）。
         *
         * @param bizType 事件类型（如 biz-side-manage-topic-changed）
         * @param params 事件参数
         */
        public synchronized void pushBizEvent(String bizType, Map<String, Object> params) {
            ObjectNode payload = JsonSupport.blankObject();
            JsonSupport.putText(payload, "bizType", bizType);
            JsonSupport.putValue(payload, "params", params == null ? new LinkedHashMap<>() : params);
            int index = openPart(CONTENT_TYPE_DATA);
            writeData(index, JsonSupport.toJson(payload));
            closePart(index);
        }

        /** 当前轮是否已开启助手消息（工具端点异步推送前需确认） */
        public boolean messageOpened() {
            return assistantMessageId != null;
        }

        /** part-content 帧装配 */
        private void content(int index, Map<String, Object> delta) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", index);
            data.put("delta", delta);
            data.put("messageId", assistantMessageId);
            send("part-content", data);
        }

        /**
         * 分配 seq 并广播事件帧。
         * 帧字段顺序与线上示例一致：先业务字段，再 type/timestamp/seq。
         */
        private void send(String event, Map<String, Object> data) {
            int current = ++seq;
            Map<String, Object> ordered = new LinkedHashMap<>(data);
            ordered.put("type", upperType(event));
            ordered.put("timestamp", nowText());
            ordered.put("seq", current);
            registry.broadcast(sessionId, eventFrame(event, ordered));
        }
    }
}