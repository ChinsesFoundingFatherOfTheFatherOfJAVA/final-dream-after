package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ExamLibDtos;
import jack.digityhuman.examservice.dto.req.PaperDtos;
import jack.digityhuman.examservice.dto.resp.ExamLibRespDtos;
import jack.digityhuman.examservice.dto.resp.PaperRespDtos;
import jack.digityhuman.examservice.service.PaperService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 试卷接口（07 §2：/api/exam/paper）。
 *
 * <p>注意 {@code /paper/catalog/**} 由 {@link PaperCatalogController} 提供，
 * 这里只做试卷本体的 CRUD 与操作类接口。
 */
@RestController
@RequestMapping("/api/exam/paper")
@RequiredArgsConstructor
public class PaperController {

    private final PaperService paperService;

    /** 2.1 列表（分页/排序在 query，筛选在 body）。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<PaperRespDtos.PaperItem>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "updateTime") String orderby,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestBody(required = false) PaperDtos.ListQuery query) {
        return ApiResponse.success(paperService.list(current, limit, orderby, direction, query));
    }

    /** 试卷列表导出 Excel（按当前筛选条件）。 */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody(required = false) PaperDtos.ListQuery query) {
        byte[] bytes = paperService.exportExcel(query);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode("试卷列表.xlsx", StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** 2.2 详情（管理页基本信息）。 */
    @GetMapping("/{id}")
    public ApiResponse<PaperRespDtos.PaperDetail> detail(@PathVariable String id) {
        return ApiResponse.success(paperService.detail(id));
    }

    /** 2.3 创建（抽屉）。 */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@RequestBody PaperDtos.SaveReq req) {
        return ApiResponse.success(Map.of("id", paperService.create(req)));
    }

    /** 2.3 编辑基本信息。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody PaperDtos.SaveReq req) {
        paperService.update(id, req);
        return ApiResponse.success(null);
    }

    /** 2.4 启停。 */
    @PutMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable String id, @RequestBody PaperDtos.DisableReq req) {
        paperService.disable(id, req.getDisabled() == null ? 1 : req.getDisabled());
        return ApiResponse.success(null);
    }

    /** 2.4 删除（仅创建者/超管；级联删题目关联/策略/共享）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        paperService.delete(id);
        return ApiResponse.success(null);
    }

    /** 2.4 转移（所有权变更）。 */
    @PutMapping("/{id}/transfer")
    public ApiResponse<Void> transfer(@PathVariable String id, @RequestBody PaperDtos.TransferReq req) {
        paperService.transfer(id, req.getToUserId());
        return ApiResponse.success(null);
    }

    /** 2.6 发布（校验链：题目/策略 → 分值）。 */
    @PutMapping("/{id}/release")
    public ApiResponse<Void> release(@PathVariable String id) {
        paperService.release(id);
        return ApiResponse.success(null);
    }

    /** 2.5 共享范围读取（未发布不拦截读取，便于前端展示）。 */
    @GetMapping("/{id}/share")
    public ApiResponse<ExamLibRespDtos.ShareInfo> getShare(@PathVariable String id) {
        return ApiResponse.success(paperService.getShare(id));
    }

    /** 2.5 共享范围保存（仅已发布）。 */
    @PutMapping("/{id}/share")
    public ApiResponse<Void> saveShare(@PathVariable String id, @RequestBody ExamLibDtos.ShareReq req) {
        paperService.saveShare(id, req);
        return ApiResponse.success(null);
    }

    /** 2.7 引用列表（考试模块回填）。 */
    @GetMapping("/{id}/use")
    public ApiResponse<List<PaperRespDtos.PaperUseItem>> useList(@PathVariable String id) {
        return ApiResponse.success(paperService.useList(id));
    }

    /** 2.4 切换抽题方式（随机卷）。 */
    @PutMapping("/{id}/ques-mode")
    public ApiResponse<Void> updateQuesMode(@PathVariable String id, @RequestBody PaperDtos.QuesModeReq req) {
        paperService.updateQuesMode(id, req.getQuesMode() == null ? 0 : req.getQuesMode());
        return ApiResponse.success(null);
    }

    /** 2.4 修改分类。 */
    @PutMapping("/{id}/catalog")
    public ApiResponse<Void> changeCatalog(@PathVariable String id, @RequestBody PaperDtos.ChangeCatalogReq req) {
        paperService.changeCatalog(id, req.getCatalogId());
        return ApiResponse.success(null);
    }

    /** 2.4 批量操作（删除 / 转移 / 修改分类）。 */
    @PostMapping("/batch")
    public ApiResponse<Void> batch(@RequestBody PaperDtos.BatchReq req) {
        paperService.batch(req);
        return ApiResponse.success(null);
    }
}
