package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.PointDtos;
import jack.digityhuman.examservice.dto.resp.PointRespDtos;
import jack.digityhuman.examservice.service.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 考核点接口（03 §4：/api/exam/point）。 */
@RestController
@RequestMapping("/api/exam/point")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    /** 4.1 按题库列考核点。 */
    @GetMapping("/list")
    public ApiResponse<List<PointRespDtos.PointItem>> list(@RequestParam String libId) {
        return ApiResponse.success(pointService.list(libId));
    }

    /** 4.2 编辑抽屉保存（增删改合并，全量覆盖）。 */
    @PostMapping("/batch")
    public ApiResponse<Void> batch(@RequestBody PointDtos.BatchReq req) {
        pointService.batchSave(req);
        return ApiResponse.success(null);
    }
}