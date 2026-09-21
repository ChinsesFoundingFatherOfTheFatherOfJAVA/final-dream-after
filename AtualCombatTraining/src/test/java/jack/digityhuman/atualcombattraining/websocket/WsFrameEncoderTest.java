package jack.digityhuman.atualcombattraining.websocket;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * WS 帧构造单测（FP-34）：seq 单轮递增、part index 递增、事件序列完整、delta 结构、messageId 格式。
 * 用 mock 的会话注册表捕获广播出去的帧文本，逐帧校验协议字段。
 */
@ExtendWith(MockitoExtension.class)
class WsFrameEncoderTest {

    @Mock
    private WsSessionRegistry registry;

    /** 注册帧捕获（mock 的 broadcast 把帧文本收集起来），返回收集列表 */
    private List<String> registerCapture() {
        List<String> raw = new ArrayList<>();
        doAnswer(invocation -> {
            raw.add(invocation.getArgument(1));
            return null;
        }).when(registry).broadcast(anyString(), anyString());
        return raw;
    }

    private ObjectNode parseFrame(String json) {
        return JsonSupport.obj(json);
    }

    /** 按事件名取帧 */
    private ObjectNode frameOf(List<String> frames, String event) {
        for (String frame : frames) {
            ObjectNode node = parseFrame(frame);
            if (event.equals(JsonSupport.text(node, "event"))) {
                return node;
            }
        }
        throw new AssertionError("未找到事件帧：" + event);
    }

    @Test
    void 一轮完整事件序列_事件顺序与seq连续递增() {
        List<String> frames = registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "s1", "t1");

        writer.ack("db-1", "m-1");
        String messageId = writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", "task-1", "ctx-1");
        int part = writer.openPart(WsFrameEncoder.CONTENT_TYPE_TEXT);
        writer.writeText(part, "你好");
        writer.closePart(part);
        writer.finish();

