package jack.digityhuman.atualcombattraining.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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
    /** 按角色性别+年龄段选对手音色，避免男客户用女声 */
    private final jack.digityhuman.atualcombattraining.service.ActorVoiceMappingService actorVoiceMappingService;
    /** 读脚本快照用；本工程没有把 ObjectMapper 注册成 bean（AGENTS：Jackson 直接用），这里自建 */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

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

    /**
     * TTS：台词 → 音频 URL（ST 侧按"文本+声音+语速"缓存幂等）。
     *
     * <p>音色由服务端按本场角色的性别与年龄段决定，不让前端传：前端拿不到也不该关心
     * 音色映射规则，否则改一次映射要连带发布前端。请求里显式给了 voiceId 时仍以其为准
     * （管理端预览等场景需要指定音色）。
     */
    @PostMapping("/tts")
    public ApiResponse<Map<String, Object>> tts(
            @PathVariable String attemptId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ActPracticeAttempt attempt = requireAccessible(authorization, attemptId);
        String text = body == null ? null : (String) body.get("text");
        if (text == null || text.isBlank()) {
            throw new BizException("合成文本为空");
        }
        String voiceId = body.get("voiceId") == null ? null : body.get("voiceId").toString();
        if (voiceId == null || voiceId.isBlank()) {
            // 角色音色跟着形象走：同一份脚本里 males/female 与年龄段决定用哪个音色
            voiceId = actorVoiceMappingService.resolve(
                    actorGender(attempt), actorAgeRange(attempt));
        }
        Integer speed = body.get("speed") instanceof Number number ? number.intValue() : 1;
        return ApiResponse.success(speechVoiceClient.synthesize(text, voiceId, speed));
    }

    /**
     * 从本场脚本快照里取对手性别。
     *
     * <p>两种形态都要认：drill-script-v1 是 {@code actor.gender}，
     * 案例快照是扁平字段 {@code actorGender}。取不到返回 null（音色走默认）。
     */
    private String actorGender(ActPracticeAttempt attempt) {
        JsonNode script = snapshot(attempt);
        if (script == null) {
            return null;
        }
        JsonNode actor = script.path("actor");
        String nested = actor.isObject() ? textOf(actor, "gender") : null;
        return nested != null ? nested : textOf(script, "actorGender");
    }

    /** 取对手年龄段，口径同 {@link #actorGender}：actor.ageRange 或扁平 actorAgeRange */
    private String actorAgeRange(ActPracticeAttempt attempt) {
        JsonNode script = snapshot(attempt);
        if (script == null) {
            return null;
        }
        JsonNode actor = script.path("actor");
        String nested = actor.isObject() ? textOf(actor, "ageRange") : null;
        return nested != null ? nested : textOf(script, "actorAgeRange");
    }

    /** 脚本快照解析；快照缺失或非法时返回 null，由调用方回退默认音色 */
    private JsonNode snapshot(ActPracticeAttempt attempt) {
        String json = attempt.getScriptSnapshotJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("脚本快照解析失败，音色回退默认 attemptId={}", attempt.getId());
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
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

