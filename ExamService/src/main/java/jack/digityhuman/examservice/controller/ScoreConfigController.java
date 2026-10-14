package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.ScoreConfigDtos;
import jack.digityhuman.examservice.service.ScoreConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 默认分值配置接口（07 §7：/api/exam/score-config）。 */
@RestController
@RequestMapping("/api/exam/score-config")
@RequiredArgsConstructor
public class ScoreConfigController {

    private final ScoreConfigService scoreConfigService;

    @GetMapping
    public ApiResponse<List<ScoreConfigDtos.Item>> list() {
        return ApiResponse.success(scoreConfigService.list());
    }

    @PutMapping
    public ApiResponse<Void> save(@RequestBody ScoreConfigDtos.SaveReq req) {
        scoreConfigService.save(req);
        return ApiResponse.success(null);
    }
}