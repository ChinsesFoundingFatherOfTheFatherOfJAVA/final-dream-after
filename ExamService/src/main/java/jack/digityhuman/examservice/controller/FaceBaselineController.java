package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.FaceBaselineDtos;
import jack.digityhuman.examservice.service.FaceBaselineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学员人脸基准照接口（2026-11-12，DER-33）。
 *
 * <p>**能力边界（DEF-01，必须同时写进 UI 文案）**：这张照片是学员自己拍的，
 * 首次就可以是别人。它只能用于检测「这次与上次不是同一人」，
 * **不是身份认证**，更不能对学员宣传成「已确认本人」。
 *
 * <p>录入**不强制**：学员可以一直跳过，考试时照常只做动作式活体。
 * 这是刻意的——强制采集人脸在很多场景需要单独告知与同意，
 * 而且设备/隐私条件不具备时强拦会把正常学员挡在门外。
 */
@RestController
@RequestMapping("/api/exam/student/face-baseline")
@RequiredArgsConstructor
public class FaceBaselineController {

    private final FaceBaselineService faceBaselineService;
    private final CurrentUserService currentUserService;

    /**
     * 查是否已录入基准照。
     *
     * <p>返回 `imageUrl` 而不是布尔值：前端要显示「已录入」的缩略图让学员确认是自己，
     * 只给 `hasBaseline=true` 的话学员看不到拍的是什么，无法纠正拍歪/拍错。
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String url = faceBaselineService.currentImageUrl(requireUser(authorization));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hasBaseline", url != null);
        data.put("imageUrl", url);
        return ApiResponse.success(data);
    }

    /**
     * 录入/重录基准照。
     *
     * <p>图片由前端先传到 `/exam/file/upload` 拿到 URL，本接口只收 URL。
     * 与签名图共用同一套文件存储，不重复造上传通道。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> save(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) FaceBaselineDtos.SaveReq req) {
        String url = faceBaselineService.save(requireUser(authorization), req);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hasBaseline", true);
        data.put("imageUrl", url);
        return ApiResponse.success(data);
    }

    /**
     * 取当前用户；解析不出就按"未登录"拒绝。
     *
     * <p>返回 40023 而不是 40101：40101 归网关，前端见到会清凭证跳登录；
     * 本模块 token 由 StaffManagement 签发，误报会把"没这个人"演成"掉登录"。
     */
    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if (userId == null || userId.isBlank()) {
            throw new BizException(40023, "未登录或登录已失效");
        }
        return userId;
    }
}
