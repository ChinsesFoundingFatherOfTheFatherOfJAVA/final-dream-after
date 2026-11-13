package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.service.StudentResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学员端结果与答卷详情（04 §11.12 / §11.13）：`/api/exam/student/attempts`。
 *
 * <p>路径与 `StudentAnswerController` 同前缀但拆成两个类：答题是写、结果是读，
 * 混在一个类里会让"这个类要不要事务"变得含糊。
 */
@RestController
@RequestMapping("/api/exam/student/attempts")
@RequiredArgsConstructor
public class StudentResultController {

    private final StudentResultService studentResultService;
    private final CurrentUserService currentUserService;

    /** §11.12 结果页。`graded=false` 时 score/passed/rankNo 为 null（DER-03）。 */
    @GetMapping("/{attemptId}/result")
    public ApiResponse<StudentResultRespDtos.Result> result(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentResultService.result(requireUser(authorization), attemptId));
    }

    /**
     * §11.13 答卷详情。
     *
     * <p>`wrongOnly` 不传时取 `answerView.wrongOnly` 的默认值；传 1 才是真过滤——
     * 答对的大题连标题都不出现，不是前端高亮。
     */
    @GetMapping("/{attemptId}/paper-detail")
    public ApiResponse<StudentResultRespDtos.PaperDetail> paperDetail(
            @PathVariable String attemptId,
            @RequestParam(required = false) Integer wrongOnly,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                studentResultService.paperDetail(requireUser(authorization), attemptId, wrongOnly));
    }

    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if (userId == null || userId.isBlank() || "0".equals(userId)) {
            throw new BizException(40023, "未获取到登录用户，请重新登录");
        }
        return userId;
    }
}
