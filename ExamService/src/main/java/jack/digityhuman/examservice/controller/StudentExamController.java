package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.StudentExamDtos;
import jack.digityhuman.examservice.dto.resp.StudentExamRespDtos;
import jack.digityhuman.examservice.service.StudentExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学员端考试接口（04 §11.2–§11.6）：`/api/exam/student/exams`。
 *
 * <p>鉴权口径：本模块不做统一拦截（与 AtualCombatTraining 学员端一致），
 * 每个方法显式从 JWT 取当前用户并传给 Service；Service 内再校验"该用户在本场考试名单里"，
 * 前端传什么 arrangeId 都越不过这一层（04 §11.1）。
 */
@RestController
@RequestMapping("/api/exam/student/exams")
@RequiredArgsConstructor
public class StudentExamController {

    private final StudentExamService studentExamService;
    private final CurrentUserService currentUserService;

    /** §11.2 我的考试列表；status 可选：not_started / in_progress / grading / finished / invalid。 */
    @GetMapping
    public ApiResponse<StudentExamRespDtos.ExamList> listMyExams(
            @RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentExamService.listMyExams(requireUser(authorization), status));
    }

    /** §11.3 入场信息（含 canStart / blockReason / 最近一次成绩）。 */
    @GetMapping("/{arrangeId}/entry")
    public ApiResponse<StudentExamRespDtos.Entry> entry(
            @PathVariable String arrangeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentExamService.entry(requireUser(authorization), arrangeId));
    }

    /** §11.4 考前校验：前端据此决定弹哪几个前置步骤（声明 → 签名 → 人脸核验）。 */
    @GetMapping("/{arrangeId}/pre-check")
    public ApiResponse<StudentExamRespDtos.PreCheck> preCheck(
            @PathVariable String arrangeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentExamService.preCheck(requireUser(authorization), arrangeId));
    }

    /** §11.4 考前声明确认（`preExamDeclare=1` 时才调用）。 */
    @PostMapping("/{arrangeId}/declare")
    public ApiResponse<Void> declare(
            @PathVariable String arrangeId,
            @RequestBody(required = false) StudentExamDtos.DeclareReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        studentExamService.declare(requireUser(authorization), arrangeId, req);
        return ApiResponse.success();
    }

    /** §11.5 考前/考后签名落库，返回 signId 供 start 绑定答卷。 */
    @PostMapping("/{arrangeId}/sign")
    public ApiResponse<Map<String, String>> sign(
            @PathVariable String arrangeId,
            @RequestBody StudentExamDtos.SignReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String signId = studentExamService.sign(requireUser(authorization), arrangeId, req);
        return ApiResponse.success(Map.of("signId", signId));
    }

    /** §11.6 开始考试；已有未交卷答卷时续答（返回同一 attemptId，不重复计次）。 */
    @PostMapping("/{arrangeId}/start")
    public ApiResponse<StudentExamRespDtos.StartResult> start(
            @PathVariable String arrangeId,
            @RequestBody(required = false) StudentExamDtos.StartReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentExamService.start(requireUser(authorization), arrangeId, req));
    }

    /**
     * 取当前用户；解析不出就按"未登录"拒绝。
     *
     * <p>这里刻意返回 40023 而不是 40101：40101 归网关，前端见到它会清凭证跳登录；
     * 本模块的 token 是 StaffManagement 签发的，误报会把"没这个人"演成"掉登录"（04 §2 注）。
     */
    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if (userId == null || userId.isBlank() || "0".equals(userId)) {
            throw new BizException(40023, "未获取到登录用户，请重新登录");
        }
        return userId;
    }
}
