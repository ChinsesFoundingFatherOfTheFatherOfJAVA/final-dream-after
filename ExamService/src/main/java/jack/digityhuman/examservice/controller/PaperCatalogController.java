package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.ExamCatalogDtos;
import jack.digityhuman.examservice.dto.resp.ExamCatalogRespDtos;
import jack.digityhuman.examservice.service.PaperCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试卷分类接口（07 §1：/api/exam/paper/catalog，与题库分类同构）。
 */
@RestController
@RequestMapping("/api/exam/paper/catalog")
@RequiredArgsConstructor
public class PaperCatalogController {

    private final PaperCatalogService paperCatalogService;

    /** 1.1 树（含管理员合并与子树统计）。 */
    @GetMapping("/tree")
    public ApiResponse<ExamCatalogRespDtos.TreeNode> tree() {
        return ApiResponse.success(paperCatalogService.tree());
    }

    /** 1.2 详情（编辑抽屉）。 */
    @GetMapping("/{id}")
    public ApiResponse<ExamCatalogRespDtos.CatalogDetail> detail(@PathVariable String id) {
        return ApiResponse.success(paperCatalogService.detail(id));
    }

    /** 1.3 批量新建（缩进文本，逐行校验）。 */
    @PostMapping
    public ApiResponse<ExamCatalogDtos.BatchCreateResult> create(
            @RequestBody ExamCatalogDtos.BatchCreateReq req) {
        return ApiResponse.success(paperCatalogService.createBatch(req));
    }

    /** 1.4 编辑（名称/管理员/继承开关）。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id,
                                    @RequestBody ExamCatalogDtos.UpdateReq req) {
        paperCatalogService.update(id, req);
        return ApiResponse.success(null);
    }

    /** 1.5 移动（整棵子树 routing_path 重算）。 */
    @PutMapping("/{id}/move")
    public ApiResponse<Void> move(@PathVariable String id,
                                  @RequestBody ExamCatalogDtos.MoveReq req) {
        paperCatalogService.move(id, req.getTargetParentId());
        return ApiResponse.success(null);
    }

    /** 1.6 禁用/启用。 */
    @PutMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable String id,
                                     @RequestBody ExamCatalogDtos.DisableReq req) {
        paperCatalogService.disable(id, req.getDisabled() == null ? 1 : req.getDisabled());
        return ApiResponse.success(null);
    }

    /** 1.7 删除（有子分类或挂库 40001）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        paperCatalogService.delete(id);
        return ApiResponse.success(null);
    }
}