        List<String> events = new ArrayList<>();
        List<Integer> seqs = new ArrayList<>();
        for (String frame : frames) {
            ObjectNode node = parseFrame(frame);
            assertEquals("event", JsonSupport.text(node, "type"));
            events.add(JsonSupport.text(node, "event"));
            seqs.add(JsonSupport.integer(JsonSupport.child(node, "data"), "seq"));
        }
        assertEquals(List.of("user-message-ack", "session-start", "message-extra-info", "message-start",
                "part-start", "part-content", "part-end", "session-finish"), events);
        // seq 单轮内从 1 连续递增
        for (int i = 0; i < seqs.size(); i++) {
            assertEquals(i + 1, seqs.get(i), "第 " + (i + 1) + " 帧 seq 应为 " + (i + 1));
        }
        assertTrue(messageId.endsWith("_0"), "助手消息 ID 应为 xxx_0 格式");
    }

    @Test
    void part_index从0递增() {
        registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "s1", "t1");
        writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", null, null);

        int first = writer.openPart(WsFrameEncoder.CONTENT_TYPE_TEXT);
        writer.writeText(first, "A");
        writer.closePart(first);
        int second = writer.openPart(WsFrameEncoder.CONTENT_TYPE_DATA);
        writer.writeData(second, "{\"bizType\":\"x\"}");
        writer.closePart(second);

        assertEquals(0, first);
        assertEquals(1, second);
        assertEquals(1, writer.currentPartIndex());
    }

    @Test
    void part三段式字段结构符合协议() {
        List<String> frames = registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "s1", "t1");
        writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", null, null);
        int index = writer.openPart(WsFrameEncoder.CONTENT_TYPE_DATA);
        writer.writeData(index, "{\"bizType\":\"biz-side-manage-topic-changed\"}");
        writer.closePart(index);

        ObjectNode startData = JsonSupport.child(frameOf(frames, "part-start"), "data");
        assertEquals(0, JsonSupport.integer(startData, "index"));
        assertEquals("PART_START", JsonSupport.text(startData, "type"));
        assertEquals("data", JsonSupport.text(startData, "contentType"));
        assertEquals("data", JsonSupport.text(JsonSupport.child(startData, "partExtendedData"), "partType"));
        assertEquals(1, JsonSupport.integer(JsonSupport.child(startData, "messageExtendedData"), "version"));
        assertNotNull(JsonSupport.text(startData, "messageId"));
        assertNotNull(JsonSupport.text(startData, "timestamp"));

        ObjectNode contentData = JsonSupport.child(frameOf(frames, "part-content"), "data");
        assertEquals(0, JsonSupport.integer(contentData, "index"));
        assertEquals("PART_CONTENT", JsonSupport.text(contentData, "type"));
        ObjectNode delta = JsonSupport.child(contentData, "delta");
        assertEquals("data_delta", JsonSupport.text(delta, "type"));
        assertEquals("{\"bizType\":\"biz-side-manage-topic-changed\"}", JsonSupport.text(delta, "data"));

        ObjectNode endData = JsonSupport.child(frameOf(frames, "part-end"), "data");
        assertEquals("PART_END", JsonSupport.text(endData, "type"));
        assertEquals(0, JsonSupport.integer(endData, "index"));
    }

    @Test
    void 文本part的delta为text_delta() {
        List<String> frames = registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "s1", "t1");
        writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", null, null);
        int index = writer.openPart(WsFrameEncoder.CONTENT_TYPE_TEXT);
        writer.writeText(index, "开始生成");
        writer.closePart(index);

        ObjectNode delta = JsonSupport.child(
                JsonSupport.child(frameOf(frames, "part-content"), "data"), "delta");
        assertEquals("text_delta", JsonSupport.text(delta, "type"));
        assertEquals("开始生成", JsonSupport.text(delta, "text"));
    }

    @Test
    void biz事件part载荷为bizType与params且可整体解析() {
        List<String> frames = registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "s1", "t1");
        writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", null, null);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("explicit", true);
        params.put("topic", "订单运营部职能培训设计");
        writer.pushBizEvent("biz-side-manage-topic-changed", params);

        ObjectNode contentData = JsonSupport.child(frameOf(frames, "part-content"), "data");
        String payload = JsonSupport.text(JsonSupport.child(contentData, "delta"), "data");
        ObjectNode parsed = JsonSupport.obj(payload);
        assertEquals("biz-side-manage-topic-changed", JsonSupport.text(parsed, "bizType"));
        ObjectNode parsedParams = JsonSupport.child(parsed, "params");
        assertEquals(true, parsedParams.get("explicit").asBoolean());
        assertEquals("订单运营部职能培训设计", JsonSupport.text(parsedParams, "topic"));
    }

    @Test
    void 心跳帧结构为type与ts() {
        ObjectNode node = JsonSupport.obj(WsFrameEncoder.pingFrame(1789627134712L));
        assertEquals("ping", JsonSupport.text(node, "type"));
        assertEquals(1789627134712L, node.get("ts").asLong());
    }

    @Test
    void 会话开始帧包含sessionId与traceId() {
        List<String> frames = registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "session-9", "trace-9");
        writer.ack("db-1", "m-1");
        writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", null, null);

        ObjectNode data = JsonSupport.child(frameOf(frames, "session-start"), "data");
        assertEquals("session-9", JsonSupport.text(data, "sessionId"));
        assertEquals("trace-9", JsonSupport.text(data, "traceId"));
    }

    @Test
    void session_start单轮只发一次() {
        List<String> frames = registerCapture();
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(registry, "s1", "t1");
        writer.ack("db-1", "m-1");
        // 显式调用 + startAssistantMessage 内部调用，不应重复发送
        writer.sessionStart();
        writer.startAssistantMessage("nova", "adb-1", "2026-09-17 10:00:00", null, null);

        long count = frames.stream()
                .map(this::parseFrame)
                .filter(node -> "session-start".equals(JsonSupport.text(node, "event")))
                .count();
        assertEquals(1, count);
    }
}