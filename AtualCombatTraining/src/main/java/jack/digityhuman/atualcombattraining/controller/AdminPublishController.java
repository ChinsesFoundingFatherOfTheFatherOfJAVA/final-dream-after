package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.common.ApiResponse;
import jack.digityhuman.atualcombattraining.dto.resp.PublishResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScriptProgressResp;
import jack.digityhuman.atualcombattraining.service.PublishInitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主题发布与初始化进度接口（/api/act/admin/theme/**，FP-41）
 * <p>
 * 与既有 AdminThemeController 同前缀、不同路径：既有 9 个接口（列表/详情/设置/版本）不变，
 * 本控制器只补发布相关 3 个增量接口。
 */
@RestController
@RequestMapping("/api/act/admin/theme")
@RequiredArgsConstructor
public class AdminPublishController {

    private final PublishInitService publishInitService;

    /** FP-41 发布主题：状态→发布中，异步逐案例编译剧本 */
    @PostMapping("/{id}/publish")
    public ApiResponse<PublishResp> publish(@PathVariable String id) {
        return ApiResponse.success(publishInitService.publish(id));
    }

    /** FP-41 初始化进度（前端 5s 轮询，status=3 停止） */
    @GetMapping("/{id}/script-progress")
    public ApiResponse<ScriptProgressResp> scriptProgress(@PathVariable String id) {
        return ApiResponse.success(publishInitService.scriptProgress(id));
    }

    /** FP-41 仅重试失败项 */
    @PostMapping("/{id}/publish/retry")
    public ApiResponse<Void> retry(@PathVariable String id) {
        publishInitService.retry(id);
        return ApiResponse.success();
    }
}