package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.dto.req.CaseAvatarSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.CaseAvatarResp;
import jack.digityhuman.atualcombattraining.service.CaseAvatarService;
import jack.digityhuman.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「案例形象」接口（/api/act/admin/theme/{themeId}/case-avatar，2026-11-05）。
 * <p>
 * <b>为什么单独一个控制器而不是塞进 AdminThemeController</b>：
 * 本接口的特殊性在于<b>双写 artifact + 列</b>（防止确认落库「先删后插」把上传的图冲掉），
 * 与 AdminThemeController 里那些"只读查询 + 主题状态机"接口的性质不同，单列一个类更好审。
 * <p>
 * 用 PUT 而非 POST：语义是"把这个案例的形象置为 X"（幂等，重复调用同值结果不变），
 * 只有首次写入 artifact 版本号可能变化。
 */
@RestController
@RequestMapping("/api/act/admin/theme")
@RequiredArgsConstructor
public class AdminCaseAvatarController {

    private final CaseAvatarService caseAvatarService;

    /**
     * 设置/清除某案例的陪练者形象（L0 上传覆盖）。
     * <p>
     * 入参 {@code scriptName} 为持久定位键；{@code scriptId} 是便利入口（行 ID 每次确认都会重建，会失效）；
     * 同主题有同名案例时须带 {@code scenarioName} 消歧，否则返回"歧义"错误而不猜。
     * <p>
     * {@code avatarUrl} 传 null/空串 = 清除覆盖，发布时回落到自动定图（L1~L4）。
     */
    @PutMapping("/{themeId}/case-avatar")
    public ApiResponse<CaseAvatarResp> saveCaseAvatar(@PathVariable String themeId,
                                                      @RequestBody CaseAvatarSaveReq req) {
        return ApiResponse.success(caseAvatarService.saveAvatar(themeId, req));
    }
}
