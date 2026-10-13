package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ExamLibDtos;
import jack.digityhuman.examservice.dto.resp.ExamLibRespDtos;
import jack.digityhuman.examservice.service.LibService;
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

/**
 * 题库接口（03 §2：/api/exam/lib）。
 */
@RestController
@RequestMapping("/api/exam/lib")
@RequiredArgsConstructor
public class LibController {

    private final LibService libService;

    /** 2.1 列表（分页在 query，筛选在 body）。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<ExamLibRespDtos.LibItem>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "updateTime") String orderby,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestBody(required = false) ExamLibDtos.ListQuery query) {
        return ApiResponse.success(libService.list(current, limit, orderby, direction, query));
    }

    /** 2.2 详情。 */
    @GetMapping("/{id}")
    public ApiResponse<ExamLibRespDtos.LibDetail> detail(@PathVariable String id) {
        return ApiResponse.success(libService.detail(id));
    }

    /** 2.3 新建（自动建默认考点）。 */
    @PostMapping
    public ApiResponse<java.util.Map<String, String>> create(@RequestBody ExamLibDtos.SaveReq req) {
        String id = libService.create(req);
        return ApiResponse.success(java.util.Map.of("id", id));
    }

    /** 2.3 编辑基本信息。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody ExamLibDtos.SaveReq req) {
        libService.update(id, req);
        return ApiResponse.success(null);
    }

    /** 2.4 启停。 */
    @PutMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable String id,
                                     @RequestBody ExamLibDtos.DisableReq req) {
        libService.disable(id, req.getDisabled() == null ? 1 : req.getDisabled());
        return ApiResponse.success(null);
    }

    /** 2.5 删除（级联逻辑删）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        libService.delete(id);
        return ApiResponse.success(null);
    }

    /** 2.6 共享范围读取。 */
    @GetMapping("/{id}/share")
    public ApiResponse<ExamLibRespDtos.ShareInfo> getShare(@PathVariable String id) {
        return ApiResponse.success(libService.getShare(id));
    }

    /** 2.6 共享范围保存（全量覆盖）。 */
    @PutMapping("/{id}/share")
    public ApiResponse<Void> saveShare(@PathVariable String id,
                                       @RequestBody ExamLibDtos.ShareReq req) {
        libService.saveShare(id, req);
        return ApiResponse.success(null);
    }

    /** 2.7 转移。 */
    @PutMapping("/{id}/transfer")
    public ApiResponse<Void> transfer(@PathVariable String id,
                                      @RequestBody ExamLibDtos.TransferReq req) {
        libService.transfer(id, req.getToUserId());
        return ApiResponse.success(null);
    }

    /** 2.8 修改分类。 */
    @PutMapping("/{id}/catalog")
    public ApiResponse<Void> changeCatalog(@PathVariable String id,
                                           @RequestBody ExamLibDtos.ChangeCatalogReq req) {
        libService.changeCatalog(id, req.getCatalogId());
        return ApiResponse.success(null);
    }

    /** 2.10 查重条件位（1=该题型已查过）。 */
    @GetMapping("/{id}/duplicate/condition")
    public ApiResponse<ExamLibRespDtos.DuplicateCondition> duplicateCondition(@PathVariable String id) {
        return ApiResponse.success(libService.duplicateCondition(id));
    }

    /** 2.9 批量（delete|transfer|changeCatalog）。 */
    @PostMapping("/batch")
    public ApiResponse<Void> batch(@RequestBody ExamLibDtos.BatchReq req) {
        libService.batch(req);
        return ApiResponse.success(null);
    }
}
