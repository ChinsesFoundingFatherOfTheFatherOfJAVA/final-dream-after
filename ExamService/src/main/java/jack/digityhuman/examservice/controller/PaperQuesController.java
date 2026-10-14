package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.PaperQuesDtos;
import jack.digityhuman.examservice.dto.resp.PaperQuesRespDtos;
import jack.digityhuman.examservice.service.PaperQuesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 固定卷题目接口（07 §4：/api/exam/paper/{id}/question*）。
 *
 * <p>与 {@link PaperController} 同在 /api/exam/paper 前缀下，靠更具体的子路径区分。
 */
@RestController
@RequestMapping("/api/exam/paper")
@RequiredArgsConstructor
public class PaperQuesController {

    private final PaperQuesService paperQuesService;

    /** 4.2 编辑页画布（按大题分组的题卡）。 */
    @GetMapping("/{id}/edit")
    public ApiResponse<PaperQuesRespDtos.EditCanvas> edit(@PathVariable String id) {
        return ApiResponse.success(paperQuesService.editCanvas(id));
    }

    /** 4.1 管理页试题列表。 */
    @GetMapping("/{id}/questions")
    public ApiResponse<PageResult<PaperQuesRespDtos.PaperQuesItem>> questions(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "-1") int type,
            @RequestParam(defaultValue = "-1") int levelType,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(paperQuesService.questions(id, current, limit, type, levelType, keyword));
    }

    /** 4.3 添加题目（同源写入）。 */
    @PostMapping("/{id}/question")
    public ApiResponse<Map<String, Object>> add(@PathVariable String id, @RequestBody PaperQuesDtos.AddReq req) {
        return ApiResponse.success(paperQuesService.addQuestion(id, req));
    }

    /** 4.4 从题库选题（只建关联）。 */
    @PostMapping("/{id}/question/pick")
    public ApiResponse<Map<String, Integer>> pick(@PathVariable String id, @RequestBody PaperQuesDtos.PickReq req) {
        return ApiResponse.success(Map.of("addedCount", paperQuesService.pick(id, req)));
    }

    /** 4.5 编辑（未发布全改 / 已发布仅内容白名单）。 */
    @PutMapping("/{id}/question/{quesId}")
    public ApiResponse<Void> update(@PathVariable String id, @PathVariable String quesId,
                                    @RequestBody PaperQuesDtos.UpdateReq req) {
        paperQuesService.updateQuestion(id, quesId, req);
        return ApiResponse.success(null);
    }

    /** 4.6 移除关联（不删题库试题）。 */
    @DeleteMapping("/{id}/question/{quesId}")
    public ApiResponse<Void> remove(@PathVariable String id, @PathVariable String quesId) {
        paperQuesService.removeQuestion(id, quesId);
        return ApiResponse.success(null);
    }

    /** 4.6 批量移除关联。 */
    @PostMapping("/{id}/question/batch-delete")
    public ApiResponse<Map<String, Integer>> batchRemove(@PathVariable String id,
                                                         @RequestBody PaperQuesDtos.BatchDeleteReq req) {
        return ApiResponse.success(Map.of("removedCount", paperQuesService.removeQuestions(id, req)));
    }

    /** 4.7 拖拽排序（含跨大题）。 */
    @PutMapping("/{id}/question/sort")
    public ApiResponse<Void> sort(@PathVariable String id, @RequestBody PaperQuesDtos.SortReq req) {
        paperQuesService.sort(id, req);
        return ApiResponse.success(null);
    }

    /** 4.8 快速设置全部分数。 */
    @PostMapping("/{id}/question/batch-score")
    public ApiResponse<Void> batchScore(@PathVariable String id, @RequestBody PaperQuesDtos.BatchScoreReq req) {
        paperQuesService.batchScore(id, req);
        return ApiResponse.success(null);
    }

    /** 5 概览统计。 */
    @GetMapping("/{id}/stat/overview")
    public ApiResponse<PaperQuesRespDtos.Overview> overview(@PathVariable String id) {
        return ApiResponse.success(paperQuesService.overview(id));
    }
}