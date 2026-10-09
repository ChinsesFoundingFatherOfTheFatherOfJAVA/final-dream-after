package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.speechtraining.dto.req.AiScoringReq;
import jack.digityhuman.speechtraining.dto.req.AvatarGenReq;
import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import jack.digityhuman.speechtraining.dto.resp.AvatarGenResp;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.service.AvatarGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 AI 基础接口（评分/形象生成）。旧单步话术生成接口（generate-script/upload）已随
 * v2.0 三步向导上线而下线——提示期路由保留于此，相关 DifyScriptGenService 死代码已删除。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AdminAiController {

    private final DifyScoringService scoringService;
    private final AvatarGenService avatarGenService;

    /**
     * 旧单步生成接口下线提示（v2.0 升级 03 §4：保留 30 天提示期，之后删除）。
     * 新入口=AIGenController 三步向导（gen-scene → gen-outline → gen-script-v2）。
     */
    @PostMapping("/generate-script")
    public ApiResponse<Void> generateScript() {
        return ApiResponse.error(1, "AI生成话术已升级为三步向导，请使用新版入口");
    }

    /** 旧上传生成接口同步下线（同上）。 */
    @PostMapping("/generate-script/upload")
    public ApiResponse<Void> generateScriptWithFile() {
        return ApiResponse.error(1, "AI生成话术已升级为三步向导，请使用新版入口");
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
