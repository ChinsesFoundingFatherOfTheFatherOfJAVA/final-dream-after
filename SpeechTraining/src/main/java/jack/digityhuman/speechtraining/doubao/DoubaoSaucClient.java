package jack.digityhuman.speechtraining.doubao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.WavHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 豆包流式语音识别（SAUC v3 二进制 WebSocket 协议）客户端。
 * <p>一次识别的完整交互：建连鉴权 → full client request（音频参数 + 请求参数）
 * → 若干 audio only request（音频分片，末片为"负包"）→ 收齐最后一包响应。
 * <p>协议要点：帧 = 4 字节头 + [sequence 4B] + payloadSize 4B + payload，
 * 其中整数一律<b>大端</b>；上行 payload 走 gzip，下行按帧头自述的压缩方式解（实测不压缩）。
 * <p>用 JDK {@link WebSocket} 而非 okhttp：okhttp 只是 dashscope SDK 的间接依赖，不应显式耦合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoubaoSaucClient {

    /** 独立 ObjectMapper：线上报文与全局 Jackson 命名策略解耦 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** HttpClient 自带选择器线程与执行器，必须全应用复用一个实例 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // 协议常量（高 4 位 | 低 4 位）
    private static final byte HEADER_VERSION_1_SIZE_1 = 0x11;
    private static final byte MT_FULL_CLIENT_REQUEST = 0b0001;
    private static final byte MT_AUDIO_ONLY_REQUEST = 0b0010;
    private static final byte MT_SERVER_ERROR_RESPONSE = 0b1111;
    private static final byte FLAG_POS_SEQUENCE = 0b0001;
    private static final byte FLAG_NEG_SEQUENCE = 0b0011;
    private static final byte SERIALIZATION_JSON_COMPRESSION_GZIP = 0x11;

    private static final int FLAG_HAS_SEQUENCE = 0b0001;
    private static final int FLAG_LAST_PACKAGE = 0b0010;
    private static final int FLAG_HAS_EVENT = 0b0100;
    private static final int COMPRESSION_GZIP = 0b0001;

    private final DoubaoAsrProperties properties;

    /**
     * 一帧服务端响应的解码结果。
     *
     * @param payload     payload JSON，无负载时为 null
     * @param lastPackage 是否为最后一包（负包）
     * @param error       服务端错误帧或本地解析失败的原因，正常为 null
     */
    record ServerFrame(String payload, boolean lastPackage, String error) {
    }

    /**
     * 识别一段 WAV（采样率与声道数从文件头读取并如实申报）。
     * <p>上行发<b>完整 WAV 字节</b>：payload 声明的 {@code format=wav} 会被服务端真实解析
     * （实测去掉 RIFF 头即报 {@code invalid WAV file format}），故头必须留在流里；
     * 申报的 rate/channel/bits 则必须来自按子块扫描的文件头，而非固定偏移。
     *
     * @return 最后一包含 {@code result.text} 的 JSON；全程未出文本（如纯静音）时为 null，由调用方按"无识别结果"处理
     */
    public String recognize(byte[] wav) {
        WavHeader header = WavHeader.parse(wav);

        BlockingQueue<ServerFrame> inbound = new LinkedBlockingQueue<>();
        AtomicReference<String> logId = new AtomicReference<>();
        WebSocket socket = null;
        long startedAt = System.currentTimeMillis();
        try {
            socket = HTTP_CLIENT.newWebSocketBuilder()
                    .header("X-Api-App-Key", properties.getAppKey())
                    .header("X-Api-Access-Key", properties.getAccessKey())
                    .header("X-Api-Resource-Id", properties.getResourceId())
                    .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                    .buildAsync(URI.create(properties.getUrl()), new FrameListener(inbound))
                    .join();

            socket.sendBinary(ByteBuffer.wrap(fullClientRequest(buildPayload(header), 1)), true).join();
            sendAudio(socket, wav, header.byteRate());

            return awaitResult(inbound, logId);
        } catch (BizException e) {
            log.error("豆包ASR识别失败: logId={}, url={}", logId.get(), properties.getUrl(), e);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("豆包ASR识别被中断: logId={}", logId.get(), e);
            throw new BizException("语音识别失败，请重试");
        } catch (Exception e) {
            log.error("豆包ASR调用异常: logId={}", logId.get(), e);
            throw new BizException("语音识别失败，请重试");
        } finally {
            closeQuietly(socket, System.currentTimeMillis() - startedAt);
        }
    }

    /** 轮询直到收到负包；返回最后一个带文本的 payload，全程无文本（如纯静音）时为 null */
    private String awaitResult(BlockingQueue<ServerFrame> inbound,
                               AtomicReference<String> logId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getTimeoutSeconds());
        String latestTextPayload = null;
        while (System.nanoTime() < deadline) {
            ServerFrame frame = inbound.poll(500, TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }
            if (frame.error() != null) {
                // 详情只进日志：错误码要能定位问题，但不进学员端提示文案
                log.error("豆包ASR返回错误帧: logId={}, {}", logId.get(), frame.error());
                throw new BizException("语音识别失败，请重试");
            }
            String payload = frame.payload();
            if (payload != null) {
                rememberLogId(payload, logId);
                if (payload.contains("\"text\"")) {
                    latestTextPayload = payload;
                }
            }
            if (frame.lastPackage() && latestTextPayload != null) {
                return latestTextPayload;
            }
        }
        throw new BizException("语音识别超时，请重试");
    }

    private void rememberLogId(String payload, AtomicReference<String> logId) {
        try {
            JsonNode node = MAPPER.readTree(payload).path("result").path("additions").path("log_id");
            if (node.isTextual()) {
                logId.set(node.asText());
            }
        } catch (Exception ignore) {
            // 单帧取不到 log_id 不影响识别，忽略
        }
    }

    /** 分片灌音频：每片 chunkMs 毫秒，末片用负 sequence 告知服务端"说完了" */
    private void sendAudio(WebSocket socket, byte[] wav, int byteRate) throws Exception {
        int chunkBytes = Math.max(2, (byteRate * properties.getChunkMs() / 1000) & ~1);
        int seq = 1;
        for (int offset = 0; offset < wav.length; offset += chunkBytes) {
            int len = Math.min(chunkBytes, wav.length - offset);
            seq++;
            boolean last = offset + len >= wav.length;
            byte[] chunk = new byte[len];
            System.arraycopy(wav, offset, chunk, 0, len);
            int signedSeq = last ? -seq : seq;
            socket.sendBinary(ByteBuffer.wrap(audioOnlyRequest(chunk, signedSeq)), true).join();
            if (last) {
                return;
            }
            if (properties.getChunkIntervalMs() > 0) {
                Thread.sleep(properties.getChunkIntervalMs());
            }
        }
    }

    private String buildPayload(WavHeader header) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("user").put("uid", "speech-training");
        ObjectNode audio = root.putObject("audio");
        // format 是容器声明，官方枚举仅 wav/mp3/ogg_opus（实测传 raw 报 45000151 unsupported format raw），
        // 服务端会按该声明解析上行字节，故 codec=raw 只描述采样编码
        audio.put("format", "wav")
                .put("codec", "raw")
                .put("rate", header.sampleRate())
                .put("bits", header.bitsPerSample())
                .put("channel", header.channels());
        ObjectNode request = root.putObject("request");
        request.put("model_name", properties.getModelName())
                .put("enable_itn", true)
                .put("enable_punc", true)
                .put("show_utterances", true)
                .put("enable_nonstream", properties.isEnableNonstream())
                .put("enable_lid", properties.isEnableLid());
        return root.toString();
    }

    private static byte[] fullClientRequest(String json, int seq) throws Exception {
        return envelope(MT_FULL_CLIENT_REQUEST, FLAG_POS_SEQUENCE, seq, gzip(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] audioOnlyRequest(byte[] pcm, int seq) throws Exception {
        byte flags = seq < 0 ? FLAG_NEG_SEQUENCE : FLAG_POS_SEQUENCE;
        return envelope(MT_AUDIO_ONLY_REQUEST, flags, seq, gzip(pcm));
    }

    /** 帧 = 头 + sequence + payloadSize + payload */
    private static byte[] envelope(byte messageType, byte flags, int seq, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER_VERSION_1_SIZE_1);
        out.write((messageType << 4) | flags);
        out.write(SERIALIZATION_JSON_COMPRESSION_GZIP);
        out.write(0);
        out.write(bigEndian(seq), 0, 4);
        out.write(bigEndian(body.length), 0, 4);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** 解一帧服务端响应；任何解不出来的情况都转成 error 帧，避免异常穿透到 WebSocket 回调线程 */
    static ServerFrame decode(byte[] frame) {
        if (frame.length < 4) {
            return new ServerFrame(null, false, "响应帧长度不足: " + frame.length);
        }
        int headerBytes = (frame[0] & 0x0F) * 4;
        int messageType = (frame[1] >> 4) & 0x0F;
        int flags = frame[1] & 0x0F;
        int compression = frame[2] & 0x0F;
        int pos = Math.max(headerBytes, 4);
        if ((flags & FLAG_HAS_SEQUENCE) != 0) {
            pos += 4;
        }
        if ((flags & FLAG_HAS_EVENT) != 0) {
            pos += 4;
        }
        boolean lastPackage = (flags & FLAG_LAST_PACKAGE) != 0;
        try {
            if (messageType == MT_SERVER_ERROR_RESPONSE) {
                int code = readIntBE(frame, pos);
                int size = Math.min(readIntBE(frame, pos + 4), frame.length - pos - 8);
                String message = size > 0 ? new String(frame, pos + 8, size, StandardCharsets.UTF_8) : "";
                return new ServerFrame(null, lastPackage, "服务端错误 code=" + code + ", message=" + message);
            }
            int size = Math.min(readIntBE(frame, pos), frame.length - pos - 4);
            if (size <= 0) {
                return new ServerFrame(null, lastPackage, null);
            }
            byte[] payload = Arrays.copyOfRange(frame, pos + 4, pos + 4 + size);
            if (compression == COMPRESSION_GZIP) {
                payload = gunzip(payload);
            }
            return new ServerFrame(payload.length == 0 ? null : new String(payload, StandardCharsets.UTF_8),
                    lastPackage, null);
        } catch (Exception e) {
            return new ServerFrame(null, lastPackage, "响应帧解析失败: " + e + ", frame=" + hex(frame, 16));
        }
    }

    private static void closeQuietly(WebSocket socket, long costMs) {
        if (socket == null) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (Exception ignore) {
            // 服务端发出负包响应后常主动关连，关闭失败不算异常
        }
        if (log.isDebugEnabled()) {
            log.debug("豆包ASR一次识别耗时 {} ms", costMs);
        }
    }

    static String hex(byte[] bytes, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString();
    }

    /** 服务端帧收集器：JDK WebSocket 按分片回调，需按 last 标志聚合成完整帧再解 */
    private final class FrameListener implements WebSocket.Listener {

        private final BlockingQueue<ServerFrame> inbound;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private FrameListener(BlockingQueue<ServerFrame> inbound) {
            this.inbound = inbound;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // 必须用相对读取：array() 不含 arrayOffset，会错位读到 WebSocket 帧头
            byte[] slice = new byte[data.remaining()];
            data.get(slice);
            buffer.write(slice, 0, slice.length);
            if (last) {
                byte[] frame = buffer.toByteArray();
                buffer.reset();
                inbound.offer(decode(frame));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            inbound.offer(new ServerFrame(null, true, "连接异常: " + error));
        }
    }

    private static byte[] gzip(byte[] src) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(src);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] src) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(src))) {
            byte[] block = new byte[4096];
            int read;
            while ((read = gz.read(block)) > 0) {
                out.write(block, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static byte[] bigEndian(int value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static int readIntBE(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24) | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8) | (src[offset + 3] & 0xFF);
    }
}
