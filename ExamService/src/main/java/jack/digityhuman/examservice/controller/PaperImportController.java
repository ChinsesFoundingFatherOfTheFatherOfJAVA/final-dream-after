package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.service.PaperImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 快速出卷导入接口（07 §6：/api/exam/paper/import）。
 *
 * <p>单独成类避免与 {@link PaperController} 的 {@code /{id}} 通配 GET 抢路由，
 * 模板下载是静态资源流、导入是 multipart，和试卷本体 CRUD 的关注点不同。
 */
@RestController
@RequestMapping("/api/exam/paper/import")
@RequiredArgsConstructor
public class PaperImportController {

    private final PaperImportService paperImportService;

    /** 6.1 下载导入模板（classpath 静态资源，与解析器同源）。 */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        byte[] bytes = paperImportService.template();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode("试卷导入模板.xlsx", StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** 6.2 导入（multipart：file + libId + catalogId + secrecy），返回逐行结果。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("libId") String libId,
            @RequestParam(value = "catalogId", required = false) String catalogId,
            @RequestParam(value = "secrecy", required = false) Integer secrecy,
            @RequestParam(value = "paperId", required = false) String paperId) {
        return ApiResponse.success(paperImportService.importFile(file, libId, catalogId, secrecy, paperId));
    }
}
