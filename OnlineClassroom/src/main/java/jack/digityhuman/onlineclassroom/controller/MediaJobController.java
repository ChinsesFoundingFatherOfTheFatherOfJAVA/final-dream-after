package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import jack.digityhuman.onlineclassroom.service.MediaJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体任务接口（MT-04/05，AI字幕与转码专项 03 §2）：转码/字幕两个管理页共用的查询与重试。
 */
@RestController
@RequestMapping("/api/kng/admin/media")
@RequiredArgsConstructor
public class MediaJobController {

    private final MediaJobService mediaJobService;

    /** 分页查询媒体任务（type=1 转码管理页 / 2 字幕管理页；tab=processing|failed）。 */
    @PostMapping("/jobs")
    public ApiResponse<KngMediaDtos.JobsResponse> jobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestBody(required = false) KngMediaDtos.JobsQuery query) {
        Integer type = query == null ? null : query.type();
        if (type == null || (type != 1 && type != 2)) {
            // type 决定管理页数据源，缺省无合理默认，直接拒绝（03 §2：必填）
            return ApiResponse.error("type 必须为 1(转码) 或 2(字幕)");
        }
        String tab = query.tab() == null ? "processing" : query.tab();
        return ApiResponse.success(mediaJobService.query(
                type, tab, query.keyword(), page, pageSize));
    }

    /** 重试任务：仅 failed 且 retryCount&lt;3；subtitle 按断点 stage 续跑；重置回 pending。 */
    @PostMapping("/jobs/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable String id) {
        mediaJobService.retry(id);
        return ApiResponse.success(null);
    }
}
