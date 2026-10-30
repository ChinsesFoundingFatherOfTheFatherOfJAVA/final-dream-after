package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.service.ProductProfileService;
import jack.digityhuman.common.api.ApiResponse;
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

import java.util.List;
import java.util.Map;

/**
 * 产品档案管理端接口（契约 04 §8 / 09 §5.1）
 * <p>
 * 路径：/api/act/admin/theme/{themeId}/product-profiles/**（与既有 admin 前缀一致）。
 * 鉴权沿用现状（R-3：本工程暂无统一拦截，管理端写接口靠前端拦截——已与用户确认全权实施）。
 */
@RestController
@RequestMapping("/api/act/admin/theme/{themeId}/product-profiles")
@RequiredArgsConstructor
public class ProductProfileAdminController {

    private final ProductProfileService productProfileService;

    /** 工作区档案列表（v0） */
    @GetMapping
    public ApiResponse<List<ActProductProfile>> list(@PathVariable String themeId) {
        return ApiResponse.success(productProfileService.listWorking(themeId));
    }

    /** 新建工作档案（v0，profileKey 服务端生成） */
    @PostMapping
    public ApiResponse<ActProductProfile> create(
            @PathVariable String themeId,
            @RequestBody ProductProfileService.ProductProfileUpsertReq req) {
        return ApiResponse.success(productProfileService.create(themeId, req));
    }

    /** 更新工作档案（profileKey 不可变；发布快照行不可编辑） */
    @PutMapping("/{profileId}")
    public ApiResponse<ActProductProfile> update(
            @PathVariable String themeId,
            @PathVariable String profileId,
            @RequestBody ProductProfileService.ProductProfileUpsertReq req) {
        return ApiResponse.success(productProfileService.update(themeId, profileId, req));
    }

    /** 删除工作档案（被场景引用时拒绝并返回场景清单） */
    @DeleteMapping("/{profileId}")
    public ApiResponse<Void> delete(@PathVariable String themeId, @PathVariable String profileId) {
        productProfileService.delete(themeId, profileId);
        return ApiResponse.success(null);
    }

    /** AI 候选生成（§5.1：只返回候选不落库） */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(
            @PathVariable String themeId,
            @RequestBody ProductProfileService.ProductProfileGenerateReq req) {
        return ApiResponse.success(Map.of(
                "candidates", productProfileService.generateCandidates(themeId, req),
                "warnings", List.of()));
    }

    /** 场景已关联档案（回显） */
    @GetMapping("/by-scenario/{scenarioId}")
    public ApiResponse<List<ActProductProfile>> listByScenario(
            @PathVariable String themeId, @PathVariable String scenarioId) {
        return ApiResponse.success(productProfileService.listByScenario(themeId, scenarioId));
    }

    /** 场景关联（PUT 覆盖式）：/admin/theme/{themeId}/scenarios/{scenarioId}/product-profiles */
    @PutMapping("/link/{scenarioId}")
    public ApiResponse<Void> linkScenario(
            @PathVariable String themeId,
            @PathVariable String scenarioId,
            @RequestBody Map<String, List<String>> body) {
        productProfileService.linkScenario(themeId, scenarioId,
                body == null ? List.of() : body.getOrDefault("profileKeys", List.of()));
        return ApiResponse.success(null);
    }

    /** 学员端档案详情的形状复用（admin 预览） */
    @GetMapping("/{profileId}")
    public ApiResponse<ActProductProfile> detail(
            @PathVariable String themeId, @PathVariable String profileId) {
        return ApiResponse.success(productProfileService.listWorking(themeId).stream()
                .filter(profile -> profile.getId().equals(profileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("产品档案不存在")));
    }
}
