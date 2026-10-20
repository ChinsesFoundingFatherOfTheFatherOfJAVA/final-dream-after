package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.ArrangeSettingDtos;
import jack.digityhuman.examservice.service.ArrangeSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户考试偏好设置（04 §9 API-46/47）：考生列设置。 */
@RestController
@RequestMapping("/api/exam/setting")
@RequiredArgsConstructor
public class ArrangeSettingController {

    private final ArrangeSettingService arrangeSettingService;

    /** API-46 读取当前用户的考生列设置。 */
    @GetMapping("/student-columns")
    public ApiResponse<ArrangeSettingDtos.ColumnSetting> getStudentColumns() {
        return ApiResponse.success(arrangeSettingService.getStudentColumns());
    }

    /** API-47 保存列设置（账号/姓名/操作列不可隐藏）。 */
    @PutMapping("/student-columns")
    public ApiResponse<Void> saveStudentColumns(@RequestBody ArrangeSettingDtos.ColumnSetting req) {
        arrangeSettingService.saveStudentColumns(req);
        return ApiResponse.success(null);
    }
}