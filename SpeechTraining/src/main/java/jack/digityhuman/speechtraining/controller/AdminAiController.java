package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.dto.req.AiScoringReq;
import jack.digityhuman.speechtraining.dto.req.AiScriptGenReq;
import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import jack.digityhuman.speechtraining.dto.resp.AiScriptGenResp;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dify.DifyScriptGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AdminAiController {

    private final DifyScriptGenService scriptGenService;
    private final DifyScoringService scoringService;

    @PostMapping("/generate-script")
    public ApiResponse<AiScriptGenResp> generateScript(@RequestBody AiScriptGenReq req) {
        return ApiResponse.success(scriptGenService.generateScript(
                req.getDescription(), req.getRounds()));
    }

    @PostMapping("/generate-script/upload")
    public ApiResponse<AiScriptGenResp> generateScriptWithFile(
            @RequestParam("description") String description,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(scriptGenService.generateScriptWithFile(description, file));
    }

    @PostMapping("/score")
    public ApiResponse<AiScoringResp> score(@RequestBody AiScoringReq req) {
        return ApiResponse.success(scoringService.score(
                req.getStandardScript(), req.getKeyPoints(),
                req.getPassCondition(), req.getUserInput()));
    }
}
