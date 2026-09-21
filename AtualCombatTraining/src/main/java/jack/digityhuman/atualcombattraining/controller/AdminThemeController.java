package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.common.ApiResponse;
import jack.digityhuman.atualcombattraining.common.PageResult;
import jack.digityhuman.atualcombattraining.dto.req.ThemeListReq;
import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.RollbackResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScenarioUnitResp;
import jack.digityhuman.atualcombattraining.dto.resp.StatusResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeDetailResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeListResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSettingsResp;
import jack.digityhuman.atualcombattraining.dto.resp.VersionResp;
import jack.digityhuman.atualcombattraining.service.ThemeService;
import jack.digityhuman.atualcombattraining.service.ThemeVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端训练主题接口（/api/act/admin/theme/**，FP-06~FP-19）
 * <p>
 * 9 个接口全部定义于本控制器，经网关 /api/act/** 路由（gateway 8080 → lb://AtualCombatTraining）。
 */
@RestController
@RequestMapping("/api/act/admin/theme")
@RequiredArgsConstructor
public class AdminThemeController {

    private final ThemeService themeService;
    private final ThemeVersionService themeVersionService;

    /** FP-06 分页查询主题列表 */
    @GetMapping("/list")
    public ApiResponse<PageResult<ThemeListResp>> list(ThemeListReq req) {
        return ApiResponse.success(themeService.list(req));
    }

    /** FP-07/08 主题详情（头部信息+统计占位+实时计数） */
    @GetMapping("/{id}")
    public ApiResponse<ThemeDetailResp> detail(@PathVariable String id) {
        return ApiResponse.success(themeService.detail(id));
    }

    /** FP-09 暂停/恢复（已发布⇄已暂停） */
    @PostMapping("/{id}/pause")
    public ApiResponse<StatusResp> pause(@PathVariable String id) {
        return ApiResponse.success(themeService.pause(id));
    }

    /** FP-09 结束（→已结束终态，不可逆） */
    @PostMapping("/{id}/finish")
    public ApiResponse<StatusResp> finish(@PathVariable String id) {
        return ApiResponse.success(themeService.finish(id));
    }

    /** FP-11/20 当前版本场景单元列表（含评估维度） */
    @GetMapping("/{id}/scenario-units")
    public ApiResponse<List<ScenarioUnitResp>> scenarioUnits(@PathVariable String id) {
        return ApiResponse.success(themeService.listScenarioUnits(id));
    }

    /** FP-13~17 读取当前版本主题设置（五表单+内容概览） */
    @GetMapping("/{id}/settings")
    public ApiResponse<ThemeSettingsResp> getSettings(@PathVariable String id) {
        return ApiResponse.success(themeVersionService.getSettings(id));
    }

    /** FP-18 整体保存主题设置（版本+1、复制场景单元、切指针、同步冗余） */
    @PutMapping("/{id}/settings")
    public ApiResponse<ThemeSaveResp> saveSettings(@PathVariable String id,
                                                   @RequestBody ThemeSettingsSaveReq req) {
        return ApiResponse.success(themeVersionService.saveSettings(id, req));
    }

    /** FP-19 版本列表（version_no 倒序） */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<VersionResp>> versions(@PathVariable String id) {
        return ApiResponse.success(themeVersionService.listVersions(id));
    }

    /** FP-19 回滚到指定版本（仅切 current_version_no 指针） */
    @PostMapping("/{id}/version/{versionNo}/rollback")
    public ApiResponse<RollbackResp> rollback(@PathVariable String id, @PathVariable Integer versionNo) {
        return ApiResponse.success(themeVersionService.rollback(id, versionNo));
    }
}
