package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.StudentAnswerDtos;
import jack.digityhuman.examservice.dto.req.StudentExamDtos;
import jack.digityhuman.examservice.dto.resp.StudentAnswerRespDtos;
import jack.digityhuman.examservice.service.StudentAnswerService;
import jack.digityhuman.examservice.service.StudentExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学员端答题接口（04 §11.7–§11.11）：`/api/exam/student/attempts`。
 *
 * <p>与 `StudentExamController` 分开：那份是入场前的清单，这份全部围绕一张答卷，
 * 鉴权口径也不同（这里只认 attemptId 归属，不看考试名单）。
 */
@RestController
@RequestMapping("/api/exam/student/attempts")
@RequiredArgsConstructor
public class StudentAnswerController {

    private final StudentAnswerService studentAnswerService;
    /**
     * 仅用于转发人脸核验落库（`reportFaceVerify` 在 StudentExamService 里，与 start/entry 同域）。
     *
     * <p>为何不在本类重写一份：核验结果影响的是答卷的 `identity_status`，与 start 共用同一套
     * “本人校验 + 不报错”语义，放两处必然分叉。
     */
    private final StudentExamService studentExamService;
    private final CurrentUserService currentUserService;

    /** §11.7 取题目（答题页）。不返回任何标准答案/解析。 */
    @GetMapping("/{attemptId}/paper")
    public ApiResponse<StudentAnswerRespDtos.Paper> paper(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentAnswerService.paper(requireUser(authorization), attemptId));
    }

    /**
     * 人脸核验结果落库（契约 04 §11.5b，DER-29 / DER-32）。
     *
     * <p>**为什么必须放在本 Controller**：Spring 的 `@RequestMapping` 类级前缀是
     * **直接拼接**的，不能被方法级绝对路径覆盖。若放进 `StudentExamController`
     * （类级前缀 `/api/exam/student/exams`），实际路径会变成
     * `/api/exam/student/exams/attempts/{id}/face-verify`（多一段 `exams`）→ 永远 404。
     * 本类前缀刚好是 `/api/exam/student/attempts`，与契约对齐。
     *
     * <p>语义上放在答卷维度是对的：核验是对“这一次作答”的身份证据，同一学员重考要重新采集。
     * 响应恒为 `{}`，**任何失败都不影响开考**（DER-07）。
     */
    @PostMapping("/{attemptId}/face-verify")
    public ApiResponse<Void> faceVerify(
            @PathVariable String attemptId,
            @RequestBody(required = false) StudentExamDtos.FaceVerifyReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        studentExamService.reportFaceVerify(requireUser(authorization), attemptId, req);
        return ApiResponse.success(null);
    }

    /** §11.8 逐题保存；同题重复提交覆盖不叠加（`uk_attempt_ques`）。 */
    @PostMapping("/{attemptId}/answers")
    public ApiResponse<StudentAnswerRespDtos.Saved> saveAnswers(
            @PathVariable String attemptId,
            @RequestBody(required = false) StudentAnswerDtos.SaveAnswersReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        int saved = studentAnswerService.saveAnswers(requireUser(authorization), attemptId, req);
        return ApiResponse.success(StudentAnswerRespDtos.Saved.of(saved));
    }

    /** §11.9 题目标记（独立于答案保存：学员可能只标记不答）。 */
    @PostMapping("/{attemptId}/flag")
    public ApiResponse<StudentAnswerRespDtos.Flagged> flag(
            @PathVariable String attemptId,
            @RequestBody(required = false) StudentAnswerDtos.FlagReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        int marked = studentAnswerService.flag(requireUser(authorization), attemptId, req);
        return ApiResponse.success(StudentAnswerRespDtos.Flagged.of(marked));
    }

    /**
     * §11.10 心跳与时间校正。前端每 30s 调一次。
     *
     * <p>前端不得以本地计时为准：`serverNow` 与 `remainingSeconds` 才是唯一基准。
     */
    @PutMapping("/{attemptId}/time")
    public ApiResponse<StudentAnswerRespDtos.TimeState> time(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentAnswerService.timeState(requireUser(authorization), attemptId));
    }

    /** §11.10 切屏等异常上报；超限时服务端强制交卷，返回 `forceSubmitted`。 */
    @PostMapping("/{attemptId}/anti-cheat")
    public ApiResponse<Map<String, Boolean>> antiCheat(
            @PathVariable String attemptId,
            @RequestBody(required = false) StudentAnswerDtos.AntiCheatReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean forced = studentAnswerService.reportAntiCheat(requireUser(authorization), attemptId, req);
        return ApiResponse.success(Map.of("forceSubmitted", forced));
    }

    /** §11.11 交卷（幂等）。交卷即终态，不可再改答案（01 §S5）。 */
    @PutMapping("/{attemptId}/submit")
    public ApiResponse<StudentAnswerRespDtos.SubmitResult> submit(
            @PathVariable String attemptId,
            @RequestBody(required = false) StudentAnswerDtos.SubmitReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentAnswerService.submit(requireUser(authorization), attemptId, req));
    }

    /**
     * 取当前用户；解析不出按 40023 拒绝（不是 40101——40101 归网关，
     * 前端见它会清凭证跳登录，会把"没这个人"演成"掉登录"，见 04 §2 注）。
     */
    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if (userId == null || userId.isBlank() || "0".equals(userId)) {
            throw new BizException(40023, "未获取到登录用户，请重新登录");
        }
        return userId;
    }
}
