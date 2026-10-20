package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.resp.ExamResultRespDtos;
import jack.digityhuman.examservice.service.ExamResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 答卷详情接口（04 §7 API-39/40）。 */
@RestController
@RequestMapping("/api/exam/result")
@RequiredArgsConstructor
public class ExamResultController {

    private final ExamResultService examResultService;

    /** API-39 答卷详情（ueId = exam_attempt.id）。 */
    @GetMapping("/{ueId}")
    public ApiResponse<ExamResultRespDtos.ResultView> view(@PathVariable String ueId) {
        return ApiResponse.success(examResultService.view(ueId));
    }

    /** API-40 导出答卷 PDF（PDFBox + 系统中文字体内嵌）。 */
    @GetMapping("/{ueId}/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String ueId) {
        ExamResultService.PdfFile file = examResultService.exportPdf(ueId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(file.filename(), StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.bytes());
    }
}