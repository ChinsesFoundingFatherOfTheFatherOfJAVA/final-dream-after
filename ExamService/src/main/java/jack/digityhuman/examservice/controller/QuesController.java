package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.dto.resp.QuesRespDtos;
import jack.digityhuman.examservice.service.QuesExportService;
import jack.digityhuman.examservice.service.QuesImportService;
import jack.digityhuman.examservice.service.QuesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 试题接口（03 §3：/api/exam/ques）。
 *
 * <p>注意 {@code /ques/ai-generate} 由 {@link AiGenController} 提供（默认 AIGentService），
 * 这里只做常规 CRUD，避免两条链路互相耦合。
 */
@RestController
@RequestMapping("/api/exam/ques")
@RequiredArgsConstructor
public class QuesController {

    private final QuesService quesService;
    private final QuesExportService quesExportService;
    private final QuesImportService quesImportService;

    /** 3.1 列表（分页/排序在 query，筛选在 body）。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<QuesRespDtos.QuesItem>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "updateTime") String orderby,
            @RequestParam(defaultValue = "DESC") String direction,
            @RequestBody(required = false) QuesDtos.ListQuery query) {
        return ApiResponse.success(quesService.list(current, limit, orderby, direction, query));
    }

    /** 3.2 详情（编辑页 / 查看抽屉共用）。 */
    @GetMapping("/{id}")
    public ApiResponse<QuesRespDtos.QuesDetail> detail(@PathVariable String id) {
        return ApiResponse.success(quesService.detail(id));
    }

    /** 3.3 新建。 */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@RequestBody QuesDtos.SaveReq req) {
        return ApiResponse.success(Map.of("id", quesService.create(req)));
    }

    /** 3.4 编辑保存。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody QuesDtos.SaveReq req) {
        quesService.update(id, req);
        return ApiResponse.success(null);
    }

    /** 3.5 删除（组合题级联子题）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        quesService.delete(id);
        return ApiResponse.success(null);
    }

    /** 6.1 下载导入模板（与导出的列结构一致，由后端就地生成）。 */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate(@RequestParam(defaultValue = "excel") String format) {
        boolean word = "word".equalsIgnoreCase(format);
        byte[] bytes = quesImportService.template(format);
        String filename = word ? "试题导入模板.docx" : "试题导入模板.xlsx";
        String contentType = word
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    /** 6.2 导入试题：逐行 try-catch，新考核点自动入库。 */
    @PostMapping("/import")
    public ApiResponse<QuesRespDtos.ImportResult> importQues(@RequestParam("file") MultipartFile file,
                                                             @RequestParam String libId) {
        return ApiResponse.success(quesImportService.importFile(file, libId));
    }

    /** 6.3 导出：按当前筛选条件导出 Excel / Word 文件流。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "excel") String format,
            @RequestParam String libId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "-1") int type,
            @RequestParam(defaultValue = "-1") int levelType,
            @RequestParam(defaultValue = "-1") int disabled,
            @RequestParam(required = false) String pointId,
            @RequestParam(defaultValue = "0") int isDuplicate) {
        QuesDtos.ListQuery query = new QuesDtos.ListQuery();
        query.setLibId(libId);
        query.setKeyword(keyword);
        query.setType(type);
        query.setLevelType(levelType);
        query.setDisabled(disabled);
        query.setPointId(pointId);
        query.setIsDuplicate(isDuplicate);

        boolean word = "word".equalsIgnoreCase(format);
        byte[] bytes = word ? quesExportService.exportWord(query) : quesExportService.exportExcel(query);
        String filename = word ? "试题列表.docx" : "试题列表.xlsx";
        String contentType = word
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    /** 3.8 查重：按题型执行并持久化 duplicate 标记，返回重复题数。 */
    @PostMapping("/duplicate/check")
    public ApiResponse<Map<String, Integer>> duplicateCheck(@RequestBody QuesDtos.DuplicateCheckReq req) {
        int type = req.getType() == null ? -1 : req.getType();
        int count = quesService.checkDuplicate(req.getLibId(), type);
        return ApiResponse.success(Map.of("duplicateCount", count));
    }

    /** 3.6 批量（delete|disable|changeLib）。 */
    @PostMapping("/batch")
    public ApiResponse<Void> batch(@RequestBody QuesDtos.BatchReq req) {
        quesService.batch(req);
        return ApiResponse.success(null);
    }
}