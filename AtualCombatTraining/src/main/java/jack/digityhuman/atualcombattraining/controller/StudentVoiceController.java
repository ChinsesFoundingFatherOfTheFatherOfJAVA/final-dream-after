package jack.digityhuman.atualcombattraining.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.agent.SpeechVoiceClient;
import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.service.AntiCheatService;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Objects;

/**
 * 学员端语音与防作弊接口（契约 03 §9.4 / §12）
 * <p>
 * 语音：录音转写（ASR）与台词合成（TTS）代理 SpeechTraining 内部接口；
 * attempt 归属与进行中校验在本层。防作弊：事件审计 + 切屏违规结算。
 */
@Slf4j
@RestController
@RequestMapping("/api/act/student/attempts/{attemptId}")
@RequiredArgsConstructor
public class StudentVoiceController {

    private final SpeechVoiceClient speechVoiceClient;
    private final ActPracticeAttemptMapper attemptMapper;
    private final CurrentUserService currentUserService;
    private final AntiCheatService antiCheatService;
    private final jack.digityhuman.atualcombattraining.service.face.FaceCheckService faceCheckService;

    /** ASR：录音 WAV → 文本（进行中的 attempt 才可用） */
    @PostMapping("/voice/transcribe")
    public ApiResponse<Map<String, Object>> transcribe(
            @PathVariable String attemptId,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireInProgress(authorization, attemptId);
        if (audio == null || audio.isEmpty()) {
            throw new BizException("音频内容为空");
        }
        try {
            return ApiResponse.success(
                    speechVoiceClient.transcribe(audio.getBytes(), language));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("语音识别失败：" + e.getMessage());
        }
    }

    /** TTS：台词 → 音频 URL（ST 侧按"文本+声音+语速"缓存幂等） */
    @PostMapping("/tts")
    public ApiResponse<Map<String, Object>> tts(
            @PathVariable String attemptId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAccessible(authorization, attemptId);
        String text = body == null ? null : (String) body.get("text");
        if (text == null || text.isBlank()) {
            throw new BizException("合成文本为空");
        }
        String voiceId = body.get("voiceId") == null ? null : body.get("voiceId").toString();
        Integer speed = body.get("speed") instanceof Number number ? number.intValue() : 1;
        return ApiResponse.success(speechVoiceClient.synthesize(text, voiceId, speed));
    }

    /** 防作弊事件上报（切屏/人脸/摄像头丢失），按规则结算切屏违规 */
    @PostMapping("/anti-cheat")
    public ApiResponse<Map<String, Object>> antiCheat(
            @PathVariable String attemptId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        if (body == null) {
            throw new BizException("缺少事件数据");
        }
        String eventType = (String) body.get("eventType");
        String clientEventId = (String) body.get("clientEventId");
        String dataJson = body.get("data") == null ? null : body.get("data").toString();
        AntiCheatService.AntiCheatResult result =
                antiCheatService.reportEvent(attemptId, userId, eventType, clientEventId, dataJson);
        return ApiResponse.success(Map.of(
                "recorded", result.recorded(),
                "violation", result.violation()));
    }

    /** 人脸能力探测：provider 未配置时前端不请求摄像头（契约 03 §12.2） */
    @GetMapping("/face/available")
    public ApiResponse<Map<String, Object>> faceAvailable() {
        return ApiResponse.success(Map.of("configured", faceCheckService.providerConfigured()));
    }

    /** 人脸核验：抓拍 JPEG → provider 比对 → 证据落库（学员端只拿到判定结果，不返回路径） */
    @PostMapping("/face/verify")
    public ApiResponse<Map<String, Object>> faceVerify(
            @PathVariable String attemptId,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        if (image == null || image.isEmpty()) {
            throw new BizException("抓拍内容为空");
        }
        try {
            return ApiResponse.success(faceCheckService.verify(
                    attemptId, userId, stage, image.getBytes()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("人脸核验失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 语音转写要求 attempt 进行中（结束后转写无意义） */
    private void requireInProgress(String authorization, String attemptId) {
        ActPracticeAttempt attempt = requireAccessible(authorization, attemptId);
        if (!Objects.equals(attempt.getStatus(), "in_progress")) {
            throw new BizException("练习未在进行中");
        }
    }

    private ActPracticeAttempt requireAccessible(String authorization, String attemptId) {
        String userId = requireUser(authorization);
        ActPracticeAttempt attempt = attemptMapper.selectOne(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getId, attemptId)
                        .eq(ActPracticeAttempt::getUserId, userId)
                        .last("LIMIT 1"));
        if (attempt == null) {
            throw new BizException("练习不存在或无权访问");
        }
        return attempt;
    }

    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if ("0".equals(userId)) {
            throw new BizException("未获取到登录用户，请重新登录");
        }
        return userId;
    }
}

