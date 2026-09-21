package jack.digityhuman.speechtraining.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.req.SendMessageReq;
import jack.digityhuman.speechtraining.dto.req.TtsReq;
import jack.digityhuman.speechtraining.dto.resp.SendMessageResp;
import jack.digityhuman.speechtraining.dto.resp.SessionDetailResp;
import jack.digityhuman.speechtraining.dto.resp.TtsResp;
import jack.digityhuman.speechtraining.dto.resp.VoiceMessageResp;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import jack.digityhuman.speechtraining.entity.VirtualTrainer;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMessageMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import jack.digityhuman.speechtraining.service.FlowEngineService;
import jack.digityhuman.speechtraining.service.SpeechService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 学员端练习会话接口（FP-10~13, FP-19, FP-20；v1.1 增加 TTS / 语音消息接口）
 */
@RestController
@RequestMapping("/api/student/sessions")
@RequiredArgsConstructor
public class StudentSessionController {

    private final FlowEngineService flowEngineService;
    private final SpeechService speechService;
    private final TrainingSessionMapper sessionMapper;
    private final TrainingSessionMessageMapper messageMapper;
    private final TrainingProjectMapper projectMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;

    /** FP-19 获取会话详情（刷新恢复） */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDetailResp> detail(@PathVariable Long sessionId) {
        return ApiResponse.success(flowEngineService.getSessionDetail(sessionId));
    }

    /** FP-12/20 发送消息（流转判定，核心） */
    @PostMapping("/{sessionId}/messages")
    public ApiResponse<SendMessageResp> sendMessage(@PathVariable Long sessionId, @RequestBody SendMessageReq req) {
        return ApiResponse.success(flowEngineService.sendMessage(sessionId, req.getContent()));
    }

    /** FP-13/19 退出训练 */
    @PostMapping("/{sessionId}/exit")
    public ApiResponse<Void> exit(@PathVariable Long sessionId) {
        flowEngineService.exit(sessionId);
        return ApiResponse.success();
    }

    /**
     * FP-24/28 陪练者消息 TTS 合成（v1.1，接口文档 5.1）
     * 按该会话项目的 virtual_trainer 配置（语言/声音/语速）合成，
     * 相同"文本+声音+语速"命中缓存直接返回。
     */
    @PostMapping("/{sessionId}/tts")
    public ApiResponse<TtsResp> tts(@PathVariable Long sessionId, @RequestBody TtsReq req) {
        // 1. 会话校验
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        // 2. 消息校验：存在且属于该会话且为陪练者消息
        if (req.getMessageId() == null) {
            throw new BizException("消息不存在或不属于该会话");
        }
        TrainingSessionMessage message = messageMapper.selectById(req.getMessageId());
        if (message == null || !sessionId.equals(message.getSessionId()) || message.getRole() == null || message.getRole() != 1) {
            throw new BizException("消息不存在或不属于该会话");
        }
        // 3. 项目必须是语音对话模式
        TrainingProject project = projectMapper.selectById(session.getProjectId());
        if (project == null || project.getDialogueMode() == null || project.getDialogueMode() != 0) {
            throw new BizException("该项目为文字对话模式，无需合成");
        }
        // 4. 取陪练者语音配置并合成
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, session.getProjectId()));
        SpeechService.TtsResult result = speechService.tts(
                message.getContent(),
                trainer == null ? null : trainer.getVoiceId(),
                trainer == null ? null : trainer.getSpeed());

        TtsResp resp = new TtsResp();
        resp.setMessageId(message.getId());
        resp.setAudioUrl(result.url());
        resp.setDurationMs(result.durationMs());
        resp.setCached(result.cached());
        return ApiResponse.success(resp);
    }

    /**
     * FP-24/28 学员语音消息（v1.1，接口文档 5.2）
     * 上传录音 → 百炼 Paraformer ASR 转文字 → 与文字模式完全相同的流转判定。
     * 识别文本长度 < 2 时直接按未通过处理（撤回+要点提示），不调 Dify。
     */
    @PostMapping("/{sessionId}/voice-messages")
    public ApiResponse<VoiceMessageResp> voiceMessage(
            @PathVariable Long sessionId,
            @RequestParam("audio") MultipartFile audio) {
        // 1. 会话存在且进行中（错误口径与 2.3 一致）
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        if (session.getStatus() == null || session.getStatus() != 0) {
            throw new BizException("会话已结束，不能继续发送");
        }
        // 2. 录音文件基础校验
        if (audio == null || audio.isEmpty()) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        byte[] wav;
        try {
            wav = audio.getBytes();
        } catch (IOException e) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        // 3. 项目必须是语音对话模式（ASR 识别语言 = 陪练者朗读语言）
        TrainingProject project = projectMapper.selectById(session.getProjectId());
        if (project == null || project.getDialogueMode() == null || project.getDialogueMode() != 0) {
            throw new BizException("该项目为文字对话模式");
        }
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, session.getProjectId()));
        String language = trainer == null || trainer.getLanguage() == null ? "mandarin" : trainer.getLanguage();

        // 4. ASR 识别（含 WAV 格式与 ≤60s 时长校验）
        SpeechService.AsrResult asr = speechService.asr(wav, language);

        // 5. 共用流转判定（FlowEngineService 内部处理 ASR 短路与落库）
        VoiceMessageResp resp = flowEngineService.sendVoiceMessage(
                sessionId, asr.text(), wav, asr.durationMs());
        return ApiResponse.success(resp);
    }
}
