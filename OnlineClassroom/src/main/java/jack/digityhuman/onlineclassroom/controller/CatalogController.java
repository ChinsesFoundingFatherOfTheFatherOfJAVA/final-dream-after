package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.onlineclassroom.common.ApiResponse;
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

    @GetMapping("/tree")
    public ApiResponse<List<KngCatalogDtos.TreeNode>> tree() {
        return ApiResponse.success(catalogService.tree());
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<KngCatalogDtos.Detail> detail(@PathVariable String id) {
        return ApiResponse.success(catalogService.detail(id));
    }

    @PostMapping
    public ApiResponse<KngCatalogDtos.IdResponse> create(
            @RequestBody KngCatalogDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(catalogService.create(
                request, currentUserService.userId(authorization)));
    }

    @PutMapping("/{id}")
    public ApiResponse<KngCatalogDtos.IdResponse> update(
            @PathVariable String id,
            @RequestBody KngCatalogDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(catalogService.update(
                id, request, currentUserService.userId(authorization)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        catalogService.delete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/batch")
    public ApiResponse<KngCatalogDtos.CountResponse> batch(
            @RequestBody KngCatalogDtos.BatchRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(catalogService.batchCreate(
                request, currentUserService.userId(authorization)));
    }

    @PostMapping("/{id}/move")
    public ApiResponse<KngCatalogDtos.CountResponse> move(
            @PathVariable String id, @RequestBody KngCatalogDtos.MoveRequest request) {
        return ApiResponse.success(catalogService.move(id, request));
    }

    @PostMapping("/{id}/move-courses")
    public ApiResponse<KngCatalogDtos.CountResponse> moveCourses(
            @PathVariable String id, @RequestBody KngCatalogDtos.MoveRequest request) {
        return ApiResponse.success(catalogService.moveCourses(id, request));
    }

    @PostMapping("/{id}/merge-to-parent")
    public ApiResponse<KngCatalogDtos.CountResponse> mergeToParent(@PathVariable String id) {
        return ApiResponse.success(catalogService.mergeToParent(id));
    }
}
