package jack.digityhuman.atualcombattraining.websocket.drill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对练 WS 帧编码器（契约 03 §9.1）
 * <p>
 * 服务端帧结构：{"type":"event","event":"assistant.delta","seq":42,"attemptId":"...","data":{...}}
 * type 固定 event（心跳 type=ping），event 名对齐 drill-chat-event-v1 的事件字典；
 * data 里按事件携带 messageId/turnNo/delta/text/targetProgress 等。
 */
public final class DrillFrameEncoder {

    public static final String TYPE_EVENT = "event";
    public static final String TYPE_PING = "ping";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DrillFrameEncoder() {
    }

    /** 通用事件帧：data 由调用方组装（messageId/turnNo/delta/text/...） */
    public static String event(String attemptId, long seq, String event, Map<String, Object> data) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", TYPE_EVENT);
        frame.put("event", event);
        frame.put("seq", seq);
        frame.put("attemptId", attemptId);
        ObjectNode dataNode = frame.putObject("data");
        if (data != null) {
            data.forEach((key, value) -> dataNode.set(key, MAPPER.valueToTree(value)));
        }
        return JsonSupport.toJson(frame);
    }

    /** 心跳帧 */
    public static String ping(long ts) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", TYPE_PING);
        frame.put("ts", ts);
        return JsonSupport.toJson(frame);
    }

    /** 常用 data 组装：turn_start/assistant_start 等（messageId + turnNo） */
    public static Map<String, Object> turnData(String messageId, long turnNo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("turnNo", turnNo);
        return data;
    }

    /** delta 帧 data（messageId + turnNo + delta） */
    public static Map<String, Object> deltaData(String messageId, long turnNo, String delta) {
        Map<String, Object> data = turnData(messageId, turnNo);
        data.put("delta", delta);
        return data;
    }

    /** end 帧 data（messageId + turnNo + text + ttsConfig） */
    public static Map<String, Object> endData(String messageId, long turnNo, String text,
                                              String ttsJson) {
        Map<String, Object> data = turnData(messageId, turnNo);
        data.put("text", text);
        data.put("ttsConfig", JsonSupport.obj(ttsJson));
        return data;
    }

    /** progress 帧 data */
    public static Map<String, Object> progressData(int targetProgress, String deltaReason,
                                                   java.util.List<String> behaviorHits,
                                                   int roundNo, int remainingSeconds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetProgress", targetProgress);
        data.put("deltaReason", deltaReason);
        data.put("behaviorHits", behaviorHits);
        data.put("roundNo", roundNo);
        data.put("remainingSeconds", remainingSeconds);
        return data;
    }
}
