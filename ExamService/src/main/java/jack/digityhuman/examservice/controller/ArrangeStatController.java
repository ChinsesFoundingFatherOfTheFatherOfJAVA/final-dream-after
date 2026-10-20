package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;
import jack.digityhuman.examservice.service.ArrangeStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试题分析与统计接口（04 §6 API-32~34/36~38）。
 *
 * <p>路径与 {@link ArrangeStudentController} 分开声明，避免 /question 与 /student 前缀互抢。
 */
@RestController
@RequestMapping("/api/exam/arrange/{id}")
@RequiredArgsConstructor
public class ArrangeStatController {

    private final ArrangeStatService arrangeStatService;

    /** API-32 试题分析列表。 */
    @PostMapping("/question/list")
    public ApiResponse<PageResult<ArrangeStatRespDtos.QuestionStat>> questionList(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestBody(required = false) ArrangeStatRespDtos.QuestionQuery query) {
        return ApiResponse.success(arrangeStatService.questionList(id, current, limit, query));
    }

    /** API-33 试题查看抽屉。 */
    @GetMapping("/question/{quesId}/view")
    public ApiResponse<ArrangeStatRespDtos.QuestionView> questionView(@PathVariable String id,
                                                                      @PathVariable String quesId) {
        return ApiResponse.success(arrangeStatService.questionView(id, quesId));
    }

    /** API-34 答题详情抽屉。 */
    @GetMapping("/question/{quesId}/answer-detail")
    public ApiResponse<ArrangeStatRespDtos.AnswerDetail> answerDetail(@PathVariable String id,
                                                                      @PathVariable String quesId) {
        return ApiResponse.success(arrangeStatService.answerDetail(id, quesId));
    }

    /** API-36 统计指标卡。 */
    @GetMapping("/analysis/overview")
    public ApiResponse<ArrangeStatRespDtos.OverviewResp> overview(@PathVariable String id) {
        return ApiResponse.success(arrangeStatService.overview(id));
    }

    /** API-37 分值统计。 */
    @GetMapping("/analysis/score")
    public ApiResponse<ArrangeStatRespDtos.ScoreResp> score(@PathVariable String id) {
        return ApiResponse.success(arrangeStatService.score(id));
    }

    /** API-38 正确率（一次返回 quesType/difficulty/point 三维度）。 */
    @GetMapping("/analysis/correct-rate")
    public ApiResponse<ArrangeStatRespDtos.CorrectRateResp> correctRate(
            @PathVariable String id,
            @RequestParam(defaultValue = "type") String dimension) {
        return ApiResponse.success(arrangeStatService.correctRate(id, dimension));
    }
}