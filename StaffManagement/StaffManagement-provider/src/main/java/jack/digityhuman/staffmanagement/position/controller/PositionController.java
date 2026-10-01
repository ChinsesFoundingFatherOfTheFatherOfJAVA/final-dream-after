package jack.digityhuman.staffmanagement.position.controller;

import jack.digityhuman.staffmanagement.common.ListVo;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.position.service.PositionService;
import jack.digityhuman.staffmanagement.position.vo.PositionVo;
import jack.digityhuman.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 岗位 / 职级的候选值接口（FP-03、FP-06）。
 */
@RestController
@RequestMapping("/api/sm/admin")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    /**
     * 岗位多选弹窗的数据源：岗位名 + 人数，支持名称模糊搜索。
     *
     * <p>例：{@code GET /api/sm/admin/positions?page=1&size=20&keyword=经理}
     */
    @GetMapping("/positions")
    public ApiResponse<PageVo<PositionVo>> positions(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false, defaultValue = "1") Integer page,
                                                     @RequestParam(required = false, defaultValue = "20") Integer size) {
        return ApiResponse.ok(positionService.pagePositions(keyword, page, size));
    }

    /** 职级 distinct 列表（字段搜索下拉用输入框，本接口供展示与校验）。 */
    @GetMapping("/job-grades")
    public ApiResponse<ListVo<String>> jobGrades() {
        return ApiResponse.ok(ListVo.of(positionService.listJobGrades()));
    }
}
