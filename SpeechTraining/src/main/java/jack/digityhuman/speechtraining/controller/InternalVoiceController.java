package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.service.SpeechService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音能力内部接口（AI实战训练场契约 03 §9.4）
 * <p>
 * 供 AtualCombatTraining 学员对练代理调用：ASR（录音转文字）与 TTS（台词合成音频）。
 * 复用 SpeechService 既有实现（Doubao ASR / DashScope TTS，TTS 自带"文本+声音+语速"缓存）。
 * <p>
 * 鉴权：X-Internal-Token 与 AGENT_INTERNAL_TOKEN 一致；未配置 token 时 fail-closed 返回 503
 * （与 AIGentService 内部接口同一安全口径）。
 */
@Slf4j
@RestController
@RequestMapping("/internal/voice")
@RequiredArgsConstructor
public class InternalVoiceController {

    private final SpeechService speechService;

    @Value("${agent.internal-token:}")
    private String internalToken;

    /** ASR：音频 → {text, durationMs}（WAV ≤60s，语言由调用方传入） */
    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> asr(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        if (audio == null || audio.isEmpty()) {
            throw new BizException("音频内容为空");
        }
        try {
            SpeechService.AsrResult result = speechService.asr(audio.getBytes(), language);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("text", result.text());
            resp.put("durationMs", result.durationMs());
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("内部 ASR 失败：{}", e.getMessage());
            throw new BizException("语音识别失败：" + e.getMessage());
        }
    }

    /** TTS：{text, voiceId, speed} → {audioUrl, durationMs}（相同文本+声音+语速走缓存） */
    @PostMapping(value = "/tts")
    public Map<String, Object> tts(
            @RequestBody TtsBody body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new BizException("合成文本为空");
        }
        SpeechService.TtsResult result = speechService.tts(
                body.text(), body.voiceId(), body.speed());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("audioUrl", result.url());
        resp.put("durationMs", result.durationMs());
        resp.put("cached", result.cached());
        return resp;
    }

    public record TtsBody(String text, String voiceId, Integer speed) {
    }

    /** fail-closed：未配置 token 一律拒绝（不阻断应用启动，只拒绝服务） */
    private void requireToken(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new BizException("内部接口未配置令牌");
        }
        if (!internalToken.equals(token)) {
            throw new BizException("内部接口令牌无效");
        }
    }
}
