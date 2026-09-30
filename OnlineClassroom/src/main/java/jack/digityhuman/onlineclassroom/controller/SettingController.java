package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.onlineclassroom.common.ApiResponse;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线课堂课程设置接口。
 */
@RestController
@RequestMapping("/api/kng/admin/setting")
@RequiredArgsConstructor
public class SettingController {

    private final SettingService settingService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ApiResponse<KngSettingDtos.Response> get() {
        KngSetting setting = settingService.get();
        return ApiResponse.success(new KngSettingDtos.Response(
                setting.getCreditMode(),
                setting.getAntiIdleEnabled() != null && setting.getAntiIdleEnabled() == 1,
                setting.getAntiIdleMode(),
                setting.getCheckIntervalMinutes(),
                setting.getConfirmSeconds(),
                setting.getPromptText()));
    }

    @PutMapping
    public ApiResponse<Void> save(
            @RequestBody KngSettingDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        settingService.save(request, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }
}
