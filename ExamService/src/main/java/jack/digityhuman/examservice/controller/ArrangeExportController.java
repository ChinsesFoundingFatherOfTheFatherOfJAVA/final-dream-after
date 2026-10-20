package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.ArrangeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;
import jack.digityhuman.examservice.service.ArrangeExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 考试导出接口（04 API-18/27/28/29/31/35）。
 *
 * <p>方法级写全路径：/{id} 通配在 {@link ArrangeController}，字面量路径优先匹配，
 * 但集中在一个类里更不容易和 /student、/question 前缀互相抢。
 */
@RestController
@RequiredArgsConstructor
public class ArrangeExportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ArrangeExportService arrangeExportService;

    /** API-18 考试列表导出。 */
    @GetMapping("/api/exam/arrange/export")
    public ResponseEntity<byte[]> exportList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String catalogId,
            @RequestParam(defaultValue = "-1") Integer publishStatus,
            @RequestParam(defaultValue = "0") Integer queryType,
            @RequestParam(required = false) String createUserId,
            @RequestParam(required = false) String createUserIds,
            @RequestParam(required = false) Integer examType,
            @RequestParam(defaultValue = "-1") Integer arrangeType) {
        ArrangeDtos.ListQuery q = new ArrangeDtos.ListQuery();
        q.setKeyword(keyword);
        q.setCatalogId(catalogId);
        q.setPublishStatus(publishStatus);
        // 导出必须和列表用同一套筛选，否则"看到 5 条、导出 50 条"
        q.setQueryType(queryType);
        q.setCreateUserId(createUserId);
        // 多选创建人：GET 参数用逗号拼接
        q.setCreateUserIds(createUserIds == null || createUserIds.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(createUserIds.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
        q.setExamType(examType);
        q.setArrangeType(arrangeType);
        return xlsx(arrangeExportService.exportArrangeList(q), "考试列表.xlsx");
    }

    /** API-27 考生导出（type=highest|all|answer）。 */
    @GetMapping("/api/exam/arrange/{id}/student/export")
    public ResponseEntity<byte[]> exportStudents(@PathVariable String id,
                                                 @RequestParam(defaultValue = "highest") String type) {
        String name = "answer".equalsIgnoreCase(type) ? "考生答卷明细.xlsx" : "考生成绩.xlsx";
        return xlsx(arrangeExportService.exportStudents(id, type), name);
    }

    /** API-28 大导出异步任务（当前实现同步生成、返回任务号）。 */
    @PostMapping("/api/exam/arrange/{id}/student/export-task")
    public ApiResponse<Map<String, Object>> createTask(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        String type = body == null ? "highest" : String.valueOf(body.getOrDefault("type", "highest"));
        return ApiResponse.success(arrangeExportService.createTask(id, type));
    }

    /** API-29 查询导出任务。 */
    @GetMapping("/api/exam/arrange/export-task/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable String taskId) {
        return ApiResponse.success(arrangeExportService.getTask(taskId));
    }

    /** 导出任务文件下载。 */
    @GetMapping("/api/exam/arrange/export-task/{taskId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String taskId) {
        return xlsx(arrangeExportService.taskFile(taskId), "导出结果.xlsx");
    }

    /** API-31 签名记录导出。 */
    @GetMapping("/api/exam/arrange/{id}/signature/export")
    public ResponseEntity<byte[]> exportSignatures(@PathVariable String id) {
        return xlsx(arrangeExportService.exportSignatures(id), "签名记录.xlsx");
    }

    /** API-35 试题分析导出。 */
    @GetMapping("/api/exam/arrange/{id}/question/export")
    public ResponseEntity<byte[]> exportQuestions(@PathVariable String id,
                                                  @RequestParam(defaultValue = "-1") Integer type,
                                                  @RequestParam(defaultValue = "-1") Integer levelType,
                                                  @RequestParam(required = false) String keyword) {
        ArrangeStatRespDtos.QuestionQuery q = new ArrangeStatRespDtos.QuestionQuery();
        q.setType(type);
        q.setLevelType(levelType);
        q.setKeyword(keyword);
        return xlsx(arrangeExportService.exportQuestionStat(id, q), "试题分析.xlsx");
    }

    private ResponseEntity<byte[]> xlsx(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(XLSX))
                .body(bytes);
    }
}
