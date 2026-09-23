package jack.digityhuman.speechtraining.doubao;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAUC 帧编解码单测（v1.2）：服务端响应帧的末包标志、压缩声明与错误帧。
 * 样例字节序取自真实链路抓包（下行不压缩 + 上行 gzip）。
 */
class DoubaoSaucFrameTest {

    private static final int MT_FULL_SERVER_RESPONSE = 0b1001;
    private static final int MT_SERVER_ERROR_RESPONSE = 0b1111;
    private static final int FLAG_POS_SEQUENCE = 0b0001;
    private static final int FLAG_LAST_SEQUENCE = 0b0011;

    @Test
    void 负包响应帧解出负载与末包标志() {
        String json = "{\"audio_info\":{\"duration\":7288},\"result\":{\"text\":\"听起来确实不错。\"}}";
        byte[] frame = serverFrame(MT_FULL_SERVER_RESPONSE, FLAG_LAST_SEQUENCE, 16, json, false);

        DoubaoSaucClient.ServerFrame decoded = DoubaoSaucClient.decode(frame);

        assertTrue(decoded.lastPackage());
        assertNull(decoded.error());
        assertEquals(json, decoded.payload());
    }

    @Test
    void 中间帧不带末包标志() {
        byte[] frame = serverFrame(MT_FULL_SERVER_RESPONSE, FLAG_POS_SEQUENCE, 3, "{\"result\":{\"text\":\"你好\"}}", false);

        DoubaoSaucClient.ServerFrame decoded = DoubaoSaucClient.decode(frame);

        assertFalse(decoded.lastPackage());
        assertEquals("{\"result\":{\"text\":\"你好\"}}", decoded.payload());
    }

    @Test
    void 按帧头声明解压gzip() throws Exception {
        String json = "{\"result\":{\"text\":\"粤语识别\"}}";
        byte[] frame = serverFrame(MT_FULL_SERVER_RESPONSE, FLAG_POS_SEQUENCE, 4, json, true);

        assertEquals(json, DoubaoSaucClient.decode(frame).payload());
    }

    @Test
    void 空负载帧不算错误() {
        byte[] frame = serverFrame(MT_FULL_SERVER_RESPONSE, 0, 0, "", false);

        DoubaoSaucClient.ServerFrame decoded = DoubaoSaucClient.decode(frame);

        assertNull(decoded.payload());
        assertNull(decoded.error());
        assertFalse(decoded.lastPackage());
    }

    @Test
    void 服务端错误帧解出错误码与描述() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x11);
        out.write((MT_SERVER_ERROR_RESPONSE << 4) | FLAG_POS_SEQUENCE);
        out.write(0x10);
        out.write(0x00);
        out.write(bigEndian(2), 0, 4);
        out.write(bigEndian(45000151), 0, 4);
        byte[] message = "音频格式不正确".getBytes(StandardCharsets.UTF_8);
        out.write(bigEndian(message.length), 0, 4);
        out.write(message, 0, message.length);

        DoubaoSaucClient.ServerFrame decoded = DoubaoSaucClient.decode(out.toByteArray());

        assertNotNull(decoded.error());
        assertTrue(decoded.error().contains("45000151"), "错误码要能进日志，否则线上无从定位: " + decoded.error());
        assertTrue(decoded.error().contains("音频格式不正确"));
    }

    @Test
    void 畸形帧转错误结果而不是抛异常() {
        // 声明了 sequence 却缺 payloadSize：解析必须兜住，异常不能逃逸到 WebSocket 回调线程
        byte[] broken = {0x11, (byte) ((MT_FULL_SERVER_RESPONSE << 4) | FLAG_POS_SEQUENCE), 0x10, 0x00, 0, 0, 0, 1};

        DoubaoSaucClient.ServerFrame decoded = DoubaoSaucClient.decode(broken);

        assertNotNull(decoded.error());
        assertNull(decoded.payload());
    }

    /** 头(4B) + [sequence] + payloadSize + payload，整数大端 */
    private static byte[] serverFrame(int messageType, int flags, int seq, String json, boolean gzip) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] body;
        try {
            body = gzip ? gzip(json.getBytes(StandardCharsets.UTF_8)) : json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        out.write(0x11);
        out.write((messageType << 4) | flags);
        out.write(gzip ? 0x11 : 0x10);
        out.write(0x00);
        if ((flags & FLAG_POS_SEQUENCE) != 0) {
            out.write(bigEndian(seq), 0, 4);
        }
        out.write(bigEndian(body.length), 0, 4);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] src) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(src);
        }
        return out.toByteArray();
    }

    private static byte[] bigEndian(int value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }
}
