package jack.digityhuman.atualcombattraining.service.impl;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jack.digityhuman.atualcombattraining.service.AssistantAsrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;

/**
 * 语音识别服务实现（百炼 Paraformer，复用 SpeechTraining 的调用模式）
 * <p>
 * 与 SpeechTraining 的差异：本模块只做"语音转文字回填输入框"，不需要时长统计与粤语分支，
 * 但保留两处关键防护：
 * 1. WAV 头解析失败或超过 60s 直接拒绝（SDK 对非法输入会抛底层异常，前置拦截提示更友好）；
 * 2. SDK 同步 call 返回 null 视为识别失败（历史教训：不判空会 NPE 或误判成功）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAsrServiceImpl implements AssistantAsrService {

    /** 录音时长上限（秒） */
    private static final int MAX_AUDIO_SECONDS = 60;
    /** 最短有效识别字数（03 文档 2.4：<2 字返回 code=1 请重试） */
    private static final int MIN_TEXT_LENGTH = 2;

    private final AssistantProperties properties;

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Override
    public String recognize(byte[] wav) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("语音识别未配置（dashscope.api-key 为空）");
        }
        if (wav == null || wav.length == 0) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        long durationMs = wavDurationMs(wav);
        if (durationMs <= 0 || durationMs > MAX_AUDIO_SECONDS * 1000L) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        File tempFile = null;
        try {
            tempFile = Files.createTempFile("assistant-asr-", ".wav").toFile();
            Files.write(tempFile.toPath(), wav);
            RecognitionParam param = RecognitionParam.builder()
                    .apiKey(apiKey)
                    .model(properties.getAsr().getModel())
                    .format("wav")
                    .sampleRate(16_000)
                    .build();
            // 同步 call：阻塞至识别完成，返回 {"sentences":[{begin_time,end_time,text},...]} JSON
            String resultJson = new Recognition().call(param, tempFile);
            // 历史教训：SDK 返回 null 即失败，必须判空
            if (resultJson == null || resultJson.isBlank()) {
                log.error("百炼 ASR 返回 null");
                throw new BizException("语音识别失败，请重试");
            }
            String text = parseText(resultJson);
            if (text.length() < MIN_TEXT_LENGTH) {
                throw new BizException("请重试");
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("百炼 ASR 识别失败", e);
            throw new BizException("语音识别失败，请重试");
        } finally {
            if (tempFile != null && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    /** 拼接 sentences[].text */
    private String parseText(String resultJson) {
        ObjectNode root = JsonSupport.obj(resultJson);
        if (root == null) {
            throw new BizException("语音识别失败，请重试");
        }
        ArrayNode sentences = JsonSupport.children(root, "sentences");
        StringBuilder text = new StringBuilder();
        for (var item : sentences) {
            if (item != null && item.isObject()) {
                String part = JsonSupport.text((ObjectNode) item, "text");
                if (part != null && !part.isEmpty()) {
                    text.append(part);
                }
            }
        }
        return text.toString().trim();
    }

    /**
     * 解析 WAV 头估算时长（毫秒）：读 fmt 块 byteRate 与 data 块大小；
     * 非法 WAV（无 RIFF/data 块）返回 -1。
     */
    static long wavDurationMs(byte[] wav) {
        if (wav.length < 44) {
            return -1;
        }
        if (!(wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F')) {
            return -1;
        }
        int byteRate = 0;
        int dataSize = -1;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            int chunkSize = readIntLE(wav, offset + 4);
            String chunkId = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("fmt ".equals(chunkId) && offset + 8 + 12 <= wav.length) {
                byteRate = readIntLE(wav, offset + 8 + 8);
            } else if ("data".equals(chunkId)) {
                dataSize = chunkSize;
                break;
            }
            if (chunkSize <= 0) {
                break;
            }
            offset += 8 + chunkSize + (chunkSize % 2);
        }
        if (byteRate <= 0 || dataSize <= 0) {
            return -1;
        }
        return (long) dataSize * 1000L / byteRate;
    }

    /** 小端 4 字节整数 */
    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}