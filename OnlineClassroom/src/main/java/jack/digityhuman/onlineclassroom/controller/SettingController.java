package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.onlineclassroom.common.ApiResponse;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
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
        return ApiResponse.success(settingService.getAll());
    }

    @PutMapping("/score")
    public ApiResponse<Void> saveScore(
            @RequestBody KngSettingDtos.ScoreConf request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        settingService.saveScore(request, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }

    @PutMapping("/cheat")
    public ApiResponse<Void> saveCheat(
            @RequestBody KngSettingDtos.CheatSaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        settingService.saveCheat(request, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }

    @PutMapping("/special")
    public ApiResponse<Void> saveSpecial(
            @RequestBody KngSettingDtos.SpecialRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        settingService.saveSpecial(request, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }

    @PutMapping("/play")
    public ApiResponse<Void> savePlay(
            @RequestBody KngSettingDtos.PlayConf request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        settingService.savePlay(request, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }

    @PutMapping("/face")
    public ApiResponse<Void> saveFace(
            @RequestBody KngSettingDtos.FaceConf request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        settingService.saveFace(request, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }
}
