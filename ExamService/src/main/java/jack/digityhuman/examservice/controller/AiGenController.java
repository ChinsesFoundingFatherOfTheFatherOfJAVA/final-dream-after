package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.AiGenDtos;
import jack.digityhuman.examservice.dto.resp.AiGenRespDtos;
import jack.digityhuman.examservice.service.AiGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * AI 建题接口（03 §7）。
 *
 * <p>路径刻意挂在 {@code /api/exam} 下：网关路由 {@code /api/exam/**} → ExamService，
 * 且这里不需要 StripPrefix（各业务应用 Controller 都带完整 /api 前缀）。
 */
@RestController
@RequestMapping("/api/exam")
@RequiredArgsConstructor
public class AiGenController {

    private final AiGenService aiGenService;

    /** 7.1 AI 建题：素材 + 各题型数量 → 题目草稿（组合题除外）。 */
    @PostMapping("/ques/ai-generate")
    public ApiResponse<AiGenRespDtos.AiGenResult> aiGenerate(@RequestBody AiGenDtos.AiGenReq req) {
        return ApiResponse.success(aiGenService.generate(req));
    }

    /** 7.2 AI 建题文件上传解析：doc/docx/ppt/pptx/pps/pdf → 纯文本。 */
    @PostMapping("/file/parse")
    public ApiResponse<Map<String, String>> parseFile(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(Map.of("text", aiGenService.parseDocument(file)));
    }
}