package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.common.ApiResponse;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.dto.req.AssistantSessionReq;
import jack.digityhuman.atualcombattraining.dto.resp.AssistantSessionResp;
import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import jack.digityhuman.atualcombattraining.dto.resp.MaterialBriefResp;
import jack.digityhuman.atualcombattraining.dto.resp.MessagePageResp;
import jack.digityhuman.atualcombattraining.service.AssistantAsrService;
import jack.digityhuman.atualcombattraining.service.AssistantFileService;
import jack.digityhuman.atualcombattraining.service.AssistantMaterialService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 设计助手 HTTP 接口（/api/act/assistant/**，FP-31/32/33/42）
 * <p>
 * 会话建立与恢复、历史消息（cursor 分页）、artifact 兜底拉取、材料上传、语音识别。
 * 聊天主链路走 WS（/api/act/ws/chat），本控制器不含消息发送接口。
 */
@RestController
@RequestMapping("/api/act/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantSessionService sessionService;
    private final AssistantFileService fileService;
    private final AssistantMaterialService materialService;
    private final AssistantAsrService asrService;

    /**
     * FP-31 建立/查找会话（03 文档 2.1）：
     * themeId 非空且已有会话则复用（isNew=false），并返回当前 artifact（工作区优先）。
     */
    @PostMapping("/sessions")
    public ApiResponse<AssistantSessionResp> sessions(@RequestBody(required = false) AssistantSessionReq req) {
        String themeId = req == null ? null : req.getThemeId();
        return ApiResponse.success(sessionService.createOrFind(themeId));
    }

    /** FP-31 历史消息（cursor 分页，按时间正序） */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<MessagePageResp> messages(@PathVariable String sessionId,
                                                 @RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) String cursor) {
        return ApiResponse.success(sessionService.listMessages(sessionId, limit, cursor));
    }

    /** FP-33 当前 artifact（右面板兜底刷新；无产物 data=null） */
    @GetMapping("/sessions/{sessionId}/artifact")
    public ApiResponse<AssistantSessionResp.Artifact> artifact(@PathVariable String sessionId) {
        return ApiResponse.success(sessionService.artifactOf(sessionId));
    }

    /**
     * 会话材料解析结果（"文件解析完成"卡片展开查看解析内容时拉取）
     * <p>
     * 返回该会话上传材料及其提取文本前 2000 字（preview）与全文长度（extractLength）；
     * 无材料时返回空列表而非报错（前端展开区据此提示"没有可展示的解析内容"）。
     */
    @GetMapping("/sessions/{sessionId}/materials")
    public ApiResponse<Map<String, List<MaterialBriefResp>>> materials(@PathVariable String sessionId) {
        return ApiResponse.success(Map.of("materials", materialService.listSessionMaterials(sessionId)));
    }

    /**
     * FP-42 材料上传（multipart 字段 file，白名单见 03 文档枚举）
     * <p>
     * 带 sessionId 时上传成功后**异步触发解析**（本地 Tika 提取 + 规则摘要 + 入 artifact + parse-file 事件）；
     * 不带则只落盘返回 fileId（保持向后兼容）。
     */
    @PostMapping("/files")
    public ApiResponse<FileUploadResp> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) String sessionId) {
        FileUploadResp stored = fileService.store(file);
        if (sessionId != null && !sessionId.isBlank()) {
            materialService.acceptParse(sessionId, stored);
        }
        return ApiResponse.success(stored);
    }

    /** FP-32 语音识别（multipart 字段 audio，16kHz 单声道 WAV） */
    @PostMapping("/asr")
    public ApiResponse<Map<String, String>> asr(@RequestParam("audio") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new BizException("录音文件读取失败，请重试");
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("text", asrService.recognize(bytes));
        return ApiResponse.success(data);
    }
}