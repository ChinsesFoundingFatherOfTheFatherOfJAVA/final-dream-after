package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ArrangeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeRespDtos;
import jack.digityhuman.examservice.service.ArrangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 考试安排接口（04 §4：/api/exam/arrange）。 */
@RestController
@RequestMapping("/api/exam/arrange")
@RequiredArgsConstructor
public class ArrangeController {

    private final ArrangeService arrangeService;

    /** API-08 列表。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<ArrangeRespDtos.ArrangeItem>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "createdAt") String orderby,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestBody(required = false) ArrangeDtos.ListQuery query) {
        return ApiResponse.success(arrangeService.list(current, limit, orderby, direction, query));
    }

    /** API-10 创建草稿。 */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@RequestBody ArrangeDtos.SaveReq req) {
        return ApiResponse.success(Map.of("id", arrangeService.create(req)));
    }

    /** API-16 批量操作。 */
    @PostMapping("/batch")
    public ApiResponse<Void> batch(@RequestBody ArrangeDtos.BatchReq req) {
        arrangeService.batch(req);
        return ApiResponse.success(null);
    }

    /** API-09 详情。 */
    @GetMapping("/{id}")
    public ApiResponse<ArrangeRespDtos.ArrangeDetail> detail(@PathVariable String id) {
        return ApiResponse.success(arrangeService.detail(id));
    }

    /** API-11 编辑。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody ArrangeDtos.SaveReq req) {
        arrangeService.update(id, req);
        return ApiResponse.success(null);
    }

    /** API-12 发布。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable String id) {
        arrangeService.publish(id);
        return ApiResponse.success(null);
    }

    /** API-13 结束。 */
    @PutMapping("/{id}/end")
    public ApiResponse<Void> end(@PathVariable String id) {
        arrangeService.end(id);
        return ApiResponse.success(null);
    }

    /** API-14 复制。 */
    @PostMapping("/{id}/copy")
    public ApiResponse<Map<String, String>> copy(@PathVariable String id) {
        return ApiResponse.success(Map.of("id", arrangeService.copy(id)));
    }

    /** API-15 删除。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        arrangeService.delete(id);
        return ApiResponse.success(null);
    }

    /** API-17 考试链接与二维码内容。 */
    @GetMapping("/{id}/link")
    public ApiResponse<ArrangeRespDtos.LinkResp> link(@PathVariable String id) {
        return ApiResponse.success(arrangeService.link(id));
    }
}