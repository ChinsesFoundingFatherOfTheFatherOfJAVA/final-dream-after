package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngCatalogDtos;
import jack.digityhuman.onlineclassroom.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线课堂分类管理接口。
 */
@RestController
@RequestMapping("/api/kng/admin/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;
    private final CurrentUserService currentUserService;

    /** 分类整树（含各节点统计），展开/搜索由前端本地完成。 */
    @GetMapping("/tree")
    public ApiResponse<List<KngCatalogDtos.TreeNode>> tree() {
        return ApiResponse.success(catalogService.tree());
    }

    /** 单个分类详情（编辑抽屉数据源）。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<KngCatalogDtos.Detail> detail(@PathVariable String id) {
        return ApiResponse.success(catalogService.detail(id));
    }

    /** 新建分类。 */
    @PostMapping
    public ApiResponse<KngCatalogDtos.IdResponse> create(
            @RequestBody KngCatalogDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(catalogService.create(
                request, currentUserService.userId(authorization)));
    }

    /** 编辑分类（名称/排序等基础字段）。 */
    @PutMapping("/{id}")
    public ApiResponse<KngCatalogDtos.IdResponse> update(
            @PathVariable String id,
            @RequestBody KngCatalogDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(catalogService.update(
                id, request, currentUserService.userId(authorization)));
    }

    /** 删除分类（存在子分类时拒绝；删除时同步清理权限矩阵行）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        catalogService.delete(id);
        return ApiResponse.success(null);
    }

    /** 批量创建（按缩进表达层级，一个事务内落库）。 */
    @PostMapping("/batch")
    public ApiResponse<KngCatalogDtos.CountResponse> batch(
            @RequestBody KngCatalogDtos.BatchRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(catalogService.batchCreate(
                request, currentUserService.userId(authorization)));
    }

    /** 移动分类到目标父级（整棵子树跟随）。 */
    @PostMapping("/{id}/move")
    public ApiResponse<KngCatalogDtos.CountResponse> move(
            @PathVariable String id, @RequestBody KngCatalogDtos.MoveRequest request) {
        return ApiResponse.success(catalogService.move(id, request));
    }

    /** 把分类下全部课件移动到目标分类（当前为占位实现，返回 0）。 */
    @PostMapping("/{id}/move-courses")
    public ApiResponse<KngCatalogDtos.CountResponse> moveCourses(
            @PathVariable String id, @RequestBody KngCatalogDtos.MoveRequest request) {
        return ApiResponse.success(catalogService.moveCourses(id, request));
    }

    /** 把当前分类的权限矩阵合并进父分类（同对象取权限并集）。 */
    @PostMapping("/{id}/merge-to-parent")
    public ApiResponse<KngCatalogDtos.CountResponse> mergeToParent(@PathVariable String id) {
        return ApiResponse.success(catalogService.mergeToParent(id));
    }
}
