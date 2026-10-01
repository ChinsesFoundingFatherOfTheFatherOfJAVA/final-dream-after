package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.speechtraining.dto.req.AiScoringReq;
import jack.digityhuman.speechtraining.dto.req.AiScriptGenReq;
import jack.digityhuman.speechtraining.dto.req.AvatarGenReq;
import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import jack.digityhuman.speechtraining.dto.resp.AiScriptGenResp;
import jack.digityhuman.speechtraining.dto.resp.AvatarGenResp;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dify.DifyScriptGenService;
import jack.digityhuman.speechtraining.service.AvatarGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AdminAiController {

    private final DifyScriptGenService scriptGenService;
    private final DifyScoringService scoringService;
    private final AvatarGenService avatarGenService;

    /** AI 生成话术流程（场景描述 + 轮次）。 */
    @PostMapping("/generate-script")
    public ApiResponse<AiScriptGenResp> generateScript(@RequestBody AiScriptGenReq req) {
        return ApiResponse.success(scriptGenService.generateScript(
                req.getDescription(), req.getRounds(), req.getFirstSpeaker()));
    }

    /** AI 生成话术流程（参考资料文件上传：PDF/docx 文本提取后拼接描述）。 */
    @PostMapping("/generate-script/upload")
    public ApiResponse<AiScriptGenResp> generateScriptWithFile(
            @RequestParam("description") String description,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "firstSpeaker", required = false) String firstSpeaker) {
        return ApiResponse.success(scriptGenService.generateScriptWithFile(description, file, firstSpeaker));
    }

    /** AI 单轮评分（标准话术 + 要点 + 学员回答）。 */
    @PostMapping("/score")
    public ApiResponse<AiScoringResp> score(@RequestBody AiScoringReq req) {
        return ApiResponse.success(scoringService.score(
                req.getStandardScript(), req.getKeyPoints(),
                req.getPassCondition(), req.getUserInput()));
    }

    /**
     * FP-26/28 AI 生成陪练者形象（v1.1，接口文档 5.3）
     * 调 Dify 生图工作流，图片转存本地文件服务后返回 URL
     */
    @PostMapping("/generate-avatar")
    public ApiResponse<AvatarGenResp> generateAvatar(@RequestBody AvatarGenReq req) {
        String imageUrl = avatarGenService.generate(req.getPrompt());
        AvatarGenResp resp = new AvatarGenResp();
        resp.setImageUrl(imageUrl);
        return ApiResponse.success(resp);
    }
}
